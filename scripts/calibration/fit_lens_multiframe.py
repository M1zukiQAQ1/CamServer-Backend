#!/usr/bin/env python3
"""Fit shared fisheye intrinsics, including Brown-Conrady decentering, over several frames.

Each frame gets its own rotation; only the intrinsics are shared and exported. Test frames
are never used for the intrinsics: they only receive a rotation fit before scoring.
Requires numpy and scipy (offline only). Run from this directory:

    python3 fit_lens_multiframe.py \
        --train broida-2026-09/frame-{57316,71577,71617,67592}-xwin-matches.csv \
        --test broida-2026-09/frame-{71597,71810,68742,70738}-xwin-matches.csv
"""
import argparse
import json

import numpy as np
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation

# Starting point: the 2026-09-06 Broida profile.
START = dict(centerX=1229.02, centerY=1093.35, radial1=790.55, radial3=-19.09, radial5=-2.73,
             aspectY=1.00043, decentering1=0.0, decentering2=0.0)
SCALE = dict(centerX=1, centerY=1, radial1=1, radial3=1, radial5=1, aspectY=1e-3, decentering1=1e-4, decentering2=1e-4)


def load(path):
    rows = np.genfromtxt(path, delimiter=",", names=True)
    ra, dec = np.deg2rad(rows["raDeg"]), np.deg2rad(rows["decDeg"])
    world = np.column_stack([np.cos(dec) * np.cos(ra), np.cos(dec) * np.sin(ra), np.sin(dec)])
    return dict(xy=np.column_stack([rows["x"], rows["y"]]), world=world)


def decenter(lens, x, y):
    xn, yn = x / 1000, y / 1000
    r2 = xn * xn + yn * yn
    p1, p2 = lens["decentering1"], lens["decentering2"]
    return (x + 1000 * (2 * p1 * xn * yn + p2 * (r2 + 2 * xn * xn)),
            y + 1000 * (p1 * (r2 + 2 * yn * yn) + 2 * p2 * xn * yn))


def radius(lens, theta):
    return theta * (lens["radial1"] + theta ** 2 * (lens["radial3"] + theta ** 2 * lens["radial5"]))


def project(lens, rotvec, world):
    """Catalog unit vectors -> image pixels; the same model as FisheyeLens.java."""
    cam = Rotation.from_rotvec(rotvec).apply(world)
    transverse = np.hypot(cam[:, 1], cam[:, 2])
    r = radius(lens, np.arctan2(transverse, cam[:, 0]))
    x, y = decenter(lens, -r * cam[:, 1] / transverse, -r * cam[:, 2] / transverse)
    return np.column_stack([lens["centerX"] + x, lens["centerY"] + y * lens["aspectY"]])


def camera_rays(lens, xy):
    dx, dy = xy[:, 0] - lens["centerX"], (xy[:, 1] - lens["centerY"]) / lens["aspectY"]
    ux, uy = dx.copy(), dy.copy()
    for _ in range(8):
        fx, fy = decenter(lens, ux, uy)
        ux, uy = ux + dx - fx, uy + dy - fy
    r = np.hypot(ux, uy)
    low, high = np.zeros_like(r), np.full_like(r, np.deg2rad(80))
    for _ in range(60):
        middle = (low + high) / 2
        above = radius(lens, middle) < r
        low, high = np.where(above, middle, low), np.where(above, high, middle)
    theta = (low + high) / 2
    s = np.sin(theta) / np.maximum(r, 1e-12)
    return np.column_stack([np.cos(theta), -ux * s, -uy * s])


def fit(frames, terms, lens=None):
    base = dict(START if lens is None else lens)
    rot0 = [Rotation.align_vectors(camera_rays(base, f["xy"]), f["world"])[0].as_rotvec() for f in frames]
    free = terms if lens is None else []
    x0 = np.concatenate([[base[t] for t in free]] + rot0)
    x_scale = np.concatenate([[SCALE[t] for t in free]] + [[1e-3] * 3] * len(frames))

    def unpack(p):
        fitted = dict(base, **dict(zip(free, p[:len(free)])))
        return fitted, [p[len(free) + 3 * k: len(free) + 3 * k + 3] for k in range(len(frames))]

    def residuals(p):
        fitted, rotations = unpack(p)
        return np.concatenate([(project(fitted, rv, f["world"]) - f["xy"]).ravel() for rv, f in zip(rotations, frames)])

    result = least_squares(residuals, x0, loss="soft_l1", f_scale=0.5, x_scale=x_scale, max_nfev=50000)
    if not result.success:
        raise RuntimeError(result.message)
    return unpack(result.x)


def errors_arcsec(lens, frame):
    _, (rotvec,) = fit([frame], [], lens)
    sky = Rotation.from_rotvec(rotvec).inv().apply(camera_rays(lens, frame["xy"]))
    cross = np.linalg.norm(np.cross(sky, frame["world"]), axis=1)
    return np.degrees(np.arctan2(cross, np.sum(sky * frame["world"], axis=1))) * 3600


def summary(errors):
    return (f"{len(errors)} stars; median {np.median(errors):.1f}, p75 {np.percentile(errors, 75):.1f}, "
            f"p90 {np.percentile(errors, 90):.1f} arcsec")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--train", nargs="+", required=True)
    parser.add_argument("--test", nargs="*", default=[])
    args = parser.parse_args()
    train, test = [load(p) for p in args.train], [load(p) for p in args.test]
    terms = list(START)
    for label, model_terms in [("without decentering", terms[:6]), ("with decentering", terms)]:
        lens, _ = fit(train, model_terms)
        print(f"{label}: train {summary(np.concatenate([errors_arcsec(lens, f) for f in train]))}")
        if test:
            print(f"{' ' * len(label)}  test  {summary(np.concatenate([errors_arcsec(lens, f) for f in test]))}")
    print(json.dumps(lens, indent=2))


if __name__ == "__main__":
    main()
