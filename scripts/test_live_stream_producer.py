import unittest
import subprocess
import sys
import time
import threading

import numpy as np

from live_stream_producer import Encoder, Settings, StarTracker, close_source, enhance_preview, frame_statistics, measure_star


def scene(stars, shape=(512, 768)):
    rng = np.random.default_rng(31)
    yy, xx = np.indices(shape)
    data = rng.normal(5, 0.6, shape)
    for x, y, peak in stars:
        data += peak * np.exp(-((xx - x) ** 2 + (yy - y) ** 2) / (2 * 1.5 ** 2))
    return np.clip(data, 0, 255).astype(np.uint8)


class AutoExposureTests(unittest.TestCase):
    def test_recovers_from_daylight_clipping_then_restores_night_ceiling(self):
        settings = Settings(150000, 1, True)
        for step in range(20):
            captured = settings.acquisition()
            settings.adjust_exposure(captured, min(1.0, captured[0] / 1000.0), step * 2.0)
        daylight_exposure = settings.acquisition()[0]
        self.assertGreaterEqual(daylight_exposure / 1000.0, 0.25)
        self.assertLessEqual(daylight_exposure / 1000.0, 0.65)
        for step in range(20, 40):
            captured = settings.acquisition()
            settings.adjust_exposure(captured, min(1.0, captured[0] / 10_000_000.0), step * 2.0)
        self.assertEqual(settings.acquisition()[0], 150000)
        self.assertEqual(settings.snapshot(), (150000, 1))

    def test_manual_override_is_exact_and_ignores_brightness(self):
        settings = Settings(150000, 1, True)
        settings.adjust_exposure(settings.acquisition(), 1.0, 0.0)
        settings.update(5000, 2, False)
        self.assertEqual(settings.acquisition()[:3], (5000, 2, False))
        self.assertFalse(settings.adjust_exposure(settings.acquisition(), 1.0, 100.0))
        self.assertEqual(settings.acquisition()[0], 5000)

    def test_unchanged_website_polls_do_not_undo_adjustment_or_settle_time(self):
        settings = Settings(150000, 1, True)
        settings.adjust_exposure(settings.acquisition(), 1.0, 0.0)
        self.assertFalse(settings.update(150000, 1, True))
        self.assertEqual(settings.acquisition()[0], 37500)
        self.assertFalse(settings.adjust_exposure(settings.acquisition(), 1.0, 1.0))
        self.assertTrue(settings.adjust_exposure(settings.acquisition(), 1.0, 2.0))

    def test_stale_frame_cannot_override_new_user_settings(self):
        settings = Settings(150000, 1, True)
        captured = settings.acquisition()
        settings.update(2000, 2, True)
        self.assertFalse(settings.adjust_exposure(captured, 1.0, 10.0))
        self.assertEqual(settings.acquisition()[0], 2000)

    def test_lower_bound_and_isolated_stars(self):
        settings = Settings(100, 1, True)
        self.assertFalse(settings.adjust_exposure(settings.acquisition(), 1.0, 0.0))
        raw = np.full((128, 128), 64, dtype=np.uint16)
        raw[32:35, 32:35] = 65520
        stats = frame_statistics(raw)
        settings = Settings(150000, 1, True)
        self.assertFalse(settings.adjust_exposure(settings.acquisition(), stats['rawP90Fraction'], 0.0))
        self.assertFalse(stats['frameOverexposed'])

    def test_detects_the_observed_12bit_saturation(self):
        stats = frame_statistics(np.full((64, 64), 65520, dtype=np.uint16))
        self.assertTrue(stats['frameOverexposed'])
        self.assertEqual(stats['nearSaturationPercent'], 100)

    def test_no_adjustment_inside_brightness_deadband(self):
        settings = Settings(150000, 1, True)
        for level in (0.25, 0.4, 0.65, float('nan')):
            self.assertFalse(settings.adjust_exposure(settings.acquisition(), level, 0.0))


class TrackingTests(unittest.TestCase):
    def test_saturated_camera_frame_stays_bright(self):
        # Observed QHY12-bit ceiling when left-aligned in a uint16 frame.
        for level in (60000, 65520, 65535):
            with self.subTest(level=level):
                raw = np.full((64, 64), level, dtype=np.uint16)
                preview = enhance_preview(raw)
                self.assertGreaterEqual(int(preview.min()), 240)
                self.assertTrue(np.all(raw == level))

    def test_bright_preview_keeps_scene_detail_when_median_is_saturated(self):
        raw = np.full((64, 64), 65520, dtype=np.uint16)
        raw[:16, :] = 32768
        preview = enhance_preview(raw)
        self.assertGreater(int(preview[0, 0]), 0)
        self.assertGreater(int(preview[-1, 0]), int(preview[0, 0]))
        self.assertGreaterEqual(int(preview[-1, 0]), 250)

    def test_empty_camera_frame_stays_black(self):
        raw = np.zeros((64, 64), dtype=np.uint16)
        self.assertFalse(np.any(enhance_preview(raw)))

    def test_native_faint_signal_survives_preview_quantization(self):
        raw = np.full((64, 64), 16, dtype=np.uint16)
        raw[31:34, 31:34] = 144  # All of these pixels used to shift to zero.
        original = raw.copy()
        preview = enhance_preview(raw)
        self.assertGreater(int(preview[32, 32]), 60)
        self.assertEqual(int(preview[0, 0]), 0)
        np.testing.assert_array_equal(raw, original)

    def test_hung_driver_cleanup_is_bounded(self):
        release = threading.Event()
        class HungCamera:
            def close(self):
                release.wait(5)
        try:
            started = time.monotonic()
            self.assertFalse(close_source(HungCamera(), timeout=0.05))
            self.assertLess(time.monotonic() - started, 1)
        finally:
            release.set()

    def test_idle_encoder_does_not_block_shutdown(self):
        encoder = Encoder(None, 1, 1, 1)
        encoder.process = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(10)'], stdout=subprocess.PIPE)
        try:
            started = time.monotonic()
            self.assertIsNone(encoder.read())
            self.assertLess(time.monotonic() - started, 2)
        finally:
            encoder.stop()

    def test_reacquires_bright_star_after_faint_lock(self):
        frame = scene([(140.3, 120.7, 30), (530.2, 380.4, 220)])
        star = measure_star(frame, near=(140, 121))
        np.testing.assert_allclose(star[:2], (530.2, 380.4), atol=0.2)

    def test_does_not_switch_similar_stars(self):
        frame = scene([(140.3, 120.7, 90), (530.2, 380.4, 110)])
        star = measure_star(frame, near=(140, 121))
        np.testing.assert_allclose(star[:2], (140.3, 120.7), atol=0.2)

    def test_hot_pixel_near_real_star_does_not_hide_it(self):
        frame = scene([(140.3, 120.7, 55)])
        frame[140, 160] = 255
        frame[350, 650] = 255
        star = measure_star(frame)
        np.testing.assert_allclose(star[:2], (140.3, 120.7), atol=0.2)

    def test_only_noise_and_hot_pixels_has_no_star(self):
        frame = scene([])
        frame[120, 140] = 255
        frame[350, 650] = 255
        self.assertIsNone(measure_star(frame))

    def test_reacquires_when_old_star_disappears(self):
        frame = scene([(530.2, 380.4, 110)])
        star = measure_star(frame, near=(140, 121), search_global=False)
        np.testing.assert_allclose(star[:2], (530.2, 380.4), atol=0.2)

    def test_target_change_resets_rms(self):
        tracker = StarTracker()
        tracker.add((140, 120, 55))
        tracker.add((141, 120, 55))
        tracker.add((530, 380, 220))
        self.assertIn('rms error: 0.00', tracker.describe())

    def test_preview_boost_leaves_measurements_unchanged(self):
        frame = scene([(140.3, 120.7, 55)])
        original = frame.copy()
        star = measure_star(frame)
        preview = enhance_preview(frame)
        np.testing.assert_array_equal(frame, original)
        self.assertEqual(star, measure_star(frame))
        self.assertGreater(preview[121, 140], frame[121, 140])
        self.assertEqual(enhance_preview(np.array([0, 255], dtype=np.uint8)).tolist(), [0, 255])


if __name__ == '__main__':
    unittest.main()
