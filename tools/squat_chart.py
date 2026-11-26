"""README figure: squat depth over one recorded set, with the reps the counter counted.

Ports the squat signal and RepCounter from the Kotlin motion module (same constants); the script
fails if its count differs from the one passed in, so the picture cannot drift from the code.

usage: chart.py POSE_FILE EXPECTED_REPS OUT_PNG [site]
"""
import math
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

BG, TEXT, MUTED, DIVIDER, ACCENT = "#101214", "#F2F0EA", "#9BA1A6", "#2A2E33", "#F2A93B"
LH, RH, LA, RA = 23, 24, 27, 28
MIN_RISE, TYPICAL, RETURN, MIN_REP_MS, AT_REST, SHALLOW_SIDE = 0.12, 0.5, 0.6, 500, 0.25, 0.38


def mid_y(f, a, b):
    wa, wb = f[a * 3 + 2] + 0.01, f[b * 3 + 2] + 0.01
    return (f[a * 3 + 1] * wa + f[b * 3 + 1] * wb) / (wa + wb)


rows = [l.split() for l in open(sys.argv[1]).read().splitlines()[1:] if l.split()[1] != "-"]
ts, depth = [], []
recent, smooth, last_t, standing = [], None, 0, 0.0
for r in rows:
    t, f = int(r[0]), [float(x) for x in r[1:]]
    recent = (recent + [mid_y(f, LA, RA) - mid_y(f, LH, RH)])[-5:]
    med = sorted(recent)[len(recent) // 2]
    smooth = med if smooth is None else smooth + (1 - math.exp(-(t - last_t) / 100)) * (med - smooth)
    last_t = t
    standing = max(standing, smooth)
    ts.append(t / 1000)
    depth.append(1 - smooth / max(standing, 0.01))

# RepCounter.add
reps, rises = [], [TYPICAL]
low, low_t, high, high_t, rising, rest_t = float("inf"), 0, 0.0, 0, False, 0
for t, s in zip(ts, depth):
    if not rising:
        if s < low:
            low, low_t = s, t
        if s - low <= MIN_RISE * AT_REST:
            rest_t = t
        if s - low >= MIN_RISE:
            rising, high, high_t = True, s, t
        continue
    if s > high:
        high, high_t = s, t
    rise = high - low
    if high - s < RETURN * min(rise, sorted(rises)[len(rises) // 2]):
        continue
    if (t - rest_t) * 1000 >= MIN_REP_MS:
        reps.append((high_t, high))
        rises = (rises + [rise])[-5:]
    rising, low, low_t = False, s, t
assert len(reps) == int(sys.argv[2]), f"counted {len(reps)}, expected {sys.argv[2]}"

plt.rcParams.update({"font.family": "Helvetica Neue", "font.size": 11})
SITE = len(sys.argv) > 4 and sys.argv[4] == "site"  # no title, larger type: the page captions it
fig, ax = plt.subplots(figsize=(7.0, 3.3) if SITE else (9.6, 3.6), dpi=240 if SITE else 200, facecolor=BG)
ax.set_facecolor(BG)
ax.axhline(SHALLOW_SIDE, color=MUTED, lw=1, ls=(0, (4, 4)), zorder=1)
ax.text(ts[-1] + 0.3, SHALLOW_SIDE, "deep enough", color=MUTED, va="center", ha="left", fontsize=10)
ax.plot(ts, depth, color=TEXT, lw=2, solid_capstyle="round", zorder=2)
for n, (t, d) in enumerate(reps, 1):
    ok = d >= SHALLOW_SIDE
    ax.plot([t], [d], marker="o", ms=8, mfc=ACCENT if ok else BG, mec=ACCENT, mew=2, zorder=3)
    ax.text(t, d + 0.045, str(n), color=TEXT, ha="center", va="bottom", fontsize=10)
ax.set_ylim(-0.03, 0.72)
ax.set_xlim(0, ts[-1] + 0.2)
ax.set_yticks([0, 0.2, 0.4, 0.6])
ax.set_yticklabels(["standing", "0.2", "0.4", "0.6"])
ax.set_xticks(range(0, int(ts[-1]) + 1, 5))
ax.set_xticklabels([f"{s} s" for s in range(0, int(ts[-1]) + 1, 5)])
ax.tick_params(colors=MUTED, length=0, pad=8)
ax.grid(axis="y", color=DIVIDER, lw=1)
ax.set_axisbelow(True)
for side in ax.spines.values():
    side.set_visible(False)
if not SITE:
    ax.set_title("How far the hips drop, as a share of standing hip height. Each dot is a counted rep; hollow ones got “Sit deeper”.",
                 color=MUTED, fontsize=10.5, loc="left", pad=14)
fig.tight_layout()
fig.savefig(sys.argv[3], facecolor=BG)
print("reps", len(reps), "shallow", sum(d < SHALLOW_SIDE for _, d in reps))
