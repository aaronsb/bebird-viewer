# Focus and proximity estimation

The Android app can estimate, frame by frame, whether the surface in view is at about the lens's focus distance, which is also the end of a fitted tip. It then shows a mm scale that is valid there, and a neutral **CLOSE** cue when something is probably at the tip end (issue #27).

This is a **best-effort proximity hint**:

- It never guarantees anything, and it doesn't prevent contact.
- **The absence of the cue must not be read as clearance.** A smooth or featureless surface reads soft even when in focus, so misses are possible.
- The wording stays "proximity" and "CLOSE", never anything medical.
- It is estimated from focus (relative sharpness), movement and brightness in the picture, and the scope's motion sensor (the roll angle, §6); the scope has no distance sensor. Always rely on common sense to operate the scope safely; the indicators never replace that.

The algorithm was developed and tuned with a desktop prototype, first on recorded sessions and then live. The Kotlin port lives in `android/app/src/main/java/com/bockelie/bebird/focus/`:

- `FocusEstimator` takes frames and returns a `FocusResult`.
- `FocusTracker` is the time-series and state-machine stage, which can be driven directly with features.
- `ScaleOverlay` produces the overlay geometry as drawing primitives.
- `ProximityGate` is the master switch's per-frame gate (below).
- `FocusConfig` holds every constant.

The core uses no Android types. `BitmapLuma.kt` is the thin adapter from a decoded `Bitmap`, which must be a software ARGB_8888 bitmap (BitmapFactory's default; a HARDWARE bitmap can't be read back) of the raw, unrotated frame.

The estimator isn't synchronised: feed it, and read `tipBlocks()`, from one frame thread.

## Master switch and settings

Proximity estimation has a master setting, **Proximity estimation**, which is on by default because it has been validated in use.

When it is off, the estimator doesn't run at all. `ProximityGate` holds the estimator and the per-frame buffers; its `onFrame(t, roll) { buffers -> bitmap.lumaInto(buffers) }` is the per-frame hook:

- **Disabled:** there is no estimator and no buffers. `onFrame` returns null without calling its fill callback, so there is no luma extraction, no mask learning, no metrics and no allocation.
- **Enabling** (`setEnabled(true)`) creates a fresh estimator and buffers. Enabling mid-stream starts over: warm-up, an empty tip mask, disarmed, no sharpness peak. A new object is fresh by construction, so there is no `reset()` that could forget a field.
- **Disabling** releases the estimator, with everything it learned, and the buffers.

With estimation off there is **no overlay**: no rings and no CLOSE (`ScaleOverlay.forFrame(null, …)` is empty). The scale is only drawn when the estimator can say whether it holds; it is never shown permanently "unverified".

**Settings (for the wiring):** the master switch (default on), the **scale style** (ring, the default; bowtie; bar; or none) and the **CLOSE indicator** (default on) are persisted in the app's existing settings store (SharedPreferences, like the remembered device), each under its own key and independent of the others. The master switch only gates whether the estimator runs: turning it off and on again keeps the previous style and CLOSE choice, and only the estimator's learned state (tip mask, peaks, arming) resets. The sub-settings only have an effect while estimation is on. They are app settings, not `FocusConfig` constants, and the wiring reads them once when they change, not per frame.

**Time:** `t` must come from a monotonic clock, such as `SystemClock.elapsedRealtimeNanos() / 1e9`, never wall time. It must be finite. A frame time earlier than the previous one is taken as the previous one, and the windows are bounded even if the clock sticks.

## 1. Inputs

| input | source | notes |
|---|---|---|
| frame | the reassembled, decoded JPEG | 480×480, unrotated. Luma: `L = (19595 R + 38470 G + 7471 B + 32768) >> 16`, the ITU-R 601 weights 0.299, 0.587 and 0.114, rounded the way Pillow's `convert("L")` does. |
| t | arrival time of the frame, in seconds, from a monotonic clock | Used for every window. Frames arrive at about 10 fps, but the intervals vary (stalls of up to 0.4 s have been seen). Real timestamps are used, never frame counts, except where a count is stated. |
| roll | the frame's last packet: `angle = d[3] + (d[1] == 2 ? 256 : 0)` | Degrees, 0–359. It is unwrapped to the nearest turn: `unw += ((raw − prev_raw + 180) mod 360) − 180`. |
| light level | the tip-light setting (raw 22–50) | **Not used.** The thresholds were tuned at raw 42, the operator's usual level. If the level changes a lot, the brightness thresholds in §5 may need scaling. This hasn't been validated. |

Frame geometry:

- The centre is C = (240, 240).
- The image circle used for metrics has radius 230.
- The block grid G is 60×60 blocks of 8×8 px.
- "Circle blocks" are the blocks whose 8×8 area is more than 200/255 covered by the disc: 2558 of the 3600.

## 2. Per-frame primitives

Downsampling rounds the way Pillow's box filter does: each row of the block is averaged and rounded, then the column of row results.

- `small` (60×60): the mean of each 8×8 luma block.
- `half` (240×240): the mean of each 2×2 luma block.
- `lap` (240×240): the 3×3 Laplacian `[0 1 0; 1 −4 1; 0 1 0]` on `half`, **plus 128 and clamped to 0..255**, with the border pixels copied from `half`. The clamp matters: it caps |Laplacian| at about 127, which changes the variance.
- `edge` (60×60): the mean of `|lap − 128|` over each 4×4 block of `lap`.
- `absd` (60×60): `|small − small_prev|`. `med` is the median of `absd` over the circle blocks. Medians here are the upper median, the element at index n/2 of the sorted values.
- `motion`: the median over **non-tip circle blocks** of `|(small − small_prev) − shift|`. `shift` is the median signed difference over the same blocks, clamped to −128..127. Removing the shift makes a global exposure change count as no motion.

## 3. Tip mask (raw frame)

A fitted probe tip shows as a bright, blurred rim in the lower right of every frame. It must be kept out of the metrics. It is found automatically because it keeps the same value while the scene moves.

State per block:

- `mu`
- `var` (initially 14² = 196)
- `on` (bool)
- `off` (a count)
- `blob` (0..1)

There is also a ring of up to 10 `small` frames with their times, and `core`, the blocks in the last mask before dilation.

Constants, all in `FocusConfig`:

| constant | value |
|---|---|
| `tipAlpha` | 0.04 |
| `tipSdOn` | 6 |
| `tipSdOff` | 14 |
| `tipMinLuma` | 90 |
| `tipMotion` | 3 |
| `tipOffUpdates` | 50 |
| `tipLearnMaxSaturated` | 0.40 |
| `tipFastFrames` | 10 |
| `tipFastAge` | 4 s |
| `tipFastMotion` | 8 |
| `tipFastRange` | 12 |
| `tipMinUpdates` | 10 |
| `tipMaxGrow` | 0.08 |
| `tipPriorX` | 0.70 |
| `tipPriorY` | 0.55 |
| `tipPriorMin` | 20 |
| `tipRegion` | 0.40 |
| `blobLuma` | 150 |
| `blobEdge` | 3 |
| `blobStep` | 6 |
| `blobAlpha` | 0.1 |
| `blobDecay` | 0.01 |
| `blobOn` | 0.6 |
| `blobOff` | 0.3 |
| `blobReach` | 8 |

Each frame runs these steps in order. The first frame is skipped because it has no `small_prev`.

1. **Cap evidence (every frame).** For each circle block:
   - If `small ≥ 150 && edge ≤ 3 && absd ≤ 6`, then `blob += 0.1·(1 − blob)`.
   - Otherwise `blob −= 0.01·blob`.

   The translucent cap is lit by the scene, so it isn't static while the scene moves. Once the scope is near something, though, it is bright, soft and steady. The slow decay means walls that hide the cap for a few seconds don't erase it. If a mask exists, it is rebuilt (step 5) every 10th frame.
2. **Gate.**
   - If `med < 3`, stop: the scene isn't moving, so there is nothing to learn.
   - If more than 40 % of the circle is saturated (`small ≥ 250`, counted over the whole grid; the corners are dark), also stop. A wall pressed on the lens, or white paper, saturates and looks static. The tip alone covers about 20–25 %.
3. **Fast seed (coarse handling only).** If `med ≥ 8`, push `(t, small)` into the ring, drop entries older than 4 s, and keep at most 10. If the ring holds 10 and `med ≥ 8`, compute `range = max − min` per block over the ring. For each circle block with `range ≤ 12 && small ≥ 90`, set `mu = small` and `var = min(var, (range/2)²)`.
4. **Slow learner (every moving frame).** The first learning frame only sets `mu = small`. After that:
   - `dv = min(40, |small − mu|)`. The clamp keeps one occluded frame from blowing up the variance.
   - `mu += 0.04·(small − mu)`
   - `var += 0.04·(dv² − var)`

   Then, per block:
   - When `on`: if `var > 14²` or `mu < 72` (0.8 × 90), increment `off`, and once `off ≥ 50` set `on = false`. Otherwise reset `off = 0`. **A block leaves the mask only after sustained change**, about 50 moving frames (≈ 5 s). This covers gunk changing how the tip looks, and walls pressing on the rim at entry.
   - When `!on`: if `var < 6²` and `mu ≥ 90`, set `on = true` and `off = 0`.

   Every 5th learning update, the mask is rebuilt (step 5).
5. **Rebuild.**
   1. Start from `on`. Output an empty mask until there have been 10 learning updates.
   2. **Lower-right prior.** Take the 4-connected components, and keep a component only if at least 20 of its blocks lie in the corner x ≥ 0.70·60, y ≥ 0.55·60, where the probe body always sits on the raw frame. Of a kept component, keep only the blocks with x ≥ 0.40·60 and y ≥ 0.40·60. This rejects LED glints (static in frame, near the centre), bands of white paper, and a static patch that merges with the tip.
   3. **Cap growth.** Search breadth-first from the kept blocks through 4-neighbours into candidate blocks, at most 8 steps (64 px). Candidates have `blob > 0.6`, or `blob > 0.3` if they were in `core` last time (hysteresis), and must lie inside the x, y ≥ 0.40 region.
   4. **Growth cap.** At most 8 % of the circle blocks may be new (not in `core`) per rebuild. If more qualify, keep the lowest-`var` ones. Set `core` to the result.
   5. Apply a 3×3 median (majority of 9, edges replicated) to the 60×60 binary grid, then a 3×3 dilation (max). This adds one block, 8 px, of margin.
   6. The **metrics mask** is the circle minus the mask blocks. At half resolution, pixel (x, y) takes full-resolution pixel (2x+1, 2y+1).
   7. `tip_frac` = mask blocks / circle blocks. **The tip counts as present when `tip_frac ≥ 0.05`.**
6. **Rescale on mask change.** On a frame whose mask was rebuilt, brightness and sharpness are measured under both the old and the new mask, on that same frame, and the running levels are multiplied by new/old:
   - by `sharp_new/sharp_old`: the sharpness EMA and the peak;
   - by `bright_new/bright_old`: the smoothed brightness, the arming base, and every stored value in the ambient, arming and slope windows.

   Without this, a mask change moves the peak and the lock drops out.

Typical behaviour: the rim is masked once there has been some coarse motion with the tip on, and the cap follows within about 1 s. In the recordings, `tip_frac` went from 0.05 to 0.18 within about 1 s of the first detection and settled at 0.20–0.27. Recordings without a tip stay at 0.00.

## 4. Brightness and sharpness

- `bright`: the mean luma over the metrics mask.
- `sharp_raw`: the population variance of `lap` over the half-resolution metrics mask. This is the wide-ROI variance of the Laplacian, with the tip and the outside of the circle masked.
- `sharp`: an EMA of `sharp_raw` with α = 0.35. The first value is taken as is.
- `peak`: every frame, `peak *= exp(−dt / 30 s)`. Then, if the frame is not coarse and is either not resting **or** armed, `peak = max(peak, sharp)`. "Resting" here is the raw roll test from §6, before the in-zone override.
- `rel = sharp / peak`.
- **Lock** (hysteresis): lock when `rel ≥ 0.90` and the estimator is warm (t − t_first ≥ 2.5 s). Unlock when `rel < 0.82`.

The depth of field is deep: magnification varied 15 % across positions that were all equally sharp. So "sharp" is relative, meaning within about 10 % of the sharpest seen in the last ~30 s, and never a fixed threshold. Absolute sharpness also differs between scenes and between runs with and without a tip.

## 5. Brightness trend and approach arming

- **Slope:** a least-squares fit of `(t, bright)` over the last 1.5 s (at least 3 samples). `slope_rel = slope / max(10, mean bright)` per second. Above +0.12 /s means approaching; below −0.12 /s means receding.
- **Smoothed level:** `bsm = EMA(bright, 0.3)`. After the 2.5 s warm-up (the stream starts with an auto-exposure ramp), `(t, bsm)` is pushed into two windows: ambient (30 s, reported only) and recent (12 s). `base` is the minimum over the recent window.
- **Arming floor:** `arm_min = 40` if the tip is present, else `100`. With the tip masked, the surface at the tip end reads darker, because the tip shades the LED: about 53 luma on a clear ruler over a black mat, against 105–165 bare.
- **Arm:** when not armed, if warm and `bsm ≥ arm_min` and `bsm ≥ 1.4·base`, the estimator arms with `arm_base = base`.
- **Disarm:** when armed, if `bsm < 1.3·arm_base` or `bsm < 0.8·arm_min` **continuously for 1.5 s**, it disarms. A momentary shadow doesn't disarm.

The reason for arming: the depth of field is deep, so a room-lit ruler across the desk is sharp and contrasty. Sharp alone must not mean "in zone", and a steady far scene never arms. Absolute brightness isn't a usable distance cue either, because auto-exposure and tilt swamp the 1/d² falloff. Only the *step* of an approach is used.

## 6. Roll: jitter, coarse, careful, resting

- `jitter`: the population std of the unwrapped roll over the last 1.0 s.
- **Coarse** (hysteresis): on when `jitter > 5°`, off when `jitter < 3.5°`.
- **Careful:** `jitter < 2.5°`.
- **Resting (raw):** the roll window spans at least 2.7 s (0.9 × 3 s) and the std over the last 3 s is below 0.3°. The roll is quantised to 1°, so a device lying still reads exactly 0.
- **Resting override:** when armed, the device counts as resting only if it isn't locked **and** there has been no in-zone for the last 10 s. So a sharp, armed, still view (a tip resting on a surface) stays in-zone.

## 7. State machine (priority order, every frame)

```
if resting                          -> RESTING
elif coarse                         -> COARSE
elif locked && careful && armed     -> IN-ZONE   (debounced, below)
elif slope_rel > +0.12              -> APPROACHING
elif slope_rel < -0.12              -> RECEDING
elif locked && careful              -> SHARP-UNARMED
else                                -> SEARCHING
```

- **Debounce:** IN-ZONE must hold for 0.4 s continuously; until then the state shows SETTLING. `t_zone`, the last time IN-ZONE or SETTLING was reached, feeds the resting override.
- **Scale locked** (overlay colour) means the state is IN-ZONE.
- **CLOSE:** if the state is IN-ZONE and `motion ≥ 2`, then `close_until = t + 0.5 s`. CLOSE shows while the state is IN-ZONE and `t ≤ close_until`. With a tip on, the working model is that sharp content moving relative to the camera means something is at the tip end.
- **Warm-up:** no lock and no arming until 2.5 s after the first frame.

## 8. Overlay

It is drawn in raw-frame coordinates centred at (240, 240).

**Saved stills (#43).** The scale is saved with the picture, because it is what makes a saved image useful for sizing:

- **Snapshot:** one image with the scale drawn in as shown at the moment of capture (style, and grey-dashed or locked). There is no scale when the style is Off or estimation is off, and then the file is exactly what it was before. A zoomed crop gets the scale too, drawn at the crop's resolution with full-frame stroke widths (thin lines) rather than enlarged.
- **Annotate:** the scale shown at the moment of pausing (or none) stays with the paused frame and is shown while annotating. Save writes the raw frame without scale or marks, and `<name>_annotated.jpg` with the scale and then the marks.
- **Drawing:** upright, centred on the upright frame's centre, at the same px/mm as on screen at zoom 1 (40 px/mm on a 480-px frame), and only inside the image circle, as the screen clips it. A short note, "APPROX. SCALE / needs focus", goes at the frame's upper right, since the file travels without the app.
- **Never saved:** CLOSE (a live warning, not part of the record), and the scale in video recordings.
- **Metadata:** the UserComment JSON gains `"proximity_scale": {"style", "locked", "px_per_mm", "tolerance_pct"}` only when a scale is drawn in. `px_per_mm` is in that image's pixels, so a zoomed crop records its enlargement.

The scale is **40 px/mm ±10 %** at the tip end, from ruler captures of 38.8–43.3 px/mm. Spacing is uniform, because distortion is below the noise out to about 4 mm. Tip angle and pressure alone change the magnification by 5–10 %.

- **Colour:**
  - Not locked: grey (170,170,170), 1 px, **dashed** (6° on, 6° off).
  - Locked (IN-ZONE): lock colour (0,230,200), 2 px, solid.
- **Ring style (default):** circles at r = k·40 px for k = 1..5. Each ring is labelled by **diameter** ("⌀2", "⌀4", "⌀6", "⌀8", "⌀10") just right of it on the horizontal axis, baseline at (C + r + 3, C − 3). "mm ±10%" sits centred below the centre, between the ⌀8 and ⌀10 rings (bottom at C + 4.7 mm), inside the image circle. Something that fits within a ring is about that ring's diameter wide.
- **Centre crosshair** (ring and bowtie): four short arms, up, down, left and right, from 0.1 to 0.4 mm (4–16 px) out, so the exact centre stays clear and the arms stay well inside the ⌀2 ring. Same colour and width as the scale; solid even when unlocked, since arms this short would show one dash or none. Upright, with no label.
- **Bowtie:**
  - Two wedges at ±15° about the horizontal (0° and 180°), with edges running from r = 0.5 mm to 5.25 mm, dashed when unlocked.
  - An arc tick across the wedge at each mm, r = 1..5, labelled "⌀2k" on the right wedge.
  - "mm ±10%" below the right wedge.
  - The centre crosshair, inside the wedges' 0.5 mm start.
- **Bar:**
  - A horizontal line through the centre, ±5.5 mm.
  - A tick each mm, 7 px half-length, and long ticks (14 px) at −5, 0 and +5 mm.
  - Labels 0..10 from left to right above the ticks, and "mm ±10%" below the right end.
  - Lines have a black outline underneath for contrast.
- **CLOSE indicator:** a yellow (255,215,0) triangle with a black outline and a black "!" (Unifont U+26A0), upper left at x 18, y 16, 40 px. The word "CLOSE" follows it in yellow with a black outline. It must not claim to prevent contact.

The rings and ticks are meant to be drawn by the same one-pixel ring-by-distance renderer as the overlay circle, with a per-pixel angle test for the dashes. Straight lines use the rings' half-open width rule (−half ≤ distance < half), so a 2-px line is 2 px wide wherever it falls, as a 2-px ring is. The labels use the bundled bitmap font, so the screen and saved stills match.

## 9. Cost

The work per frame is:

- two downsamples;
- one 3×3 convolution on 240×240;
- masked sums per block;
- arithmetic on the 60×60 grid;
- a mask rebuild about every 5 moving frames.

The Kotlin port keeps per-block sums, so measuring under the old and new masks costs nothing extra. On the JVM test run (`FocusCostTest`) it takes about 0.5 ms per frame. It should run off the UI thread, on every frame; nothing needs skipping.

## 10. Rejected: LED dip

The idea was to lower the LED by 20 % every 2 s and read a large relative brightness drop as LED-lit, and therefore near. It failed on two counts:

- **The scope pauses the video while it processes a light change.** There were 8 stalls of 0.2–0.39 s, against a normal maximum interval of 0.10 s. The operator saw the light and camera "resetting".
- **Auto-exposure cancels the dip.** The last measured drop was 7 %.

It is not a distance cue and it is not ported. A light set plus commit also causes a ~0.3 s video stall on its own. That's one more reason for the dimmer's debounce.

## 11. Known limitations

- **Relative, not absolute:** the lock means "near the sharpest in the last ~30 s". A featureless or smooth surface reads soft even in focus, so misses are possible. **The absence of the indicator must not imply clearance.**
- **Scale uncertainty:** ±10 %. The in-focus band is a few mm deep, not a plane.
- **Arming needs a visible approach**, meaning a brightness step after the stream starts. Starting already on or in the target shows no IN-ZONE until the scope backs off and approaches again.
- **Brightness thresholds** (`arm_min` 100 bare, 40 with a tip) come from a handful of runs at LED raw level 42. With a tip on, brightness is a weak approach cue.
- **Tip learning needs coarse motion:** the rim needs a few seconds of coarse motion after the tip is fitted, and gentle handling delays it. Until then the bright tip inflates the brightness. That mainly affects arming; the sharpness lock is barely affected because the tip is blurred.
- **The lower-right prior assumes the current probe geometry.** A different probe or mount needs new prior constants.
- **CLOSE needs motion ≥ 2.** A tip resting perfectly still on a surface shows IN-ZONE with locked rings but no CLOSE. Hand tremor alone exceeds the threshold, so without a tip CLOSE is effectively the same as IN-ZONE.
- **IN-ZONE flickers to SEARCHING** when sharpness dips just below 0.82 × peak. SETTLING debounces only the entry.
- **Bright but soft (closer than the zone)** shows as SEARCHING, or RESTING after 10 s still. It isn't a distinct "too close" state.

## Port notes

The desktop prototype is the reference, and the port follows it where the original write-up and the code differed:

- **Rescale timing:** the rescale happens on the frame whose learning step rebuilt the mask. The rebuild runs before the metrics.
- **Image circle:** the prototype rasterises its r = 230 disc with Pillow. The port uses the integer test `dx² + dy² ≤ 53120`, which gives the same 2558 circle blocks and differs by 40 edge pixels.
- **Initial mask:** before the first rebuild, the prototype's half-resolution mask is a bicubic resize of the disc. The port uses the same nearest-pixel rule throughout, which only affects the first few frames.

## Regression reference

`android/app/src/test/resources/focus/` holds per-frame feature logs exported from the prototype for six recorded sessions (the frames themselves are private). Each log has:

- `t`, `roll`, `bright`, `sharp_raw`, `motion` and `tip`;
- the rescale ratios `k_sharp` and `k_bright` on rebuild frames;
- the prototype's `state`, `close` and `armed`.

It also holds four synthetic runs, whose frames `SyntheticScope` regenerates bit for bit:

- `synthetic-tip` and `synthetic-notip`: coarse handling in dim light, an approach, careful movement lit, then set down, with and without a tip;
- `synthetic-cap`: a bright, soft ring around the tip that isn't static, so only the cap evidence can add it. It dims briefly (hysteresis keeps it) and then for good (it leaves);
- `synthetic-removal`: the tip taken off mid-run (its blocks leave only after sustained change).

`android/app/src/test/tools/focus_golden.py` regenerates all of these logs from the prototype, which is kept outside the repository with the recordings. It contains the synthetic scripts, the same as `SyntheticScope`'s.

- `RegressionReplayTest` feeds the recorded features through `FocusTracker`. States, CLOSE and arming must agree with the prototype on every frame, and the per-5 s percentages must equal the table below.
- `GoldenPipelineTest` runs the whole estimator on the synthetic frames against the prototype's output on the same frames. Motion, the tip fraction, states, CLOSE and arming agree exactly; brightness and sharpness agree closely (see Port notes).

The live-180731 run was recorded with the tip in an ear. There the operator reported that the CLOSE cue coincided with feeling the tip make contact. That is one observation of the cue working as intended, not a guarantee.

| recording | scenario | in-zone % per 5 s | CLOSE % per 5 s | tip mask (max) |
|---|---|---|---|---|
| ear | tip on, ear: coarse 5–10 s, entry ~10 s, careful 30–50 s, exit ~52 s | 0 0 0 0 0 32 100 100 100 100 10 0 | 0 0 0 0 0 32 100 100 100 100 10 0 | 0.26 |
| notip | no tip, ear: settled sharp 15–25 s, set down (resting) 35–40 s | 0 0 0 44 62 0 0 0 0 | 0 0 0 44 48 0 0 0 0 | 0.00 |
| live-173059 | no tip, ruler: far 0–15 s, on ruler 20–40 s and 55–85 s, too close/soft 85–102 s | 0 0 0 8 79 100 100 83 2 0 2 73 69 94 83 44 56 0 0 0 7 18 0 | 0 0 0 8 73 100 33 15 2 0 2 73 60 41 56 37 46 0 0 0 7 18 0 | 0.00 |
| live-174920 | no tip, ruler, LED dips ignored: on ruler 10–50 s, set down 60 s+ | 0 0 29 56 61 0 22 35 17 28 0 0 0 0 0 | 0 0 27 49 61 0 22 35 15 26 0 0 0 0 0 | 0.00 |
| live-175352 | no tip on ruler 10–25 s; tip fitted ~31–33 s; tip on ruler 40–50 s; set down 55 s+ | 0 2 67 88 65 0 0 0 56 30 0 0 0 0 0 | 0 2 65 88 65 0 0 0 56 30 0 0 0 0 0 | 0.20 |
| live-180731 | tip on, ear (operator-observed): entry ~40 s, careful in-ear 60–90 s, out/set down 90 s+ | 0 0 8 34 18 0 0 18 0 0 0 0 29 43 69 100 69 71 0 0 0 0 0 0 0 0 0 | 0 0 8 32 11 0 0 18 0 0 0 0 29 43 69 100 67 71 0 0 0 0 0 0 0 0 0 | 0.27 |

What the table shows:

- There is no IN-ZONE in the far or in-air phases (ear 0–10 s, live-173059 0–15 s).
- IN-ZONE holds through the careful phases.
- The state is RESTING when the scope is set down (notip 35 s+, live-175352 60 s+).
- The tip mask is 0.00 on every run without a tip, and reaches ≥ 0.18 within seconds of a tip being fitted.
