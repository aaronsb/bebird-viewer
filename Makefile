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

.PHONY: help venv run app app-image install uninstall \
        android-image android-test android-apk android-shell clean distclean
.DEFAULT_GOAL := help

# Containers run as the calling user, so everything they write is owned by you, never root.
# Only the directory a build needs is mounted; nothing privileged, no Docker socket.
UIDGID       := $(shell id -u):$(shell id -g)
NOT_ROOT     = @test "$$(id -u)" != 0 || { echo "don't build as root: the output would be root-owned"; exit 1; }
DOCKER_RUN   := docker run --rm --user $(UIDGID) --security-opt no-new-privileges
APP_IMAGE    := bebird-viewer-build:desktop
DROID_IMAGE  := bebird-viewer-build:android
GRADLE_VOL   := bebird-gradle
GRADLE       := ./gradlew --console=plain
DROID_RUN    := $(DOCKER_RUN) -v "$(CURDIR)/android:/work" -v $(GRADLE_VOL):/home/builder/.gradle $(DROID_IMAGE)

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

# The source is mounted read-only and the container has no network; only dist/ is writable.
# PyInstaller's work files stay inside the container.
dist/$(APP): $(SOURCES) requirements.txt docker/desktop.Dockerfile | app-image
	mkdir -p dist
	$(DOCKER_RUN) --network none -v "$(CURDIR):/src:ro" -v "$(CURDIR)/dist:/out" $(APP_IMAGE) \
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

android-shell: android-image  ## open a shell in the Android build container
	$(DOCKER_RUN) -it -v "$(CURDIR)/android:/work" -v $(GRADLE_VOL):/home/builder/.gradle $(DROID_IMAGE) bash

clean:  ## remove build output (desktop and Android)
	rm -rf build dist $(APP).spec __pycache__ android/build android/app/build android/.gradle android/.kotlin

distclean: clean  ## also remove .venv, the Gradle cache volume and the build images
	rm -rf $(VENV)
	-docker volume rm $(GRADLE_VOL)
	-docker image rm $(APP_IMAGE) $(DROID_IMAGE)
