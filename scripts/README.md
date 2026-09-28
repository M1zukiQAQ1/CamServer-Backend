# Camera-host scripts

## `live_stream_producer.py` — seeing-monitor video

Streams the seeing camera to the backend as real H.264 video. Frames from the QHY camera module
are fed to `ffmpeg` at a fixed frame rate, encoded with `libx264` (one MP4 fragment per frame,
a keyframe every second), and pushed to `POST /api/live/ingest` in one long-lived chunked HTTP
request. The backend relays the stream to browsers (`GET /api/live/stream.mp4`, played through
Media Source Extensions on the site's Seeing Monitor page).

Once a second the script also posts telemetry (`POST /api/live/telemetry`): capture timestamp
(for the latency figure), the star centroid and its RMS wander, exposure, gain and frame rate.
The reply carries the exposure/gain chosen on the website, which the camera applies immediately.

The camera defaults to 16-bit readout. The preview subtracts the background and stretches the
native signal before reducing it to video (`--preview-gain 3`, `1` uses linear conversion).
For bright backgrounds the preview smoothly switches to the full sensor range, so
daytime or saturated frames remain bright instead of becoming black. This does not
change the camera exposure or recover detail that was clipped at the sensor.
The video defaults to CRF 18 with an 8 Mb/s cap; the cap is not a constant bitrate.
Higher quality reduces compression artifacts but does not remove sensor noise.
Converting to 8 bits first destroys the sub-256-count signals that make up most of the night
sky. Tracking uses the unstretched camera pixels, retaining fractional 8-bit-equivalent counts
for 16-bit inputs; `starPeak` is reported on that 0–255.94 scale. `cameraBitDepth` reports the
actual readout selection. SDK cleanup is bounded so a hung USB/GPS driver cannot stall restarts.
The tracker checks the full field every two seconds and switches if a resolved star has twice
the current target's aperture flux. Hot pixels are rejected; changing targets or capture
settings clears the RMS history. Coordinates and RMS are native-camera pixels, and RMS includes
pointing drift. Brightest-star tracking does not establish an astrometric identification or a
calibrated atmospheric seeing measurement. Telemetry provides `starX`, `starY`, `starPeak`,
`starSaturated`, `frameWidth` and `frameHeight` for the site's tracking marker. Reduce exposure
or gain when the marker reports saturation.

The page also reports motion after linear drift removal, brightness fluctuations,
and recent measurement history with CSV export. Optional optics, noise calibration
and an operator-confirmed Polaris region enable a seeing estimate in arcseconds.
See [Polaris image-motion monitoring](SEEING.md) for calibration, quality states,
and limitations. Copy `seeing_metrics.py` beside the producer when deploying it;
pass `--seeing-config /path/to/seeing.json` or set `CAMSERVER_SEEING_CONFIG` to load
calibration. With no configuration, measurements remain relative and the tracked
star is unconfirmed.

The backend persists controls atomically in `app.live.settings-file` (default
`./data/seeing-monitor-settings.json`; override with `CAMSERVER_SEEING_SETTINGS_FILE`).
First-run defaults can be set with `CAMSERVER_SEEING_EXPOSURE_US` and `CAMSERVER_SEEING_GAIN`.
Set an absolute path outside the build directory in production. The service must be able to
write the settings directory. Invalid settings return HTTP 400,
and a storage failure returns an error instead of claiming the values were saved.

Automatic exposure is enabled by default by the backend. The selected exposure is
its maximum; broad sky brightness can shorten it down to 100 microseconds, and it
returns to the selected value as the sky darkens. Gain is unchanged. Adjustments
are separated by two seconds and ignore isolated bright stars. Exposure/gain
changes reset tracking RMS so different acquisition settings are not mixed.
Turn off Automatic exposure on the site for exact manual control; the mode is saved
with exposure and gain. Legacy saved settings keep their values as the automatic
exposure ceiling. A producer connected to an older backend stays in manual mode.
Telemetry reports the exposure used for each captured frame as `exposureUs`, the
selected ceiling as `requestedExposureUs`, and near-saturation statistics even
when no star can be found. A persistent overexposure warning at minimum exposure
requires a lower gain or an optical change.

### Requirements

- Python 3.8+ with `numpy`
- `ffmpeg` with `libx264` (`sudo apt install ffmpeg`)
- the camera driver directory (`allSkyCamera_device/camera`) for `--camera`

### Usage

```bash
# synthetic scene, useful to check the whole path without hardware
python3 live_stream_producer.py --mock --backend https://armageddon.deepspace.ucsb.edu --insecure-tls --token "$TOKEN"

# real camera
python3 live_stream_producer.py --camera --camera-path /home/pi/allSkyCamera/camera \
    --backend https://armageddon.deepspace.ucsb.edu --insecure-tls --token "$TOKEN"
```

Environment variables `CAMSERVER_BACKEND`, `CAMSERVER_LIVE_INGEST_TOKEN` and
`CAMSERVER_TLS_INSECURE=1` replace `--backend`, `--token` and `--insecure-tls`.
`seeing-monitor-stream.service` is a systemd unit template for running it permanently.

Useful options: `--fps` (default 5), `--max-width` (downscale, default 1920; `0` keeps the native
3856 px), `--roi x,y,w,h`, `--preset`/`--crf`/`--max-bitrate` for quality vs CPU, `--encoder` to
use a hardware encoder ffmpeg knows about, `--stretch` to normalise dim frames.

The token is whatever `CAMSERVER_LIVE_INGEST_TOKEN` is set to in the backend's service unit;
when the backend has no token configured the header is simply ignored.
