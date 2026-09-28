#!/usr/bin/env python3
"""Headless capture from a Bebird ES otoscope: save JPEG frames, or write MJPEG to stdout.

Usage:
  grab.py [seconds] [--out DIR]     save frames as DIR/f0000.jpg ... (default ./frames, 15 s)
  grab.py --live | ffplay -f mjpeg -i -    stream MJPEG to stdout until interrupted

Same protocol handling as viewer.py: one START, battery poll as keepalive, STOP on exit.
"""
import argparse, os, signal, socket, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wifi import default_iface  # noqa: E402

IFACE = default_iface()  # BEBIRD_IFACE, else the first Wi-Fi device
CAM = "192.168.5.1"
VIDEO_CLIENT_PORT = 58081  # fixed, so a restart reuses the scope's client slot
START, STOP, BATTERY = b"\x20\x36", b"\x20\x37", b"\x66\x3a"


def iface_ip(name):
    import fcntl, struct
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        return socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", name.encode()[:15]))[20:24])
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


def sock(ip, port, local_port=0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((ip, local_port))  # the scope's Wi-Fi address: keeps traffic off any wired LAN
    s.connect((CAM, port))
    return s


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("seconds", nargs="?", type=float)
    ap.add_argument("--live", action="store_true", help="write MJPEG to stdout")
    ap.add_argument("--out", default="frames")
    a = ap.parse_args()
    secs = a.seconds if a.seconds is not None else (float("inf") if a.live else 15)
    log = sys.stderr

    ip = iface_ip(IFACE)
    if not ip:
        sys.exit(f"{IFACE} has no address: join the scope's Wi-Fi first (or set BEBIRD_IFACE)")
    video, ctrl = sock(ip, 58080, VIDEO_CLIENT_PORT), sock(ip, 58090)
    video.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 5 << 20)
    video.settimeout(0.1)
    ctrl.setblocking(False)
    if not a.live:
        os.makedirs(a.out, exist_ok=True)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt))

    parts, fid, frames, pkts = {}, None, 0, 0
    video.send(STOP); time.sleep(0.1); video.send(START)
    t0 = t_start = last_poll = time.time()
    try:
        while time.time() - t0 < secs:
            now = time.time()
            if now - last_poll >= 1:
                ctrl.send(BATTERY); last_poll = now
            try:
                while True:
                    ctrl.recv(64)
            except (BlockingIOError, ConnectionRefusedError):
                pass
            if frames == 0 and now - t_start > 2:  # no video yet: one STOP/START retry, like the app
                video.send(STOP); time.sleep(0.1); video.send(START); t_start = time.time()
            try:
                d = video.recv(4096)
            except (socket.timeout, ConnectionRefusedError):
                continue
            pkts += 1
            if len(d) < 5:
                continue
            if d[0] != fid:
                parts, fid = {}, d[0]
            parts[d[2]] = d[4:]
            if not d[1]:
                continue
            n = max(parts)
            if all(i in parts for i in range(1, n + 1)):
                jpg = b"".join(parts[i] for i in range(1, n + 1))
                jpg = close_jpeg(jpg)
                if jpg[:2] == b"\xff\xd8":
                    if a.live:
                        sys.stdout.buffer.write(jpg); sys.stdout.buffer.flush()
                    else:
                        with open(os.path.join(a.out, f"f{frames:04d}.jpg"), "wb") as f:
                            f.write(jpg)
                    frames += 1
            parts = {}
    except (KeyboardInterrupt, BrokenPipeError):
        if a.live:  # reader went away: stop stdout's final flush from raising at exit
            os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
    finally:
        video.send(STOP)
        print(f"packets={pkts} frames={frames} in {time.time() - t0:.1f}s", file=log)


if __name__ == "__main__":
    main()
