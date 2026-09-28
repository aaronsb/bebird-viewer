# bebird-viewer: run from source, or build and install a standalone binary.
PYTHON  ?= python3
PREFIX  ?= $(HOME)/.local
APP     := bebird-viewer
VENV    := .venv
PY      := $(VENV)/bin/python
SOURCES := viewer.py wifi.py
# Qt modules the viewer doesn't use; leaving them out keeps the binary smaller
EXCLUDES := $(addprefix --exclude-module PyQt6.,QtWebEngineCore QtWebEngineWidgets QtQml QtQuick \
            QtMultimedia QtPdf QtSql QtTest QtDesigner QtBluetooth QtPositioning QtSensors)

.PHONY: help venv run app install uninstall clean distclean
.DEFAULT_GOAL := help

help:  ## list targets
	@grep -E '^[a-z]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | expand -t 12

venv: $(VENV)/.ok  ## create .venv with the dependencies and PyInstaller

$(VENV)/.ok: requirements.txt
	$(PYTHON) -m venv $(VENV)
	$(PY) -m pip install --quiet --upgrade pip
	$(PY) -m pip install --quiet -r requirements.txt pyinstaller
	touch $@

run: venv  ## run the viewer from source
	$(PY) viewer.py

app: dist/$(APP)  ## build dist/bebird-viewer, a single self-contained executable

dist/$(APP): $(SOURCES) $(VENV)/.ok
	$(PY) -m PyInstaller --noconfirm --clean --log-level WARN --onefile --windowed \
		--name $(APP) $(EXCLUDES) viewer.py
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

clean:  ## remove build output
	rm -rf build dist $(APP).spec __pycache__

distclean: clean  ## also remove .venv
	rm -rf $(VENV)
