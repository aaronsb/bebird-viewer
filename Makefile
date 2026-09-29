# SPDX-License-Identifier: GPL-3.0-or-later
# bebird-viewer: run from source, build the desktop binary and the Android app, install.
# Builds run only inside Docker containers, as the calling user (see docker/).
PYTHON  ?= python3
PREFIX  ?= $(HOME)/.local
APP     := bebird-viewer
VENV    := .venv
PY      := $(VENV)/bin/python
SOURCES := viewer.py wifi.py
# Qt modules the viewer doesn't use; leaving them out keeps the binary smaller
EXCLUDES := $(addprefix --exclude-module PyQt6.,QtWebEngineCore QtWebEngineWidgets QtQml QtQuick \
            QtMultimedia QtPdf QtSql QtTest QtDesigner QtBluetooth QtPositioning QtSensors)

.PHONY: help venv run app app-image app-lock install uninstall \
        android-image android-test android-apk android-release android-release-verify android-shell \
        release-sign clean distclean
.DEFAULT_GOAL := help

# Containers run as the calling user, so everything they write is owned by you, never root.
# Only the directory a build needs is mounted; nothing privileged, no Docker socket.
UIDGID       := $(shell id -u):$(shell id -g)
NOT_ROOT     = @test "$$(id -u)" != 0 || { echo "don't build as root: the output would be root-owned"; exit 1; }
DOCKER_RUN   := docker run --rm --user $(UIDGID) --cap-drop ALL --security-opt no-new-privileges
APP_IMAGE    := bebird-viewer-build:desktop
DROID_IMAGE  := bebird-viewer-build:android
GRADLE_VOL   := bebird-gradle
GRADLE       := ./gradlew --console=plain
DROID_MOUNTS := -v "$(CURDIR)/android:/work" -v $(GRADLE_VOL):/home/builder/.gradle
DROID_RUN    := $(DOCKER_RUN) $(DROID_MOUNTS) $(DROID_IMAGE)
RELEASE_DIR  := android/app/build/outputs/apk/release
APKSIGNER    := /opt/android-sdk/build-tools/34.0.0/apksigner
# Release signing (docs/building.md): the keystore file is mounted read-only, and the passwords
# are passed by name only, so their values never appear on a command line or in make's output.
KEYSTORE_IN  := /run/bebird/release.keystore
SIGN_ARGS    = $(if $(BEBIRD_KEYSTORE),-v "$(abspath $(BEBIRD_KEYSTORE)):$(KEYSTORE_IN):ro" \
               -e BEBIRD_KEYSTORE=$(KEYSTORE_IN) -e BEBIRD_KEYSTORE_PASSWORD -e BEBIRD_KEY_ALIAS -e BEBIRD_KEY_PASSWORD)

help:  ## list targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | expand -t 16

venv: $(VENV)/.ok  ## create .venv with the dependencies, for running from source

$(VENV)/.ok: requirements.txt
	$(PYTHON) -m venv $(VENV)
	$(PY) -m pip install --quiet --upgrade pip
	$(PY) -m pip install --quiet -r requirements.txt
	touch $@

run: venv  ## run the viewer from source
	$(PY) viewer.py

app: dist/$(APP)  ## build dist/bebird-viewer, a single self-contained executable (in Docker)

app-image:  ## build the desktop build image (Ubuntu 22.04, PyQt6, PyInstaller)
	$(NOT_ROOT)
	docker build -t $(APP_IMAGE) -f docker/desktop.Dockerfile .

app-lock: app-image  ## re-resolve docker/desktop-requirements.lock (pinned, hashed) from its .in file
	$(NOT_ROOT)
	$(DOCKER_RUN) -e CUSTOM_COMPILE_COMMAND="make app-lock" -v "$(CURDIR)/docker:/w" -w /w $(APP_IMAGE) sh -c \
		'python3 -m venv /tmp/pt && /tmp/pt/bin/pip install -q pip-tools && /tmp/pt/bin/pip-compile -q \
		--generate-hashes --allow-unsafe --strip-extras --no-emit-index-url \
		--output-file desktop-requirements.lock desktop-requirements.in'

# Only the sources are mounted, read-only, and the container has no network; only dist/ is
# writable. PyInstaller's work files stay inside the container.
dist/$(APP): $(SOURCES) docker/desktop-requirements.lock docker/desktop.Dockerfile | app-image
	mkdir -p dist
	$(DOCKER_RUN) --network none $(foreach f,$(SOURCES),-v "$(CURDIR)/$(f):/src/$(f):ro") \
		-v "$(CURDIR)/dist:/out" $(APP_IMAGE) \
		pyinstaller --noconfirm --clean --log-level WARN --onefile --windowed \
		--distpath /out --workpath /tmp/build --specpath /tmp/spec \
		--name $(APP) $(EXCLUDES) /src/viewer.py
	@ls -lh $@

install:  ## install the built binary and a desktop launcher under PREFIX (default ~/.local)
	@test -f dist/$(APP) || { echo "dist/$(APP) not found: run 'make app' first (as your user, not root)"; exit 1; }
	install -Dm755 dist/$(APP) "$(PREFIX)/bin/$(APP)"
	install -Dm644 packaging/$(APP).svg "$(PREFIX)/share/icons/hicolor/scalable/apps/$(APP).svg"
	@mkdir -p "$(PREFIX)/share/applications"
	sed 's|@BIN@|$(PREFIX)/bin/$(APP)|' packaging/$(APP).desktop.in > "$(PREFIX)/share/applications/$(APP).desktop"
	-update-desktop-database "$(PREFIX)/share/applications" 2>/dev/null
	@echo "installed $(PREFIX)/bin/$(APP)"

uninstall:  ## remove what install added
	rm -f "$(PREFIX)/bin/$(APP)" "$(PREFIX)/share/applications/$(APP).desktop" \
		"$(PREFIX)/share/icons/hicolor/scalable/apps/$(APP).svg"

android-image:  ## build the Android build image (JDK 17, Android SDK 35)
	$(NOT_ROOT)
	docker build --build-arg UID=$(shell id -u) --build-arg GID=$(shell id -g) -t $(DROID_IMAGE) -f docker/android.Dockerfile .

android-test: android-image  ## run the Android unit tests (in Docker)
	$(DROID_RUN) $(GRADLE) test

android-apk: android-image  ## build the debug APK into android/app/build/outputs/apk/debug/ (in Docker)
	$(DROID_RUN) $(GRADLE) assembleDebug
	@ls -lh android/app/build/outputs/apk/debug/*.apk

# apksigner checks only what a device at the APK's minSdk (29) needs, which is v3 alone;
# --min-sdk-version 24 makes it verify the v2 signature as well. $(1) is the APK.
verify_apk = $(DOCKER_RUN) --network none -v "$(abspath $(1)):/apk/$(notdir $(1)):ro" $(DROID_IMAGE) \
	$(APKSIGNER) verify --print-certs -v --min-sdk-version 24 "/apk/$(notdir $(1))" && sha256sum "$(1)"

# Signed when BEBIRD_KEYSTORE and the other BEBIRD_* variables are set, unsigned otherwise.
# The output directory is emptied first so a stale signed or unsigned APK can't be picked up.
android-release: android-image  ## build the release APK into android/app/build/outputs/apk/release/ (in Docker)
	$(if $(BEBIRD_KEYSTORE),@test -f "$(BEBIRD_KEYSTORE)" || { echo "BEBIRD_KEYSTORE: no such file: $(BEBIRD_KEYSTORE)"; exit 1; })
	rm -rf $(RELEASE_DIR)
	$(DOCKER_RUN) $(SIGN_ARGS) $(DROID_MOUNTS) $(DROID_IMAGE) $(GRADLE) assembleRelease
	@ls -lh $(RELEASE_DIR)/*.apk
ifdef BEBIRD_KEYSTORE
	@$(call verify_apk,$(RELEASE_DIR)/app-release.apk)
else
	@echo "unsigned: BEBIRD_KEYSTORE is not set"; sha256sum $(RELEASE_DIR)/app-release-unsigned.apk
endif

android-release-verify: android-image  ## check an APK's v2/v3 signature, print its certificate and SHA-256: APK=path
	@test -f "$(APK)" || { echo "usage: make android-release-verify APK=path/to/app.apk"; exit 1; }
	@$(call verify_apk,$(APK))

# Runs on your machine, not in Docker: it needs your gpg-agent and gh login. The release workflow
# never sees the GPG key; this adds a detached signature of the published checksum afterwards.
REL_TAG = v$(VERSION)
REL_APK = bebird-$(VERSION).apk
release-sign:  ## GPG-sign a published release's .sha256 and upload the .asc: VERSION=X.Y.Z [GPG_KEY=id]
	@test -n "$(VERSION)" || { echo "usage: make release-sign VERSION=X.Y.Z [GPG_KEY=key id]"; exit 1; }
	@set -e; d=$$(mktemp -d); trap 'rm -rf "$$d"' EXIT; \
	gh release download $(REL_TAG) --dir "$$d" --pattern $(REL_APK) --pattern $(REL_APK).sha256; \
	(cd "$$d" && sha256sum -c $(REL_APK).sha256); \
	gpg --armor --detach-sign $(if $(GPG_KEY),--local-user "$(GPG_KEY)") \
		--output "$$d/$(REL_APK).sha256.asc" "$$d/$(REL_APK).sha256"; \
	gpg --verify "$$d/$(REL_APK).sha256.asc" "$$d/$(REL_APK).sha256"; \
	gh release upload $(REL_TAG) "$$d/$(REL_APK).sha256.asc"; \
	echo "uploaded $(REL_APK).sha256.asc to $(REL_TAG)"

android-shell: android-image  ## open a shell in the Android build container
	$(DOCKER_RUN) -it -v "$(CURDIR)/android:/work" -v $(GRADLE_VOL):/home/builder/.gradle $(DROID_IMAGE) bash

clean:  ## remove build output (desktop and Android)
	rm -rf build dist $(APP).spec __pycache__ android/build android/app/build android/.gradle android/.kotlin

distclean: clean  ## also remove .venv, the Gradle cache volume and the build images
	rm -rf $(VENV)
	-docker volume rm $(GRADLE_VOL)
	-docker image rm $(APP_IMAGE) $(DROID_IMAGE)
