# QHY fisheye calibrations

## Broida, 2026-09-23 (current)

The Broida profile was refitted with Brown-Conrady decentering terms
(`decentering1`, `decentering2`, acting on radii normalised by 1000 pixels) on
star positions measured the way the backend now measures them: a
Gaussian-windowed centroid (sigma 1.4 px, 15 x 15 window) on the linear FITS
values after subtracting the median of an 8-14 px annulus. The 2026-09-06 fit
below used 8-bit detection centroids, 71% of which were integer peak pixels on
recent frames, and had no decentering; its held-out errors were about 0.5 px.

`measure_xwin_matches.py` produced `broida-2026-09/frame-<id>-xwin-matches.csv`
(unique catalog stars with no Bright Star Catalog neighbour within 0.3 degrees,
within 60 degrees of the axis). `fit_lens_multiframe.py` fits shared intrinsics
with one rotation per frame; test frames only receive a rotation fit.

| Frames | Role | Stars | Median | p75 | p90 |
| --- | --- | ---: | ---: | ---: | ---: |
| 57316, 71577, 71617, 67592 (2026-09-05, 09-09, 09-23) | fit | 450 | 41.6" | 61.3" | 88.0" |
| 71597, 71810, 68742, 70738 (2026-09-13, 09-20, 09-23/24) | held out | 281 | 42.9" | 62.2" | 96.3" |

Without the decentering terms the same data give 62.4" held-out median (the
old profile with the new centroids: 62.6"). A 7th-order radial term, prism terms
and explicit refraction were each worth at most 1-2" and were left out. The
remaining scatter grows with magnitude (about 0.14 px for mag < 2.5 stars,
0.28 px at mag 4.5-5.5), so it is centroid noise rather than lens shape; the
image scale is about 261"/px near the axis.

With the backend pipeline (LOST, SIMBAD catalog, masks) at the old 180"
association limit, the held-out frames 71597, 71810 and 68742 have median
catalog match errors of 38.7", 44.6" and 44.6". Pooled over seven night frames,
four of which were fitting frames, the median went from 86.6" / p90 148"
(production build) to 42.7" / 91.5", and 73% instead of 31% of associations fall
within the 60" confirmation limit. On 32 frames of the 2026-09-22/23 night the
new build solved all 32 (production: 31) and confirmed 1346 stars within 60"
(median 33.2"), against 597 within 60" in production.

## Broida, 2026-09-06 (superseded)

The profile in `src/main/resources/catalogs/fisheye-lenses.json` applies only to
camera `QHY5III678M-54ffe941916d2aa46` at its 2500 x 2180 acquisition ROI. The
sensor's full width is 3856 pixels; treating the cropped height as the fisheye
diameter and assuming east-right image orientation caused the previous failure.

The model is `r = radial1*theta + radial3*theta^3 + radial5*theta^5`, with theta in
radians, radius in pixels, fitted optical center, and a vertical aspect factor.
Only angles through 60 degrees from the optical axis are used. This is an
intrinsic lens model: the stored profile contains no celestial attitude, time,
azimuth, or catalog star coordinates. LOST independently determines orientation
for each exposure. Recalibrate if the lens, focus, crop, or camera changes.

`frame-57316-matches.csv` contains measured centroids from the captured frame at
2026-09-05T11:58:13.050Z, paired with the upstream LOST Bright Star Catalog.
Initial associations came from searching both image parities, center, radius,
rotation, and radial exponent against the observed pattern. Unique associations
within 5 pixels supplied 101 matches, including a few blends/outliers retained
for robust fitting. The CSV records the deterministic train/holdout split.

`fit_lens.py` reproduces the fit with NumPy/SciPy. Rotation is fitted jointly but
discarded when exporting intrinsics. The 67 training stars have 0.8480-pixel RMS;
the 34 held-out stars have 0.5438-pixel RMS and 0.3449-pixel median error. No
held-out coordinates participate in fitting. Those figures describe this image,
not an accuracy guarantee for every detection or changing camera optics.

The native integration tests also use independent exposures 57304 and 57292,
captured one and two hours earlier. Their measured centroid CSVs and JPEGs are
under `src/test/resources/lost/`. Frame 57316 uses the source preview; the other
two use the backend's masked crop previews. Image/centroid coordinates remain
in the original 2500 x 2180 ROI. Their measured flux and centroids, never projected
catalog positions, form the native input. The third JPEG fixture can produce a false first Pyramid candidate, exercising
the independently verified retry on a cleaner cohort of detections. These fixtures test the solver path; production checks also
rerun source extraction from the original image files.

## White Mountain OVL

The second profile applies only to camera `QHY5III678C-57bbd14782e9f938e` at
2500 x 2180. `frame-56690-matches.csv` contains 50 measured FITS centroids from
2026-03-22T06:05:32.857Z with unique Bright Star Catalog associations. Initial
matching ranked measured detections by aperture flux before searching the
fisheye geometry. The same `fit_lens.py` reproduces the model: 33 training stars
have 0.2853-pixel RMS and 17 held-out stars have 0.4734-pixel RMS, 0.3053-pixel
median error. The fitted celestial rotation is discarded.

The lens model is bounded to 60 degrees; source detection is restricted further
to 52 degrees (approximately 697 pixels from the calibrated optical center).
This excludes the building/antenna lights around OVL's rim, including a bright
source near (474, 1060) that survived the previous generic mask. Apply the
ellipse in original image coordinates before detecting sources. Do not rescale
or shift source positions when masking. Changing this radius trades sky coverage
against contamination and should be checked on real images.

OVL's camera record has no database coordinates. The bundled station coordinates
are 37.36055556 N, -118.32666667 E, from the official
[Owens Valley Station page](https://www.wmrc.edu/owens-valley-station/)
(37 degrees 21 minutes 38 seconds N, 118 degrees 19 minutes 36 seconds W).
Using Broida's configured coordinates gave the wrong expected zenith.

Full native integration tests use JPEG previews 56690, 56678, 56702 (March 2026)
and 34909 (November 2025), under `src/test/resources/lost/ovl/`. The latter three
were not used for calibration. Frame 56702's original FITS is truncated: its
2500 x 2180 x 3 16-bit header declares more data than the file contains. The test
includes that header without pixel data and verifies use of the readable JPEG
companion. Missing or unreadable companions must still fail. Production checks
also rerun complete extraction from the original server files.
