import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO

import numpy as np

import calibrate_noise


def spot_frames(count=300, jitter=0.05, drift=0.0, step=0.0, peak=20000.0, read_noise=40.0, seed=1, size=96):
    """16-bit frames of a Gaussian spot (sigma 1.8 px) whose centre moves by `jitter` px RMS per axis."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    frames, times = [], []
    for i in range(count):
        t = i * 0.2
        x = size / 2 + 0.3 + rng.normal(0, jitter) + drift * t + (step if i >= count // 2 else 0.0)
        y = size / 2 - 0.2 + rng.normal(0, jitter)
        image = 800.0 + peak * np.exp(-((xx - x) ** 2 + (yy - y) ** 2) / (2 * 1.8 ** 2)) + rng.normal(0, read_noise, xx.shape)
        frames.append(np.clip(image, 0, 65535).astype(np.uint16))
        times.append(t)
    return frames, times


class CalibrateNoiseTests(unittest.TestCase):
    def test_recovers_the_injected_centroid_jitter(self):
        for jitter in (0.03, 0.1):
            result = calibrate_noise.measure(*spot_frames(jitter=jitter))
            self.assertAlmostEqual(result["centroid_noise_px"], jitter, delta=0.2 * jitter)
            self.assertEqual(result["frames"], 300)
            self.assertEqual(calibrate_noise.warnings(result), [])

    def test_a_still_spot_gives_only_the_measurement_noise(self):
        result = calibrate_noise.measure(*spot_frames(jitter=0.0))
        # Not zero: the centroid's brightness threshold lets edge pixels in and out from frame to frame.
        self.assertLess(result["centroid_noise_px"], 0.03)
        self.assertGreater(result["centroid_noise_px"], 0.0)

    def test_steady_drift_is_removed_but_reported(self):
        result = calibrate_noise.measure(*spot_frames(jitter=0.05, drift=0.02))   # 1.2 px per minute
        self.assertAlmostEqual(result["centroid_noise_px"], 0.05, delta=0.012)
        self.assertAlmostEqual(result["driftPxPerMinute"], 1.2, delta=0.1)
        self.assertTrue(any("drifted" in note for note in calibrate_noise.warnings(result)))

    def test_a_bump_in_the_middle_is_flagged(self):
        frames, times = spot_frames(jitter=0.02, seed=3)
        noisy, _ = spot_frames(jitter=0.2, seed=4)
        result = calibrate_noise.measure(frames[:150] + noisy[150:], times)
        self.assertTrue(any("two halves" in note for note in calibrate_noise.warnings(result)))

    def test_photometry_is_in_8bit_equivalent_counts(self):
        result = calibrate_noise.measure(*spot_frames(peak=20000.0, read_noise=40.0))
        # Gaussian volume 2*pi*sigma^2*peak, divided by 256
        self.assertAlmostEqual(result["noise_flux"], 2 * np.pi * 1.8 ** 2 * 20000 / 256, delta=0.05 * 1600)
        self.assertAlmostEqual(result["noise_background_rms"], 40 / 256, delta=0.03)
        self.assertAlmostEqual(result["fwhmPx"], 2.355 * 1.8, delta=0.5)

    def test_refuses_saturated_missing_or_too_few_frames(self):
        with self.assertRaisesRegex(ValueError, "saturated"):
            calibrate_noise.measure(*spot_frames(count=120, peak=90000.0))
        frames, times = spot_frames(count=120)
        blank = [np.full_like(f, 800) for f in frames]
        with self.assertRaisesRegex(ValueError, "only 0 of 120"):
            calibrate_noise.measure(blank, times)
        with self.assertRaisesRegex(ValueError, "at least 100"):
            calibrate_noise.measure(frames[:50], times[:50])
        with self.assertRaisesRegex(ValueError, "lost in"):
            calibrate_noise.measure(frames[:100] + blank[:20], times)

    def test_command_line_prints_the_calibration_fragment(self):
        frames, times = spot_frames(count=150, jitter=0.04)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "frames.npz")
            np.savez_compressed(path, frames=np.stack(frames), times=np.asarray(times))
            out, err = StringIO(), StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = calibrate_noise.main(["--frames-file", path, "--exposure", "5000", "--gain", "50"])
        self.assertEqual(code, 0)
        fragment = json.loads(out.getvalue())
        self.assertEqual(sorted(fragment), ["centroid_noise_px", "noise_background_rms", "noise_exposure_us", "noise_flux", "noise_gain"])
        self.assertEqual((fragment["noise_exposure_us"], fragment["noise_gain"]), (5000, 50))
        # The fragment is accepted by the producer's configuration loader.
        from seeing_metrics import SeeingConfig
        SeeingConfig(plate_scale_arcsec_px=4.0957, **fragment)


if __name__ == "__main__":
    unittest.main()
