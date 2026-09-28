#!/usr/bin/env python3
"""Measure star/catalog correspondences with the backend's linear-FITS centroid.

The centroid is PlateSolveService.linearCentroid: median background of an 8-14 px annulus,
then a Gaussian-windowed (sigma 1.4 px, 15 x 15 window) centroid iterated on the raw FITS
values. A plate-solve result JSON of the same frame seeds only the initial rotation; the
catalog positions come from LOST's Bright Star Catalog. Requires numpy, scipy and astropy.

    python3 measure_xwin_matches.py frame.fits plate-solve.json out.csv \
        --catalog ../../data/lost/bright-star-catalog.tsv
"""
import argparse
import json

import numpy as np
from astropy.io import fits
from scipy import ndimage
from scipy.spatial.transform import Rotation

from fit_lens_multiframe import camera_rays

# The 2026-09-06 profile is only used to identify stars; the fit replaces it.
IDENTIFY_LENS = dict(centerX=1229.0216343754541, centerY=1093.3544246702218, radial1=790.5517330230037,
                     radial3=-19.091983716113646, radial5=-2.7310432164017437, aspectY=1.000431671656578,
                     decentering1=0.0, decentering2=0.0)
SIGMA, HALF, INNER, OUTER = 1.4, 7, 8, 14


def unit(ra_deg, dec_deg):
    ra, dec = np.deg2rad(ra_deg), np.deg2rad(dec_deg)
    return np.column_stack([np.cos(dec) * np.cos(ra), np.cos(dec) * np.sin(ra), np.sin(dec)])


def linear_centroid(data, x0, y0):
    cx, cy = int(round(x0)), int(round(y0))
    if cx < OUTER + 1 or cy < OUTER + 1 or cx >= data.shape[1] - OUTER - 1 or cy >= data.shape[0] - OUTER - 1:
        return None
    gy, gx = np.mgrid[cy - OUTER: cy + OUTER + 1, cx - OUTER: cx + OUTER + 1]
    ring = np.hypot(gx - cx, gy - cy)
    patch = data[cy - OUTER: cy + OUTER + 1, cx - OUTER: cx + OUTER + 1]
    background = np.median(patch[(ring >= INNER) & (ring <= OUTER)])
    x, y = float(x0), float(y0)
    for _ in range(20):
        xi, yi = int(round(x)), int(round(y))
        if abs(xi - cx) > OUTER - HALF or abs(yi - cy) > OUTER - HALF:
            return None
        wy, wx = np.mgrid[yi - HALF: yi + HALF + 1, xi - HALF: xi + HALF + 1]
        value = np.clip(data[yi - HALF: yi + HALF + 1, xi - HALF: xi + HALF + 1] - background, 0, None)
        weight = value * np.exp(-((wx - x) ** 2 + (wy - y) ** 2) / (2 * SIGMA ** 2))
        if weight.sum() <= 0:
            return None
        nx = x + 2 * (weight * (wx - x)).sum() / weight.sum()
        ny = y + 2 * (weight * (wy - y)).sum() / weight.sum()
        converged = abs(nx - x) < 1e-4 and abs(ny - y) < 1e-4
        x, y = nx, ny
        if converged:
            break
    return (x, y) if np.hypot(x - x0, y - y0) <= 1.5 else None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("fits")
    parser.add_argument("solution")
    parser.add_argument("output")
    parser.add_argument("--catalog", required=True)
    args = parser.parse_args()
    data = fits.getdata(args.fits).astype(float)
    rows = [line.split("|") for line in open(args.catalog)]
    bsc = np.array([(float(p[0]), float(p[1]), int(p[2]), float(p[4])) for p in rows])
    bsc = bsc[bsc[:, 3] <= 6.5]
    catalog = unit(bsc[:, 0], bsc[:, 1])

    # Initial rotation from the backend's identified stars (sky positions -> nearest BSC star).
    named = [s for s in json.load(open(args.solution))["stars"] if s.get("name")]
    seeds = unit(np.array([s["raDeg"] for s in named]), np.array([s["decDeg"] for s in named]))
    world = catalog[np.argmax(seeds @ catalog.T, axis=1)]
    rays = camera_rays(IDENTIFY_LENS, np.array([[s["x"], s["y"]] for s in named]))
    keep = np.ones(len(rays), bool)
    for _ in range(4):
        rotation = Rotation.align_vectors(rays[keep], world[keep])[0]
        error = np.degrees(np.arccos(np.clip(np.sum(rotation.apply(world) * rays, axis=1), -1, 1))) * 3600
        keep = error < max(150, 3 * np.median(error))

    # Peaks on a background-subtracted, lightly smoothed image, refined with the backend centroid.
    small = ndimage.median_filter(data[::8, ::8], size=9)
    subtracted = data - ndimage.zoom(small, 8, order=1)[: data.shape[0], : data.shape[1]]
    noise = 1.4826 * np.median(np.abs(subtracted[::4, ::4] - np.median(subtracted[::4, ::4])))
    smooth = ndimage.gaussian_filter(subtracted, 1.0)
    ys, xs = np.nonzero((smooth == ndimage.maximum_filter(smooth, 7)) & (smooth > 2.5 * noise))
    order = np.argsort(-smooth[ys, xs])[:1500]
    points = np.array([p for p in (linear_centroid(data, x, y) for x, y in zip(xs[order], ys[order])) if p])
    rays = camera_rays(IDENTIFY_LENS, points)
    inside = np.degrees(np.arccos(np.clip(rays[:, 0], -1, 1))) < 60
    points, sky = points[inside], rotation.inv().apply(rays[inside])

    # Keep unique associations within 500 arcsec whose catalog star has no neighbour within 0.3 deg.
    dots = sky @ catalog.T
    best = np.argmax(dots, axis=1)
    separation = np.degrees(np.arccos(np.clip(dots[np.arange(len(best)), best], -1, 1))) * 3600
    isolated = np.array([np.sum(catalog @ catalog[b] > np.cos(np.radians(0.3))) == 1 for b in best])
    index = np.nonzero((separation < 500) & isolated)[0]
    index = index[np.bincount(best[index], minlength=len(catalog))[best[index]] == 1]
    table = np.column_stack([points[index], bsc[best[index]][:, [0, 1, 3, 2]]])
    np.savetxt(args.output, table, delimiter=",", header="x,y,raDeg,decDeg,mag,hr", comments="",
               fmt=["%.4f", "%.4f", "%.6f", "%.6f", "%.2f", "%d"])
    print(f"{len(points)} centroids within 60 degrees; {len(index)} unique isolated catalog matches")


if __name__ == "__main__":
    main()
