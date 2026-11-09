import unittest
from dataclasses import replace
import subprocess
import sys
import time
import threading

import numpy as np

from live_stream_producer import (Capture, bin2x2, Encoder, Frame, PreviewRenderer, PreviewSettings, Settings, StarTracker,
                                  close_source, enhance_preview, frame_statistics, measure_star)


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


class PictureSettingsTests(unittest.TestCase):
    def test_website_values_are_validated(self):
        preview = PreviewSettings()
        self.assertTrue(preview.update(4.5, 3))
        self.assertEqual(preview.snapshot(), (4.5, 3))
        self.assertFalse(preview.update(0.5, 3))
        self.assertFalse(preview.update(4.5, 9))
        self.assertFalse(preview.update("nan", 1))
        self.assertFalse(preview.update(None, None))
        self.assertEqual(preview.snapshot(), (4.5, 3))

    def _capture(self, frames):
        capture = Capture(object(), Settings(10000, 50), False, threading.Event(), 1.0)
        capture.preview.update(1.0, frames)
        return capture

    def _frame(self, index, shape):
        return Frame(data=b"", width=shape[1], height=shape[0], channels=1, index=index, captured_at=0.0,
                     actual_exposure_s=0.01, star=None, exposure_us=10000, gain=50)

    def test_averaging_reduces_noise_in_the_picture_only(self):
        rng = np.random.default_rng(3)
        shape = (64, 64)
        renderer = PreviewRenderer(self._capture(8), threading.Event())
        raws = [np.clip(rng.normal(20000, 800, shape), 0, 65535).astype(np.uint16) for _ in range(40)]
        originals = [raw.copy() for raw in raws]
        for i, raw in enumerate(raws):
            out = renderer.render(raw, self._frame(i, shape))
        averaged = np.frombuffer(out.data, dtype=np.uint8).reshape(shape).astype(float)
        single = np.frombuffer(PreviewRenderer(self._capture(1), threading.Event()).render(raws[-1], self._frame(0, shape)).data,
                               dtype=np.uint8).reshape(shape).astype(float)
        self.assertLess(averaged.std(), single.std() / 2)
        self.assertEqual((out.width, out.height, out.channels), (64, 64, 1))
        for raw, original in zip(raws, originals):
            self.assertTrue(np.array_equal(raw, original), "native frames must not be modified")

    def test_wide_frames_are_binned_for_the_video_only(self):
        raw = np.arange(4 * 6, dtype=np.uint16).reshape(4, 6) * 1000
        self.assertEqual(bin2x2(raw).tolist(), [[3500, 5500, 7500], [15500, 17500, 19500]])
        self.assertEqual(bin2x2(np.ones((5, 7), np.uint8) * 9).shape, (2, 3))
        renderer = PreviewRenderer(self._capture(1), threading.Event(), max_width=16)
        out = renderer.render(np.zeros((20, 40), np.uint16), self._frame(0, (20, 40)))
        self.assertEqual((out.width, out.height), (20, 10))
        narrow = PreviewRenderer(self._capture(1), threading.Event(), max_width=32).render(
            np.zeros((20, 40), np.uint16), self._frame(0, (20, 40)))
        self.assertEqual((narrow.width, narrow.height), (40, 20))

    def test_average_restarts_when_measurement_settings_change(self):
        shape = (8, 8)
        renderer = PreviewRenderer(self._capture(4), threading.Event())
        renderer.render(np.full(shape, 60000, np.uint16), self._frame(0, shape))
        changed = replace(self._frame(1, shape), exposure_us=1000)
        out = renderer.render(np.zeros(shape, np.uint16), changed)
        self.assertFalse(np.any(np.frombuffer(out.data, dtype=np.uint8)), "old bright frames must not bleed in")

    def test_capture_publishes_native_frames_and_renderer_the_picture(self):
        stop = threading.Event()

        class Source:
            count = 0

            def read(self, settings):
                Source.count += 1
                if Source.count > 30:
                    stop.set()
                time.sleep(0.005)
                return np.full((32, 48), 1000, np.uint16), 0.01

        capture = Capture(Source(), Settings(10000, 50), False, stop, 3.0)
        capture.preview.update(3.0, 4)
        renderer = PreviewRenderer(capture, stop)
        renderer.start()
        capture.run()
        renderer.join(timeout=2)
        frame = capture.latest_picture()
        self.assertIsNotNone(frame)
        self.assertEqual((frame.width, frame.height, len(frame.data)), (48, 32, 48 * 32))
        self.assertEqual(capture.latest().data, b"", "captured frames carry no picture")
        self.assertGreater(renderer.rendered, 0)
        self.assertGreaterEqual(capture.frames, 30)


if __name__ == '__main__':
    unittest.main()
