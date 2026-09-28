import dataclasses
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

import numpy as np

from live_stream_producer import Capture, PolarisLocator, Settings, measure_polaris_region
from seeing_metrics import SeeingConfig

WIDTH, HEIGHT = 1000, 800
EPOCH = 1790220000.0
# Model puts Polaris at (500, 400) at EPOCH, 400 px from the pole: ~0.03 px/s of sidereal motion.
CONFIG = SeeingConfig(calibration_id='test-cal', polaris_roi=(0, 0, WIDTH, HEIGHT),
                      polaris_reference=(EPOCH, 500.0, 400.0, 500.0, 0.0, -1),
                      calibration_geometry=(WIDTH, HEIGHT))
SETTINGS = (10000, 50)


def frame(*stars, seed=0):
    """Native uint16 frame: x, y, amplitude (counts) per star."""
    rng = np.random.default_rng(seed)
    raw = rng.normal(1000, 30, (HEIGHT, WIDTH))
    yy, xx = np.indices(raw.shape)
    for x, y, amplitude in stars:
        raw += amplitude * np.exp(-((xx - x) ** 2 + (yy - y) ** 2) / (2 * 2.3 ** 2))
    return np.clip(raw, 0, 65535).astype(np.uint16)


def gray(*stars, seed=0):
    return frame(*stars, seed=seed).astype(np.float32) / 256.0


def acquire(locator, image, epoch=EPOCH, current=None, times=3):
    result = None
    for i in range(times):
        result = locator.acquire(image, epoch, SETTINGS, float(i), current)
    return result


class PolarisLocatorTest(unittest.TestCase):
    def test_recovers_mount_shift_and_tracks_in_narrow_region(self):
        locator = PolarisLocator(CONFIG)
        image = gray((560, 440, 20000), (760, 440, 2500))  # Polaris shifted +60/+40, neighbour 200 px away
        self.assertIsNone(measure_polaris_region(image, locator.region(EPOCH)))
        self.assertIsNone(locator.acquire(image, EPOCH, SETTINGS, 0.0))  # needs confirmation
        found = acquire(locator, image, times=2)
        self.assertAlmostEqual(found[0], 560, delta=0.5)
        self.assertAlmostEqual(found[1], 440, delta=0.5)
        self.assertAlmostEqual(locator.offset[0], 60, delta=0.5)
        self.assertAlmostEqual(locator.offset[1], 40, delta=0.5)
        tracked = measure_polaris_region(image, locator.region(EPOCH))
        self.assertAlmostEqual(tracked[0], 560, delta=0.5)

    def test_hot_pixels_in_window_do_not_hide_polaris(self):
        raw = frame((560, 440, 6000))  # 10 ms Polaris peaks far below a hot pixel
        rng = np.random.default_rng(3)
        for x, y in zip(rng.integers(320, 700, 20), rng.integers(220, 620, 20)):
            if abs(x - 560) > 20 or abs(y - 440) > 20:
                raw[y, x] = 65535
        found = acquire(PolarisLocator(CONFIG), raw.astype(np.float32) / 256.0)
        self.assertAlmostEqual(found[0], 560, delta=0.5)
        self.assertAlmostEqual(found[1], 440, delta=0.5)

    def test_no_lock_on_neighbour_when_polaris_is_off_sensor(self):
        # Flux learned: prediction near the bottom edge, Polaris itself just below it.
        locator = PolarisLocator(CONFIG)
        locator.offset = (0.0, 330.0)  # predicts y=730, 70 px inside the sensor
        locator.flux = (*SETTINGS, 5000.0)
        image = gray((600, 650, 2500))  # only a >8x fainter neighbour in the window
        self.assertIsNone(acquire(locator, image, times=6))
        self.assertEqual(locator.offset, (0.0, 330.0))
        # Flux unknown: the window must lie wholly on the sensor before anything is tried.
        locator.flux = None
        self.assertIsNone(acquire(locator, image, times=6))
        # Changed exposure/gain make the learned flux inapplicable, so the same rule applies.
        locator.flux = (20000, 50, 5000.0)
        self.assertIsNone(acquire(locator, image, times=6))

    def test_single_outlier_barely_moves_offset(self):
        locator = PolarisLocator(CONFIG)
        locator.offset = (60.0, 40.0)
        image = gray((560, 440, 20000))
        locator.tracked(image, (568.0, 440.0, 80.0), EPOCH, SETTINGS, 0.0)
        self.assertLess(abs(locator.offset[0] - 60.0), 0.5)
        for _ in range(200):
            locator.tracked(image, (560.0, 440.0, 80.0), EPOCH, SETTINGS, 0.0)
        self.assertAlmostEqual(locator.offset[0], 60.0, delta=0.01)

    def test_inconsistent_detections_are_not_confirmed(self):
        locator = PolarisLocator(CONFIG)
        first, second = gray((560, 440, 20000)), gray((600, 420, 20000))
        for i in range(6):
            self.assertIsNone(locator.acquire(first if i % 2 else second, EPOCH, SETTINGS, float(i)))
        self.assertEqual(locator.offset, (0.0, 0.0))

    def test_similar_stars_are_ambiguous(self):
        locator = PolarisLocator(CONFIG)
        self.assertIsNone(acquire(locator, gray((560, 440, 20000), (440, 360, 12000)), times=6))

    def test_wrong_lock_is_replaced_but_correct_lock_is_kept(self):
        locator = PolarisLocator(CONFIG)
        image = gray((560, 440, 20000), (620, 520, 2500))
        found = acquire(locator, image, current=(620.0, 520.0, 10.0))
        self.assertAlmostEqual(found[0], 560, delta=0.5)
        polaris = (found[0], found[1], found[2])
        self.assertIsNone(acquire(locator, image, current=polaris, times=6))

    def test_state_survives_restart_only_for_same_calibration(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'data' / 'polaris-tracking.json'
            locator = PolarisLocator(CONFIG, str(path))
            acquire(locator, gray((560, 440, 20000)))
            restored = PolarisLocator(CONFIG, str(path))
            self.assertAlmostEqual(restored.offset[0], 60, delta=0.5)
            self.assertEqual(restored.flux[:2], SETTINGS)
            other = PolarisLocator(dataclasses.replace(CONFIG, calibration_id='new-cal'), str(path))
            self.assertEqual(other.offset, (0.0, 0.0))
            path.write_text('{"calibration_id": "test-cal", "offset": ["x"]}')
            self.assertEqual(PolarisLocator(CONFIG, str(path)).offset, (0.0, 0.0))

    def test_describe_reports_prediction_and_visibility(self):
        locator = PolarisLocator(CONFIG)
        locator.offset = (60.0, 40.0)
        info = locator.describe(EPOCH, (WIDTH, HEIGHT))
        self.assertEqual((info['predictedX'], info['predictedY'], info['onSensor']), (560.0, 440.0, True))
        self.assertIsNone(PolarisLocator(dataclasses.replace(CONFIG, polaris_reference=None)).describe(EPOCH, None))


class FakeSource:
    def __init__(self, raw, frames, stop):
        self.raw, self.frames, self.stop = raw, frames, stop
        self.count = 0

    def read(self, settings):
        self.count += 1
        if self.count >= self.frames:
            self.stop.set()
        time.sleep(0.02)
        return self.raw, 0.01


class CaptureAcquisitionTest(unittest.TestCase):
    def test_capture_reacquires_shifted_polaris_and_measures_it(self):
        now = time.time()
        config = dataclasses.replace(CONFIG, polaris_reference=(now, 500.0, 400.0, 500.0, 0.0, -1))
        stop = threading.Event()
        source = FakeSource(frame((560, 440, 20000)), 250, stop)
        capture = Capture(source, Settings(*SETTINGS), False, stop, 1.0, config)
        capture.run()
        self.assertAlmostEqual(capture.polaris.offset[0], 60, delta=1)
        self.assertAlmostEqual(capture.polaris.offset[1], 40, delta=1)
        self.assertIn('x: 560', capture.tracker.describe())
        self.assertGreater(capture.seeing.snapshot(time.monotonic())['samples'], 20)


if __name__ == '__main__':
    unittest.main()
