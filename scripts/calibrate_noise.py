"""Measure the seeing monitor's centroid noise floor from an artificial star (see SEEING.md).

Point the seeing camera at a fixed pinhole or LED indoors, about as bright and as sharp as Polaris
looks at night, with camera and source clamped down. This program records frames, measures the
spot in each with the producer's own functions, removes linear drift, and prints the five
noise values for seeing-calibration.json:

    centroid_noise_px, noise_exposure_us, noise_gain, noise_flux, noise_background_rms

Live, on seeingcam (stop the stream first, only one program can hold the camera:
``sudo systemctl stop seeing-monitor-stream``):

    python3 calibrate_noise.py --camera --camera-path /home/pi/allSkyCamera/camera \\
        --exposure 5000 --gain 50 --frames 600 --save noise-frames.npz

From frames recorded earlier (an .npz with ``frames`` (N, H, W) and ``times`` (N,) seconds):

    python3 calibrate_noise.py --frames-file noise-frames.npz --exposure 5000 --gain 50

Use the exposure and gain the monitor runs at night with automatic exposure off; the monitor
only estimates seeing while its settings equal the two recorded here. The aperture cannot be
measured this way: read the f-number set on the lens (aperture_mm = focal length / f-number; the
2026-09-10 plate solution gives a focal length of 100.7 mm) or measure the entrance pupil with
calipers.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

from live_stream_producer import CameraSource, measure_star
from seeing_metrics import detrend, star_photometry

MIN_FRAMES = 100


def measurement_image(raw: np.ndarray) -> np.ndarray:
    """The image the producer measures: 8-bit-equivalent counts with fractions kept, one channel."""
    image = raw.astype(np.float32) / 256.0 if raw.dtype == np.uint16 else raw.astype(np.float32)
    return image if image.ndim == 2 else image.mean(axis=2)


def measure(frames: Sequence[np.ndarray], times: Sequence[float]) -> Dict[str, Any]:
    """Centroid noise and reference photometry of the brightest spot across the frames.

    Raises ValueError when the frames cannot give a trustworthy number.
    """
    xs: List[float] = []
    ys: List[float] = []
    ts: List[float] = []
    fluxes: List[float] = []
    backgrounds: List[float] = []
    widths: List[float] = []
    saturated = 0
    # Find the spot once with the wide search, then follow it the way the producer does between
    # searches. The wide search itself is not used as a sample: it measures in a different window.
    last: Optional[Tuple[float, float]] = None
    for raw in frames:
        seed = measure_star(measurement_image(raw))
        if seed is not None:
            last = (float(seed[0]), float(seed[1]))
            break
    for raw, at in zip(frames, times):
        full_scale = 65535.0 if raw.dtype == np.uint16 else 255.0
        gray = measurement_image(raw)
        star = measure_star(gray, near=last, search_global=False) if last is not None else None
        if star is None:
            continue
        photometry = star_photometry(gray, star)
        if photometry is None or photometry["fwhmPx"] is None:
            continue
        x, y = float(star[0]), float(star[1])
        ix, iy = int(round(x)), int(round(y))
        # 0.99 of full scale also catches 12-bit data left-aligned in 16 bits (65520).
        if float(raw[max(0, iy - 8):iy + 9, max(0, ix - 8):ix + 9].max()) >= 0.99 * full_scale:
            saturated += 1
        last = (x, y)
        xs.append(x)
        ys.append(y)
        ts.append(float(at))
        fluxes.append(photometry["flux"])
        backgrounds.append(photometry["backgroundRms"])
        widths.append(photometry["fwhmPx"])

    total = len(frames)
    used = len(xs)
    if used < MIN_FRAMES:
        raise ValueError(f"the spot was measured in only {used} of {total} frames; at least {MIN_FRAMES} are needed")
    if used < 0.9 * total:
        raise ValueError(f"the spot was lost in {total - used} of {total} frames; it is too faint or not stable")
    if saturated > 0.01 * used:
        raise ValueError(f"the spot is saturated in {saturated} of {used} frames; dim the source or shorten the exposure")

    # SEEING.md: per axis, residual sum of squares after removing a straight line, over N - 2.
    residual_x, drift_x = detrend(ts, xs)
    residual_y, drift_y = detrend(ts, ys)
    variance_x = float(np.sum(residual_x ** 2)) / (used - 2)
    variance_y = float(np.sum(residual_y ** 2)) / (used - 2)
    noise = math.sqrt((variance_x + variance_y) / 2)
    # A source that moves is not a noise reference: compare the two halves of the run.
    half = used // 2
    first = math.sqrt((float(np.var(residual_x[:half])) + float(np.var(residual_y[:half]))) / 2)
    second = math.sqrt((float(np.var(residual_x[half:])) + float(np.var(residual_y[half:]))) / 2)
    flux = float(np.median(fluxes))
    return {
        "centroid_noise_px": round(noise, 5),
        "noise_flux": round(flux, 2),
        "noise_background_rms": round(float(np.median(backgrounds)), 4),
        "frames": used,
        "durationSeconds": round(ts[-1] - ts[0], 2),
        "noiseXPx": round(math.sqrt(variance_x), 5),
        "noiseYPx": round(math.sqrt(variance_y), 5),
        "firstHalfPx": round(first, 5),
        "secondHalfPx": round(second, 5),
        "driftPxPerMinute": round(60 * math.hypot(float(drift_x), float(drift_y)), 4),
        "fwhmPx": round(float(np.median(widths)), 2),
        "fluxScatterPercent": round(100 * 1.4826 * float(np.median(np.abs(np.asarray(fluxes) - flux))) / flux, 2),
        "position": [round(float(np.mean(xs)), 1), round(float(np.mean(ys)), 1)],
    }


def warnings(result: Dict[str, Any]) -> List[str]:
    notes = []
    low, high = sorted((result["firstHalfPx"], result["secondHalfPx"]))
    if high > 1.5 * max(low, 1e-6):
        notes.append(f"The two halves of the run disagree ({result['firstHalfPx']} and {result['secondHalfPx']} px): "
                     "something moved or the air was not still. Repeat before using the number.")
    if result["driftPxPerMinute"] > 0.5:
        notes.append(f"The spot drifted {result['driftPxPerMinute']} px per minute; check the mounts.")
    if result["fluxScatterPercent"] > 5:
        notes.append(f"The brightness varied by {result['fluxScatterPercent']} %; use a steadier source.")
    return notes


def capture(args: argparse.Namespace) -> Tuple[List[np.ndarray], List[float]]:
    source = CameraSource(args.camera_path, None, 16)
    frames: List[np.ndarray] = []
    times: List[float] = []
    try:
        for _ in range(5):   # the first frames after a settings change can carry the old exposure
            source.read((args.exposure, args.gain))
        started = time.monotonic()
        while len(frames) < args.frames:
            raw, _ = source.read((args.exposure, args.gain))
            times.append(time.monotonic() - started)
            # Keep a window around the frame's brightest spot: 600 full frames would not fit in memory.
            if not frames:
                gray = measurement_image(raw)
                star = measure_star(gray)
                if star is None:
                    raise SystemExit("No spot found in the first frame; check the source, focus and exposure.")
                cx, cy = int(round(star[0])), int(round(star[1]))
                half = args.window // 2
                y0 = max(0, min(raw.shape[0] - args.window, cy - half))
                x0 = max(0, min(raw.shape[1] - args.window, cx - half))
                print(f"spot at ({star[0]:.1f}, {star[1]:.1f}); recording a {args.window} px window", file=sys.stderr)
            frames.append(np.array(raw[y0:y0 + args.window, x0:x0 + args.window], copy=True))
    finally:
        source.close()
    return frames, times


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    where = parser.add_mutually_exclusive_group(required=True)
    where.add_argument("--camera", action="store_true", help="record from the QHY camera now")
    where.add_argument("--frames-file", help=".npz with 'frames' (N, H, W) and 'times' (N,) in seconds")
    parser.add_argument("--camera-path", default="/home/pi/allSkyCamera/camera", help="directory containing camera.py")
    parser.add_argument("--exposure", type=int, required=True, help="exposure in microseconds, as used at night")
    parser.add_argument("--gain", type=int, required=True, help="gain, as used at night")
    parser.add_argument("--frames", type=int, default=600, help="frames to record (default 600)")
    parser.add_argument("--window", type=int, default=256, help="side of the recorded window in pixels (default 256)")
    parser.add_argument("--save", help="write the recorded frames to this .npz (keep it with the calibration)")
    args = parser.parse_args(argv)

    if args.camera:
        frames, times = capture(args)
        if args.save:
            np.savez_compressed(args.save, frames=np.stack(frames), times=np.asarray(times),
                                exposure_us=args.exposure, gain=args.gain)
    else:
        with np.load(args.frames_file) as data:
            frames, times = list(data["frames"]), [float(t) for t in data["times"]]
    try:
        result = measure(frames, times)
    except ValueError as e:
        print(f"No calibration: {e}.", file=sys.stderr)
        return 1
    for note in warnings(result):
        print("WARNING:", note, file=sys.stderr)
    print(json.dumps({"measured": result}, indent=2), file=sys.stderr)
    print(json.dumps({"centroid_noise_px": result["centroid_noise_px"], "noise_exposure_us": args.exposure,
                      "noise_gain": args.gain, "noise_flux": result["noise_flux"],
                      "noise_background_rms": result["noise_background_rms"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
