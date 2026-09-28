#!/usr/bin/env python3
"""
Seeing-monitor live video producer.

Captures frames from the QHY camera module (or a synthetic scene with --mock), encodes them to
H.264 with ffmpeg, and pushes the resulting fragmented MP4 to the CamServer backend over a single
long-lived HTTP POST (/api/live/ingest). A side channel (/api/live/telemetry) reports the capture
timestamp, the measured star position and the camera settings once per second and picks up the
exposure/gain requested on the website.

The video is a constant-frame-rate stream: the encoder is fed at --fps regardless of how fast the
camera delivers frames, repeating the last frame when a long exposure is still running, so the
viewer never stalls and the fragment cadence stays predictable.

Requirements: Python 3.8+, numpy, ffmpeg with libx264 (Debian/Ubuntu: apt install ffmpeg).

Examples:
  python3 live_stream_producer.py --mock --backend http://localhost:8080
  python3 live_stream_producer.py --camera --camera-path /home/pi/allSkyCamera/camera \
      --backend https://armageddon.deepspace.ucsb.edu --insecure-tls --token "$CAMSERVER_LIVE_INGEST_TOKEN"
"""

from __future__ import annotations

import argparse
import datetime as dt
import http.client
import json
import math
import os
import select
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Deque, Dict, List, Optional, Tuple

from seeing_metrics import SeeingConfig, SeeingMonitor, star_photometry

try:
    import numpy as np
except ImportError:  # pragma: no cover - numpy is a hard requirement
    print("numpy is required: pip install numpy", file=sys.stderr)
    raise SystemExit(2)


DEFAULT_BACKEND = os.environ.get("CAMSERVER_BACKEND", "https://armageddon.deepspace.ucsb.edu")
DEFAULT_CAMERA_PATH = "../allSkyCamera_device/camera"
INGEST_PATH = "/api/live/ingest"
TELEMETRY_PATH = "/api/live/telemetry"
MIN_EXPOSURE_US = 100
MAX_EXPOSURE_US = 100_000_000
READ_CHUNK = 64 * 1024


def log(message: str) -> None:
    stamp = dt.datetime.now().strftime("%H:%M:%S")
    print(f"[{stamp}] {message}", flush=True)


# --------------------------------------------------------------------------- settings


def clamp_exposure(value: int) -> int:
    return max(MIN_EXPOSURE_US, min(MAX_EXPOSURE_US, int(value)))


class Settings:
    """Exposure/gain shared between the telemetry thread (writer) and capture thread (reader)."""

    def __init__(self, exposure: int, gain: int, auto_exposure: bool = False) -> None:
        self._lock = threading.Lock()
        self.exposure = clamp_exposure(exposure)
        self.gain = max(1, int(gain))
        self.auto_exposure = auto_exposure
        self.effective_exposure = self.exposure
        self._revision = 0
        self._last_adjustment = -math.inf

    def update(self, exposure: Any = None, gain: Any = None, auto_exposure: Any = None) -> bool:
        changed = False
        with self._lock:
            try:
                if exposure is not None:
                    value = clamp_exposure(int(float(exposure)))
                    changed |= value != self.exposure
                    self.exposure = value
                if gain is not None:
                    value = max(1, int(float(gain)))
                    changed |= value != self.gain
                    self.gain = value
                if isinstance(auto_exposure, bool):
                    changed |= auto_exposure != self.auto_exposure
                    self.auto_exposure = auto_exposure
            except (TypeError, ValueError):
                return False
            if changed:
                self._revision += 1
                self.effective_exposure = min(self.effective_exposure, self.exposure) if self.auto_exposure else self.exposure
                self._last_adjustment = -math.inf
        return changed

    def snapshot(self) -> Tuple[int, int]:
        with self._lock:
            return self.exposure, self.gain

    def acquisition(self) -> Tuple[int, int, bool, int]:
        with self._lock:
            return self.effective_exposure, self.gain, self.auto_exposure, self._revision

    def adjust_exposure(self, captured: Tuple[int, int, bool, int], brightness: float, now: float) -> bool:
        """Control broad sky brightness; isolated stars must not shorten night exposures."""
        with self._lock:
            current = (self.effective_exposure, self.gain, self.auto_exposure, self._revision)
            if not self.auto_exposure or captured != current or now - self._last_adjustment < 2.0:
                return False
            if not math.isfinite(brightness):
                return False
            if brightness >= 0.95:
                factor = 0.25  # A clipped frame cannot tell us how far above full scale it is.
            elif brightness > 0.65:
                factor = max(0.5, 0.45 / brightness)
            elif brightness < 0.25:
                factor = min(2.0, math.sqrt(0.45 / max(0.001, brightness)))
            else:
                return False
            next_exposure = min(self.exposure, clamp_exposure(round(self.effective_exposure * factor)))
            if next_exposure == self.effective_exposure:
                return False
            self.effective_exposure = next_exposure
            self._last_adjustment = now
            return True


# --------------------------------------------------------------------------- frames


@dataclass
class Frame:
    data: bytes
    width: int
    height: int
    channels: int
    index: int
    captured_at: float
    actual_exposure_s: float
    star: Optional[Tuple[float, float, float]]
    exposure_us: int = 0
    gain: int = 1
    auto_exposure: bool = False
    statistics: Optional[Dict[str, Any]] = None


def frame_statistics(raw: np.ndarray) -> Dict[str, Any]:
    sample = raw[::8, ::8]
    full_scale = 65535.0 if raw.dtype == np.uint16 else 255.0
    median, p90 = np.percentile(sample, [50, 90])
    # Near-full-scale also catches 12-bit sensors left-aligned into uint16 (65520).
    clipped = float(np.mean(sample >= full_scale * 0.99))
    return {"rawMedian": float(median), "rawP90Fraction": float(p90 / full_scale),
            "nearSaturationPercent": round(clipped * 100, 2), "frameOverexposed": clipped >= 0.1}


def _evaluate_candidate(gray: np.ndarray, cy: int, cx: int, half: int = 32) -> Optional[Tuple[float, float, float]]:
    """Find a resolved peak, then centroid only its small, background-subtracted aperture."""
    y0, y1 = max(0, cy - half), min(gray.shape[0], cy + half + 1)
    x0, x1 = max(0, cx - half), min(gray.shape[1], cx + half + 1)
    window = gray[y0:y1, x0:x1].astype(np.float32)
    if min(window.shape) < 3:
        return None
    background = float(np.median(window))
    noise = 1.4826 * float(np.median(np.abs(window - background)))
    smoothed = (
        window[:-2, :-2] + window[:-2, 1:-1] + window[:-2, 2:]
        + window[1:-1, :-2] + window[1:-1, 1:-1] + window[1:-1, 2:]
        + window[2:, :-2] + window[2:, 1:-1] + window[2:, 2:]
    ) / 9.0
    # A hot pixel can share the search window with a star. Reject that candidate,
    # then keep looking instead of comparing the star with an unrelated raw maximum.
    for _ in range(8):
        py, px = np.unravel_index(int(np.argmax(smoothed)), smoothed.shape)
        contrast = float(smoothed[py, px]) - background
        if contrast < max(4.0, 5.0 * noise / 3.0):
            return None
        py, px = py + 1, px + 1
        core = window[py - 1:py + 2, px - 1:px + 2]
        raw_peak = float(core.max())
        smoothed[max(0, py - 3):py + 2, max(0, px - 3):px + 2] = -np.inf
        if contrast < 0.25 * (raw_peak - background):
            continue
        # An 8-pixel radius includes the PSF without mixing separate field stars.
        ay, ax = max(0, py - 8), max(0, px - 8)
        aperture = window[ay:py + 9, ax:px + 9]
        weights = aperture - background
        weights[weights < max(2.0 * noise, 0.15 * contrast)] = 0.0
        total = float(weights.sum())
        if total <= 0:
            continue
        ys, xs = np.indices(weights.shape)
        x = x0 + ax + float((xs * weights).sum() / total)
        y = y0 + ay + float((ys * weights).sum() / total)
        return float(x), float(y), float(aperture.max())
    return None


def _star_flux(gray: np.ndarray, star: Tuple[float, float, float]) -> float:
    x, y = int(round(star[0])), int(round(star[1]))
    patch = gray[max(0, y - 8):y + 9, max(0, x - 8):x + 9].astype(np.float32)
    return float(np.maximum(patch - np.median(patch), 0).sum())


def measure_star(gray: np.ndarray, near: Optional[Tuple[float, float]] = None,
                 search_global: bool = True) -> Optional[Tuple[float, float, float]]:
    """Track one star, periodically acquiring a clearly brighter resolved source.

    All measurements use the native, unstretched frame. Hysteresis avoids swapping
    similarly bright stars, while the global check prevents a permanent faint-star lock.
    The brightest source is a Polaris candidate, not an astrometric identification.
    """
    if gray.ndim != 2 or gray.size == 0:
        return None
    current = None
    if near is not None:
        ny, nx = int(round(near[1])), int(round(near[0]))
        if 0 <= ny < gray.shape[0] and 0 <= nx < gray.shape[1]:
            current = _evaluate_candidate(gray, ny, nx, half=24)
            if current is not None and not search_global:
                return current

    best = current
    best_flux = _star_flux(gray, current) if current else 0.0
    for star, flux in _bright_candidates(gray):
        if flux > best_flux:
            best, best_flux = star, flux
    if current is not None and best_flux < 2.0 * _star_flux(gray, current):
        return current
    return best


def _bright_candidates(gray: np.ndarray, count: int = 16,
                       step: Optional[int] = None) -> List[Tuple[Tuple[float, float, float], float]]:
    """Resolved stars at the brightest coarse maxima, each with its aperture flux."""
    step = step or max(1, min(gray.shape) // 512)
    rows = gray.shape[0] // step * step
    cols = gray.shape[1] // step * step
    coarse = gray[:rows, :cols].reshape(rows // step, step, cols // step, step).mean(axis=(1, 3), dtype=np.float32)
    found = []
    for _ in range(count):
        by, bx = np.unravel_index(int(np.argmax(coarse)), coarse.shape)
        if not np.isfinite(coarse[by, bx]):
            break
        # Exclude the whole candidate neighbourhood, including hot-pixel clusters.
        radius = max(1, 16 // step)
        coarse[max(0, by - radius):by + radius + 1, max(0, bx - radius):bx + radius + 1] = -np.inf
        star = _evaluate_candidate(gray, by * step + step // 2, bx * step + step // 2)
        if star is not None:
            found.append((star, _star_flux(gray, star)))
    return found


def measure_polaris_region(gray: np.ndarray, roi: Tuple[int, int, int, int],
                           near: Optional[Tuple[float, float]] = None) -> Optional[Tuple[float, float, float]]:
    """Search only an operator-identified Polaris region, never the rest of the sky.

    Once acquired, reject large jumps instead of switching to another field star.
    Region coordinates refer to native captured pixels, including any camera ROI.
    """
    x, y, width, height = roi
    if x < 0 or y < 0 or x + width > gray.shape[1] or y + height > gray.shape[0]:
        return None
    crop = gray[y:y + height, x:x + width]
    if near is None:
        star = measure_star(crop)
    else:
        nx, ny = near[0] - x, near[1] - y
        if not (0 <= nx < width and 0 <= ny < height):
            return None
        star = _evaluate_candidate(crop, round(ny), round(nx), half=16)
        if star is not None and math.hypot(star[0] - nx, star[1] - ny) > 8:
            return None
    if star is None or min(star[0], star[1], width - 1 - star[0], height - 1 - star[1]) < 12:
        return None  # a truncated aperture produces biased centroids near the ROI edge
    return star[0] + x, star[1] + y, star[2]


class PolarisLocator:
    """Follows the camera's slow shift on its mount between recalibrations.

    The sidereal model predicts Polaris for a fixed pointing. The mount creeps
    (the whole field moved +53/+40 px from 2026-09-10 to 09-24), so tracking
    learns that offset. After a loss, a wider window re-acquires Polaris only if it
    outshines every other source there, holds still over several searches and is
    not fainter than the flux learned at the same settings. The same test replaces
    a lock on a fainter star once Polaris is back in the window.
    """
    WINDOW = 200            # half-width, native px; field stars this close are >100x fainter at 10 ms
    CONFIRMATIONS = 3
    DOMINANCE = 4.0
    SAME_STAR_PX = 32       # candidates this close are one bright star's wings
    MIN_FLUX_FRACTION = 0.3
    SMOOTHING = 0.05
    SAVE_INTERVAL = 60.0

    def __init__(self, config: SeeingConfig, state_path: Optional[str] = None) -> None:
        self.config = config
        self.state_path = Path(state_path) if state_path else None
        self.offset = (0.0, 0.0)
        self.flux: Optional[Tuple[int, int, float]] = None  # exposure, gain, tracked flux
        self._pending: List[Tuple[float, float]] = []
        self._saved = -math.inf
        self._save_failed = False
        self._load()

    def _load(self) -> None:
        if self.state_path is None or not self.state_path.exists():
            return
        try:
            state = json.loads(self.state_path.read_text())
            if state.get("calibration_id") != self.config.calibration_id:
                log(f"ignoring Polaris tracking state for calibration {state.get('calibration_id')}")
                return
            dx, dy = (float(v) for v in state["offset"])
            flux = state.get("flux")
            if not (math.isfinite(dx) and math.isfinite(dy)):
                raise ValueError("offset is not finite")
            self.offset = (dx, dy)
            self.flux = (int(flux[0]), int(flux[1]), float(flux[2])) if flux else None
            log(f"Polaris offset from saved state: {dx:+.1f}, {dy:+.1f} px")
        except (OSError, ValueError, KeyError, TypeError, IndexError) as exc:
            log(f"ignoring unreadable Polaris tracking state: {exc}")

    def save(self, now: float, force: bool = False) -> None:
        if self.state_path is None or (not force and now - self._saved < self.SAVE_INTERVAL):
            return
        self._saved = now
        state = {"calibration_id": self.config.calibration_id, "offset": list(self.offset),
                 "flux": list(self.flux) if self.flux else None, "updated": time.time()}
        temporary = self.state_path.with_name(self.state_path.name + ".tmp")
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            temporary.write_text(json.dumps(state))
            os.replace(temporary, self.state_path)
            self._save_failed = False
        except OSError as exc:
            if not self._save_failed:
                log(f"could not save Polaris tracking state: {exc}")
            self._save_failed = True

    def predicted(self, epoch: float) -> Optional[Tuple[float, float]]:
        position = self.config.polaris_at(epoch)
        return None if position is None else (position[0] + self.offset[0], position[1] + self.offset[1])

    def region(self, epoch: float) -> Tuple[int, int, int, int]:
        return self.config.region_at(epoch, self.offset)

    def tracked(self, gray: np.ndarray, star: Tuple[float, float, float], epoch: float,
                settings: Tuple[int, int], now: float) -> None:
        """Follow creep slowly; one wrong centroid barely moves the prediction."""
        position = self.config.polaris_at(epoch)
        if position is None:
            return
        dx, dy = star[0] - position[0], star[1] - position[1]
        self.offset = (self.offset[0] + self.SMOOTHING * (dx - self.offset[0]),
                       self.offset[1] + self.SMOOTHING * (dy - self.offset[1]))
        flux = _star_flux(gray, star)
        if self.flux is None or self.flux[:2] != tuple(settings):
            self.flux = (settings[0], settings[1], flux)
        else:
            self.flux = (settings[0], settings[1], self.flux[2] + self.SMOOTHING * (flux - self.flux[2]))
        self.save(now)

    def acquire(self, gray: np.ndarray, epoch: float, settings: Tuple[int, int], now: float,
                current: Optional[Tuple[float, float, float]] = None) -> Optional[Tuple[float, float, float]]:
        """Return Polaris found in the wide window once confirmed, else None."""
        position = self.predicted(epoch)
        if position is None:
            return None
        px, py = position
        height, width = gray.shape
        known_flux = self.flux[2] if self.flux and self.flux[:2] == tuple(settings) else None
        # Without a known flux only a single star can be tested, so the whole window
        # must be on the sensor: Polaris just past the edge must not pass a neighbour.
        margin = 48 if known_flux else self.WINDOW
        if not (margin <= px < width - margin and margin <= py < height - margin):
            self._pending.clear()
            return None
        x0, y0 = max(0, int(px) - self.WINDOW), max(0, int(py) - self.WINDOW)
        x1, y1 = min(width, int(px) + self.WINDOW + 1), min(height, int(py) + self.WINDOW + 1)
        # 4x4 blocks dilute single hot pixels, which outshine a 10 ms Polaris peak.
        candidates = sorted(_bright_candidates(gray[y0:y1, x0:x1], 24, step=4), key=lambda c: -c[1])
        star, flux = candidates[0] if candidates else (None, 0.0)
        if star is not None:
            # A wing maximum's window can clip the star; centroid again centred on it.
            star = _evaluate_candidate(gray[y0:y1, x0:x1], int(round(star[1])), int(round(star[0])))
        x, y = (star[0] + x0, star[1] + y0) if star else (-1.0, -1.0)
        others = [f for s, f in candidates[1:] if math.hypot(s[0] + x0 - x, s[1] + y0 - y) > self.SAME_STAR_PX]
        if (star is None or flux <= 0 or (others and flux < self.DOMINANCE * max(others))
                or (known_flux is not None and flux < self.MIN_FLUX_FRACTION * known_flux)
                or min(x, y, width - 1 - x, height - 1 - y) < 12):
            self._pending.clear()
            return None
        if current is not None and math.hypot(x - current[0], y - current[1]) <= 8:
            self._pending.clear()
            return None  # already tracking it
        if current is not None and flux < self.DOMINANCE * _star_flux(gray, current):
            self._pending.clear()
            return None
        if self._pending and math.hypot(x - self._pending[-1][0], y - self._pending[-1][1]) > 4:
            self._pending.clear()
        self._pending.append((x, y))
        if len(self._pending) < self.CONFIRMATIONS:
            return None
        self._pending.clear()
        model = self.config.polaris_at(epoch)
        self.offset = (x - model[0], y - model[1])
        self.flux = (settings[0], settings[1], flux)
        log(f"Polaris acquired at {x:.1f}, {y:.1f}; offset from calibration {self.offset[0]:+.1f}, {self.offset[1]:+.1f} px")
        self.save(now, force=True)
        return x, y, star[2]

    def describe(self, epoch: float, geometry: Optional[Tuple[int, int]]) -> Optional[Dict[str, Any]]:
        position = self.predicted(epoch)
        if position is None:
            return None
        on_sensor = None
        if geometry:
            on_sensor = 48 <= position[0] < geometry[0] - 48 and 48 <= position[1] < geometry[1] - 48
        return {"predictedX": round(position[0], 1), "predictedY": round(position[1], 1),
                "offsetX": round(self.offset[0], 1), "offsetY": round(self.offset[1], 1),
                "onSensor": on_sensor}


def enhance_preview(image: np.ndarray, gain: float = 3.0) -> np.ndarray:
    """Stretch native data before quantization; faint 16-bit signals must not become zero."""
    if image.dtype == np.uint16:
        if gain == 1.0:
            return to_8bit(image, False)
        sample = image[::8, ::8]
        black = float(np.median(sample))
        span = max(4096.0, float(np.percentile(sample, 99.9)) - black)
        # Background subtraction is useful for a dark sky, but it must fade out
        # for bright scenes: a saturated median otherwise turns white into black.
        # Blend toward the full sensor range so the day/night transition is smooth.
        daylight = float(np.clip((black - 4096.0) / 12288.0, 0.0, 1.0))
        black *= 1.0 - daylight
        span = span * (1.0 - daylight) + 65535.0 * daylight
        values = ((np.arange(65536, dtype=np.float32) - black) / span).clip(0, 1)
        lut = (255.0 * np.power(values, 1.0 / gain)).astype(np.uint8)
        return np.ascontiguousarray(lut[image])
    if gain == 1.0:
        return image
    # A lookup table keeps this inexpensive on the Pi; zero stays black and faint
    # signals are expanded before H.264 compression can erase them.
    values = np.arange(256, dtype=np.float32) / 255.0
    lut = (255.0 * np.power(values, 1.0 / gain)).clip(0, 255).astype(np.uint8)
    return np.ascontiguousarray(lut[image])


class StarTracker:
    """Keeps recent centroids and formats the position string shown on the site."""

    def __init__(self, window: int = 30) -> None:
        self._points: Deque[Tuple[float, float]] = deque(maxlen=window)
        self._lock = threading.Lock()

    def add(self, star: Optional[Tuple[float, float, float]]) -> None:
        with self._lock:
            if star is None:
                self._points.clear()
            else:
                if self._points and math.hypot(star[0] - self._points[-1][0], star[1] - self._points[-1][1]) > 24:
                    self._points.clear()  # acquisition changes must not become fake seeing spikes
                self._points.append((star[0], star[1]))

    def describe(self) -> str:
        with self._lock:
            points = list(self._points)
        if not points:
            return "no star detected"
        xs = np.array([p[0] for p in points])
        ys = np.array([p[1] for p in points])
        rms = math.sqrt(float(((xs - xs.mean()) ** 2 + (ys - ys.mean()) ** 2).mean()))
        return f"x: {xs[-1]:.2f}, y: {ys[-1]:.2f}, rms error: {rms:.2f}"


def to_8bit(frame: np.ndarray, stretch: bool) -> np.ndarray:
    array = np.asarray(frame)
    if array.ndim == 3 and array.shape[0] in (1, 3) and array.shape[-1] not in (1, 3):
        array = np.transpose(array, (1, 2, 0))
    if array.ndim == 3 and array.shape[-1] == 1:
        array = array[..., 0]

    if stretch:
        values = array.astype(np.float32)
        low = float(values.min())
        high = float(values.max())
        if high > low:
            values = (values - low) * (255.0 / (high - low))
        return np.ascontiguousarray(values.clip(0, 255).astype(np.uint8))

    if array.dtype == np.uint8:
        return np.ascontiguousarray(array)
    if array.dtype == np.uint16:
        return np.ascontiguousarray((array >> 8).astype(np.uint8))
    return np.ascontiguousarray(np.clip(array, 0, 255).astype(np.uint8))


# --------------------------------------------------------------------------- sources


class MockSource:
    """A drifting, twinkling star over a noisy sky, for testing without hardware."""

    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        y, x = np.mgrid[0:height, 0:width]
        gradient = 18 + 20 * (y / max(1, height - 1)) + 8 * np.sin(x * 0.01)
        self.background = gradient.astype(np.float32)
        rng = np.random.default_rng(7)
        for _ in range(60):
            sx, sy = int(rng.integers(0, width)), int(rng.integers(0, height))
            self.background[max(0, sy - 1):sy + 2, max(0, sx - 1):sx + 2] += float(rng.integers(40, 140))
        self.rng = np.random.default_rng()
        self.index = 0

    def read(self, settings: Tuple[int, int]) -> Tuple[np.ndarray, float]:
        exposure_us, gain = settings
        t = self.index * 0.2
        frame = self.background + self.rng.normal(0, 4, size=self.background.shape).astype(np.float32)
        cx = self.width * 0.5 + 60 * math.sin(t * 0.13) + self.rng.normal(0, 1.2)
        cy = self.height * 0.5 + 40 * math.cos(t * 0.09) + self.rng.normal(0, 1.2)
        radius = 24
        x0, y0 = int(cx) - radius, int(cy) - radius
        yy, xx = np.mgrid[y0:y0 + 2 * radius, x0:x0 + 2 * radius]
        brightness = min(230.0, 60.0 + gain * 1.5 + exposure_us / 200.0)
        sigma = 2.2 + 0.6 * abs(math.sin(t * 0.5))
        star = brightness * np.exp(-(((xx - cx) ** 2) + ((yy - cy) ** 2)) / (2 * sigma ** 2))
        ys = slice(max(0, y0), min(self.height, y0 + 2 * radius))
        xs = slice(max(0, x0), min(self.width, x0 + 2 * radius))
        frame[ys, xs] += star[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0]
        self.index += 1
        time.sleep(0.05)
        return frame.clip(0, 255).astype(np.uint8), exposure_us / 1e6


class CameraSource:
    """Frames from the QHY camera driver in allSkyCamera_device/camera/camera.py."""

    def __init__(self, camera_path: str, roi: Optional[Tuple[Tuple[int, int], Tuple[int, int]]], bit_depth: int) -> None:
        resolved = Path(camera_path).expanduser().resolve()
        sys.path.insert(0, str(resolved))
        from camera import Camera  # type: ignore

        self.camera = Camera()
        self.camera.config_continuous_mode()
        self.roi = roi
        self.bit_depth = bit_depth
        log(f"camera {self.camera.camera_id} ready: {self.camera.resolution[0]}x{self.camera.resolution[1]}, "
            f"{'color' if self.camera.color else 'mono'}")

    def read(self, settings: Tuple[int, int]) -> Tuple[np.ndarray, float]:
        exposure_us, gain = settings
        started = time.monotonic()
        result = self.camera.expose(int(exposure_us), gain=int(gain), bbp=self.bit_depth, roi=self.roi)
        elapsed = time.monotonic() - started
        # Older driver versions return just the frame; newer ones return (frame, exposure seconds).
        if isinstance(result, tuple):
            frame, actual = result[0], float(result[1])
        else:
            frame, actual = result, elapsed
        # The driver reuses its buffer for the next exposure, so take a copy now.
        return np.array(frame, copy=True), actual

    def close(self) -> None:
        try:
            self.camera.close()
        except Exception:
            pass


# --------------------------------------------------------------------------- capture thread


class Capture(threading.Thread):
    def __init__(self, source: Any, settings: Settings, stretch: bool, stop_event: threading.Event,
                 preview_gain: float = 3.0, seeing_config: Optional[SeeingConfig] = None,
                 seeing_history: Optional[str] = None, polaris_state: Optional[str] = None) -> None:
        super().__init__(name="capture", daemon=True)
        self.source = source
        self.settings = settings
        self.stretch = stretch
        self.preview_gain = preview_gain
        self.stop_event = stop_event
        self.tracker = StarTracker()
        self.seeing = SeeingMonitor(seeing_config, simulated=isinstance(source, MockSource), history_path=seeing_history)
        self.polaris = PolarisLocator(self.seeing.config, polaris_state)
        self._lock = threading.Lock()
        self._latest: Optional[Frame] = None
        self.frames = 0
        self.errors = 0
        self._recent: Deque[float] = deque(maxlen=50)

    def latest(self) -> Optional[Frame]:
        with self._lock:
            return self._latest

    def capture_fps(self) -> float:
        with self._lock:
            stamps = list(self._recent)
        if len(stamps) < 2 or stamps[-1] <= stamps[0]:
            return 0.0
        return (len(stamps) - 1) / (stamps[-1] - stamps[0])

    def run(self) -> None:
        last_measure = 0.0
        last_search = 0.0
        last_settings = self.settings.acquisition()[:2]
        last_star: Optional[Tuple[float, float, float]] = None
        polaris_anchor: Optional[Tuple[float, float]] = None
        last_acquisition = -math.inf
        last_detection = -math.inf
        last_geometry = None
        while not self.stop_event.is_set():
            try:
                captured_settings = self.settings.acquisition()
                raw, actual = self.source.read(captured_settings[:2])
                captured_mono, captured_epoch = time.monotonic(), time.time()
                statistics = frame_statistics(raw)
                # Keep fractional 8-bit-equivalent counts for full-precision
                # centroiding; shifting uint16 by 8 would throw faint signal away.
                measurement = raw.astype(np.float32) / 256.0 if raw.dtype == np.uint16 else to_8bit(raw, False)
                gray = measurement if measurement.ndim == 2 else measurement.mean(axis=2)
                image = enhance_preview(to_8bit(raw, True) if self.stretch else raw, self.preview_gain)
                # Star measurement is the expensive part; at most ~10 per second is plenty.
                now = captured_mono
                # The overlay may retain the latest centroid; only new measurements
                # enter the seeing window. Changed acquisition settings force a measurement.
                if (now - last_measure >= 0.1 or captured_settings[:2] != last_settings
                        or gray.shape != last_geometry or statistics["frameOverexposed"]):
                    if gray.shape != last_geometry or now - last_detection > 3:
                        polaris_anchor = None
                        if gray.shape != last_geometry:
                            last_star = None
                            self.tracker.add(None)
                        last_geometry = gray.shape
                    current_settings = captured_settings[:2]
                    if current_settings != last_settings:
                        self.tracker.add(None)
                        last_star = None
                        last_settings = current_settings
                    search_global = now - last_search >= 2.0 or last_star is None
                    if statistics["frameOverexposed"]:
                        last_star = None
                    elif self.seeing.config.polaris_roi:
                        config = self.seeing.config
                        if config.calibration_geometry and tuple(reversed(gray.shape)) != tuple(config.calibration_geometry):
                            last_star = None
                        else:
                            last_star = measure_polaris_region(gray, self.polaris.region(captured_epoch), polaris_anchor)
                            # Wide re-acquisition: every second while lost, every 10 s as a lock check.
                            if now - last_acquisition >= (10.0 if last_star else 1.0):
                                last_acquisition = now
                                found = self.polaris.acquire(gray, captured_epoch, captured_settings[:2], now, last_star)
                                if found:
                                    last_star = found
                            if last_star:
                                self.polaris.tracked(gray, last_star, captured_epoch, captured_settings[:2], now)
                        if last_star:
                            polaris_anchor = last_star[:2]
                    else:
                        last_star = measure_star(gray, near=last_star[:2] if last_star else None, search_global=search_global)
                    if search_global:
                        last_search = now
                    last_measure = now
                    self.tracker.add(last_star)
                    if last_star:
                        last_detection = now
                    self.seeing.add(now, captured_epoch, last_star, star_photometry(gray, last_star) if last_star else None,
                                    captured_settings[0], captured_settings[1], gray.shape,
                                    overexposed=statistics["frameOverexposed"])
                star = last_star
                channels = 1 if image.ndim == 2 else image.shape[2]
                frame = Frame(
                    data=image.tobytes(),
                    width=image.shape[1],
                    height=image.shape[0],
                    channels=channels,
                    index=self.frames,
                    captured_at=captured_epoch,
                    actual_exposure_s=actual,
                    star=star,
                    exposure_us=captured_settings[0],
                    gain=captured_settings[1],
                    auto_exposure=captured_settings[2],
                    statistics=statistics,
                )
                with self._lock:
                    self._latest = frame
                    self.frames += 1
                    self._recent.append(frame.captured_at)
                if self.settings.adjust_exposure(captured_settings, statistics["rawP90Fraction"], time.monotonic()):
                    log(f"auto exposure: {captured_settings[0]}us -> {self.settings.acquisition()[0]}us")
            except Exception as exc:
                self.errors += 1
                log(f"capture failed: {exc}")
                if self.errors <= 3:
                    traceback.print_exc()
                time.sleep(1.0)


# --------------------------------------------------------------------------- ffmpeg encoder


class Encoder:
    def __init__(self, args: argparse.Namespace, width: int, height: int, channels: int) -> None:
        self.args = args
        self.width = width
        self.height = height
        self.channels = channels
        self.process: Optional[subprocess.Popen] = None
        self.frames_written = 0
        self._stderr_thread: Optional[threading.Thread] = None
        self.last_error = ""

    def command(self) -> List[str]:
        a = self.args
        fps = max(1, int(round(a.fps)))
        gop = max(1, int(round(a.fps * a.keyframe_interval)))
        cmd = [
            a.ffmpeg, "-hide_banner", "-loglevel", "warning", "-nostdin",
            "-f", "rawvideo", "-pix_fmt", "gray" if self.channels == 1 else "rgb24",
            "-video_size", f"{self.width}x{self.height}", "-framerate", str(fps),
            "-i", "pipe:0",
        ]
        filters = []
        if a.max_width and self.width > a.max_width:
            filters.append(f"scale={a.max_width}:-2")
        if filters:
            cmd += ["-vf", ",".join(filters)]
        cmd += [
            "-c:v", a.encoder, "-pix_fmt", "yuv420p",
            "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0", "-bf", "0",
        ]
        if a.encoder == "libx264":
            cmd += ["-preset", a.preset, "-tune", "zerolatency", "-crf", str(a.crf)]
            if a.max_bitrate:
                cmd += ["-maxrate", a.max_bitrate, "-bufsize", a.max_bitrate]
        else:
            cmd += ["-b:v", a.max_bitrate or "4M"]
        movflags = "empty_moov+default_base_moof+" + ("frag_every_frame" if a.frag_every_frame else "frag_keyframe")
        cmd += ["-f", "mp4", "-movflags", movflags, "-flush_packets", "1", "pipe:1"]
        return cmd

    def start(self) -> None:
        cmd = self.command()
        log("starting encoder: " + " ".join(cmd))
        self.process = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )
        self._stderr_thread = threading.Thread(target=self._drain_stderr, name="ffmpeg-stderr", daemon=True)
        self._stderr_thread.start()

    def _drain_stderr(self) -> None:
        process = self.process
        if process is None or process.stderr is None:
            return
        for raw in iter(process.stderr.readline, b""):
            line = raw.decode("utf-8", "replace").rstrip()
            if line:
                self.last_error = line
                log(f"ffmpeg: {line}")

    def write(self, frame: bytes) -> bool:
        process = self.process   # stop() may clear it from another thread at any time
        if process is None or process.stdin is None:
            return False
        try:
            view = memoryview(frame)
            while len(view):
                written = process.stdin.write(view)
                if not written:
                    return False
                view = view[written:]
            self.frames_written += 1
            return True
        except (BrokenPipeError, OSError, ValueError, AttributeError):
            return False

    def read(self) -> Optional[bytes]:
        process = self.process
        if process is None or process.stdout is None:
            return b""
        try:
            # The pacer stops feeding frames on SIGTERM. Do not block forever on
            # ffmpeg's still-open stdout while the main loop needs to shut it down.
            ready, _, _ = select.select([process.stdout], [], [], 0.5)
            if not ready:
                return None
            return os.read(process.stdout.fileno(), READ_CHUNK)
        except (OSError, ValueError):
            return b""

    def alive(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def stop(self) -> None:
        process = self.process
        self.process = None
        if process is None:
            return
        for stream in (process.stdin, process.stdout):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


class Pacer(threading.Thread):
    """Feeds the newest frame to ffmpeg at a fixed rate, repeating it while the camera is busy."""

    def __init__(self, capture: Capture, encoder: Encoder, fps: float, stop_event: threading.Event) -> None:
        super().__init__(name="pacer", daemon=True)
        self.capture = capture
        self.encoder = encoder
        self.interval = 1.0 / max(0.1, fps)
        self.stop_event = stop_event
        self.failed = threading.Event()
        self.repeated = 0
        self._last_index = -1

    def run(self) -> None:
        next_tick = time.monotonic()
        while not self.stop_event.is_set() and not self.failed.is_set():
            frame = self.capture.latest()
            if frame is None:
                time.sleep(0.02)
                next_tick = time.monotonic()
                continue
            if frame.width != self.encoder.width or frame.height != self.encoder.height or frame.channels != self.encoder.channels:
                self.failed.set()   # geometry changed: the session restarts with a new encoder
                break
            if frame.index == self._last_index:
                self.repeated += 1
            self._last_index = frame.index
            if not self.encoder.write(frame.data):
                self.failed.set()
                break
            next_tick += self.interval
            delay = next_tick - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            else:
                next_tick = time.monotonic()


# --------------------------------------------------------------------------- backend client


class Backend:
    def __init__(self, base_url: str, token: str, insecure_tls: bool, timeout: float) -> None:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError(f"invalid backend URL: {base_url}")
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.base_path = parsed.path.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.context: Optional[ssl.SSLContext] = None
        if self.scheme == "https":
            self.context = ssl._create_unverified_context() if insecure_tls else ssl.create_default_context()

    def describe(self) -> str:
        return f"{self.scheme}://{self.host}:{self.port}{self.base_path}"

    def connection(self, timeout: Optional[float] = None) -> http.client.HTTPConnection:
        timeout = self.timeout if timeout is None else timeout
        if self.scheme == "https":
            return http.client.HTTPSConnection(self.host, self.port, timeout=timeout, context=self.context)
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def headers(self) -> Dict[str, str]:
        headers = {"User-Agent": "camserver-live-producer/2"}
        if self.token:
            headers["X-Live-Token"] = self.token
        return headers

    def post_json(self, path: str, payload: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        body = json.dumps(payload).encode("utf-8")
        headers = self.headers()
        headers["Content-Type"] = "application/json"
        conn = self.connection()
        try:
            conn.request("POST", self.base_path + path, body=body, headers=headers)
            response = conn.getresponse()
            raw = response.read()
            try:
                data = json.loads(raw.decode("utf-8")) if raw else {}
            except ValueError:
                data = {"raw": raw[:200].decode("utf-8", "replace")}
            return response.status, data
        finally:
            conn.close()

    def open_ingest(self, producer_info: Dict[str, Any]) -> http.client.HTTPConnection:
        conn = self.connection()
        conn.putrequest("POST", self.base_path + INGEST_PATH, skip_accept_encoding=True)
        for key, value in self.headers().items():
            conn.putheader(key, value)
        conn.putheader("Content-Type", "video/mp4")
        conn.putheader("Transfer-Encoding", "chunked")
        conn.putheader("X-Live-Producer", json.dumps(producer_info, separators=(",", ":")))
        conn.endheaders()
        return conn

    @staticmethod
    def send_chunk(conn: http.client.HTTPConnection, data: bytes) -> None:
        conn.send(b"%X\r\n" % len(data) + data + b"\r\n")

    @staticmethod
    def finish(conn: http.client.HTTPConnection) -> Optional[str]:
        try:
            conn.send(b"0\r\n\r\n")
            conn.sock.settimeout(3.0)  # type: ignore[union-attr]
            response = conn.getresponse()
            return f"HTTP {response.status}: {response.read(300).decode('utf-8', 'replace')}"
        except Exception:
            return None
        finally:
            conn.close()


# --------------------------------------------------------------------------- telemetry thread


class Telemetry(threading.Thread):
    def __init__(self, backend: Backend, capture: Capture, settings: Settings, stop_event: threading.Event,
                 state: "SessionState", poll_settings: bool) -> None:
        super().__init__(name="telemetry", daemon=True)
        self.backend = backend
        self.capture = capture
        self.settings = settings
        self.stop_event = stop_event
        self.state = state
        self.poll_settings = poll_settings
        self.failures = 0
        self.last_latency_ms: Optional[int] = None

    def payload(self) -> Dict[str, Any]:
        frame = self.capture.latest()
        exposure_us, gain = self.settings.snapshot()
        payload: Dict[str, Any] = {
            "pos": self.capture.tracker.describe(),
            "exposureUs": frame.exposure_us if frame is not None else self.settings.acquisition()[0],
            "requestedExposureUs": exposure_us,
            "gain": frame.gain if frame is not None else gain,
            "autoExposure": frame.auto_exposure if frame is not None else self.settings.acquisition()[2],
            "captureFps": round(self.capture.capture_fps(), 2),
            "framesCaptured": self.capture.frames,
            "encoder": self.state.encoder_name,
            "streaming": self.state.streaming,
            "host": socket.gethostname(),
            "previewGain": self.capture.preview_gain,
            "cameraBitDepth": getattr(self.capture.source, "bit_depth", 8),
            "seeing": self.capture.seeing.snapshot(time.monotonic()),
        }
        polaris = self.capture.polaris.describe(frame.captured_at if frame is not None else time.time(),
                                                (frame.width, frame.height) if frame is not None else None)
        if polaris is not None:
            payload["polaris"] = polaris
        # GPS receiver state from the camera module (src/common/gps.py); shown in the page's telemetry list
        gps = getattr(getattr(self.capture.source, "camera", None), "gps", None)
        if gps is not None and hasattr(gps, "status"):
            payload["gps"] = str(gps.status)
        if frame is not None:
            payload.update(frame.statistics or {})
            payload["ts"] = dt.datetime.fromtimestamp(frame.captured_at).strftime("%S.%f")
            payload["actualExposureMs"] = round(frame.actual_exposure_s * 1000.0, 1)
            payload["frame"] = f"{frame.width}x{frame.height}"
            if frame.star is not None:
                payload["starPeak"] = round(frame.star[2], 1)
                payload["starX"] = round(frame.star[0], 3)
                payload["starY"] = round(frame.star[1], 3)
                payload["starSaturated"] = frame.star[2] >= 250
            payload["frameWidth"] = frame.width
            payload["frameHeight"] = frame.height
        return payload

    def run(self) -> None:
        while not self.stop_event.is_set():
            started = time.monotonic()
            try:
                payload = self.payload()
                status, data = self.backend.post_json(TELEMETRY_PATH, payload)
                if status == 401:
                    log("telemetry rejected: wrong or missing --token (HTTP 401)")
                    self.failures += 1
                elif status != 200:
                    log(f"telemetry failed: HTTP {status} {data}")
                    self.failures += 1
                else:
                    self.failures = 0
                    self.last_latency_ms = data.get("latencyMs")
                    if data.get("historyStored") is True:
                        self.capture.seeing.acknowledge(payload["seeing"]["history"])
                    settings = data.get("settings") if self.poll_settings else None
                    if isinstance(settings, dict) and self.settings.update(settings.get("exposure"), settings.get("gain"), settings.get("autoExposure")):
                        exposure_us, gain = self.settings.snapshot()
                        log(f"settings from site: exposure={exposure_us}us gain={gain}")
            except Exception as exc:
                self.failures += 1
                if self.failures <= 3 or self.failures % 30 == 0:
                    log(f"telemetry error: {exc}")
            self.stop_event.wait(max(0.2, 1.0 - (time.monotonic() - started)))


@dataclass
class SessionState:
    encoder_name: str = ""
    streaming: bool = False
    bytes_sent: int = 0
    sessions: int = 0


def close_source(source: Any, timeout: float = 3.0) -> bool:
    """Bound vendor SDK cleanup so a USB/GPS hang cannot prevent service restart."""
    if not hasattr(source, "close"):
        return True
    cleanup = threading.Thread(target=source.close, name="camera-cleanup", daemon=True)
    cleanup.start()
    cleanup.join(timeout=timeout)
    return not cleanup.is_alive()


# --------------------------------------------------------------------------- session


def run_session(args: argparse.Namespace, backend: Backend, capture: Capture, state: SessionState,
                stop_event: threading.Event) -> str:
    """Runs one encoder + one ingest connection until something breaks. Returns the reason."""
    frame = capture.latest()
    while frame is None and not stop_event.is_set():
        time.sleep(0.1)
        frame = capture.latest()
    if frame is None:
        return "stopped"

    encoder = Encoder(args, frame.width, frame.height, frame.channels)
    encoder.start()
    state.encoder_name = args.encoder
    pacer = Pacer(capture, encoder, args.fps, stop_event)
    pacer.start()

    producer_info = {
        "host": socket.gethostname(),
        "source": "mock" if args.mock else "camera",
        "fps": args.fps,
        "encoder": args.encoder,
        "input": f"{frame.width}x{frame.height}x{frame.channels}",
    }
    conn: Optional[http.client.HTTPConnection] = None
    reason = "unknown"
    sent = 0
    last_status = time.monotonic()
    try:
        conn = backend.open_ingest(producer_info)
        state.streaming = True
        state.sessions += 1
        log(f"ingest connection open to {backend.describe()}{INGEST_PATH}")
        while not stop_event.is_set():
            if pacer.failed.is_set():
                reason = "encoder input closed (frame geometry changed or ffmpeg exited)"
                break
            data = encoder.read()
            if data is None:
                continue
            if not data:
                reason = f"ffmpeg exited ({encoder.last_error or 'no error output'})"
                break
            Backend.send_chunk(conn, data)
            sent += len(data)
            state.bytes_sent += len(data)
            now = time.monotonic()
            if now - last_status >= args.status_every:
                exposure_us, gain = capture.settings.snapshot()
                log(f"streaming: sent={sent / 1e6:.1f}MB captured={capture.frames} "
                    f"({capture.capture_fps():.1f} fps) encoded={encoder.frames_written} repeated={pacer.repeated} "
                    f"exposure={exposure_us}us gain={gain} star={capture.tracker.describe()}")
                last_status = now
        else:
            reason = "stopped"
    except (BrokenPipeError, ConnectionError, socket.timeout, OSError, http.client.HTTPException) as exc:
        reason = f"connection failed: {exc}"
    finally:
        state.streaming = False
        pacer.failed.set()
        encoder.stop()
        pacer.join(timeout=2)
        if conn is not None:
            summary = Backend.finish(conn) if reason == "stopped" else None
            if summary:
                log(f"server closed the session: {summary}")
            else:
                try:
                    conn.close()
                except Exception:
                    pass
    return reason


def preflight(backend: Backend, settings: Settings) -> None:
    """Fails fast on a wrong token or unreachable backend before the camera starts streaming."""
    delay = 2.0
    while True:
        try:
            status, data = backend.post_json(TELEMETRY_PATH, {"pos": "starting", "host": socket.gethostname()})
        except Exception as exc:
            log(f"backend {backend.describe()} not reachable ({exc}); retrying in {delay:.0f}s")
            time.sleep(delay)
            delay = min(30.0, delay * 2)
            continue
        if status == 401:
            raise SystemExit("the backend rejected the ingest token (HTTP 401); pass --token or set CAMSERVER_LIVE_INGEST_TOKEN")
        if status == 404:
            raise SystemExit(f"{backend.describe()}{TELEMETRY_PATH} is missing: the backend is too old for this producer")
        if status != 200:
            log(f"unexpected reply from backend: HTTP {status} {data}; retrying in {delay:.0f}s")
            time.sleep(delay)
            delay = min(30.0, delay * 2)
            continue
        served = data.get("settings")
        if isinstance(served, dict) and settings.update(served.get("exposure"), served.get("gain"), served.get("autoExposure")):
            exposure_us, gain = settings.snapshot()
            log(f"initial settings from site: exposure={exposure_us}us gain={gain}")
        log(f"backend {backend.describe()} accepted the token")
        return


# --------------------------------------------------------------------------- cli


def parse_roi(value: str) -> Optional[Tuple[Tuple[int, int], Tuple[int, int]]]:
    if value.strip().lower() in ("", "full", "none"):
        return None
    parts = [int(part.strip()) for part in value.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError('--roi must be x,y,width,height or "full"')
    x, y, width, height = parts
    if width <= 0 or height <= 0:
        raise argparse.ArgumentTypeError("--roi width and height must be positive")
    return (x, y), (width, height)


def parse_size(value: str) -> Tuple[int, int]:
    try:
        width, height = value.lower().split("x")
        return max(16, int(width)), max(16, int(height))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("size must look like 1920x1080") from exc


def ffmpeg_supports_frag_every_frame(ffmpeg: str) -> bool:
    try:
        output = subprocess.run([ffmpeg, "-hide_banner", "-h", "muxer=mp4"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return "frag_every_frame" in output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stream the seeing-monitor camera to the CamServer backend as H.264 video.")
    parser.add_argument("--backend", default=DEFAULT_BACKEND, help=f"Backend base URL (default: {DEFAULT_BACKEND}, env CAMSERVER_BACKEND)")
    parser.add_argument("--token", default=os.environ.get("CAMSERVER_LIVE_INGEST_TOKEN", ""), help="Ingest token (env CAMSERVER_LIVE_INGEST_TOKEN)")
    parser.add_argument("--insecure-tls", action="store_true", default=os.environ.get("CAMSERVER_TLS_INSECURE") == "1",
                        help="Do not verify the backend certificate (needed for the self-signed one)")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--camera", action="store_true", help="Use the QHY camera module (default)")
    source.add_argument("--mock", action="store_true", help="Generate a synthetic scene instead of using the camera")
    parser.add_argument("--camera-path", default=DEFAULT_CAMERA_PATH, help=f"Directory containing camera.py (default: {DEFAULT_CAMERA_PATH})")
    parser.add_argument("--roi", type=parse_roi, default=None, help='Camera ROI as x,y,width,height (default: full frame)')
    parser.add_argument("--bit-depth", type=int, choices=(8, 16), default=16, help="Camera readout depth (default: 16; preserves faint stars)")
    parser.add_argument("--exposure", type=int, default=1000, help="Initial exposure in microseconds until the site sets one (default: 1000)")
    parser.add_argument("--gain", type=int, default=1, help="Initial gain until the site sets one (default: 1)")
    parser.add_argument("--preview-gain", type=float, default=3.0, help="Display gamma boost, 1 = linear (default: 3); measurements stay unstretched")
    default_calibration = Path(__file__).with_name("seeing-calibration.json")
    parser.add_argument("--seeing-config", default=os.environ.get("CAMSERVER_SEEING_CONFIG") or
                        (str(default_calibration) if default_calibration.exists() else None),
                        help="JSON optics, noise calibration and Polaris region (see SEEING.md); pixels only if absent")
    parser.add_argument("--stretch", action="store_true", help="Normalise each frame to its min/max before encoding")
    parser.add_argument("--mock-size", type=parse_size, default=(1920, 1080), help="Synthetic frame size (default: 1920x1080)")
    parser.add_argument("--fps", type=float, default=5.0, help="Video frame rate fed to the encoder (default: 5)")
    parser.add_argument("--keyframe-interval", type=float, default=1.0, help="Seconds between keyframes; also the maximum join delay (default: 1)")
    parser.add_argument("--max-width", type=int, default=1920, help="Downscale wider frames to this width; 0 keeps the native size (default: 1920)")
    parser.add_argument("--encoder", default="libx264", help="ffmpeg video encoder (default: libx264; e.g. h264_v4l2m2m, h264_nvenc)")
    parser.add_argument("--preset", default="ultrafast", help="libx264 preset (default: ultrafast)")
    parser.add_argument("--crf", type=int, default=18, help="libx264 quality, lower is better (default: 18)")
    parser.add_argument("--max-bitrate", default="8M", help="Bitrate cap such as 8M; empty for none (default: 8M)")
    parser.add_argument("--ffmpeg", default=os.environ.get("FFMPEG", "ffmpeg"), help="ffmpeg executable (default: ffmpeg on PATH)")
    parser.add_argument("--poll-settings", action=argparse.BooleanOptionalAction, default=True, help="Apply exposure/gain chosen on the site (default: on)")
    parser.add_argument("--timeout", type=float, default=20.0, help="Socket timeout in seconds (default: 20)")
    parser.add_argument("--status-every", type=float, default=10.0, help="Seconds between status lines (default: 10)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        seeing_config = SeeingConfig.load(args.seeing_config)
    except (OSError, TypeError, ValueError) as exc:
        raise SystemExit(f"Invalid seeing configuration: {exc}")
    args.backend = args.backend.rstrip("/")
    if not math.isfinite(args.preview_gain) or not 1.0 <= args.preview_gain <= 6.0:
        raise SystemExit("--preview-gain must be between 1 and 6")
    if not args.mock:
        args.camera = True

    if shutil.which(args.ffmpeg) is None and not os.path.exists(args.ffmpeg):
        print(f"ffmpeg not found ({args.ffmpeg}); install it with: sudo apt install ffmpeg", file=sys.stderr)
        return 2
    args.frag_every_frame = ffmpeg_supports_frag_every_frame(args.ffmpeg)
    if not args.frag_every_frame:
        log("this ffmpeg lacks frag_every_frame; fragments will follow keyframes (higher latency)")

    backend = Backend(args.backend, args.token, args.insecure_tls, args.timeout)
    settings = Settings(args.exposure, args.gain)
    stop_event = threading.Event()

    def request_stop(signum: int, _frame: Any) -> None:
        log(f"signal {signum}: stopping")
        stop_event.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    if args.mock:
        source: Any = MockSource(*args.mock_size)
        log(f"mock source {args.mock_size[0]}x{args.mock_size[1]}")
    else:
        source = CameraSource(args.camera_path, args.roi, args.bit_depth)

    history_path = str(Path(__file__).with_name("data") / "seeing-history.sqlite3") if args.camera else None
    polaris_state = str(Path(__file__).with_name("data") / "polaris-tracking.json") if args.camera else None
    capture = Capture(source, settings, args.stretch, stop_event, args.preview_gain, seeing_config, history_path,
                      polaris_state)
    preflight(backend, settings)
    capture.start()

    state = SessionState()
    telemetry = Telemetry(backend, capture, settings, stop_event, state, args.poll_settings)
    telemetry.start()

    delay = 1.0
    while not stop_event.is_set():
        started = time.monotonic()
        reason = run_session(args, backend, capture, state, stop_event)
        if stop_event.is_set():
            break
        if time.monotonic() - started > 30:
            delay = 1.0
        log(f"session ended ({reason}); reconnecting in {delay:.0f}s")
        stop_event.wait(delay)
        delay = min(30.0, delay * 2)

    capture.join(timeout=2)
    if not close_source(source):
        log("camera SDK cleanup timed out; exiting to release the device")
        # The driver's GPS thread is not a daemon either. All encoded data has
        # already been closed above; process exit releases remaining USB handles.
        os._exit(0)
    log(f"stopped after {state.sessions} session(s), {state.bytes_sent / 1e6:.1f} MB sent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
