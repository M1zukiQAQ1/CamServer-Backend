# Polaris image-motion monitoring

`live_stream_producer.py` measures star motion directly on native, unstretched camera
frames through `seeing_metrics.py`. The Seeing Monitor page shows motion after linear
drift removal, slow drift, brightness fluctuation, recent history, and a CSV export.
An optional calibration enables a **single-star model estimate** of zenith seeing
FWHM at 500 nm in arcseconds. A verified plate scale also enables angular image motion, with sensor noise still included.
Aperture and instrument-noise calibration are separately required for atmospheric seeing.

SSH inspection of `seeingcam` identified a **QHY5III678M**, with 3856 × 2180 captured
pixels. QHY specifies a 2.0 µm pixel pitch for this sensor. The device information
did not identify the installed lens, focal length, or clear aperture; the camera
model alone cannot determine them. [QHY specifications](https://www.qhyccd.com/qhy5iii678/)

## Configure the producer

Install `seeing_metrics.py` beside `live_stream_producer.py` whenever copying or
deploying the producer. There is no additional Python dependency beyond NumPy.
Configuration is optional and read at startup:

```bash
python3 live_stream_producer.py --camera --camera-path /home/pi/allSkyCamera/camera \
    --backend https://armageddon.deepspace.ucsb.edu --seeing-config /path/to/seeing.json
```

Existing token and TLS options still apply. `CAMSERVER_SEEING_CONFIG=/path/to/seeing.json`
is equivalent to `--seeing-config`. Restart the producer after editing the file.
The following is a valid starting file; every calibration value defaults to `null`:

```json
{
  "plate_scale_arcsec_px": null,
  "aperture_mm": null,
  "altitude_deg": null,
  "centroid_noise_px": null,
  "noise_exposure_us": null,
  "noise_gain": null,
  "noise_flux": null,
  "noise_background_rms": null,
  "polaris_roi": null
}
```

| Key | Meaning and accepted values |
| --- | --- |
| `plate_scale_arcsec_px` | Angular size of one native captured pixel, arcseconds/pixel; greater than 0, at most 300. |
| `aperture_mm` | Effective clear aperture diameter in millimetres; greater than 0, at most 2000. |
| `altitude_deg` | Polaris altitude above the horizon in degrees; greater than 0, at most 90. Static fallback; a configured site and Polaris coordinates enable time-dependent geometric altitude. |
| `centroid_noise_px` | Measured **per-axis** centroid noise RMS in native pixels; 0–10. Do not use radial RMS or guess zero. |
| `noise_exposure_us` | Integer exposure used for the noise calibration, in microseconds; 1–100,000,000. |
| `noise_gain` | Integer camera gain used for the noise calibration; 1–100. |
| `noise_flux` | Positive reference background-subtracted aperture flux measured during centroid noise calibration; summed 8-bit-equivalent counts. |
| `noise_background_rms` | Positive reference sky-annulus background RMS measured during centroid noise calibration; 8-bit-equivalent counts per pixel. |
| `polaris_roi` | Operator-confirmed Polaris region `[x, y, width, height]`; integer coordinates, nonnegative origin, minimum 32 × 32 pixels. |

Unknown keys and invalid values fail at startup. A region outside the captured
frame yields no target. Region coordinates use the origin and pixel scale of the
native captured frame, **after any camera `--roi` crop**, before preview resizing.
This setting restricts tracking to a region; it does not crop the video. Confirm
Polaris from the field before supplying it, and allow room for its nightly motion.
Without it the producer tracks an unconfirmed bright star and withholds the seeing estimate.

Determine plate scale from a calibrated star field, or from the actual optical train:

```text
plate_scale_arcsec_px = 206.264806 × pixel_pitch_um / focal_length_mm
aperture_mm = focal_length_mm / f_number
```

The aperture expression assumes the stated f-number describes the effective entrance
pupil at the selected setting; account for stops and other restrictions. Use the
2.0 µm pitch only for unbinned native QHY5III678M pixels. Lens adapters, reducers,
binning and distortion can change the effective scale.

## Measure the noise floor

Use a stable artificial star on a short, controlled indoor optical path with similar
flux and spot width to Polaris. Secure the source and camera to avoid vibration.
Record repeated native frames at the intended exposure, gain, focus and readout
settings, then run the same centroid measurement and linear detrending. For each
axis, divide the residual sum of squares by `N - 2`; set `centroid_noise_px` to
`sqrt((variance_x + variance_y) / 2)`. Retain the frames and calibration settings.
Dark frames alone do not include the star's photon noise. Do not use ordinary
outdoor star wander as the noise floor: it already contains atmospheric motion.
The [SBS monitor manual](https://www.sbscientific.com/site/assets/files/1056/sm-4_seeing_monitor_instructions_v4.pdf)
describes a comparable-brightness indoor LED reference.

Enter that exposure and gain in the two `noise_*` fields. Both must exactly match
the current capture settings before seeing is estimated. Disable Automatic exposure
on the page for stable calibration conditions. Recalibrate after changes to focus,
readout, optical scale, or substantially different signal/background levels; the
software cannot automatically check all those conditions. Record the corresponding
aperture flux and sky-annulus noise from the same `star_photometry` measurement as
`noise_flux` and `noise_background_rms`. Both use native counts scaled to the
8-bit-equivalent range: 16-bit frames are divided by 256 before measurement, with
fractional values retained. Each valid sample in the current window must have flux
and background RMS within ±25% of these references. Otherwise the seeing estimate
is withheld until the signal matches again or calibration is updated. Relative
motion and brightness measurements continue. This check reduces the risk of
cloud-dimmed stars producing a false seeing increase from a changed noise floor.

## Sampling and interpretation

The producer measures at most about ten fresh frames per second, independently of
video encoding and browser polling. It fits a separate straight line to x and y
against monotonic capture time over a rolling 60-second window. Completed results
require at least 256 valid samples spanning 30 seconds. Slow cameras may never
accumulate 256 within the window. At least three samples allow provisional relative
metrics while the status remains `collecting`.

Exposures above 10,000 µs withhold the seeing estimate because they average away
rapid motion. This short-exposure and 256-sample approach follows the practical SIMM
method described in the [SBS manual](https://www.sbscientific.com/site/assets/files/1056/sm-4_seeing_monitor_instructions_v4.pdf).
No correction for finite exposure is implemented; even exposures at or below
10 ms can smooth motion and bias the estimate low.

| Measurement | Meaning |
| --- | --- |
| `jitterRmsPx` | Radial RMS after linear drift removal: `sqrt(variance_x + variance_y)`; native pixels, with centroid noise still included. |
| `driftPxPerMinute` | Magnitude of the fitted linear drift; native pixels/minute. |
| `fluxVariationPercent` | Detrended background-subtracted aperture flux RMS divided by mean flux, expressed as a percentage. This includes measurement noise and is not atmospheric transmission. |
| `fwhmPx` | Median observed spot-width estimate in native pixels, available in telemetry. Optics and focus contribute to it. |
| `seeingArcsec` | Noise-corrected, model-derived zenith FWHM at 500 nm; unavailable values are `null`, not zero. |

For the seeing calculation, the two per-axis variances are averaged, then the
calibrated per-axis noise variance is subtracted. With `s` the plate scale,
`n` the calibrated noise RMS, `D` the aperture in metres, and `h` the altitude:

```text
v_px = (variance_x + variance_y) / 2 - n²
v_rad = v_px × (s / 206264.806247)²
seeing_arcsec = 0.98 × (D / 500e-9)^0.2 × (v_rad / 0.182)^0.6
               × 206264.806247 × sin(h)^0.6
```

`sin(h)` uses the altitude converted to radians. The coefficient `0.182` assumes
single-aperture, per-axis Zernike tilt, approximated by a windowed centroid. It is
half the infinite-separation differential coefficient `0.364` in equation 8 of
[Tokovinin (2002)](https://instrumentation.obs.carnegiescience.edu/Software/CDIMM/dimm_tokovinin.pdf),
since the variance of two independent spots adds. This adaptation assumes a
Kolmogorov spectrum; it is not a two-aperture DIMM measurement. Values at or below
the calibrated variance floor produce `below_noise_floor` and no seeing number.

Mount vibration, local air currents, focus, centroid windowing, finite turbulence
outer scale and imperfect noise calibration can bias this single-star estimate.
Linear detrending removes slow pointing drift but cannot distinguish atmospheric
motion from faster mechanical movement. Brightness fluctuations may include clouds,
scintillation, and sensor noise; no calibrated transmission or cloud fraction is
inferred.

## Quality states, history and export

The monitor rejects lost stars, overexposed frames, near-saturated star peaks
(250 on the 8-bit-equivalent scale), and aperture background-SNR below 20. This
SNR proxy uses sky-annulus noise and does not include source shot noise. Invalid
samples, target jumps over eight pixels, changed exposure/gain/frame geometry, or a
capture gap over three seconds clear the measurement window. Data older than three
seconds is stale. Missing calibration, an unconfirmed target, mismatched noise
settings or reference signal/background levels, long exposures and simulated frames
prevent an arcsecond estimate; valid relative measurements remain available.

The camera saves a measurement every 30 seconds in `data/seeing-history.sqlite3`
beside the producer script, using SQLite WAL and full synchronous commits. Records
include a unique producer session, target, settings, instantaneous altitude and the
calibration configuration. An acknowledged outbox replays up to 120 oldest pending
records per telemetry request; records remain on camera after acknowledgment.
Only the backend's successful disk-write acknowledgment advances the outbox.
Network failures, backend restarts and producer restarts therefore retain pending
measurements. Database errors are exposed as `historyStorageError`; a disk failure
cannot guarantee retention. No automatic retention deletion is configured.

The backend also stores UTC daily JSONL journals at `app.live.history-dir` (production:
`/home/dorothy/CamServer-Backend/data/seeing-history`). It deduplicates replayed
records, flushes writes to disk and repairs an interrupted final append. Include
both this directory and the camera database in the site's normal backup policy.
The archive begins with this deployment; previously unrecorded nights cannot be recovered.

`GET /api/live/seeing/history?start=<ISO UTC>&end=<ISO UTC>&maxPoints=1200`
returns time-bucket averages and the original record count. Ranges are half-open
`[start,end)`, may span up to 366 days, and accept 100–5000 graph points. Empty
ranges are distinct from storage errors. Gaps, restarts, changed settings and
mixed-quality buckets are never bridged. CSV at the same route plus `.csv`
contains every original record in the requested period, including provenance.
The dashboard provides 1h, 6h, 24h, 7d, 30d and custom date/time ranges in the
viewer's timezone. Preset ranges refresh every 30 seconds; custom ranges remain fixed.

## Seeingcam field calibration, 2026-09-10

An astrometry.net solve of a one-second diagnostic field identified Polaris using
index-4113. The 1920×1086 video solution was converted to the 3856×2180 native
sensor coordinates: 4.09433 and 4.09717 arcsec/pixel on the two axes (mean 4.09575).
With QHY's 2 µm pitch this corresponds to an effective focal length of 100.72 mm.
It does **not** identify the lens model, iris setting or clear aperture.

`seeing-calibration.json` beside the script is loaded automatically unless an
explicit path overrides it. `calibration_id` identifies the field solution;
`calibration_geometry` prevents reuse with a different frame size.
`polaris_reference = [epoch, x, y, pole_x, pole_y, rotation_sign]` predicts the
identified star's sidereal motion in a 96-pixel search region. Its center may move
outside the sensor; in that case no measurement is reported. Re-solve after moving
the camera or changing optics. This narrow-field rotation approximation was
checked against subsequent live telemetry to within four native pixels.

The mount creeps: by 2026-09-24 every star in the field had moved (+53, +40) px
from the 2026-09-10 solution, and Polaris sat outside the 96-pixel region. The
producer therefore adds a learned offset to the prediction. Tracking updates it
slowly. After a loss, the producer searches a ±200 px window about once a second.
It accepts a star as Polaris only when all of these hold:

- it is at least 4× brighter than every other source in the window;
- it stays within 4 px over three searches;
- it has at least 30% of the flux learned at the same exposure and gain;
- the window lies wholly on the sensor, when no flux is known yet.

The same test replaces a lock on a fainter star. The offset and flux persist in
`data/polaris-tracking.json` for the same `calibration_id`, and telemetry reports
them under `polaris`. A large offset means the camera has moved and should be
re-solved.

`site_latitude_deg`, `site_longitude_deg` and `polaris_equatorial_of_date=[RA,Dec]`
provide a geometric altitude calculation from mean sidereal time. The installed
site coordinates come from the device's existing UCSB Broida-roof configuration;
the GPS presently has no fix. Coordinates are precessed to the calibration date;
refresh this calibration periodically and after moving the camera.

The deployed capture is 10,000 µs, gain 50, manual exposure. Polaris is resolved
and unsaturated at those settings. Clear aperture and an instrument-noise reference
remain unmeasured, so `seeingArcsec` remains null. `jitterArcsec` is the measured
radial image-motion RMS times the verified scale, without a noise subtraction.
