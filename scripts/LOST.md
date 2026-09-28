# LOST plate solving

[UWCubeSat/LOST](https://github.com/UWCubeSat/lost) is the backend's only plate
solver. The integration uses upstream commit `6543ec81`, native Pyramid star
identification (`--star-id-algo py`), and Davenport Q attitude estimation
(`--attitude-algo dqm`). Astrometry.net execution, guided WCS fitting, and cached
WCS fallback have been removed. Failed or unavailable LOST solves return promptly
with the measured detections; they do not start another solver.

## Pipeline

1. Load the image, apply obstruction masks, and detect measured point sources.
   An unreadable FITS file can use its same-image JPEG/PNG companion; the result
   records that source substitution. White Mountain OVL excludes the illuminated
   rim beyond 52 degrees from its calibrated optical axis before detection.
   For compact unsaturated sources, remeasure the centroid in a three-pixel
   aperture above the median local background, including wings below the detection
   threshold. Preserve saturated, extended, masked-edge, or unstable measurements.
   Use the camera/ROI-specific lens profile where available, or estimate initial
   fisheye geometry for other cameras. A coarse estimate never constitutes a solve.
2. Rectify measured centroids into a 1600 x 1600, 100-degree pinhole field. The
   calibrated camera uses background-subtracted aperture flux to rank detections;
   peak intensity saturates and otherwise ranks many bright stars by scan line.
   Select separated, spatially distributed detections and pass the brightest
   40 to LOST as Gaussian centroids in a lossless PNG. No catalog positions enter
   the rendered input.
3. Preserve the calibrated physical image orientation. Mirror the generic
   east-right horizontal projection to match LOST's camera convention. Account
   for its half-pixel coordinate convention and focal length.
4. Convert LOST's sky-to-camera quaternion back to celestial coordinates. Require
   finite near-unit coefficients, an optical center within 15 degrees of the
   expected zenith, and at least six distinct catalog confirmations within 60
   arcseconds (or the configured catalog radius, if smaller). This association
   limit is stricter than the native Pyramid pair-distance search tolerance.
5. A first unique four-star Pyramid can be false. If independent verification
   rejects it, retry with 75% and 50% of the selected brightest detections, with
   three attempts maximum and unchanged verification. An unavailable process,
   missing attitude, or timeout ends the solve. The API returns FAILED when no
   attitude can be verified and SOLVER_UNAVAILABLE when LOST is disabled/missing.
6. With at least 12 distinct catalog matches, remove inconsistent detections
   from a refinement pass using a median/MAD residual threshold capped at 60
   arcseconds. Reserve every third inlier for validation. Run LOST once more on
   measured training centroids with a three-second deadline. Accept only a pose
   whose held-out RMS does not increase, whose held-out matches remain within
   tolerance, and which passes the original catalog/zenith checks. Otherwise
   retain the verified initial pose. Unmatched detections remain inspectable;
   they can include faint stars as well as artificial sources.

For calibrated cameras, sky coordinates are also evaluated outside the square
native input field when they remain within the measured 60-degree radial lens
domain. Other detections remain unsolved. `solution.wcsFile` is null: the
solution is a celestial attitude plus a calibrated lens, not a FITS WCS file.
Each attempt writes `centroids.png`, `attitude.txt` when available, and `lost.log`
under `<work-dir>/<image-id>/lost-<unique-suffix>/`. Results for the same image can
be cached; `force=true` reruns extraction and solving.

## Installation

Install the upstream compiler, Make, Cairo, Eigen, and groff prerequisites. From
the backend directory, clone LOST if necessary and build its native database:

```sh
git clone https://github.com/UWCubeSat/lost ../lost
bash scripts/setup_lost.sh ../lost ./data/lost
```

The script builds LOST and generates a roughly 41 MB database of 5,000 bright
stars with pair distances covering 0.2–130 degrees. It installs the required
`bright-star-catalog.tsv` beside the database. Build the database on the machine
and LOST binary that will use it; its native binary format is platform dependent.

Set `LOST_COMMAND` and `LOST_DATABASE` to absolute executable/database paths. The
local Spring profile already points at this workspace's checkout and database.

Configuration under `app.plate-solve.lost`:

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Enable LOST when its executable and files are available |
| `command` | `${LOST_COMMAND:lost}` | Executable path without shell arguments |
| `database` | `${LOST_DATABASE:data/lost/bright-stars.dat}` | Native database, with adjacent TSV |
| `timeout-seconds` | `15` | Deadline per initial process, at most three attempts; optional refinement capped at three seconds |
| `angular-tolerance-deg` | `0.05` | Native Pyramid pair-distance tolerance |
| `max-stars` | `40` | Input detections per attempt, bounded to 6–80 |

The native matcher uses 1,000 estimated false stars and maximum mismatch
probability 0.0001. These inputs are not an empirical accuracy guarantee.
Capture time, site coordinates, and lens calibration matter. The OVL camera uses
its station coordinates from `catalogs/observing-sites.json`; other cameras use
the configured site coordinates. See
[calibration/README.md](calibration/README.md) for the measured QHY model and its
reproducible fitting data. A different lens, crop, or focus requires recalibration.

Compressed FITS extraction can still use CFITSIO `imcopy`, independently of plate
solving, with `app.plate-solve.fits-extraction-timeout-seconds` (30 seconds).
Astrometry.net and its indexes are not required by the backend.

## Validation

```sh
LOST_TEST_COMMAND="$LOST_COMMAND" LOST_TEST_DATABASE="$LOST_DATABASE" \
  mvn -Djava.awt.headless=true test
```

Tests cover quaternion direction, fractional pixel centers, invalid/stale/failed
process outputs, timeouts, synthetic known fields with false detections, lens
camera/ROI boundaries, rejection of noise, and prompt unsuccessful requests.
Three Broida and four OVL night frames exercise the calibrations and actual
native LOST binary. OVL tests run full image loading, rim masking, extraction,
solving, and refinement, including a truncated FITS companion and another season.
Additional tests inject false detections and failed/worse refinement attitudes
to verify exclusion and retention of the original pose. A known subpixel Gaussian
checks centroid measurement, and the reported 176-arcsecond association near
(1849.44, 1348.24) in OVL frame 56702 must remain unconfirmed. Production verification
reruns detection and LOST from original images through the public API, with force
enabled.
