#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate the focus estimator's test logs (android/app/src/test/resources/focus/).

The reference is the desktop prototype of issue #27, focuslive.py (Python + Pillow). It is kept
outside the repository with the private recordings. Each log holds per-frame numbers only: the
features the prototype measured, the rescale ratios on frames where the tip mask was rebuilt,
and the state it reached.

  focus_golden.py --prototype DIR synthetic
      the synthetic runs; SyntheticScope.kt regenerates the same frames bit for bit
  focus_golden.py --prototype DIR recording NAME FRAMES_DIR
      a recorded session (FRAMES_DIR holds frames.csv and fNNNN.jpg; the frames stay private)

Output goes to --out (default: the test resources directory next to this script).
"""
import argparse, csv, os, sys

M = 0xFFFFFFFF
COLS = ["t", "roll", "bright", "sharp_raw", "motion", "tip", "k_sharp", "k_bright", "state", "close", "armed"]


def hash32(a, b):
    h = ((a * 73856093) ^ (b * 19349663)) & M
    h ^= h >> 13
    h = (h * 1274126177) & M
    h ^= h >> 16
    return h


def in_tip(x, y):
    return (x - 380) ** 2 + (y - 380) ** 2 < 10000


def in_ring(x, y):
    """The translucent cap: an annulus around the tip disc."""
    return 10000 <= (x - 380) ** 2 + (y - 380) ** 2 < 19600


def coarse_roll(n):
    return 100 + (n * 37) % 21 - 10


def approach(n):
    """(step x, step y, gain/16, roll, tip, ring) for frame n: coarse handling in dim light,
    an approach ramping the brightness up, careful slow movement lit, then set down."""
    if n < 120:
        return 9, 5, 6, coarse_roll(n), True, None
    if n < 150:
        return 3, 1, 6 + (n - 120) * 16 // 30, 100 + n % 3 - 1, True, None
    if n < 250:
        return 1, 0, 22, 100 + (n // 3) % 2, True, None
    return 0, 0, 22, 100, True, None


def cap(n):
    """Coarse handling throughout with the tip disc and a bright, soft ring around it that drifts
    150-210 (not static, so only cap evidence can add it); dimmed to 60 briefly at 8-11 s
    (hysteresis keeps it) and for good from 15 s (its evidence decays and it leaves)."""
    dim = 80 <= n < 110 or n >= 150
    ring = 60 if dim else 150 + abs((2 * n) % 120 - 60)
    return 9, 5, 10, coarse_roll(n), True, ring


def removal(n):
    """Coarse handling lit; the tip is taken off at 10 s."""
    return 9, 5, 10, coarse_roll(n), n < 100, None


SCRIPTS = {"approach": approach, "cap": cap, "removal": removal}


def synthetic(script, frames, tip=True):
    from PIL import Image
    ox = oy = 0
    for n in range(frames):
        sx, sy, gain, roll, tip_on, ring = script(n)
        tip_on = tip_on and tip
        ox += sx
        oy += sy
        px = bytearray(480 * 480)
        for y in range(480):
            cy = (y + oy) >> 3
            base = y * 480
            for x in range(480):
                if tip_on and in_tip(x, y):
                    v = 205
                elif ring is not None and in_ring(x, y):
                    v = ring
                else:
                    v = min(255, (40 + (hash32((x + ox) >> 3, cy) & 127)) * gain >> 4)
                px[base + x] = v
        yield round(0.35 + n * 0.1, 4), roll, Image.frombytes("L", (480, 480), bytes(px))


def recording(d):
    from PIL import Image
    for r in csv.reader(open(os.path.join(d, "frames.csv"))):
        yield float(r[1]), int(r[2]), Image.open(os.path.join(d, f"f{int(r[0]):04d}.jpg")).convert("RGB")


def run(F, frames, path):
    class Est(F.Estimator):
        def _rebuild_mask(self):
            self.rb = True
            super()._rebuild_mask()

    est = Est()
    a = F.T["sharp_alpha"]
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(COLS)
        for t, roll, img in frames:
            est.rb = False
            s0, b0 = est.sharp, est.bsm
            st = est.update(img, t, roll)
            ks = kb = ""
            if est.rb:  # recover the ratios the prototype multiplied its running levels by
                if s0 is not None and s0 > 0:
                    ks = repr((st["sharp"] - a * st["sharp_raw"]) / ((1 - a) * s0))[:12]
                if b0 is not None and b0 > 0:
                    kb = repr((est.bsm - 0.3 * st["bright"]) / (0.7 * b0))[:12]
            w.writerow([f"{t:.4f}", roll, f"{st['bright']:.6f}", f"{st['sharp_raw']:.6f}", f"{st['motion']:.0f}",
                        f"{st['tip']:.6f}", ks, kb, st["state"], int(st["close"]), int(st["armed"])])
    print(path, file=sys.stderr)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--prototype", required=True, help="directory holding focuslive.py")
    ap.add_argument("--out", default=os.path.join(here, "..", "resources", "focus"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("synthetic")
    rec = sub.add_parser("recording")
    rec.add_argument("name")
    rec.add_argument("frames")
    args = ap.parse_args()
    sys.path.insert(0, args.prototype)
    argv, sys.argv = sys.argv, [sys.argv[0]]  # focuslive parses nothing at import, but be safe
    import focuslive as F
    sys.argv = argv
    os.makedirs(args.out, exist_ok=True)
    if args.cmd == "synthetic":
        run(F, synthetic(approach, 300, tip=True), os.path.join(args.out, "synthetic-tip.csv"))
        run(F, synthetic(approach, 300, tip=False), os.path.join(args.out, "synthetic-notip.csv"))
        run(F, synthetic(cap, 300), os.path.join(args.out, "synthetic-cap.csv"))
        run(F, synthetic(removal, 250), os.path.join(args.out, "synthetic-removal.csv"))
    else:
        run(F, recording(args.frames), os.path.join(args.out, f"{args.name}.csv"))


if __name__ == "__main__":
    main()
