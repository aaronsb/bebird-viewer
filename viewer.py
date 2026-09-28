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

from PyQt6.QtCore import QObject, QRectF, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QImage, QKeySequence, QPainter, QPainterPath, QPixmap, QShortcut, QTransform
from PyQt6.QtWidgets import (QApplication, QCheckBox, QHBoxLayout, QLabel, QPushButton, QSizePolicy,
                             QSlider, QSpinBox, QVBoxLayout, QWidget)

# Wi-Fi interface joined to the scope's access point (override with BEBIRD_IFACE)
IFACE = os.environ.get("BEBIRD_IFACE", "wlan0")
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


class Scope(QObject):
    """Owns the sockets. Receives video on a thread and emits complete JPEG frames."""
    frame = pyqtSignal(bytes, int)       # jpeg, angle in degrees
    battery = pyqtSignal(int, int)       # state, percent
    light = pyqtSignal(int)              # current light level from the scope
    status = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.video = self.ctrl = None
        self.running = False

    def _sock(self, ip, port, local_port=0):
        # Bind to the Wi-Fi address so nothing goes out the wired LAN (which also has a 192.168.5.1)
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((ip, local_port))
        s.connect((CAM, port))
        return s

    def start(self):
        ip = iface_ip(IFACE)
        if not ip:
            self.status.emit(f"not connected to the scope's Wi-Fi ({IFACE})")
            return False
        self.video, self.ctrl = self._sock(ip, 58080, VIDEO_CLIENT_PORT), self._sock(ip, 58090)
        self.video.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 5 << 20)
        self.video.settimeout(0.1)
        self.ctrl.setblocking(False)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()
        return True

    def stop(self):
        self.running = False
        time.sleep(0.15)  # let the receive loop exit before sending STOP
        try:
            self.video and self.video.send(STOP)
        except OSError:
            pass

    def send(self, data):
        try:
            self.ctrl and self.ctrl.send(data)
        except OSError:
            pass

    def set_light(self, level):
        self.send(bytes([0x66, 0x3C, level]))
        self.send(b"\x66\x3c\xff")  # commit: the level only takes effect after this

    def _handle_ctrl(self, b):
        if len(b) == 4:
            state, pct = int.from_bytes(b[:2], "big"), int.from_bytes(b[2:], "big")
            self.battery.emit(state, pct)
        elif len(b) == 1:
            self.light.emit(b[0])

    def _run(self):
        parts, fid, frames = {}, None, 0
        self.stats = {"pkts": 0, "done": 0, "dropped": 0, "superseded": 0}
        t_start = last_poll = last_rx = time.time()
        self.video.send(STOP); time.sleep(0.1)  # clear any session left on our port
        self.video.send(START)                  # then exactly one START
        lost = False
        while self.running:
            now = time.time()
            if now - last_poll >= 1:
                self.send(BATTERY); last_poll = now
            try:
                while True:
                    self._handle_ctrl(self.ctrl.recv(64))
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
                end = jpg.rfind(b"\xff\xd9")
                jpg = jpg[:end + 2] if end > 0 else jpg + b"\xff\xd9"
                if jpg[:2] == b"\xff\xd8":
                    frames += 1; self.stats["done"] += 1
                    self.frame.emit(jpg, d[3] + (256 if d[1] == 2 else 0))
            parts = {}


class Viewer(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("bebird")
        self.scope = Scope()
        self.img = None            # last displayed (rotated) QImage
        self.angle = 0
        self.shown_angle = 0
        self.saved = self.load_state()
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

        self.status = QLabel("")
        self.info = QLabel("")

        controls = QHBoxLayout()
        for w in (self.light_btn, self.slider, self.slider_label, self.autorot, self.roll_label, self.offset,
                  self.snap_btn, self.rec_btn, self.reconnect_btn):
            controls.addWidget(w)
        controls.addStretch()
        bottom = QHBoxLayout()
        bottom.addWidget(self.status); bottom.addStretch(); bottom.addWidget(self.info)
        layout = QVBoxLayout(self)
        layout.addWidget(self.video, 1); layout.addLayout(controls); layout.addLayout(bottom)

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
        self.scope.light.connect(self.on_reported_light)
        self.scope.status.connect(self.status.setText)
        self.bat_text = ""
        t = QTimer(self); t.timeout.connect(self.tick); t.start(1000)
        self.resize(720, 800)
        if not self.scope.start():
            self.video.setText("Not connected to the scope's Wi-Fi.\nTurn the scope on, then press Reconnect.")

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

    def on_battery(self, state, pct):
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
        if self.img is not None:
            path = self._path("jpg")
            self.img.save(path, "JPEG", 95)
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
        self.scope = Scope()
        self.scope.frame.connect(self.on_frame)
        self.scope.battery.connect(self.on_battery)
        self.scope.light.connect(self.on_reported_light)
        self.scope.status.connect(self.status.setText)
        self.asserted = False
        self.status.setText("reconnecting…")
        if not self.scope.start():
            self.video.setText("Not connected to the scope's Wi-Fi.\nTurn the scope on, then press Reconnect.")

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
                       "offset": self.offset.value(), "autorotate": self.autorot.isChecked()}, f)

    def closeEvent(self, e):
        self.save_state()
        self.toggle_record(False)
        self.scope.stop()
        super().closeEvent(e)


if __name__ == "__main__":
    import signal
    app = QApplication(sys.argv)
    w = Viewer(); w.show()
    # Ctrl-C / kill: close the window properly so STOP is sent (otherwise the scope keeps streaming)
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: w.close())
    pulse = QTimer(); pulse.timeout.connect(lambda: None); pulse.start(250)  # let Python see signals
    sys.exit(app.exec())
