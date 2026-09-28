import dataclasses
import json
import math
import unittest
import threading
import tempfile
from pathlib import Path
from unittest.mock import patch

import numpy as np

from live_stream_producer import Capture, Settings, SessionState, Telemetry, measure_polaris_region, measure_star
from seeing_metrics import (ARCSEC_PER_RADIAN, SeeingConfig, SeeingMonitor,
                            detrend, star_photometry, zenith_seeing)


CONFIG = SeeingConfig(plate_scale_arcsec_px=4.1253, aperture_mm=25, altitude_deg=35,
                      centroid_noise_px=0.03, noise_exposure_us=10000, noise_gain=1, noise_flux=2000, noise_background_rms=1,
                      polaris_roi=(0, 0, 400, 400))


def fill(monitor, jitter=0.2, drift=(0.07, -0.04), exposure=10000, gain=1, count=300):
    rng = np.random.default_rng(17)
    for i in range(count):
        t = i * 0.2
        star = (100 + drift[0] * t + rng.normal(0, jitter),
                100 + drift[1] * t + rng.normal(0, jitter), 120)
        monitor.add(t, 1700000000 + t, star,
                    dict(flux=2000 + rng.normal(0, 40), snr=100, fwhmPx=3, backgroundRms=1),
                    exposure, gain, (400, 400))
    return monitor.snapshot(t)


class SeeingTests(unittest.TestCase):
    def test_persistent_history_outbox_survives_restart_and_acknowledgment(self):
        with tempfile.TemporaryDirectory() as folder:
            path = str(Path(folder) / 'history.sqlite3')
            first = SeeingMonitor(CONFIG, history_path=path)
            original = fill(first)
            self.assertTrue(original['historyPersistent'])
            session = original['sessionId']
            first._db.close()
            second = SeeingMonitor(CONFIG, history_path=path)
            restored = second.snapshot(0)
            self.assertEqual(restored['history'][0]['sessionId'], session)
            self.assertNotEqual(restored['sessionId'], session)
            self.assertEqual(restored['history'][0]['calibration']['plate_scale_arcsec_px'], CONFIG.plate_scale_arcsec_px)
            for metric in ('driftPxPerMinute','fwhmPx','flux','backgroundRms','durationSeconds'):
                self.assertEqual(restored['history'][0][metric], original[metric])
            second.acknowledge(restored['history'])
            ack = second._ack
            second._db.close()
            third = SeeingMonitor(CONFIG, history_path=path)
            self.assertEqual(third._ack, ack)
            self.assertEqual(third._db.execute('SELECT count(*) FROM measurements').fetchone()[0], 1)
            third._db.close()

    def test_polaris_prediction_follows_sidereal_rotation_and_stays_outside_sensor(self):
        config = dataclasses.replace(CONFIG, polaris_reference=(1700000000, 200, 100, 100, 100, -1))
        self.assertEqual(config.region_at(1700000000), (152, 52, 96, 96))
        self.assertEqual(config.region_at(1700000000 + 86164.0905/4), (52, -48, 96, 96))
        self.assertIsNone(measure_polaris_region(np.ones((400,400)), (52,-48,96,96)))

    def test_altitude_matches_astropy_reference_and_angular_motion_uses_native_scale(self):
        config = dataclasses.replace(CONFIG, site_latitude_deg=34.4133, site_longitude_deg=-119.8610,
                                     polaris_equatorial_of_date=(46.72630296748609,89.37422974381558))
        self.assertAlmostEqual(config.altitude_at(1789018300),34.36106,delta=.01)
        result=fill(SeeingMonitor(config))
        self.assertAlmostEqual(result['jitterArcsec'], result['jitterRmsPx']*config.plate_scale_arcsec_px)

    def test_linear_drift_is_removed_with_irregular_timestamps(self):
        t = np.random.default_rng(9).uniform(0, 60, 300)
        t.sort()
        values = np.column_stack((20 + 0.4 * t, 50 - 0.3 * t))
        residual, slope = detrend(t, values)
        np.testing.assert_allclose(residual, 0, atol=1e-12)
        np.testing.assert_allclose(slope, (0.4, -0.3), atol=1e-12)

    def test_known_motion_survives_drift_removal(self):
        a = fill(SeeingMonitor(), drift=(0, 0))
        b = fill(SeeingMonitor(), drift=(0.07, -0.04))
        self.assertAlmostEqual(a['jitterRmsPx'], b['jitterRmsPx'], places=12)
        self.assertAlmostEqual(b['jitterRmsPx'], math.sqrt(2) * 0.2, delta=0.03)
        self.assertAlmostEqual(b['driftPxPerMinute'], math.hypot(.07, .04) * 60, delta=.2)
        self.assertAlmostEqual(b['fluxVariationPercent'], 2, delta=.3)
        self.assertEqual(b['status'], 'relative')
        self.assertIsNone(b['seeingArcsec'])

    def test_forward_physical_model_recovers_known_zenith_seeing(self):
        # Independent forward r0 calculation prevents radial/per-axis or angular-unit mistakes.
        expected = 1.6
        r0_zenith = .98 * 500e-9 / (expected / ARCSEC_PER_RADIAN)
        r0_slant = r0_zenith * math.sin(math.radians(CONFIG.altitude_deg)) ** .6
        variance_rad = .182 * (500e-9) ** 2 * r0_slant ** (-5 / 3) * .025 ** (-1 / 3)
        variance_px = variance_rad / (CONFIG.plate_scale_arcsec_px / ARCSEC_PER_RADIAN) ** 2 + .03 ** 2
        self.assertAlmostEqual(zenith_seeing(variance_px, CONFIG), expected, places=10)

    def test_valid_calibration_emits_estimate_and_noise_floor_never_emits_zero(self):
        self.assertGreater(fill(SeeingMonitor(CONFIG))['seeingArcsec'], 0)
        result = fill(SeeingMonitor(CONFIG), jitter=0)
        self.assertEqual(result['status'], 'below_noise_floor')
        self.assertIsNone(result['seeingArcsec'])

    def test_each_calibration_and_acquisition_gate(self):
        cases = [(dataclasses.replace(CONFIG, polaris_roi=None), 10000, 1, 'target_unconfirmed'),
                 (dataclasses.replace(CONFIG, aperture_mm=None), 10000, 1, 'calibration_required'),
                 (CONFIG, 25000, 1, 'long_exposure'),
                 (CONFIG, 5000, 1, 'noise_calibration_mismatch'),
                 (CONFIG, 10000, 2, 'noise_calibration_mismatch')]
        for config, exposure, gain, blocker in cases:
            with self.subTest(blocker=blocker):
                result = fill(SeeingMonitor(config), exposure=exposure, gain=gain)
                self.assertIn(blocker, result['blockers'])
                self.assertIsNone(result['seeingArcsec'])
                self.assertGreater(result['jitterRmsPx'], 0)
        result = fill(SeeingMonitor(CONFIG, simulated=True))
        self.assertIn('simulated', result['blockers'])
        self.assertIsNone(result['seeingArcsec'])

    def test_clouds_or_changed_background_invalidate_fixed_noise_calibration(self):
        for config in (dataclasses.replace(CONFIG, noise_flux=4000),
                       dataclasses.replace(CONFIG, noise_background_rms=2)):
            result = fill(SeeingMonitor(config))
            self.assertIn('noise_signal_mismatch', result['blockers'])
            self.assertIsNone(result['seeingArcsec'])
            self.assertGreater(result['jitterRmsPx'], 0)

    def test_loss_saturation_weak_signal_and_frame_clipping_clear_measurement(self):
        for star, photometry, clipped, state in (
            (None, None, False, 'searching'),
            ((104, 98, 255), dict(flux=2000, snr=100, fwhmPx=3, backgroundRms=1), False, 'saturated'),
            ((104, 98, 100), dict(flux=100, snr=3, fwhmPx=3, backgroundRms=1), False, 'low_signal'),
            (None, None, True, 'overexposed'),
        ):
            with self.subTest(state=state):
                monitor = SeeingMonitor(CONFIG)
                fill(monitor)
                monitor.add(60, 1700000060, star, photometry, 10000, 1, (400, 400), clipped)
                result = monitor.snapshot(60)
                self.assertEqual(result['status'], state)
                self.assertEqual(result['samples'], 0)
                self.assertIsNone(result['jitterRmsPx'])
                self.assertIsNone(result['seeingArcsec'])
                self.assertEqual(result['timestamp'], 1700000060)

    def test_target_settings_geometry_and_gap_start_new_window(self):
        for t, star, exposure, shape, reason in (
            (60, (200, 200, 120), 10000, (400, 400), 'target_changed'),
            (60, (104, 98, 120), 5000, (400, 400), 'settings_changed'),
            (60, (104, 98, 120), 10000, (800, 800), 'settings_changed'),
            (65, (104, 98, 120), 10000, (400, 400), 'capture_gap'),
        ):
            monitor = SeeingMonitor(CONFIG)
            fill(monitor)
            monitor.add(t, 1700000000 + t, star, dict(flux=2000, snr=100, fwhmPx=3, backgroundRms=1), exposure, 1, shape)
            result = monitor.snapshot(t)
            self.assertEqual(result['samples'], 1)
            self.assertEqual(result['resetReason'], reason)
            self.assertIsNone(result['seeingArcsec'])

    def test_duplicate_frames_do_not_inflate_samples_and_stall_expires_metrics(self):
        monitor = SeeingMonitor(CONFIG)
        before = fill(monitor)
        monitor.add(59.8, 1700000059.8, (500, 500, 100), None, 1, 1, (400, 400))
        self.assertEqual(monitor.snapshot(60)['samples'], before['samples'])
        stale = monitor.snapshot(64)
        self.assertEqual(stale['status'], 'stale')
        self.assertIsNone(stale['jitterRmsPx'])
        self.assertIsNone(stale['seeingArcsec'])

    def test_warmup_needs_both_duration_and_samples(self):
        for count in (10, 200):
            result = fill(SeeingMonitor(CONFIG), count=count)
            self.assertEqual(result['status'], 'collecting')
            self.assertIsNone(result['seeingArcsec'])
        monitor = SeeingMonitor(CONFIG)
        for i in range(300):
            monitor.add(i*.05, 1700000000+i*.05, (100, 100, 120), dict(flux=2000, snr=100, fwhmPx=3, backgroundRms=1), 10000, 1, (400, 400))
        self.assertEqual(monitor.snapshot(15)['status'], 'collecting')

    def test_history_is_bounded_and_serializes_without_nan(self):
        monitor = SeeingMonitor(CONFIG)
        for i in range(130):
            monitor.add(i*30, 1700000000+i*30, None, None, 10000, 1, (400, 400))
            result = monitor.snapshot(i*30)
        self.assertEqual(len(result['history']), 120)
        json.dumps(result, allow_nan=False)

    def test_stalled_history_advances_time_without_claiming_new_capture(self):
        monitor = SeeingMonitor(CONFIG)
        fill(monitor)
        first = monitor.snapshot(60)
        later = monitor.snapshot(90)
        self.assertEqual(first['timestamp'], later['timestamp'])
        self.assertEqual(later['history'][-1]['timestamp'], 1700000090)
        self.assertEqual(later['history'][-1]['status'], 'stale')
        self.assertIsNone(later['history'][-1]['jitterRmsPx'])

    def test_invalid_calibration_rejected(self):
        for values in ({'aperture_mm': 0}, {'altitude_deg': -1}, {'plate_scale_arcsec_px': float('nan')},
                       {'centroid_noise_px': -1}, {'noise_gain': 1.1}, {'polaris_roi': (0, 0, 10, 10)},
                       {'plate_scale_arcsec_px': True}, {'altitude_deg': 91}):
            with self.assertRaises(ValueError):
                SeeingConfig(**values)

    def test_raw_photometry_and_restricted_target_selection(self):
        yy, xx = np.indices((200, 300))
        gray = 5 + 100 * np.exp(-((xx-100.2)**2 + (yy-100.6)**2) / 4.5)
        original = gray.copy()
        star = measure_star(gray)
        photo = star_photometry(gray, star)
        self.assertGreater(photo['snr'], 20)
        self.assertAlmostEqual(photo['fwhmPx'], 2.35482 * 1.5, delta=.05)
        np.testing.assert_array_equal(gray, original)
        gray += 220 * np.exp(-((xx-250)**2 + (yy-100)**2) / 4.5)
        found = measure_polaris_region(gray, (70, 70, 60, 60))
        np.testing.assert_allclose(found[:2], (100.2, 100.6), atol=.2)
        self.assertIsNone(measure_polaris_region(gray, (70, 70, 400, 400)))
        self.assertIsNone(measure_polaris_region(gray, (96, 70, 60, 60)))
        self.assertIsNone(star_photometry(gray, (2, 2, 100)))


class CaptureIntegrationTests(unittest.TestCase):
    def capture(self, positions, times):
        stop = threading.Event()
        yy, xx = np.indices((160, 160))
        class Source:
            index = 0
            now = 0
            def read(self, settings):
                position = positions[self.index]
                self.now = times[self.index]
                self.index += 1
                frame = np.full((160, 160), 16.0)
                if position is not None:
                    frame += 22000 * np.exp(-((xx-position)**2 + (yy-80.4)**2)/4.5)
                if self.index == len(positions):
                    stop.set()
                return frame.astype(np.uint16), .01
        source = Source()
        config = dataclasses.replace(CONFIG, polaris_roi=(0, 0, 160, 160))
        capture = Capture(source, Settings(10000, 1), False, stop, seeing_config=config)
        with patch('live_stream_producer.time.monotonic', side_effect=lambda: source.now), \
                patch('live_stream_producer.time.time', side_effect=lambda: 1700000000+source.now):
            capture.run()
        self.assertEqual(capture.errors, 0)
        return capture

    def test_recovers_selected_star_after_clouds_and_drift(self):
        capture = self.capture([60.2, None, 72.2], [100, 100.2, 104])
        self.assertAlmostEqual(capture.latest().star[0], 72.2, delta=.2)
        data = capture.seeing.snapshot(104)
        self.assertEqual(data['samples'], 1)
        self.assertIsNone(data['seeingArcsec'])

    def test_skipped_frames_and_telemetry_polls_do_not_add_samples(self):
        capture = self.capture([60.2] * 10, [100+i*.02 for i in range(10)])
        self.assertEqual(capture.frames, 10)
        self.assertEqual(capture.seeing.snapshot(100.18)['samples'], 2)
        reporter = Telemetry(None, capture, capture.settings, threading.Event(), SessionState(), False)
        with patch('live_stream_producer.time.monotonic', return_value=100.18):
            for _ in range(5):
                payload = reporter.payload()
                self.assertEqual(payload['seeing']['samples'], 2)
                json.dumps(payload, allow_nan=False)
        self.assertIsNotNone(capture.latest().star)


if __name__ == '__main__':
    unittest.main()
