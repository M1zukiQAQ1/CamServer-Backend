import json
import os
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

import device_agent
from device_agent import CaptureRequests, DeviceAgent, Heater


class FakeBackend:
    """Answers POST /api/devices/heartbeat like the Spring endpoint: queued commands go out once."""

    def __init__(self) -> None:
        self.reports: list[dict] = []
        self.queue: list[dict] = []
        self.lock = threading.Lock()
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.headers.get("Authorization") != "Bearer t0ken":
                    self.send_response(401)
                    self.end_headers()
                    return
                with backend.lock:
                    backend.reports.append(body)
                    commands, backend.queue = backend.queue, []
                data = json.dumps({"ok": True, "commands": commands}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args) -> None:
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def results(self) -> list[dict]:
        with self.lock:
            return [r for report in self.reports for r in report.get("results", [])]

    def wait_for(self, predicate, timeout: float = 5.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(0.02)
        return False


class DeviceAgentTests(unittest.TestCase):
    def setUp(self) -> None:
        self.backend = FakeBackend()
        self.env = mock.patch.dict(os.environ, {
            "DEVICE_AGENT_TOKEN": "t0ken", "DEVICE_AGENT_BACKEND": self.backend.url,
            "DEVICE_AGENT_INTERVAL": "0.05", "DEVICE_AGENT_DRY_RUN": "1",
            "DEVICE_AGENT_HEATER_PIN": ""})  # never touch the real heater line from a test
        self.env.start()

    def tearDown(self) -> None:
        self.env.stop()
        self.backend.server.shutdown()

    def test_reports_sensors_status_and_host(self) -> None:
        DeviceAgent(sensors=lambda: {"temperature": 21.0, "humidity": 40.0}, status=lambda: {"cameraId": "X"},
                    service="all-sky-camera.service").start()
        self.assertTrue(self.backend.wait_for(lambda: self.backend.reports))
        report = self.backend.reports[0]
        self.assertEqual(report["sensors"], {"temperature": 21.0, "humidity": 40.0})
        self.assertEqual(report["status"], {"cameraId": "X"})
        self.assertIn("hostname", report["host"])
        self.assertEqual(report["capabilities"], ["RESTART_SERVICE", "REBOOT", "SHUTDOWN"])

    def test_failing_sensor_does_not_stop_reports(self) -> None:
        def broken():
            raise OSError("I2C bus error")
        DeviceAgent(sensors=broken).start()
        self.assertTrue(self.backend.wait_for(lambda: len(self.backend.reports) >= 2))
        self.assertIn("I2C bus error", self.backend.reports[0]["sensors"]["error"])

    def test_power_commands_are_dry_run_and_run_once(self) -> None:
        agent = DeviceAgent(service="x.service")
        with mock.patch.object(DeviceAgent, "_run_checked") as run:
            agent.start()
            self.backend.queue = [{"id": "a", "type": "REBOOT"}, {"id": "a", "type": "REBOOT"},
                                  {"id": "b", "type": "SHUTDOWN"}]
            self.assertTrue(self.backend.wait_for(lambda: len(self.backend.results()) >= 2))
            time.sleep(0.2)
            run.assert_not_called()
        results = self.backend.results()
        self.assertEqual([r["id"] for r in results], ["a", "b"])
        self.assertTrue(all(r["ok"] and "dry run" in r["message"] for r in results))

    def test_real_power_command_reports_before_acting(self) -> None:
        os.environ["DEVICE_AGENT_DRY_RUN"] = "0"
        agent = DeviceAgent(power_prefix=[])
        calls = []
        with mock.patch.object(DeviceAgent, "_run_checked", side_effect=lambda argv: calls.append(
                (argv, len(self.backend.results())))):
            agent.start()
            self.backend.queue = [{"id": "p", "type": "SHUTDOWN"}]
            self.assertTrue(self.backend.wait_for(lambda: calls))
        self.assertEqual(calls[0][0], ["systemctl", "poweroff"])
        self.assertEqual(calls[0][1], 1, "the backend heard about it before poweroff ran")

    def test_capture_round_trip(self) -> None:
        captures = CaptureRequests()
        DeviceAgent(capture=captures.request).start()

        def loop():
            while True:
                if captures.wait(5):
                    captures.take()
                    captures.finish({"imgId": 42, "message": "picture 42 uploaded"})
                    return
        threading.Thread(target=loop, daemon=True).start()
        self.backend.queue = [{"id": "c", "type": "CAPTURE"}]
        self.assertTrue(self.backend.wait_for(
            lambda: any(not r["running"] for r in self.backend.results())))
        final = [r for r in self.backend.results() if not r["running"]][0]
        self.assertEqual((final["ok"], final["imgId"], final["message"]), (True, 42, "picture 42 uploaded"))
        self.assertEqual(self.backend.reports[1]["capabilities"][0], "CAPTURE")

    def test_without_token_the_agent_stays_off(self) -> None:
        os.environ["DEVICE_AGENT_TOKEN"] = ""
        os.environ["DEVICE_AGENT_TOKEN_FILE"] = "/nonexistent/device-agent.token"
        agent = DeviceAgent().start()
        time.sleep(0.2)
        self.assertIsNone(agent._thread)
        self.assertEqual(self.backend.reports, [])


    def test_token_from_file(self) -> None:
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".token") as f:
            f.write("t0ken\n")
            f.flush()
            os.environ["DEVICE_AGENT_TOKEN"] = ""
            os.environ["DEVICE_AGENT_TOKEN_FILE"] = f.name
            DeviceAgent().start()
            self.assertTrue(self.backend.wait_for(lambda: self.backend.reports))


    def test_heater_command_and_report(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            line = FakeLine()
            heater = Heater(line, lambda: 10.0, os.path.join(tmp, "heater.json"), interval=60)
            DeviceAgent(sensors=lambda: {"temperature": 10.0}, heater=heater).start()
            self.backend.queue = [{"id": "h", "type": "HEATER", "mode": "ON"},
                                  {"id": "x", "type": "HEATER", "mode": "WARM"}]
            self.assertTrue(self.backend.wait_for(lambda: len(self.backend.results()) >= 2))
            ok, bad = self.backend.results()
            self.assertTrue(ok["ok"])
            self.assertTrue(ok["heater"]["on"] and ok["heater"]["pinHigh"])
            self.assertFalse(bad["ok"])
            self.assertIn("mode must be", bad["message"])
            self.assertEqual(line.writes, [False, True])
            self.assertIn("HEATER", self.backend.reports[-1]["capabilities"])
            self.assertEqual(self.backend.reports[-1]["heater"]["mode"], "ON")

    def test_heater_unavailable_is_not_offered(self) -> None:
        os.environ["DEVICE_AGENT_HEATER_PIN"] = "26"
        os.environ["DEVICE_AGENT_HEATER_CHIP"] = "no-such-chip"
        agent = DeviceAgent().start()
        self.assertIsNone(agent.heater)
        self.assertNotIn("HEATER", agent.capabilities())
        self.backend.queue = [{"id": "h", "type": "HEATER", "mode": "ON"}]
        self.assertTrue(self.backend.wait_for(lambda: self.backend.results()))
        self.assertFalse(self.backend.results()[0]["ok"])


class FakeLine:
    offset = 26

    def __init__(self) -> None:
        self.value = False
        self.writes: list[bool] = []

    def set(self, on: bool) -> None:
        self.value = on
        self.writes.append(on)

    def get(self) -> bool:
        return self.value


class HeaterTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        self.state = os.path.join(self.tmp.name, "heater.json")
        self.line = FakeLine()
        self.temperature: float | None = 20.0
        self.now = 1000.0

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def heater(self) -> Heater:
        def read():
            if isinstance(self.temperature, Exception):
                raise self.temperature
            return self.temperature
        return Heater(self.line, read, self.state, min_dwell=60, clock=lambda: self.now)

    def test_first_start_is_off_and_drives_the_line_low(self) -> None:
        heater = self.heater()
        heater.tick()
        self.assertEqual((heater.mode, heater.target, self.line.writes), ("OFF", 24.0, [False]))

    def test_mode_and_target_survive_a_restart(self) -> None:
        self.heater().set("auto", 18.5)
        with open(self.state) as f:
            self.assertEqual(json.load(f), {"mode": "AUTO", "target": 18.5})
        restored = self.heater()
        self.assertEqual((restored.mode, restored.target), ("AUTO", 18.5))
        restored.set("ON")  # target kept when only the mode changes
        self.assertEqual(self.heater().target, 18.5)

    def test_rejects_bad_settings_without_changing_anything(self) -> None:
        heater = self.heater()
        for mode, target in (("WARM", None), ("AUTO", 200), ("AUTO", "hot")):
            with self.assertRaises(ValueError):
                heater.set(mode, target)
        self.assertEqual((heater.mode, heater.target), ("OFF", 24.0))
        self.assertFalse(os.path.exists(self.state))

    def test_thermostat_hysteresis_and_dwell(self) -> None:
        heater = self.heater()
        self.temperature = 23.0
        heater.set("AUTO", 24)                 # 23 < 23.5: on at once (manual change)
        self.assertTrue(heater.on)
        self.temperature = 24.4                # inside the band: stays on
        self.now += 120
        heater.tick()
        self.assertTrue(heater.on)
        self.temperature = 24.6                # above the band: off
        heater.tick()
        self.assertFalse(heater.on)
        self.temperature = 20.0                # cold again, but only 10 s later: dwell holds it off
        self.now += 10
        heater.tick()
        self.assertFalse(heater.on)
        self.now += 60
        heater.tick()
        self.assertTrue(heater.on)
        self.assertEqual(self.line.writes, [True, False, True])

    def test_sensor_failure_turns_auto_off_immediately(self) -> None:
        heater = self.heater()
        self.temperature = 10.0
        heater.set("AUTO", 24)
        self.assertTrue(heater.on)
        self.temperature = OSError("I2C bus error")
        heater.tick()                          # no dwell for the fail-safe
        self.assertFalse(heater.on)
        self.assertIn("I2C bus error", heater.report()["error"])
        self.temperature = None
        heater.tick()
        self.assertFalse(heater.on)

    def test_manual_modes_ignore_the_sensor(self) -> None:
        heater = self.heater()
        self.temperature = OSError("I2C bus error")
        heater.set("ON")
        self.assertTrue(heater.on and self.line.value)
        report = heater.report()
        self.assertNotIn("error", report)
        self.assertEqual((report["mode"], report["pinHigh"]), ("ON", True))
        heater.set("OFF")
        self.assertFalse(self.line.value)

    def test_corrupt_state_file_falls_back_to_off(self) -> None:
        with open(self.state, "w") as f:
            f.write('{"mode": "BLAST"}')
        self.assertEqual(self.heater().mode, "OFF")


class CaptureRequestsTests(unittest.TestCase):
    def test_wait_sleeps_without_requests(self) -> None:
        started = time.monotonic()
        self.assertFalse(CaptureRequests().wait(0.1))
        self.assertGreaterEqual(time.monotonic() - started, 0.09)

    def test_request_during_a_capture_waits_for_the_next_one(self) -> None:
        captures = CaptureRequests()
        captures.take()  # a scheduled picture is being taken
        out = {}
        t = threading.Thread(target=lambda: out.update(captures.request(2)))
        t.start()
        self.assertTrue(captures.wait(1))
        captures.finish({"imgId": 1})  # the scheduled one: not ours
        self.assertTrue(t.is_alive())
        captures.take()
        captures.finish({"imgId": 2})
        t.join(1)
        self.assertEqual(out, {"imgId": 2})

    def test_timeout_and_error(self) -> None:
        captures = CaptureRequests()
        with self.assertRaises(TimeoutError):
            captures.request(0.05)
        self.assertFalse(captures.wait(0), "a timed-out request is withdrawn")
        t = threading.Thread(target=lambda: (captures.wait(1), captures.take(), captures.finish(error="upload failed")))
        t.start()
        with self.assertRaisesRegex(RuntimeError, "upload failed"):
            captures.request(2)


class HostInfoTests(unittest.TestCase):
    def test_host_info_never_raises(self) -> None:
        info = device_agent.host_info("svc")
        self.assertEqual(info["service"], "svc")
        self.assertIn("hostname", info)


if __name__ == "__main__":
    unittest.main()
