#!/usr/bin/env python3
"""Tighten a screen recording and encode it for sharing.

Drops dead air (Playwright records at a fixed rate, so agent thinking time
becomes stretches of identical frames), then encodes H.264 at a bitrate that
survives YouTube's re-compression: text-dense screen capture smears badly at
the ~350 kb/s OpenCV's writer defaults to.

    python3 tighten.py in.webm out.mp4 [bitrate]
"""
import subprocess
import sys

import cv2
import numpy as np
from imageio_ffmpeg import get_ffmpeg_exe

MAX_STATIC = 75        # ~3.0s of stillness kept per pause: unhurried
DIFF_THRESHOLD = 0.45  # mean abs gray diff (0-255) below which a frame is "still"


def main(src, dst, bitrate="3M"):
    cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {src}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    ff = subprocess.Popen(
        [
            get_ffmpeg_exe(), "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "bgr24",
            "-s", f"{w}x{h}", "-r", f"{fps}", "-i", "-",
            "-c:v", "libx264", "-preset", "slow", "-tune", "stillimage",
            "-b:v", bitrate, "-maxrate", bitrate, "-bufsize", "6M",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart",
            dst,
        ],
        stdin=subprocess.PIPE,
    )

    prev_small = None
    static_run = 0
    kept = read = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        read += 1

        small = cv2.cvtColor(cv2.resize(frame, (320, 180)), cv2.COLOR_BGR2GRAY).astype(np.float32)
        diff = 999.0 if prev_small is None else float(np.abs(small - prev_small).mean())
        prev_small = small

        if diff < DIFF_THRESHOLD:
            static_run += 1
            if static_run > MAX_STATIC:
                continue  # dead air
        else:
            static_run = 0

        ff.stdin.write(frame.tobytes())
        kept += 1

    cap.release()
    ff.stdin.close()
    ff.wait()
    print(f"in : {read} frames {read/fps:6.1f}s")
    print(f"out: {kept} frames {kept/fps:6.1f}s  ({100*kept/max(read,1):.0f}% kept)  @ {bitrate}  {w}x{h}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "3M")
