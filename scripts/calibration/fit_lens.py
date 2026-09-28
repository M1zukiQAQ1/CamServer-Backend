#!/usr/bin/env python3
"""Reproduce camera intrinsics from measured star/catalog correspondences.

Requires numpy and scipy, only for offline calibration. No Python dependency is
added to the backend. Training flags keep one third of the matches held out.
Run from this directory: python3 fit_lens.py frame-57316-matches.csv
"""
import argparse
import json

import numpy as np
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("matches")
args = parser.parse_args()
rows = np.genfromtxt(args.matches, delimiter=",", names=True)
uv = np.column_stack([rows["x"], rows["y"]])
ra, dec = np.deg2rad(rows["raDeg"]), np.deg2rad(rows["decDeg"])
world = np.column_stack([np.cos(dec) * np.cos(ra), np.cos(dec) * np.sin(ra), np.sin(dec)])
dx, dy = uv[:, 0] - 1250, uv[:, 1] - 1090
radius = np.hypot(dx, dy)
theta = radius / 780
camera = np.column_stack([np.cos(theta), -dx / radius * np.sin(theta), -dy / radius * np.sin(theta)])
rotation, _ = Rotation.align_vectors(camera, world)
initial = np.r_[1250, 1090, 780, 0, 0, rotation.as_rotvec(), 1]


def project(parameters):
    rays = Rotation.from_rotvec(parameters[5:8]).apply(world)
    transverse = np.hypot(rays[:, 1], rays[:, 2])
    angle = np.arctan2(transverse, rays[:, 0])
    radius = parameters[2] * angle + parameters[3] * angle**3 + parameters[4] * angle**5
    return np.column_stack([
        parameters[0] - radius * rays[:, 1] / transverse,
        parameters[1] - radius * rays[:, 2] / transverse * parameters[8],
    ])


training = rows["training"] == 1
fit = least_squares(lambda p: (project(p)[training] - uv[training]).ravel(), initial,
                    loss="soft_l1", f_scale=0.7, max_nfev=3000)
error = np.linalg.norm(project(fit.x) - uv, axis=1)
if not fit.success:
    raise RuntimeError(fit.message)
print(json.dumps(dict(zip(["centerX", "centerY", "radial1", "radial3", "radial5", "aspectY"],
                          fit.x[[0, 1, 2, 3, 4, 8]])), indent=2))
for name, subset in [("training", training), ("held out", ~training)]:
    print(f"{name}: {subset.sum()} stars; RMS {np.sqrt(np.mean(error[subset]**2)):.4f} px; "
          f"median {np.median(error[subset]):.4f} px")
