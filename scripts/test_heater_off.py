import os
import subprocess
import sys
import unittest
from unittest import mock

import heater_off


class Line:
    """Stands in for device_agent.GpioLine: records the claim and the level."""

    claims: list = []
    busy = 0   # the first `busy` claims fail, as while the old program still holds the line

    def __init__(self, offset, chip, consumer="heater", initial=False):
        if Line.busy > 0:
            Line.busy -= 1
            raise OSError(16, "Device or resource busy")
        self.level = initial
        Line.claims.append((offset, chip, consumer, initial, self))

    def set(self, on):
        self.level = on

    def get(self):
        return self.level


class HeaterOffTests(unittest.TestCase):
    def setUp(self) -> None:
        Line.claims, Line.busy = [], 0

    def test_claims_the_configured_line_low(self) -> None:
        with mock.patch.dict(os.environ, {"DEVICE_AGENT_HEATER_PIN": "26"}):
            message = heater_off.heater_off(Line)
        offset, chip, _, initial, line = Line.claims[0]
        self.assertEqual((offset, chip, initial, line.level), (26, "pinctrl-rp1", False, False))
        self.assertIn("driven low", message)

    def test_waits_for_the_old_program_to_let_go(self) -> None:
        Line.busy = 2
        message = heater_off.heater_off(Line, delay=0)
        self.assertEqual(len(Line.claims), 1)
        self.assertIn("driven low", message)

    def test_reports_instead_of_raising_when_the_line_stays_busy(self) -> None:
        Line.busy = 99
        message = heater_off.heater_off(Line, attempts=3, delay=0)
        self.assertEqual(Line.claims, [])
        self.assertIn("could not drive GPIO 26 low", message)

    def test_no_heater_configured_touches_nothing(self) -> None:
        with mock.patch.dict(os.environ, {"DEVICE_AGENT_HEATER_PIN": "none"}):
            self.assertEqual(heater_off.heater_off(Line), "no heater configured")
        self.assertEqual(Line.claims, [])

    def test_script_exits_zero_when_the_chip_is_missing(self) -> None:
        # The claim fails; systemd must still see success. A chip label that does not exist keeps
        # this test away from the real heater line when it runs on a Pi.
        script = os.path.join(os.path.dirname(os.path.abspath(heater_off.__file__)), "heater_off.py")
        env = dict(os.environ, DEVICE_AGENT_HEATER_CHIP="no-such-chip-for-test")
        run = subprocess.run([sys.executable, script], capture_output=True, text=True, timeout=30, env=env)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("could not drive GPIO", run.stdout)


if __name__ == "__main__":
    unittest.main()
