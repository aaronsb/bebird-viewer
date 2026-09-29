# Building

Both builds run in Docker containers, never directly on your machine, so you need **Docker with BuildKit** (the `buildx` plugin; on Arch, install `docker-buildx`) and `make`; nothing else. The containers run as your user, so everything they produce is owned by you, and they mount only the directory the build needs. The build images are defined in [`docker/`](../docker/). `make help` lists every target.

| Target | Does |
|---|---|
| `make app` | build the desktop binary `dist/bebird-viewer` |
| `make android-test` | run the Android unit tests (JVM, no device needed) |
| `make android-apk` | build the Android debug APK |
| `make android-shell` | open a shell in the Android build container |
| `make clean` | remove build output (desktop and Android) |
| `make distclean` | also remove `.venv`, the Gradle cache volume and the build images |

`make app-image` and `make android-image` (re)build the images; the other targets do that for you, and it's quick once Docker has the layers cached. The desktop image installs its Python packages from [`docker/desktop-requirements.lock`](../docker/desktop-requirements.lock), pinned by version and hash; `make app-lock` re-resolves it from `docker/desktop-requirements.in`.

Caches stay in Docker rather than in your home directory: Gradle's (dependencies, the Gradle distribution, and the Android debug signing key) in the named volume `bebird-gradle`, and pip's in Docker's build cache while the desktop image is built. `make distclean` removes the volume and the images. The pip cache goes when Docker prunes its build cache; note that `docker builder prune` clears the build cache of every project on the machine, not just this one. Removing the volume also replaces the debug signing key, so a debug APK built afterwards won't install over an earlier one without uninstalling it first.

## Desktop

`make app` builds the standalone desktop binary; installing and running it are covered in [desktop.md](desktop.md#standalone-app).

## Android

```sh
make android-test   # JVM unit tests
make android-apk    # debug APK
```

The build image carries JDK 17 and the Android SDK (platform 35), so neither is needed on your machine. The debug APK lands in `android/app/build/outputs/apk/debug/`. Install it with `adb install` or by opening it on the phone.

## CI

CI runs the same make targets in an image built from the same Dockerfile for every change under `android/` and uploads the APK. The workflow is [`.github/workflows/android.yml`](../.github/workflows/android.yml).
