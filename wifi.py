"""Join a Bebird scope's Wi-Fi through NetworkManager (nmcli), without disturbing other networking.

Connections created here never become the default route and have IPv6 off, so a wired or
second Wi-Fi link keeps carrying normal traffic. Every function blocks (connect can take
~20 s), so call them off the GUI thread.
"""
import os, re, subprocess

SSID_PREFIX = "bebird-"
SCOPE_NET = "192.168.5."


def _nmcli(*args, timeout=30):
    """Run nmcli; returns (ok, stdout, stderr). Never raises for nmcli failures."""
    try:
        p = subprocess.run(["nmcli", *args], capture_output=True, text=True, timeout=timeout)
        return p.returncode == 0, p.stdout, p.stderr.strip()
    except FileNotFoundError:
        return False, "", "nmcli not found (NetworkManager is required for Wi-Fi controls)"
    except subprocess.TimeoutExpired:
        return False, "", "nmcli timed out"


def _fields(line):
    """Split one line of `nmcli -t` output: fields are ':'-separated, with '\\:' and '\\\\' escaped."""
    return [re.sub(r"\\(.)", r"\1", f) for f in re.split(r"(?<!\\):", line)]


def wifi_devices():
    ok, out, _ = _nmcli("-t", "-f", "DEVICE,TYPE", "device")
    return [f[0] for f in map(_fields, out.splitlines()) if len(f) >= 2 and f[1] == "wifi"] if ok else []


def default_iface():
    """BEBIRD_IFACE if set, else the first Wi-Fi device NetworkManager knows, else wlan0."""
    return os.environ.get("BEBIRD_IFACE") or next(iter(wifi_devices()), "wlan0")


def scan(iface, rescan="auto"):
    """Visible scope networks as [(ssid, signal)], strongest first. rescan: 'yes', 'no' or 'auto'."""
    ok, out, _ = _nmcli("-t", "-f", "SSID,SIGNAL", "device", "wifi", "list", "ifname", iface, "--rescan", rescan)
    if not ok and rescan == "yes":  # NM refuses back-to-back scans; fall back to its cached list
        ok, out, _ = _nmcli("-t", "-f", "SSID,SIGNAL", "device", "wifi", "list", "ifname", iface, "--rescan", "no")
    best = {}
    for f in map(_fields, out.splitlines()):
        if len(f) >= 2 and f[0].startswith(SSID_PREFIX):
            best[f[0]] = max(best.get(f[0], 0), int(f[1] or 0))
    return sorted(best.items(), key=lambda kv: -kv[1])


def status(iface):
    """{'connected': bool, 'connection': name, 'ssid': str, 'ip': str, 'on_scope': bool}."""
    ok, out, _ = _nmcli("-t", "-f", "GENERAL.STATE,GENERAL.CONNECTION,IP4.ADDRESS", "device", "show", iface)
    info = {"connected": False, "connection": "", "ssid": "", "ip": "", "on_scope": False}
    if not ok:
        return info
    for line in out.splitlines():
        key, _, val = line.partition(":")
        if key == "GENERAL.STATE":
            info["connected"] = val.startswith("100")
        elif key == "GENERAL.CONNECTION":
            info["connection"] = val if val != "--" else ""
        elif key.startswith("IP4.ADDRESS") and not info["ip"]:
            info["ip"] = val.split("/")[0]
    if info["connection"]:
        ok, out, _ = _nmcli("-g", "802-11-wireless.ssid", "connection", "show", info["connection"])
        info["ssid"] = out.strip() if ok else ""
    info["on_scope"] = info["connected"] and info["ip"].startswith(SCOPE_NET)
    return info


def _profile_for(ssid):
    ok, out, _ = _nmcli("-t", "-f", "NAME,TYPE", "connection", "show")
    for f in map(_fields, out.splitlines()) if ok else []:
        if len(f) >= 2 and f[1] == "802-11-wireless":
            ok2, s, _ = _nmcli("-g", "802-11-wireless.ssid", "connection", "show", f[0])
            if ok2 and s.strip() == ssid:
                return f[0]
    return None


def connect(iface, ssid):
    """Join a scope network on iface, creating a NetworkManager profile the first time.
    Returns (ok, message)."""
    safe = ("ipv4.never-default", "yes", "ipv6.method", "disabled", "connection.interface-name", iface)
    name = _profile_for(ssid)
    if name:
        # keep an existing profile, but make sure it can't take over the default route
        ok, _, err = _nmcli("connection", "modify", name, *safe)
    else:
        name = ssid
        ok, _, err = _nmcli("connection", "add", "type", "wifi", "ifname", iface, "con-name", name,
                            "ssid", ssid, "connection.autoconnect", "no", *safe)
    if not ok:
        return False, f"couldn't prepare connection: {err}"
    ok, _, err = _nmcli("--wait", "25", "connection", "up", name, "ifname", iface, timeout=40)
    return (True, f"joined {ssid}") if ok else (False, f"couldn't join {ssid}: {err}")


def disconnect(iface):
    """Leave the scope network. Uses 'connection down' so NetworkManager may autoconnect the
    interface's usual network again."""
    st = status(iface)
    if not st["connection"]:
        return True, "not connected"
    ok, _, err = _nmcli("connection", "down", st["connection"])
    return (True, f"left {st['ssid'] or st['connection']}") if ok else (False, err)
