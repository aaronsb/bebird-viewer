# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Versions are those of the Android app (`versionName`); the desktop app ships from source and has no separate version.

## [0.1.0] - unreleased

The first release of the Android app, as a signed APK on GitHub Releases.

### Added

- Android app (Android 10 or later, app ID `com.bockelie.bebird`) for Bebird "ES" Wi-Fi scopes, with no vendor app, account or internet connection needed:
  - Joins the scope's Wi-Fi as an app-only network, leaving the phone's other networking untouched. Remembers each scope, with an optional nickname, and reconnects to the last one at launch.
  - Live video kept upright by the scope's motion sensor, with a manual trim and pinch-zoom.
  - Tip-light control, read back from the scope to confirm the level.
  - Snapshots (JPEG, plus a crop of the zoomed view) and MP4 recordings under `Pictures/Bebird/`, with the status band burned in and metadata matching the desktop app's. **Files** opens the folder.
  - Annotate a paused picture with ovals, boxes, arrows, freehand lines and text in five colours; marks can be moved or deleted, and Save keeps the plain picture and an annotated copy.
  - An approximate mm scale (ring, bowtie or bar) and a CLOSE indicator, estimated from focus, brightness, movement and the motion sensor; the scale is drawn into saved pictures.
  - BATTERY LOW warning for the scope's battery.
  - Hold-to-confirm Disconnect (2 s) and Quit (5 s, which also switches the scope off once video has started), with haptic feedback while they fill.
  - A grace period (1 minute by default) that keeps the connection while you switch apps briefly, with a countdown notification.
  - Light, dark or system theme, and screen-reader support for the live view.
- Desktop app (Linux, PyQt6): live video, light control, auto-rotate, snapshots and recordings, and Wi-Fi handling through NetworkManager, plus command-line tools. Runs from source or as a standalone binary.
- Containerized builds (`make`) and a release workflow that publishes the signed APK with its SHA-256 checksum, and `make release-sign` to add a GPG signature of the checksum.

[0.1.0]: https://github.com/aaronsb/bebird-viewer/releases/tag/v0.1.0
