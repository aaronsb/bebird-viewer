# bebird-viewer

A small Linux viewer for **Bebird "ES" Wi-Fi otoscope / ear cameras** that doesn't need the vendor app, an account, or an internet connection. It talks only to the scope, over the scope's own Wi-Fi.

- Live video (MJPEG, ~10 fps at 480×480)
- Tip-light dimmer mapped onto the LED's visible range, applied after a short debounce and read back to confirm
- Auto-rotate from the scope's built-in motion sensor, with a manual trim
- Snapshots (as displayed, with date, roll, light and battery in the EXIF metadata) and recordings (raw stream, `.mkv`)
- Battery level and charging state
- Wi-Fi handling: finds the scope's network, joins it without taking over your normal networking, and rejoins and restarts video when the scope comes back after a power cycle

> Not affiliated with or endorsed by Bebird. The protocol below was worked out for interoperability by observing the device on the network and by studying how the official Android app talks to it. No vendor code or firmware is included in this repository.

## Screenshots

| | |
|---|---|
| ![The viewer showing a toy Unimog truck from a few centimetres away](docs/media/qt-app.png) | ![A close-up of the same truck's wheel, filling the view](docs/media/focused.png) |
| The viewer, pointed at a Hot Wheels Unimog for scale. | One wheel of the same truck at the lens's focus distance, which shows the field of view and sharpness you get in use. |

## Status

Tested with one device: model `ES`, firmware `4.0.24.997`, SoC Beken BK7231U. Other Bebird Wi-Fi models from the same family probably speak the same protocol, but the light ranges and some commands may differ. Reports welcome.

## Requirements

- Linux (the tools use a Linux ioctl to find the Wi-Fi interface address)
- Python 3.10+ with **PyQt6** and **Pillow** (`pip install PyQt6 Pillow` or your distro's packages)
- **ffmpeg** for recording (optional); `ffplay` for `grab.py --live` (optional)
- **NetworkManager** (`nmcli`) for the built-in Wi-Fi controls. Without it, join the scope's network yourself.
- A Wi-Fi interface you can dedicate to the scope while viewing. If your only internet connection is Wi-Fi on the same card, you'll be offline while connected to the scope.

## Setup

The scope is an **open access point** named `bebird-ES-XXXXXX` that gives out addresses in `192.168.5.0/24`; the camera is `192.168.5.1`.

The viewer handles joining it. Any network whose name starts with `bebird` counts as a scope. The Wi-Fi row defaults to **Any Bebird scope**, which prefers the scope you used last (remembered in `~/.config/bebird/last-device.json`; **Forget** clears it) and otherwise takes the strongest one in range. You can also pick a specific network. Press **Connect**. The first time, it creates a NetworkManager connection for that network that
- never becomes the default route,
- has IPv6 turned off,
- doesn't autoconnect on its own,

so a wired link or another network keeps carrying your normal traffic. With **Auto-join** on (the default), the viewer rejoins the scope's network whenever it reappears, for example after the scope switches itself off and you power it back on, and restarts the video.

The Wi-Fi interface is picked automatically: the one you last used, else the first Wi-Fi device NetworkManager knows. To force one, set `BEBIRD_IFACE`, which also applies to the command-line tools:

```sh
export BEBIRD_IFACE=wlp10s0
```

To join by hand instead, for example without NetworkManager's GUI rights, use the equivalent command:

```sh
nmcli con add type wifi ifname wlan0 con-name bebird-ES-XXXXXX ssid bebird-ES-XXXXXX \
    ipv4.never-default yes ipv6.method disabled connection.autoconnect no
nmcli con up bebird-ES-XXXXXX
```

If you run a host firewall that drops inbound UDP (for example ufw's default), allow the scope's subnet on that interface:

```sh
sudo ufw allow in on wlan0 from 192.168.5.0/24   # your Wi-Fi interface
```

Every socket binds to the Wi-Fi interface's address, so nothing meant for the scope can leak onto another network that happens to also use `192.168.5.1`.

## Building

Both builds run in Docker containers, never directly on your machine, so you need **Docker** (and `make`); nothing else. The containers run as your user, so everything they produce is owned by you, and they mount only the directory the build needs. The build images are defined in [`docker/`](docker/).

| Target | Does |
|---|---|
| `make app` | build the desktop binary `dist/bebird-viewer` |
| `make android-test` | run the Android unit tests (JVM, no device needed) |
| `make android-apk` | build the Android debug APK |
| `make android-shell` | open a shell in the Android build container |
| `make clean` | remove build output (desktop and Android) |
| `make distclean` | also remove `.venv`, the Gradle cache volume and the build images |

`make app-image` and `make android-image` (re)build the images; the other targets do that for you, and it's quick once Docker has the layers cached.

Caches stay in Docker rather than in your home directory: Gradle's (dependencies, the Gradle distribution, and the Android debug signing key) in the named volume `bebird-gradle`, and pip's in Docker's build cache while the desktop image is built. `make distclean` removes the volume and the images; `docker builder prune` clears the build cache. Removing the volume also replaces the debug signing key, so a debug APK built afterwards won't install over an earlier one without uninstalling it first.

## Standalone app

`make app` builds `dist/bebird-viewer`, a single self-contained executable (about 55 MB: Python, Qt and Pillow included). The machine running it needs no Python install. It's built on Ubuntu 22.04, the oldest base the current PyQt6 wheels install on, so it runs on distributions with **glibc 2.35 or newer** (Ubuntu 22.04, Debian 12, Fedora 36 and later). The build container has no network access and sees the source read-only; only `dist/` is writable.

```sh
make app                                         # builds the image on first use, then the binary
make app && make install                         # binary to ~/.local/bin, plus a launcher entry and icon
make app && sudo make install PREFIX=/usr/local  # system-wide
make uninstall
make run                                         # or run from source in a local .venv (no build involved)
```

`make install` only copies an existing build, so the build never runs as root. The binary bundles Python, Qt and the X11 libraries Qt needs, but uses the host's OpenGL (`libGL`/`libEGL`) and Wayland client libraries, which any desktop system has. It still calls host programs: install **ffmpeg** for recording and **NetworkManager** (`nmcli`) for the Wi-Fi controls.

The single-file binary unpacks itself to a temporary directory on each launch, so it takes a few seconds to start.

## Android app

A native Android port (Kotlin, Jetpack Compose, Android 10+) lives in [`android/`](android/), app ID `com.bockelie.bebird`. **Work in progress:** so far it has the protocol core and frame reassembly with unit tests, and a placeholder screen; joining the scope's Wi-Fi and live video come next. Progress is tracked in [#8](https://github.com/aaronsb/bebird-viewer/issues/8).

```sh
make android-test   # JVM unit tests
make android-apk    # debug APK
```

The build image carries JDK 17 and the Android SDK (platform 35), so neither is needed on your machine. The debug APK lands in `android/app/build/outputs/apk/debug/`. CI runs the same make targets in the same image for every change under `android/` and uploads the APK.

## Usage

```sh
./live.sh                          # the viewer
./grab.py 10 --out frames          # save 10 s of JPEG frames
./grab.py --live | ffplay -f mjpeg -i -
./light.sh 30                      # raw light level 0-100 (0 = off); no argument reads it
./send.py 66 39 01 01              # raw command, prints the reply (here: board-info JSON)
```

### Viewer controls

| Control | Key | Does |
|---|---|---|
| Light button | `L` | tip light off / back to the previous level |
| Light slider | `↑` `↓` ±1, `PgUp` `PgDn` ±10 | brightness; sent 300 ms after you stop adjusting, then read back |
| Auto-rotate | `A` | keep the picture upright using the motion sensor |
| Roll | — | live roll angle from the sensor |
| Trim | `[` `]` | manual rotation added on top, 15° steps |
| Snapshot | `S` | saves the displayed image to `~/Pictures/bebird/`, with metadata (below) |
| Record | `R` | records the raw stream to `~/Pictures/bebird/*.mkv` |
| Reconnect | — | restart the video session |
| Wi-Fi row | — | interface, scope network (default: any), Scan, Connect/Disconnect, Auto-join, Forget |
| | `F` / `Esc` / `Q` | fullscreen / leave fullscreen / quit |

Light level, trim, auto-rotate, the Wi-Fi interface and network, and Auto-join are remembered in `~/.config/bebird/state.json`. On connect, the viewer waits for video, then re-applies the saved light level so the scope's state matches the UI.

`BEBIRD_DEBUG=1 ./live.sh` prints per-second packet and frame counts, and the JPEG decoder's warnings. About 1 frame in 100 from the scope's encoder has a stray byte before its end marker; it decodes fine, so that warning is hidden otherwise.

### Snapshot metadata

Each snapshot records how it was taken, which makes a series of images (for example a daily healing log) self-describing:

- **Standard EXIF:** date and time taken with timezone offset, Make/Model (`Bebird` / `ES`), Software (`bebird-viewer`), and Orientation "normal", since the rotation is already applied to the pixels.
- **ImageDescription:** a readable line, e.g. `roll 47 deg, rotated 47 deg (auto) + trim 0 deg, light 100% (scope 50), battery 100% (battery)`.
- **UserComment:** the same data as JSON, plus the scope's model, hardware and firmware.

The scope's serial number and unique ID are deliberately left out, because these pictures tend to get shared. The scope seems to answer the board-info request only soon after power-on, so the viewer caches the last answer in `~/.config/bebird/state.json`.

```sh
exiftool -ImageDescription -UserComment ~/Pictures/bebird/*.jpg
```

## Protocol

All traffic is UDP between your machine and `192.168.5.1`. Multi-byte integers are big-endian unless noted.

| Port | Direction | Purpose |
|---|---|---|
| 58080 | ⇄ | video control and MJPEG data |
| 58090 | ⇄ | commands and replies |
| 58098 | ⇄ | motion sensor channel (not needed by the viewer) |
| 58099 | ← broadcast | status beacon, JSON, ~10×/s |

### Video (58080)

- **Start:** `20 36`. **Stop:** `20 37`. The scope streams back to the source address and port of the START.
- **Send START once.** A second START while streaming re-initialises the camera; repeated STARTs eventually wedge video until a power cycle.
- **Keepalive:** after a single START, video stops within about a second unless the client keeps talking. The official app, and this viewer, poll the battery (`66 3A` on 58090) once a second, which keeps the stream alive.
- **Stop when you're done, from the same port.** The scope keeps streaming to every client that ever sent START until that client sends STOP, even if the port is dead. Kill a few clients without STOP and the bandwidth is split between ghosts; each live client gets only a few fps. The tools use a fixed client port (58081), send STOP before START, and send STOP on exit. A power cycle clears everything.

Each video datagram carries part of a JPEG:

| Byte | Meaning |
|---|---|
| 0 | frame id (all packets of one frame share it) |
| 1 | 0 = more packets follow; non-zero = last packet of the frame |
| 2 | packet index, starting at 1 |
| 3 | on the last packet: roll angle, low 8 bits |
| 4… | JPEG data |

Concatenate the payloads in index order. Drop the frame if any index is missing. On the last packet the roll angle is `b[3] + 256` when `b[1] == 2`, otherwise `b[3]`, giving 0–359°. To keep the picture upright, draw the frame rotated clockwise by that angle; the official app ignores changes under 3° to avoid jitter.

### Commands (58090)

| Command | Reply | Meaning |
|---|---|---|
| `66 39 01 01` | JSON, may span several datagrams | board info: model, firmware, SoC, battery details, light level, `rotate_angle`, `float_angle`, … |
| `66 3A` | 4 bytes | battery: high 16 bits state (0/1 on battery, 2 charging, 3 charged, 4 disconnecting), low 16 bits percent |
| `66 3C nn` | — | tip light level `nn` = 0–100; **takes effect only after** `66 3C FF` |
| `66 3C FF` | — | commit the light level |
| `66 3C FE` | 1 byte | query the light level |
| `66 3F 00 00` / `66 3F 00 01` | — | tip light off / on at the saved level (what the app sends when leaving / entering its camera screen) |
| `66 3F 02 01` / `66 3F 02 00` | — | blue status LED on / off (the charging logic overrides it quickly) |
| `66 3F 01 00` | — | **avoid:** on the ES this switches the camera off, light and video together, and it stays off until a power cycle |
| `66 3E` | — | named "reboot" in the app; in practice it switches the scope off |

The light is very non-linear. On the tested unit it's invisible below about 20 and stops getting brighter around 48, so the viewer maps its 1–100 % slider onto raw 22–50 and uses 0 for off.

### Beacon (58099)

The scope broadcasts a JSON status beacon about ten times a second, for example:

```json
{"brand":"bebird","model":"ES","mac":"…","ssid":"bebird-ES-XXXXXX","password":"MTIzNDU2Nzg=",
 "wifi_encrypt":false,"ipaddr":"192.168.5.1","button":1,"video_on":0,"battery":65636}
```

`battery` uses the same state/percent packing as `66 3A`. The `password` field is base64 (`12345678`) and is broadcast in the clear even though the access point is open.

## Security notes

- The access point is open by default, and anyone in range can join it and view the camera the same way this tool does.
- The protocol has no authentication. Anyone on the scope's network can stream, change settings, or switch it off.
- The scope reports no HTTP server of its own (`has_http: false`) and has no route to the internet unless someone configures it to join another network. This viewer never contacts anything but the scope.

See [docs/app-analysis.md](docs/app-analysis.md) for what the official app does on the network.

## License

Copyright (C) 2026 Aaron Bockelie. Licensed under the [GNU General Public License v3.0 or later](LICENSE) (`GPL-3.0-or-later`).

Versions up to and including commit `398f346` were released under the MIT License, and copies obtained under those terms stay MIT.
