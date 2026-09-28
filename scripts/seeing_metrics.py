"""Single-star image motion on native frames; optional Kolmogorov seeing estimate.

See SEEING.md for units, calibration, equations and limitations. No video frames
or browser polling times enter the estimator. All durations use monotonic time.
"""

from collections import deque
from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
import threading
import uuid
import sqlite3

import numpy as np

ARCSEC_PER_RADIAN = 206264.80624709636


@dataclass(frozen=True)
class SeeingConfig:
    plate_scale_arcsec_px: float = None
    aperture_mm: float = None
    altitude_deg: float = None
    centroid_noise_px: float = None  # measured per-axis RMS at the same exposure/gain
    noise_exposure_us: int = None
    noise_gain: int = None
    noise_flux: float = None
    noise_background_rms: float = None
    polaris_roi: tuple = None  # operator-identified region: x, y, width, height
    calibration_id: str = None
    polaris_reference: tuple = None  # epoch, star x/y, pole x/y, rotation sign; native pixels
    calibration_geometry: tuple = None  # width, height of the calibrated native frame
    site_latitude_deg: float = None
    site_longitude_deg: float = None
    polaris_equatorial_of_date: tuple = None  # RA, declination in degrees at calibration epoch

    def __post_init__(self):
        for name, low, high in (
            ("plate_scale_arcsec_px", 0, 300), ("aperture_mm", 0, 2000),
            ("altitude_deg", 0, 90), ("centroid_noise_px", -1e-12, 10),
            ("noise_exposure_us", 0, 100_000_000), ("noise_gain", 0, 100),
            ("noise_flux", 0, 100_000), ("noise_background_rms", 0, 255),
        ):
            value = getattr(self, name)
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float))
                                      or not math.isfinite(value) or not low < value <= high):
                raise ValueError(f"Invalid {name}")
        for name in ("noise_exposure_us", "noise_gain"):
            value = getattr(self, name)
            if value is not None and int(value) != value:
                raise ValueError(f"{name} must be an integer")
        if self.polaris_roi is not None:
            roi = self.polaris_roi
            if (not isinstance(roi, (list, tuple)) or len(roi) != 4
                    or any(type(v) is not int for v in roi)
                    or min(roi[:2]) < 0 or min(roi[2:]) < 32):
                raise ValueError("polaris_roi must be [x, y, width, height], at least 32x32")
        if self.calibration_id is not None and (not isinstance(self.calibration_id, str) or len(self.calibration_id) > 100):
            raise ValueError("Invalid calibration_id")
        if self.polaris_reference is not None:
            ref = self.polaris_reference
            if (not isinstance(ref, (list, tuple)) or len(ref) != 6
                    or not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in ref)
                    or ref[0] < 1577836800 or ref[-1] not in (-1, 1) or self.polaris_roi is None):
                raise ValueError("Invalid polaris_reference")
        if self.calibration_geometry is not None and (len(self.calibration_geometry) != 2
                or any(type(v) is not int or v < 32 for v in self.calibration_geometry)):
            raise ValueError("Invalid calibration_geometry")
        for name, limit in (("site_latitude_deg", 90), ("site_longitude_deg", 180)):
            value = getattr(self, name)
            if value is not None and (not isinstance(value, (int, float)) or isinstance(value, bool)
                                      or not math.isfinite(value) or abs(value) > limit):
                raise ValueError("Invalid " + name)
        if self.polaris_equatorial_of_date is not None:
            coords = self.polaris_equatorial_of_date
            if (not isinstance(coords, (list, tuple)) or len(coords) != 2
                    or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in coords)
                    or not 0 <= coords[0] < 360 or not -90 <= coords[1] <= 90
                    or self.site_latitude_deg is None or self.site_longitude_deg is None):
                raise ValueError("Invalid polaris_equatorial_of_date")

    def altitude_at(self, epoch):
        if self.polaris_equatorial_of_date is None or epoch is None:
            return self.altitude_deg
        ra, dec = self.polaris_equatorial_of_date
        days = epoch / 86400 + 2440587.5 - 2451545.0
        centuries = days / 36525
        sidereal = 280.46061837 + 360.98564736629 * days + 0.000387933 * centuries ** 2 - centuries ** 3 / 38710000
        hour = math.radians((sidereal + self.site_longitude_deg - ra) % 360)
        lat, dec = math.radians(self.site_latitude_deg), math.radians(dec)
        return math.degrees(math.asin(math.sin(lat) * math.sin(dec) + math.cos(lat) * math.cos(dec) * math.cos(hour)))

    def polaris_at(self, epoch):
        """Calibrated native position of Polaris; None without a sidereal reference."""
        if self.polaris_reference is None:
            return None
        t, x, y, cx, cy, sign = self.polaris_reference
        angle = sign * (epoch - t) * 2 * math.pi / 86164.0905
        dx, dy = x - cx, y - cy
        return (cx + dx * math.cos(angle) - dy * math.sin(angle),
                cy + dx * math.sin(angle) + dy * math.cos(angle))

    def region_at(self, epoch, offset=(0.0, 0.0)):
        position = self.polaris_at(epoch)
        if position is None:
            return self.polaris_roi
        # A fixed camera watches Polaris circle the pole. Keep a narrow search
        # region on the identified star, including when it leaves the sensor.
        # The offset is the learned mount shift since calibration.
        return (int(round(position[0] + offset[0])) - 48, int(round(position[1] + offset[1])) - 48, 96, 96)

    @classmethod
    def load(cls, filename):
        if not filename:
            return cls()
        values = json.loads(Path(filename).read_text())
        if not isinstance(values, dict):
            raise ValueError("Seeing configuration must be a JSON object")
        return cls(**values)  # unknown/misspelled calibration keys fail at startup


def detrend(times, values):
    """Fit intercept + linear drift using actual elapsed times, separately per axis."""
    t = np.asarray(times, dtype=float)
    t = t - t[0]
    design = np.column_stack((np.ones(len(t)), t - t.mean()))
    coefficients = np.linalg.lstsq(design, np.asarray(values, dtype=float), rcond=None)[0]
    return np.asarray(values) - design @ coefficients, coefficients[1]


def zenith_seeing(variance_px, config, altitude_deg=None):
    """Per-axis Z-tilt variance -> zenith FWHM (arcsec) at 500 nm.

    Windowed centroids approximate Z-tilt: K=0.182 for one aperture/axis
    (half the infinite-separation differential coefficient in Tokovinin 2002).
    This is a model estimate, not a DIMM measurement.
    """
    corrected = variance_px - config.centroid_noise_px ** 2
    if corrected <= 1e-15:
        return None  # below noise floor is not perfect/zero seeing
    variance_rad = corrected * (config.plate_scale_arcsec_px / ARCSEC_PER_RADIAN) ** 2
    return (0.98 * ((config.aperture_mm / 1000) / 500e-9) ** 0.2
            * (variance_rad / 0.182) ** 0.6 * ARCSEC_PER_RADIAN
            * math.sin(math.radians(config.altitude_deg if altitude_deg is None else altitude_deg)) ** 0.6)


def star_photometry(gray, star):
    """Background-subtracted fixed-aperture flux and observed spot width.

    Flux fluctuations include photon/read noise; width includes optics/focus.
    Neither is labelled atmospheric transmission or true seeing.
    """
    x, y = star[:2]
    ix, iy = int(round(x)), int(round(y))
    if ix < 12 or iy < 12 or ix + 12 >= gray.shape[1] or iy + 12 >= gray.shape[0]:
        return None
    patch = gray[iy - 12:iy + 13, ix - 12:ix + 13].astype(float)
    yy, xx = np.indices(patch.shape)
    radius2 = (xx - 12) ** 2 + (yy - 12) ** 2
    sky = patch[(radius2 >= 100) & (radius2 <= 144)]
    background = float(np.median(sky))
    noise = max(0.05, 1.4826 * float(np.median(np.abs(sky - background))))
    aperture = radius2 <= 64
    signal = patch - background
    flux = float(signal[aperture].sum())  # signed, to avoid a positive noise bias
    snr = flux / (noise * math.sqrt(int(aperture.sum())))
    weights = np.where(aperture, np.maximum(signal, 0), 0)
    total = float(weights.sum())
    width = 2.35482 * math.sqrt(float((weights * ((xx - 12 - (x - ix)) ** 2
                                                 + (yy - 12 - (y - iy)) ** 2)).sum()) / (2 * total)) if total > 0 else None
    return {"flux": flux, "snr": snr, "fwhmPx": width, "backgroundRms": noise}


class SeeingMonitor:
    WINDOW_SECONDS = 60
    MIN_SAMPLES = 256
    MIN_DURATION = 30
    HISTORY_INTERVAL = 30

    def __init__(self, config=None, simulated=False, history_path=None):
        self.config = config or SeeingConfig()
        self.simulated = simulated
        self.session_id = str(uuid.uuid4())
        self._lock = threading.Lock()
        self._points = deque(maxlen=600)
        self._history = deque(maxlen=120)
        self._db = None
        self._storage_error = None
        if history_path:
            path = Path(history_path)
            path.parent.mkdir(parents=True, exist_ok=True)
            self._db = sqlite3.connect(str(path), check_same_thread=False)
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("PRAGMA synchronous=FULL")
            self._db.execute("CREATE TABLE IF NOT EXISTS measurements (session TEXT, timestamp REAL, json TEXT, PRIMARY KEY(session,timestamp))")
            self._db.execute("CREATE INDEX IF NOT EXISTS measurement_time ON measurements(timestamp)")
            self._db.execute("CREATE TABLE IF NOT EXISTS sync_state (id INTEGER PRIMARY KEY, timestamp REAL)")
            saved_ack = self._db.execute("SELECT timestamp FROM sync_state WHERE id=1").fetchone()
            self._ack = saved_ack[0] if saved_ack else 0
            rows = self._db.execute("SELECT json FROM measurements ORDER BY timestamp DESC LIMIT 120").fetchall()
            self._history.extend(json.loads(row[0]) for row in reversed(rows))
        self._last_history = -math.inf
        self._last_time = None
        self._last_epoch = None
        self._settings = None
        self._state = "searching"
        self._segment = 0
        self._reset_reason = None

    def _reset(self, reason):
        self._points.clear()
        self._segment += 1
        self._reset_reason = reason

    def add(self, timestamp, epoch, star, photometry, exposure_us, gain, geometry, overexposed=False):
        """One sample per measured raw frame. Duplicate timestamps are ignored."""
        with self._lock:
            if self._last_time is not None and timestamp <= self._last_time:
                return
            if self._last_time is not None and timestamp - self._last_time > 3:
                self._reset("capture_gap")
            settings = (exposure_us, gain, geometry)
            if self._settings is not None and settings != self._settings:
                self._reset("settings_changed")
            self._settings = settings
            self._last_time = timestamp
            self._last_epoch = epoch
            invalid = ("overexposed" if overexposed else "searching" if star is None
                       else "saturated" if star[2] >= 250 else "low_signal" if photometry is None
                       or not all(v is not None and math.isfinite(v)
                                  for v in (*star, photometry["flux"], photometry["snr"],
                                            photometry["fwhmPx"], photometry["backgroundRms"]))
                       or photometry["flux"] <= 0 or photometry["snr"] < 20 else None)
            if invalid:
                if self._points:
                    self._reset(invalid)
                self._state = invalid
            else:
                if self._points and math.hypot(star[0] - self._points[-1][2], star[1] - self._points[-1][3]) > 8:
                    self._reset("target_changed")
                self._points.append((timestamp, epoch, *star[:2], photometry["flux"], photometry["fwhmPx"], photometry["backgroundRms"]))
                self._state = "collecting"
            while self._points and timestamp - self._points[0][0] > self.WINDOW_SECONDS:
                self._points.popleft()

    def snapshot(self, now):
        with self._lock:
            data = self._measure(now)
            if self._last_time is not None and now - self._last_history >= self.HISTORY_INTERVAL:
                point = {key: data.get(key) for key in (
                    "timestamp", "status", "segment", "jitterRmsPx", "seeingArcsec", "fluxVariationPercent",
                    "samples", "exposureUs", "gain", "jitterArcsec", "altitudeDeg",
                    "driftPxPerMinute", "fwhmPx", "flux", "backgroundRms", "durationSeconds", "blockers", "resetReason")}
                point.update(sessionId=self.session_id, target=data["target"], simulated=self.simulated,
                             calibration=asdict(self.config))
                # Advance history through outages; capture timestamp remains unchanged
                # in current telemetry so consumers can detect a stopped camera.
                point["timestamp"] = self._last_epoch + max(0, now - self._last_time)
                if self._db is not None:
                    try:
                        with self._db:
                            self._db.execute("INSERT OR IGNORE INTO measurements VALUES (?, ?, ?)",
                                             (self.session_id, point["timestamp"], json.dumps(point, allow_nan=False)))
                        self._storage_error = None
                    except sqlite3.Error as error:
                        self._storage_error = str(error)
                self._history.append(point)
                self._last_history = now
            history = list(self._history)
            if self._db is not None:
                pending = self._db.execute("SELECT json FROM measurements WHERE timestamp > ? ORDER BY timestamp LIMIT 120",
                                           (self._ack,)).fetchall()
                if pending:
                    history = [json.loads(row[0]) for row in pending]
            return {**data, "history": history, "historyPersistent": self._db is not None,
                    "historyStorageError": self._storage_error}

    def acknowledge(self, history):
        """Advance the durable outbox only after the server confirms its disk write."""
        if self._db is None or not history:
            return
        with self._lock:
            latest = max(point["timestamp"] for point in history)
            if latest <= self._ack:
                return
            with self._db:
                self._db.execute("INSERT OR REPLACE INTO sync_state VALUES (1, ?)", (latest,))
            self._ack = latest

    def _measure(self, now):
        points = list(self._points)
        age = None if self._last_time is None else max(0, now - self._last_time)
        stale = age is not None and age > 3
        data = {"version": 1, "status": "stale" if stale else self._state,
                "sessionId": self.session_id, "calibration": asdict(self.config),
                "target": "polaris_roi" if self.config.polaris_roi else "unconfirmed_star",
                "simulated": self.simulated, "sampleAgeSeconds": age, "samples": len(points),
                "requiredSamples": self.MIN_SAMPLES, "requiredDurationSeconds": self.MIN_DURATION,
                "windowSeconds": self.WINDOW_SECONDS, "segment": self._segment,
                "resetReason": self._reset_reason, "timestamp": self._last_epoch,
                "exposureUs": self._settings[0] if self._settings else None,
                "gain": self._settings[1] if self._settings else None,
                "altitudeDeg": self.config.altitude_at(self._last_epoch), "jitterArcsec": None,
                "jitterRmsPx": None, "seeingArcsec": None, "fluxVariationPercent": None,
                "driftPxPerMinute": None, "fwhmPx": None, "durationSeconds": 0,
                "flux": None, "backgroundRms": None,
                "plateScaleArcsecPx": self.config.plate_scale_arcsec_px, "blockers": []}
        if stale or len(points) < 3:
            return data
        p = np.asarray(points, dtype=float)
        residual, drift = detrend(p[:, 0], p[:, 2:4])
        # Two fitted parameters per axis; do not confuse radial RMS with one-axis sigma.
        axis_variance = np.sum(residual ** 2, axis=0) / (len(p) - 2)
        flux_residual, _ = detrend(p[:, 0], p[:, 4])
        duration = p[-1, 0] - p[0, 0]
        data.update(jitterRmsPx=float(np.sqrt(axis_variance.sum())),
                    driftPxPerMinute=float(np.linalg.norm(drift) * 60), durationSeconds=float(duration),
                    fluxVariationPercent=float(np.std(flux_residual, ddof=2) / p[:, 4].mean() * 100),
                    fwhmPx=float(np.median(p[:, 5])), flux=float(np.median(p[:, 4])),
                    backgroundRms=float(np.median(p[:, 6])))
        if self.config.plate_scale_arcsec_px is not None:
            data["jitterArcsec"] = data["jitterRmsPx"] * self.config.plate_scale_arcsec_px
        blockers = data["blockers"]
        if self.simulated:
            blockers.append("simulated")
        if not self.config.polaris_roi:
            blockers.append("target_unconfirmed")
        if any(getattr(self.config, key) is None for key in (
                "plate_scale_arcsec_px", "aperture_mm", "centroid_noise_px",
                "noise_exposure_us", "noise_gain", "noise_flux", "noise_background_rms")) or data["altitudeDeg"] is None:
            blockers.append("calibration_required")
        elif (self.config.noise_exposure_us, self.config.noise_gain) != self._settings[:2]:
            blockers.append("noise_calibration_mismatch")
        elif (np.any(np.abs(p[:, 4] / self.config.noise_flux - 1) > .25)
              or np.any(np.abs(p[:, 6] / self.config.noise_background_rms - 1) > .25)):
            blockers.append("noise_signal_mismatch")
        if data["exposureUs"] > 10000:
            blockers.append("long_exposure")
        if data["altitudeDeg"] is not None and data["altitudeDeg"] <= 0:
            blockers.append("below_horizon")
        if len(points) < self.MIN_SAMPLES or duration < self.MIN_DURATION:
            data["status"] = "collecting"
        else:
            data["status"] = "relative" if blockers else "estimated"
            if not blockers:
                data["seeingArcsec"] = zenith_seeing(float(axis_variance.mean()), self.config, data["altitudeDeg"])
                if data["seeingArcsec"] is None:
                    data["status"] = "below_noise_floor"
        return data
