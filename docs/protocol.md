# Protocol

How the scope talks over Wi-Fi, as used by both apps. The Android app's [`Protocol.kt`](../android/app/src/main/java/com/bockelie/bebird/proto/Protocol.kt) and [`viewer.py`](../viewer.py) both follow this page.

> Not affiliated with or endorsed by Bebird. The protocol below was worked out for interoperability by observing the device on the network and by studying how the official Android app talks to it. No vendor code or firmware is included in this repository.

## Tested device

Tested with one device: model `ES`, firmware `4.0.24.997`, SoC Beken BK7231U. Other Bebird Wi-Fi models from the same family probably speak the same protocol, but the light ranges and some commands may differ. Reports welcome.

## Ports

All traffic is UDP between your machine and `192.168.5.1`. Multi-byte integers are big-endian unless noted.

| Port | Direction | Purpose |
|---|---|---|
| 58080 | ⇄ | video control and MJPEG data |
| 58090 | ⇄ | commands and replies |
| 58098 | ⇄ | motion sensor channel (not needed by the viewer) |
| 58099 | ← broadcast | status beacon, JSON, ~10×/s |

## Video (58080)

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

## Commands (58090)

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
| `66 3E` | — | named "reboot" in the app; in practice it switches the scope off until its power button is pressed. Only the Android app sends it, after STOP: **Power off scope**, Quit, and powering off when the app lets go of the scope |

The light is very non-linear. On the tested unit it's invisible below about 20 and stops getting brighter around 48, so the viewer maps its 1–100 % slider onto raw 22–50 and uses 0 for off.

## Beacon (58099)

The scope broadcasts a JSON status beacon about ten times a second, for example:

```json
{"brand":"bebird","model":"ES","mac":"…","ssid":"bebird-ES-XXXXXX","password":"MTIzNDU2Nzg=",
 "wifi_encrypt":false,"ipaddr":"192.168.5.1","button":1,"video_on":0,"battery":65636}
```

`battery` uses the same state/percent packing as `66 3A`. `mac` is the Wi-Fi chip's station MAC, not the access point's BSSID: on the tested ES the BSSID is that address plus one (the 48-bit value + 1, e.g. `…9E` → `…9F`), a common convention for these chips' soft-AP. The Android app uses this to reconnect to a known scope by BSSID when Android won't reveal the BSSID itself. The `password` field is base64 (`12345678`) and is broadcast in the clear even though the access point is open.

