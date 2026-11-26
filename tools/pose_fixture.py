"""Turn exercise clips into pose fixtures for the Kotlin motion module.

One text file per clip: a header line `width height fps frames`, then one line per frame:
`t_ms` followed by 33 landmarks as `x y visibility`, x and y in image heights (x already multiplied by
width/height, so distances and angles are true to the picture), `-` for a frame with no person.
Also writes a contact sheet (one small frame every `SHEET_EVERY_S`) so a person can count the reps.

usage: pose.py MODEL CLIPS_DIR OUT_DIR [name-filter]
"""
import pathlib
import sys

import cv2
import mediapipe as mp
import numpy as np

SHEET_EVERY_S = 0.5
SHEET_H = 180
FLIP_FRAMES = 5

model, clips, out = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
only = sys.argv[4] if len(sys.argv) > 4 else ""
(out / "poses").mkdir(parents=True, exist_ok=True)
(out / "sheets").mkdir(parents=True, exist_ok=True)

for clip in sorted(clips.glob("*.mp4")):
    if only not in clip.name:
        continue
    cap = cv2.VideoCapture(str(clip))
    w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    # A fresh landmarker per clip: VIDEO mode tracks across frames and needs rising timestamps.
    options = mp.tasks.vision.PoseLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=model),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
    )
    lines, thumbs, n, missed, next_thumb, flipped, resets = [], [], 0, 0, 0.0, 0, 0
    landmarker = mp.tasks.vision.PoseLandmarker.create_from_options(options)
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        t_ms = int(n * 1000 / fps)
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        result = landmarker.detect_for_video(image, t_ms)
        if result.pose_landmarks:
            lm = result.pose_landmarks[0]
            # Tracking can lock on upside down after the person bends towards the camera, and stays
            # there. The app does the same as this: after FLIP_FRAMES such frames, start tracking afresh.
            flipped = flipped + 1 if lm[11].y + lm[12].y > lm[23].y + lm[24].y else 0
            if flipped >= FLIP_FRAMES:
                landmarker.close()
                landmarker = mp.tasks.vision.PoseLandmarker.create_from_options(options)
                flipped, resets = 0, resets + 1
            lines.append(f"{t_ms} " + " ".join(f"{p.x * w / h:.3f} {p.y:.3f} {p.visibility:.1f}" for p in lm))
        else:
            lines.append(f"{t_ms} -")
            missed += 1
        if t_ms / 1000 >= next_thumb:
            thumbs.append(cv2.resize(frame, (int(w * SHEET_H / h), SHEET_H)))
            next_thumb += SHEET_EVERY_S
        n += 1
    landmarker.close()
    cap.release()
    (out / "poses" / (clip.stem + ".txt")).write_text(f"{w} {h} {fps:.2f} {n}\n" + "\n".join(lines) + "\n")
    if not thumbs:
        print(f"{clip.stem}: no frames could be read")
        continue
    # Contact sheet, 10 frames to a row.
    rows = [thumbs[i:i + 10] for i in range(0, len(thumbs), 10)]
    blank = np.zeros_like(thumbs[0])
    sheet = np.vstack([np.hstack(r + [blank] * (10 - len(r))) for r in rows])
    cv2.imwrite(str(out / "sheets" / (clip.stem + ".jpg")), sheet, [cv2.IMWRITE_JPEG_QUALITY, 70])
    print(f"{clip.stem}: {w}x{h} {fps:.0f}fps {n} frames {n / fps:.1f}s, no pose in {missed}, tracking restarted {resets}x")
