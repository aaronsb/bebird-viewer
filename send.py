#!/usr/bin/env python3
"""Send raw bytes to the scope and print any reply.

Usage: send.py <hex bytes...> [--port N]      (default port 58090, the command port)
  send.py 66 3a            battery: 4 bytes, big-endian, high 16 = state, low 16 = percent
  send.py 66 39 01 01      board info (JSON)
  send.py 66 3c fe         query light level
"""
import argparse, os, socket, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grab import CAM, IFACE, iface_ip  # noqa: E402

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("bytes", nargs="+")
ap.add_argument("--port", type=int, default=58090)
a = ap.parse_args()

ip = iface_ip(IFACE)
if not ip:
    sys.exit(f"{IFACE} has no address: join the scope's Wi-Fi first (or set BEBIRD_IFACE)")
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind((ip, 0))  # the scope's Wi-Fi address: keeps traffic off any wired LAN
s.connect((CAM, a.port))
s.settimeout(0.7)
s.send(bytes.fromhex("".join(a.bytes)))
reply = b""
try:
    while True:  # board info can span several datagrams
        reply += s.recv(4096)
        s.settimeout(0.2)
except (socket.timeout, ConnectionRefusedError):
    pass
if not reply:
    print("no reply")
elif reply.lstrip().startswith(b"{"):
    print(reply.decode(errors="replace"))
else:
    print(reply.hex(" "))
