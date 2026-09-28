#!/bin/bash
# Start the viewer. If NetworkManager has a connection named "bebird" (see README), bring it up first.
cd "$(dirname "$0")"
IFACE="${BEBIRD_IFACE:-wlan0}"
if ! ip -4 addr show "$IFACE" 2>/dev/null | grep -q 'inet 192\.168\.5\.'; then
    nmcli con up bebird >/dev/null 2>&1 || echo "not on the scope's Wi-Fi yet; the viewer will wait for Reconnect" >&2
fi
exec python3 viewer.py
