#!/usr/bin/env python3
"""Bebird ES otoscope viewer with controls (PyQt6).

Protocol (recovered from the official app + live testing):
  :58080  video   START 20 36 once, STOP 20 37. MJPEG in UDP packets with a 4-byte
                  header [frame id][last flag][index from 1][angle low byte].
                  On the last packet the angle is b[3] (+256 when flag == 2).
  :58090  command battery 66 3A (also the stream keepalive, every 1 s);
                  light 66 3C <0-100> then commit 66 3C FF; query light 66 3C FE.
Never resend START while video flows: it re-initialises the camera and can wedge it.

Keys: L light on/off, Up/Down light +/-1, PgUp/PgDn light +/-10, A auto-rotate,
      [ ] rotation trim, S snapshot, R record, F fullscreen, Q quit.
"""
import fcntl, json, os, socket, struct, subprocess, sys, threading, time
from datetime import datetime
from io import BytesIO

from PIL import Image

import wifi

from PyQt6.QtCore import QObject, QRectF, Qt, QTimer, pyqtSignal, qInstallMessageHandler
from PyQt6.QtGui import QImage, QKeySequence, QPainter, QPainterPath, QPixmap, QShortcut, QTransform
from PyQt6.QtWidgets import (QApplication, QCheckBox, QComboBox, QHBoxLayout, QLabel, QPushButton, QSizePolicy,
                             QSlider, QSpinBox, QVBoxLayout, QWidget)

CAM = "192.168.5.1"
# The scope streams to every (ip, port) that ever sent START until that same port sends STOP,
# even if the port is dead. A fixed local port means a restarted viewer reuses its slot
# instead of splitting the scope's bandwidth with ghost clients.
VIDEO_CLIENT_PORT = 58081
START, STOP, BATTERY = b"\x20\x36", b"\x20\x37", b"\x66\x3a"
OUT_DIR = os.path.expanduser("~/Pictures/bebird")
STATE_FILE = os.path.expanduser("~/.config/bebird/state.json")
ASSERT_DELAY_MS = 1500  # after the first frame, before re-applying the light level
LIGHT_MAX = 50          # tip stops visibly brightening around 41-48; 50 is "full"
LIGHT_MIN = 22          # ~20 looks off (LED likely still conducting); 27 is very dim but visible
LIGHT_QUIESCE_MS = 300  # light control must be still this long before the level is sent
BAT_STATE = {0: "battery", 1: "battery", 2: "charging", 3: "charged", 4: "disconnect"}


def iface_ip(name):
    """IPv4 address of the scope's Wi-Fi interface, or None if not connected."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        req = struct.pack("256s", name.encode()[:15])
        return socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, req)[20:24])  # SIOCGIFADDR
    except OSError:
        return None
    finally:
        s.close()


def close_jpeg(jpg):
    """Trim a reassembled frame at its end-of-image marker, or repair a missing one. When the
    last packet is exactly full the scope can drop the marker's second byte, leaving a trailing
    FF; adding a whole FF D9 then gives the decoder a stray byte ("extraneous bytes before
    marker 0xd9"), so only D9 is added in that case, as the official app does."""
    end = jpg.rfind(b"\xff\xd9")
    if end > 0:
        return jpg[:end + 2]
    return jpg + (b"\xd9" if jpg.endswith(b"\xff") else b"\xff\xd9")


class Scope(QObject):
    """Owns the sockets. Receives video on a thread and emits complete JPEG frames."""
    frame = pyqtSignal(bytes, int)       # jpeg, angle in degrees
    battery = pyqtSignal(int, int)       # state, percent
    light = pyqtSignal(int)              # current light level from the scope
    boardinfo = pyqtSignal(dict)         # model, firmware, ... (requested on connect)
    status = pyqtSignal(str)

    def __init__(self, iface):
        super().__init__()
        self.iface = iface
        self.video = self.ctrl = None
        self.running = False
        self.thread = None
        self._info_buf = b""
        self._collecting_info = False  # board-info JSON is only accepted during the start-up window

    def _sock(self, ip, port, local_port=0):
        # Keep scope traffic on the Wi-Fi interface: another network (e.g. a wired LAN) may also
        # have a 192.168.5.1. SO_BINDTODEVICE pins the interface (unprivileged since Linux 5.7);
        # binding the Wi-Fi address is the fallback where that isn't allowed.
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, self.iface.encode())
        except OSError:
            pass
        s.bind((ip, local_port))
        s.connect((CAM, port))
        return s

    def start(self):
        ip = iface_ip(self.iface)
        if not ip:
            self.status.emit(f"not connected to the scope's Wi-Fi ({self.iface})")
            return False
        self.video, self.ctrl = self._sock(ip, 58080, VIDEO_CLIENT_PORT), self._sock(ip, 58090)
        self.video.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 5 << 20)
        self.video.settimeout(0.1)
        self.ctrl.setblocking(False)
        self.running = True
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        return True

    def stop(self):
        """Stop the receive thread, then send STOP and close the sockets. Waiting for the thread
        guarantees it can't send a START after our STOP (a repeated START can wedge the camera)."""
        self.running = False
        if self.thread:
            self.thread.join(timeout=1.5)
        for sock, msg in ((self.video, STOP), (self.ctrl, None)):
            if sock:
                try:
                    if msg:
                        sock.send(msg)
                except OSError:
                    pass
                sock.close()
        self.video = self.ctrl = None

    def send(self, data):
        try:
            self.ctrl and self.ctrl.send(data)
        except OSError:
            pass

    def set_light(self, level):
        self.send(bytes([0x66, 0x3C, level]))
        self.send(b"\x66\x3c\xff")  # commit: the level only takes effect after this

    def _handle_ctrl(self, b):
        # board info is JSON that may span several datagrams; only buffer it during the start-up
        # window, so a lost fragment can't swallow later battery/light replies
        if self._collecting_info and (self._info_buf or b.lstrip().startswith(b"{")):
            self._info_buf += b
            if self._info_buf.rstrip().endswith(b"}"):
                try:
                    self.boardinfo.emit(json.loads(self._info_buf))
                except ValueError:
                    pass
                self._info_buf = b""
        elif len(b) == 4:
            state, pct = int.from_bytes(b[:2], "big"), int.from_bytes(b[2:], "big")
            self.battery.emit(state, pct)
        elif len(b) == 1:
            self.light.emit(b[0])

    def _run(self):
        parts, fid, frames = {}, None, 0
        self.stats = {"pkts": 0, "done": 0, "dropped": 0, "superseded": 0}
        t_start = last_poll = last_rx = time.time()
        self.video.send(STOP)                   # clear any session left on our port
        # board info (for snapshot metadata). The scope seems to answer this only soon after
        # power-on, so the viewer also caches the last answer it got.
        self.send(b"\x66\x39\x01\x01")
        self._collecting_info, deadline = True, time.time() + 0.6
        while self.running and time.time() < deadline:
            try:
                self._handle_ctrl(self.ctrl.recv(4096))
            except (BlockingIOError, ConnectionRefusedError, OSError):
                time.sleep(0.02)
        self._collecting_info, self._info_buf = False, b""
        if not self.running:
            return                              # stopped during start-up: never send START
        self.video.send(START)                  # then exactly one START
        lost = False
        while self.running:
            now = time.time()
            if now - last_poll >= 1:
                self.send(BATTERY); last_poll = now
            try:
                while True:
                    self._handle_ctrl(self.ctrl.recv(4096))  # board info is ~1.4 KB
            except (BlockingIOError, ConnectionRefusedError, OSError):
                pass
            if frames == 0 and now - t_start > 2:  # app does this only before the first frame
                self.video.send(STOP); time.sleep(0.1); self.video.send(START); t_start = time.time()
                self.status.emit("waiting for video…")
            if frames and not lost and now - last_rx > 3:
                lost = True
                self.status.emit("video stopped — scope off or wedged (power-cycle it, then Reconnect)")
            try:
                d = self.video.recv(4096)
            except (socket.timeout, ConnectionRefusedError, OSError):
                continue
            last_rx = time.time()
            if lost:
                lost = False; self.status.emit("")
            if len(d) < 5:
                continue
            self.stats["pkts"] += 1
            if d[0] != fid:
                if parts: self.stats["superseded"] += 1  # frame never got its last packet
                parts, fid = {}, d[0]
            parts[d[2]] = d[4:]
            if not d[1]:
                continue
            n = max(parts)
            if not all(i in parts for i in range(1, n + 1)):
                self.stats["dropped"] += 1
            else:
                jpg = b"".join(parts[i] for i in range(1, n + 1))
                jpg = close_jpeg(jpg)
                if jpg[:2] == b"\xff\xd8":
                    frames += 1; self.stats["done"] += 1
                    self.frame.emit(jpg, d[3] + (256 if d[1] == 2 else 0))
            parts = {}


class Background(QObject):
    """Run a blocking call on a thread and deliver its result to a callback on the GUI thread."""
    done = pyqtSignal(object, object)

    def __init__(self):
        super().__init__()
        # a bound method of a QObject living on the GUI thread, so emits from workers are queued
        self.done.connect(self._deliver)

    def _deliver(self, cb, result):
        cb(result)

    def run(self, fn, cb):
        """cb receives fn()'s result, or the exception it raised."""
        def work():
            try:
                result = fn()
            except Exception as e:  # noqa: BLE001 - reported to the user by the callback
                result = e
            self.done.emit(cb, result)
        threading.Thread(target=work, daemon=True).start()


class Viewer(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("bebird")
        self.img = None            # last displayed (rotated) QImage
        self.angle = 0
        self.shown_angle = 0
        self.saved = self.load_state()
        self.iface = os.environ.get("BEBIRD_IFACE") or self.saved.get("iface") or wifi.default_iface()
        self.scope = Scope(self.iface)
        self.bg = Background()
        self.wifi_busy = False     # one nmcli operation at a time
        self.on_scope_wifi = False
        self.last_join_try = 0.0
        self.join_backoff = 6      # seconds between auto-join attempts; grows after failures
        self.light_level = self.saved.get("light", 100)
        self.light_before_off = self.saved.get("light_before_off", 100)
        self.asserted = False
        self.reported_light = None
        self.recorder = None
        self.fps_count, self.fps = 0, 0

        self.video = QLabel("connecting…")
        self.video.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.video.setMinimumSize(320, 320)
        self.video.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)
        self.video.setStyleSheet("background:#000; color:#aaa")

        self.light_btn = QPushButton("Light: on")
        self.light_btn.setCheckable(True); self.light_btn.setChecked(True)
        self.light_btn.clicked.connect(self.toggle_light)
        self.slider = QSlider(Qt.Orientation.Horizontal)
        self.slider.setRange(0, 100); self.slider.setValue(100); self.slider.setFixedWidth(140)
        self.slider.setSingleStep(1); self.slider.setPageStep(10)
        # debounce: any change restarts the timer; the level is sent once the control is quiet
        self.light_timer = QTimer(self, singleShot=True, interval=LIGHT_QUIESCE_MS)
        self.light_timer.timeout.connect(lambda: self.assert_light(self.slider.value()))
        self.slider.valueChanged.connect(self.on_slider_moved)
        self.slider_label = QLabel("100%"); self.slider_label.setFixedWidth(40)

        self.autorot = QCheckBox("Auto-rotate"); self.autorot.setChecked(True)
        self.offset = QSpinBox(); self.offset.setRange(-180, 180); self.offset.setSingleStep(15)
        self.offset.setSuffix("°"); self.offset.setPrefix("Trim ")
        self.offset.setToolTip("manual rotation trim added on top of auto-rotate ([ and ] keys)")
        self.roll_label = QLabel("Roll: –"); self.roll_label.setFixedWidth(80)
        self.roll_label.setToolTip("live roll angle from the scope's motion sensor")
        self.snap_btn = QPushButton("Snapshot"); self.snap_btn.clicked.connect(self.snapshot)
        self.rec_btn = QPushButton("Record"); self.rec_btn.setCheckable(True)
        self.rec_btn.clicked.connect(self.toggle_record)
        self.reconnect_btn = QPushButton("Reconnect"); self.reconnect_btn.clicked.connect(self.reconnect)

        self.iface_box = QComboBox(); self.iface_box.setToolTip("Wi-Fi interface used for the scope")
        self.iface_box.addItems(sorted(set(wifi.wifi_devices()) | {self.iface}))
        self.iface_box.setCurrentText(self.iface)
        self.iface_box.currentTextChanged.connect(self.on_iface_changed)
        self.net_box = QComboBox(); self.net_box.setMinimumWidth(170)
        self.net_box.setToolTip("which scope to join: any Bebird network (preferring the last one used), "
                                "or a specific one")
        self.net_box.addItem("Any Bebird scope", None)
        if self.saved.get("network"):
            self.net_box.addItem(self.saved["network"], self.saved["network"])
            self.net_box.setCurrentIndex(1)
        self.forget_btn = QPushButton("Forget"); self.forget_btn.clicked.connect(self.forget_device)
        self.seen = {}             # ssid -> bssid from the latest scan
        self.update_forget_tip()
        self.scan_btn = QPushButton("Scan"); self.scan_btn.clicked.connect(lambda: self.scan_wifi("yes"))
        self.wifi_btn = QPushButton("Connect"); self.wifi_btn.clicked.connect(self.toggle_wifi)
        self.autojoin = QCheckBox("Auto-join")
        self.autojoin.setToolTip("rejoin the scope's Wi-Fi and restart video whenever it comes back")
        self.autojoin.setChecked(self.saved.get("autojoin", True))
        self.wifi_label = QLabel("Wi-Fi: …")

        self.status = QLabel("")
        self.info = QLabel("")

        controls = QHBoxLayout()
        for w in (self.light_btn, self.slider, self.slider_label, self.autorot, self.roll_label, self.offset,
                  self.snap_btn, self.rec_btn, self.reconnect_btn):
            controls.addWidget(w)
        controls.addStretch()
        wifi_row = QHBoxLayout()
        for w in (QLabel("Wi-Fi"), self.iface_box, self.net_box, self.scan_btn, self.wifi_btn,
                  self.autojoin, self.forget_btn, self.wifi_label):
            wifi_row.addWidget(w)
        wifi_row.addStretch()
        bottom = QHBoxLayout()
        bottom.addWidget(self.status); bottom.addStretch(); bottom.addWidget(self.info)
        layout = QVBoxLayout(self)
        layout.addWidget(self.video, 1); layout.addLayout(controls); layout.addLayout(wifi_row)
        layout.addLayout(bottom)

        keys = {"L": self.toggle_light, "Up": lambda: self.nudge_light(1),
                "Down": lambda: self.nudge_light(-1), "PgUp": lambda: self.nudge_light(10),
                "PgDown": lambda: self.nudge_light(-10), "A": self.autorot.toggle,
                "[": lambda: self.offset.stepBy(-1), "]": lambda: self.offset.stepBy(1),
                "S": self.snapshot, "R": self.rec_btn.click, "F": self.toggle_fullscreen,
                "Q": self.close, "Esc": self.exit_fullscreen}
        for k, fn in keys.items():
            QShortcut(QKeySequence(k), self, activated=fn)

        self.on_light(self.light_level)
        self.offset.setValue(self.saved.get("offset", 0))
        self.autorot.setChecked(self.saved.get("autorotate", True))
        self.scope.frame.connect(self.on_frame)
        self.scope.battery.connect(self.on_battery)
        self.scope.boardinfo.connect(self.on_boardinfo)
        self.scope.light.connect(self.on_reported_light)
        self.scope.status.connect(self.status.setText)
        self.bat_text = ""
        self.board, self.battery = self.saved.get("scope", {}), None
        t = QTimer(self); t.timeout.connect(self.tick); t.start(1000)
        self.resize(760, 840)
        self.video.setText("Looking for the scope's Wi-Fi…")
        w = QTimer(self); w.timeout.connect(self.poll_wifi); w.start(3000)
        self.poll_wifi()
        self.scan_wifi("auto")

    # --- light -------------------------------------------------------------
    def apply_light(self, level):
        level = max(0, min(100, level))
        self.scope.set_light(self.to_scope(level))
        self.on_light(level)

    @staticmethod
    def to_scope(level):
        """Viewer 0% -> scope 0 (off); 1-100% -> scope LIGHT_MIN..LIGHT_MAX, the visible range.

        The scope accepts 0-100 but the LED is invisible below ~20 and saturates near 50."""
        if level <= 0:
            return 0
        return round(LIGHT_MIN + (level - 1) * (LIGHT_MAX - LIGHT_MIN) / 99)

    def on_reported_light(self, level):
        self.reported_light = level

    def on_light(self, level):
        self.light_level = level
        if level:
            self.light_before_off = level
        self.slider.blockSignals(True); self.slider.setValue(level); self.slider.blockSignals(False)
        self.slider_label.setText(f"{level}%")
        self.light_btn.setChecked(level > 0)
        self.light_btn.setText("Light: on" if level else "Light: off")

    def toggle_light(self):
        self.apply_light(0 if self.light_level else (self.light_before_off or 100))

    def nudge_light(self, delta):
        self.slider.setValue(self.slider.value() + delta)  # goes through the debounce

    def on_slider_moved(self, v):
        self.slider_label.setText(f"{v}%")
        self.status.setText(f"light {v}%…")
        self.light_timer.start()

    # --- video -------------------------------------------------------------
    def on_frame(self, jpg, angle):
        self.angle = angle
        self.roll_label.setText(f"Roll: {angle}°")
        img = QImage.fromData(jpg, "JPEG")
        if img.isNull():
            return
        # Same as the official app: rotate the frame by +angle (clockwise, y-down), ignoring
        # changes under 3 degrees so sensor noise doesn't make the picture twitch.
        diff = abs(angle - self.shown_angle) % 360
        if min(diff, 360 - diff) >= 3:
            self.shown_angle = angle
        rot = self.offset.value() + (self.shown_angle if self.autorot.isChecked() else 0)
        if rot % 360:
            w, h = img.width(), img.height()
            r = img.transformed(QTransform().rotate(rot), Qt.TransformationMode.SmoothTransformation)
            img = r.copy((r.width() - w) // 2, (r.height() - h) // 2, w, h)
        self.img = img
        if self.recorder:
            try:
                self.recorder.stdin.write(jpg)
            except (BrokenPipeError, OSError):
                self.toggle_record(False)
        self.fps_count += 1
        if not self.asserted:
            self.asserted = True
            QTimer.singleShot(ASSERT_DELAY_MS, self.assert_light)
        self.show_image()

    def show_image(self):
        if self.img is None:
            return
        side = min(self.video.width(), self.video.height())
        pm = QPixmap.fromImage(self.img).scaled(side, side, Qt.AspectRatioMode.KeepAspectRatio,
                                                Qt.TransformationMode.SmoothTransformation)
        # round mask: the scope image is circular, so hide the corners the rotation exposes
        out = QPixmap(pm.size()); out.fill(Qt.GlobalColor.black)
        p = QPainter(out)
        path = QPainterPath(); path.addEllipse(QRectF(out.rect()))
        p.setRenderHint(QPainter.RenderHint.Antialiasing); p.setClipPath(path)
        p.drawPixmap(0, 0, pm); p.end()
        self.video.setPixmap(out)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.show_image()

    def on_boardinfo(self, info):
        # keep only non-identifying fields; cached so snapshots have them even when the
        # scope doesn't answer (it seems to reply only soon after power-on)
        board = {k: info[k] for k in ("brand", "model", "hardware", "firmware", "soc") if k in info}
        if board:
            self.board = board
            self.save_state()

    def on_battery(self, state, pct):
        self.battery = (pct, BAT_STATE.get(state, str(state)))
        self.bat_text = f"battery {pct}% ({BAT_STATE.get(state, state)})"

    def tick(self):
        self.fps, self.fps_count = self.fps_count, 0
        if os.environ.get("BEBIRD_DEBUG") and getattr(self.scope, "stats", None):
            print(f"{self.scope.stats}  shown={self.fps}", file=sys.stderr, flush=True)
            self.scope.stats = dict.fromkeys(self.scope.stats, 0)
        rec = "  ● REC" if self.recorder else ""
        self.info.setText(f"{self.fps} fps   angle {self.angle}°   {self.bat_text}{rec}")

    # --- capture -----------------------------------------------------------
    def _path(self, ext):
        os.makedirs(OUT_DIR, exist_ok=True)
        return os.path.join(OUT_DIR, datetime.now().strftime(f"bebird-%Y%m%d-%H%M%S.{ext}"))

    def snapshot(self):
        if self.img is None:
            return
        now = datetime.now().astimezone()
        auto = self.autorot.isChecked()
        trim = self.offset.value()
        applied = (trim + (self.shown_angle if auto else 0)) % 360
        meta = {
            "taken": now.isoformat(timespec="seconds"),
            "roll_deg": self.angle,
            "rotation_applied_deg": applied,
            "auto_rotate": auto,
            "trim_deg": trim,
            "light_pct": self.light_level,
            "light_scope_level": self.to_scope(self.light_level),
            "battery_pct": self.battery[0] if self.battery else None,
            "battery_state": self.battery[1] if self.battery else None,
            # deliberately no serial/uuid: snapshots get shared with doctors and others
            "scope": {k: self.board.get(k) for k in ("brand", "model", "hardware", "firmware", "soc")
                      if self.board.get(k) is not None},
        }
        # EXIF text fields are ASCII-only, so no degree signs here
        desc = (f"roll {self.angle} deg, rotated {applied} deg ({'auto' if auto else 'manual'}) + trim {trim} deg, "
                f"light {self.light_level}% (scope {meta['light_scope_level']})")
        if self.battery:
            desc += f", battery {self.battery[0]}% ({self.battery[1]})"

        exif = Image.Exif()
        exif[0x0112] = 1                                     # Orientation: rotation is already in the pixels
        exif[0x010E] = desc                                  # ImageDescription
        exif[0x010F] = "Bebird"                              # Make
        exif[0x0110] = str(self.board.get("model", "ES"))    # Model
        exif[0x0131] = "bebird-viewer"                       # Software
        exif[0x0132] = now.strftime("%Y:%m:%d %H:%M:%S")     # DateTime
        sub = exif.get_ifd(0x8769)                           # Exif sub-IFD
        sub[0x9003] = now.strftime("%Y:%m:%d %H:%M:%S")      # DateTimeOriginal
        sub[0x9011] = now.strftime("%z")[:3] + ":" + now.strftime("%z")[3:]  # OffsetTimeOriginal
        sub[0x9286] = b"ASCII\0\0\0" + json.dumps(meta).encode()  # UserComment (machine-readable)

        rgb = self.img.convertToFormat(QImage.Format.Format_RGB888)
        pil = Image.frombuffer("RGB", (rgb.width(), rgb.height()), rgb.constBits().asstring(rgb.sizeInBytes()),
                               "raw", "RGB", rgb.bytesPerLine(), 1)
        path = self._path("jpg")
        pil.save(path, "JPEG", quality=95, exif=exif)
        self.status.setText(f"saved {path}")

    def toggle_record(self, on=None):
        on = self.rec_btn.isChecked() if on is None else on
        if on and not self.recorder:
            path = self._path("mkv")
            # raw (unrotated) MJPEG stream copied as-is; wall-clock timestamps for variable fps
            self.recorder = subprocess.Popen(
                ["ffmpeg", "-loglevel", "error", "-use_wallclock_as_timestamps", "1", "-f", "mjpeg",
                 "-i", "-", "-c", "copy", path], stdin=subprocess.PIPE)
            self.status.setText(f"recording {path}")
        elif not on and self.recorder:
            self.recorder.stdin.close(); self.recorder.wait(timeout=5); self.recorder = None
            self.status.setText("recording saved")
        self.rec_btn.setChecked(bool(self.recorder))
        self.rec_btn.setText("Stop rec" if self.recorder else "Record")

    # --- window ------------------------------------------------------------
    def toggle_fullscreen(self):
        self.showNormal() if self.isFullScreen() else self.showFullScreen()

    def exit_fullscreen(self):
        if self.isFullScreen():
            self.showNormal()

    def reconnect(self):
        self.scope.stop()
        self.scope = Scope(self.iface)
        self.scope.frame.connect(self.on_frame)
        self.scope.battery.connect(self.on_battery)
        self.scope.boardinfo.connect(self.on_boardinfo)
        self.scope.light.connect(self.on_reported_light)
        self.scope.status.connect(self.status.setText)
        self.asserted = False
        self.status.setText("reconnecting…")
        if not self.scope.start():
            self.video.setText("Not connected to the scope's Wi-Fi.\nTurn the scope on and Connect.")

    # --- Wi-Fi ----------------------------------------------------------------
    def wanted(self):
        """The specific SSID picked in the dropdown, or None for "any Bebird scope"."""
        return self.net_box.currentData()

    def wifi_op(self, fn, cb):
        """Run one nmcli job at a time off the GUI thread. Returns False if another is running.
        Results from a job that ran on a different interface than the current one are dropped."""
        if self.wifi_busy:
            return False
        self.wifi_busy = True
        iface = self.iface
        def finish(result):
            self.wifi_busy = False
            if isinstance(result, Exception):
                self.status.setText(f"Wi-Fi error: {result}")
            elif iface == self.iface:
                cb(result)
        self.bg.run(fn, finish)
        return True

    def poll_wifi(self):
        self.wifi_op(lambda: wifi.status(self.iface), self.on_wifi_status)

    def on_wifi_status(self, st):
        was, self.on_scope_wifi = self.on_scope_wifi, st["on_scope"]
        if st["on_scope"]:
            self.wifi_label.setText(f"on {st['ssid'] or st['connection']} ({st['ip']})")
            self.wifi_btn.setText("Disconnect")
            self.remember(st["ssid"])
            if not was:
                self.reconnect()  # Wi-Fi just came up (or viewer just started): start video
            return
        self.wifi_btn.setText("Connect")
        other = f" (interface is on {st['ssid'] or st['connection']})" if st["connected"] else ""
        self.wifi_label.setText("not on the scope's Wi-Fi" + other)
        if was:
            self.status.setText("scope Wi-Fi lost — scope off or out of range")
        # auto-join: look for a scope network every few seconds and join when one appears
        if self.autojoin.isChecked() and time.time() - self.last_join_try > self.join_backoff:
            self.last_join_try = time.time()
            self.find_and_join("auto", quiet=True)

    def find_and_join(self, rescan, quiet=False):
        iface, wanted = self.iface, self.wanted()
        def pick():
            nets = wifi.scan(iface, rescan)
            return nets, wifi.choose(nets, wanted)
        def done(result):
            nets, net = result
            self.note_seen(nets)
            if net:
                self.join(net["ssid"])
            elif not quiet:
                self.status.setText(f"{wanted} isn't in range" if wanted else
                                    "no Bebird networks in range — is the scope on?")
        self.wifi_op(pick, done)

    def join(self, ssid):
        self.wifi_label.setText(f"joining {ssid}…")
        iface = self.iface
        self.wifi_op(lambda: wifi.connect(iface, ssid), self.on_join_result)

    def on_join_result(self, result):
        ok, msg = result
        # back off after a failed join: each attempt can take the interface off its usual network
        self.join_backoff = 6 if ok else min(self.join_backoff * 2, 120)
        self.last_join_try = time.time()
        self.status.setText(msg if ok else f"{msg} (next auto-join try in {self.join_backoff} s)")
        self.poll_wifi()  # picks up the new state, records the device and starts the video

    def toggle_wifi(self):
        if self.wifi_busy:
            self.status.setText("Wi-Fi is busy — try again in a moment")
            return
        if self.on_scope_wifi:
            iface = self.iface
            if self.wifi_op(lambda: wifi.disconnect(iface),
                            lambda r: (self.status.setText(r[1]), self.poll_wifi())):
                self.autojoin.setChecked(False)  # otherwise it would rejoin straight away
                self.scope.stop()
        else:
            self.join_backoff = 6  # a manual Connect resets the auto-join backoff
            self.find_and_join("yes")

    # the last-used scope, kept in ~/.config/bebird/last-device.json
    def remember(self, ssid):
        if not ssid:
            return
        last = wifi.load_last_device() or {}
        bssid = self.seen.get(ssid, "")
        if last.get("ssid") != ssid or (bssid and last.get("bssid") != bssid):
            wifi.save_last_device(ssid, bssid or last.get("bssid", ""))
            self.update_forget_tip()

    def forget_device(self):
        wifi.forget_last_device()
        self.update_forget_tip()
        self.status.setText("forgot the last-used scope; auto-join will take the strongest Bebird in range")

    def update_forget_tip(self):
        last = wifi.load_last_device()
        self.forget_btn.setEnabled(bool(last))
        self.forget_btn.setToolTip(f"forget the last-used scope ({last['ssid']})" if last
                                   else "no remembered scope")

    def note_seen(self, nets):
        for n in nets:
            self.seen[n["ssid"]] = n["bssid"]
            if self.net_box.findData(n["ssid"]) < 0:
                self.net_box.addItem(n["ssid"], n["ssid"])

    def scan_wifi(self, rescan):
        self.scan_btn.setEnabled(False)
        iface = self.iface
        self.bg.run(lambda: wifi.scan(iface, rescan), self.on_scan)  # scans may overlap a status poll

    def on_scan(self, nets):
        self.scan_btn.setEnabled(True)
        if isinstance(nets, Exception):
            self.status.setText(f"Wi-Fi scan failed: {nets}")
            return
        self.note_seen(nets)
        if nets:
            self.status.setText("in range: " + ", ".join(f"{n['ssid']} ({n['signal']}%)" for n in nets))
        else:
            self.status.setText("no Bebird networks in range — is the scope on?")

    def on_iface_changed(self, iface):
        if iface and iface != self.iface:
            self.scope.stop()
            self.iface, self.on_scope_wifi = iface, False
            self.poll_wifi()

    def assert_light(self, level=None):
        """Send the light level (default: the viewer's current one), commit it, then read it back."""
        self.apply_light(self.light_level if level is None else level)
        self.reported_light = None
        self.status.setText(f"light set to {self.light_level}% — verifying…")
        QTimer.singleShot(500, lambda: self.scope.send(b"\x66\x3c\xfe"))
        QTimer.singleShot(1500, self.check_light)

    def check_light(self):
        want, raw = self.light_level, self.to_scope(self.light_level)
        self.status.setText(f"light {want}% (scope {raw}) confirmed" if self.reported_light == raw
                            else f"light: scope reports {self.reported_light}, wanted {raw} ({want}%)")

    @staticmethod
    def load_state():
        try:
            with open(STATE_FILE) as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def save_state(self):
        os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
        with open(STATE_FILE, "w") as f:
            json.dump({"light": self.light_level, "light_before_off": self.light_before_off,
                       "offset": self.offset.value(), "autorotate": self.autorot.isChecked(),
                       "scope": self.board, "iface": self.iface, "network": self.wanted(),
                       "autojoin": self.autojoin.isChecked()}, f)

    def closeEvent(self, e):
        self.save_state()
        self.toggle_record(False)
        self.scope.stop()
        super().closeEvent(e)


def qt_messages(mode, context, message):
    """Keep Qt's JPEG decoder complaints (an occasional damaged frame over Wi-Fi) out of normal
    output; show them with BEBIRD_DEBUG=1. Everything else passes through."""
    if (context.category or "").startswith("qt.gui.imageio"):
        if os.environ.get("BEBIRD_DEBUG"):
            print(f"[debug] {context.category}: {message}", file=sys.stderr, flush=True)
        return
    print(message, file=sys.stderr, flush=True)


if __name__ == "__main__":
    import signal
    qInstallMessageHandler(qt_messages)
    app = QApplication(sys.argv)
    w = Viewer(); w.show()
    # Ctrl-C / kill: close the window properly so STOP is sent (otherwise the scope keeps streaming)
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: w.close())
    pulse = QTimer(); pulse.timeout.connect(lambda: None); pulse.start(250)  # let Python see signals
    sys.exit(app.exec())
