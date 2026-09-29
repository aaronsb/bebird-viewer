# Building

Both builds run in Docker containers, never directly on your machine, so you need **Docker with BuildKit** (the `buildx` plugin; on Arch, install `docker-buildx`) and `make`; nothing else. The containers run as your user, so everything they produce is owned by you, and they mount only the directory the build needs. The build images are defined in [`docker/`](../docker/). `make help` lists every target.

| Target | Does |
|---|---|
| `make app` | build the desktop binary `dist/bebird-viewer` |
| `make android-test` | run the Android unit tests (JVM, no device needed) |
| `make android-apk` | build the Android debug APK |
| `make android-release` | build the unsigned Android release APK (see [Release builds](#release-builds)) |
| `make android-release-sign` | sign it offline with the release key |
| `make android-release-verify APK=…` | check an APK's signature and print its certificate and SHA-256 |
| `make android-lock` | re-record the Gradle dependency checksums (see [Dependency verification](#dependency-verification)) |
| `make release-sign VERSION=…` | GPG-sign a published release's checksum (on your machine; see [Cutting a release](#cutting-a-release)) |
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

## Dependency verification

Gradle checks every dependency and plugin it downloads against the SHA-256 checksums in [`android/gradle/verification-metadata.xml`](../android/gradle/verification-metadata.xml) and fails the build on a mismatch or an unlisted artifact. After adding or upgrading a dependency or plugin, run

```sh
make android-lock
```

which resolves everything afresh for the tasks CI runs (tests, debug and release APKs) and records the new checksums. It records whatever it downloads, so review the diff: it should add or change only the artifacts you meant to.

## Release builds

Building and signing are separate steps, so the Gradle build, which runs plugins and dependencies with network access, never sees the signing key:

```sh
make android-release        # unsigned release APK
make android-release-sign   # sign it offline
```

`make android-release` builds `app-release-unsigned.apk` into `android/app/build/outputs/apk/release/`. It is shrunk and obfuscated by R8 (rules in [`android/app/proguard-rules.pro`](../android/app/proguard-rules.pro)), and the build checks that it still carries every asset in `android/app/required-assets.txt`, as it does for the debug APK. Android won't install an unsigned APK, and the release is never signed with the debug key.

`make android-release-sign` signs it into `app-release.apk` in a container with no network that sees only that directory and the keystore (read-only). It checks the APK is zip-aligned, signs with `apksigner` (v2 and v3 signatures; v3 allows rotating to a new key later; no v1, which only Android 6 and older need), then prints the verification, the signing certificate and the APK's SHA-256. It reads the key from these environment variables:

| Variable | Value |
|---|---|
| `BEBIRD_KEYSTORE` | path to the keystore file |
| `BEBIRD_KEYSTORE_PASSWORD` | the keystore's password |
| `BEBIRD_KEY_ALIAS` | the key's alias in the keystore |
| `BEBIRD_KEY_PASSWORD` | the key's password (for a PKCS12 keystore, the same as the keystore's) |

The passwords go into the container by name only, so they don't appear on a command line or in `make`'s output. `make android-release-verify APK=path/to/app.apk` runs the same check on any APK.

Keystores and signing properties never go in git; `.gitignore` excludes `*.jks`, `*.keystore`, `*.p12`, `*.pfx` and `release-signing.properties`.

### Creating the release key

Do this once, outside the repository. `keytool` comes with the build image, so nothing needs installing:

```sh
mkdir -p ~/bebird-signing && chmod 700 ~/bebird-signing
make android-image
docker run --rm -it --user "$(id -u):$(id -g)" --network none -v ~/bebird-signing:/keys \
    bebird-viewer-build:android keytool -genkeypair -keystore /keys/bebird-release.keystore \
    -storetype PKCS12 -alias bebird -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=Aaron Bockelie, O=bebird-viewer"
```

That makes a 4096-bit RSA key valid for 30 years; `keytool` asks for the password. Print the certificate's SHA-256 fingerprint with:

```sh
docker run --rm -it --user "$(id -u):$(id -g)" --network none -v ~/bebird-signing:/keys:ro \
    bebird-viewer-build:android keytool -list -v -keystore /keys/bebird-release.keystore -alias bebird
```

**Back the keystore and its password up**, in at least two places that don't depend on each other (an encrypted offline copy and a password manager, say). Android installs an update only if it is signed with the same key as the installed app, so if the key is lost, nobody can update their installed app: every user would have to uninstall it and install an APK under a new key. The key is also what identifies the app for Android developer verification (below).

### Developer verification

Android now requires apps on certified devices to come from a verified developer, including apps installed from outside Google Play. Enforcement starts in Brazil, Indonesia, Singapore and Thailand on 2026-09-30 and extends worldwide from 2027. Register the package name `com.bockelie.bebird` and the release certificate's SHA-256 fingerprint in the Android Developer Console. Source: [developer.android.com/developer-verification](https://developer.android.com/developer-verification).

### GitHub environment and secrets

The [release workflow](../.github/workflows/release.yml) reads the key from the secrets of a GitHub environment named `release`, and only its sign job uses that environment. Restrict the environment to release tags, so no other workflow, branch or manual run on a branch can read the key:

```sh
repo=aaronsb/bebird-viewer
gh api -X PUT repos/$repo/environments/release \
    -F 'deployment_branch_policy[protected_branches]=false' \
    -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/$repo/environments/release/deployment-branch-policies -f name='v*.*.*' -f type=tag
```

(In the web UI: Settings → Environments → New environment `release` → Deployment branches and tags → Selected branches and tags → add a tag rule `v*.*.*`.) You can also add yourself as a required reviewer there, so every signing run waits for your approval.

Then set the secrets and the variable in that environment. The keystore goes in from its file, and for the passwords `gh` prompts, so no value lands in your shell history:

```sh
base64 -w0 ~/bebird-signing/bebird-release.keystore | gh secret set BEBIRD_KEYSTORE_BASE64 --env release
gh secret set BEBIRD_KEYSTORE_PASSWORD --env release
gh secret set BEBIRD_KEY_PASSWORD --env release
gh secret set BEBIRD_KEY_ALIAS --env release --body bebird
gh variable set BEBIRD_CERT_SHA256 --env release --body "<the SHA-256 fingerprint>"
```

If any of them were set at repository level earlier, remove those copies (`gh secret delete NAME`, `gh variable delete BEBIRD_CERT_SHA256`), since every workflow can read repository secrets.

`BEBIRD_CERT_SHA256` is required for publishing: the workflow refuses to publish an APK signed with any other certificate, or when the variable is missing. It isn't secret, so it is a variable (a secret of that name works too).

### Cutting a release

1. In `android/app/build.gradle.kts`, raise `versionCode` by one and set `versionName` to the new version (`X.Y.Z`).
2. In `CHANGELOG.md`, give the version its own section, `## [X.Y.Z] - YYYY-MM-DD`, and a link at the bottom.
3. Merge that to `main`, then tag it, signed with your GPG key, and push the tag:

   ```sh
   git tag -s vX.Y.Z -m "bebird-viewer X.Y.Z"
   git push origin vX.Y.Z
   ```

4. When the workflow has published the release, sign its checksum on your machine:

   ```sh
   make release-sign VERSION=X.Y.Z        # GPG_KEY=<key id> to use a key other than the default
   ```

The workflow checks that the tag matches `versionName`, runs the unit tests and builds the unsigned APK in one job, signs it offline and verifies the signature and certificate in a second job (the only one with the key), and creates the GitHub Release with `bebird-X.Y.Z.apk`, its `.sha256`, `LICENSE` and `LICENSES/Apache-2.0.txt`. The release notes are the version's `CHANGELOG.md` section, or GitHub's generated notes if there is none, followed by how to verify the download. R8's `mapping.txt`, which turns obfuscated stack traces back into source names, is kept as a workflow artifact; download it if you want it beyond GitHub's artifact retention.

The workflow doesn't require the tag to be signed, since it has no public key to check it against, but a signed tag lets anyone check that the release was cut from a commit you vouched for (`git tag -v vX.Y.Z`).

GPG never runs in CI; the private key stays on your machine. `make release-sign` runs on the host, not in Docker, because it uses your `gpg-agent` and your `gh` login. It downloads the APK and its `.sha256` from the release, checks one against the other, makes a detached ASCII-armoured signature of the `.sha256` (`gpg --armor --detach-sign`) and uploads it as `bebird-X.Y.Z.apk.sha256.asc`.

To try the workflow without publishing, run it by hand (Actions → Release → Run workflow, or `gh workflow run release.yml --ref <ref>`). It uploads the APK as a workflow artifact only: signed when run on a `v*.*.*` tag, which the `release` environment allows, and unsigned on any other ref.

### Verifying a release

With the release's files in one directory:

```sh
sha256sum -c bebird-X.Y.Z.apk.sha256                          # the APK matches its checksum
gpg --verify bebird-X.Y.Z.apk.sha256.asc bebird-X.Y.Z.apk.sha256   # the checksum is signed by the maintainer's key
apksigner verify --print-certs bebird-X.Y.Z.apk               # Android signing certificate
```

The certificate's SHA-256 digest must be the one given in the release notes; it is the same for every release. `make android-release-verify APK=bebird-X.Y.Z.apk` runs `apksigner` in the build container.

## CI

CI runs the same make targets in an image built from the same Dockerfile for every change under `android/`: the unit tests, the debug APK (uploaded as an artifact) and the unsigned release APK, which checks that R8 and the required-assets check pass. Dependency verification applies there too. The workflow is [`.github/workflows/android.yml`](../.github/workflows/android.yml).
