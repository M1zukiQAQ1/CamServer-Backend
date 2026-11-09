"""Remote-control agent for a camera host (Raspberry Pi), run as a thread inside the capture program.

Every few seconds it posts a heartbeat to the backend (POST /api/devices/heartbeat) with the
host's sensors and system information; the reply carries commands an admin queued in the admin
panel (/admin → Cameras). The Pi only calls out, the same way it uploads frames.

Commands:
  CAPTURE          take a picture now (only when the program passes ``capture``)
  RESTART_SERVICE  restart the capture program (``restart`` callable or a systemd unit)
  REBOOT           reboot the Pi
  SHUTDOWN         power the Pi off (it stays off until someone power-cycles it)
  HEATER           set the enclosure heater: {"mode": "AUTO"|"ON"|"OFF", "target": °C}

The heater board is switched by one GPIO line (BCM 26 on the Pi 5's RP1, HIGH = on), driven
through the kernel's GPIO character device. In AUTO a thermostat on the enclosure AHT20 keeps it
near the target; ON/OFF hold until changed. The mode survives restarts (heater-state.json).
HEATER is only offered when the line could be claimed.

Configuration (environment, e.g. a systemd EnvironmentFile readable only by the service):
  DEVICE_AGENT_TOKEN     this host's token; else read from DEVICE_AGENT_TOKEN_FILE (default:
                         device-agent.token next to this file). No token = agent off
  DEVICE_AGENT_BACKEND   backend base URL (default https://armageddon.deepspace.ucsb.edu)
  DEVICE_AGENT_INTERVAL  seconds between heartbeats (default 5)
  DEVICE_AGENT_DRY_RUN   1 = log REBOOT/SHUTDOWN/RESTART_SERVICE instead of running them
  DEVICE_AGENT_INSECURE  1 = skip TLS certificate verification
  DEVICE_AGENT_HEATER_PIN    heater GPIO line offset (default 26; empty or "none" = no heater)
  DEVICE_AGENT_HEATER_CHIP   label of the GPIO chip (default pinctrl-rp1)
  DEVICE_AGENT_HEATER_STATE  where the mode/target are kept (default heater-state.json next to this file)

Nothing in here may take the capture program down: every step is guarded and the thread loops
forever. Standard library only, so it runs under the system python3 as well as a venv.
"""

from __future__ import annotations

import fcntl
import glob
import json
import logging
import os
import platform
import shutil
import signal
import socket
import ssl
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Callable

VERSION = "2026-10-07"
log = logging.getLogger("device_agent")

CAPTURE = "CAPTURE"
RESTART_SERVICE = "RESTART_SERVICE"
REBOOT = "REBOOT"
SHUTDOWN = "SHUTDOWN"
HEATER = "HEATER"
HEATER_MODES = ("AUTO", "ON", "OFF")


def _read(path: str) -> str | None:
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return None


TOKEN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "device-agent.token")


def _token_from_file() -> str:
    """Fallback when DEVICE_AGENT_TOKEN is unset: device-agent.token next to this file (mode 600)."""
    return (_read(os.environ.get("DEVICE_AGENT_TOKEN_FILE", TOKEN_FILE)) or "").strip()


def host_info(service: str | None = None) -> dict[str, Any]:
    """System facts for the panel. Each field is independent; a failing probe just leaves it out."""
    info: dict[str, Any] = {"hostname": socket.gethostname()}
    try:
        out = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=3).stdout
        info["ips"] = [ip for ip in out.split() if ":" not in ip or not ip.startswith("fe80")]
    except Exception:
        pass
    uptime = _read("/proc/uptime")
    if uptime:
        info["uptimeSeconds"] = round(float(uptime.split()[0]))
    load = _read("/proc/loadavg")
    if load:
        info["load"] = [float(x) for x in load.split()[:3]]
    info["cpus"] = os.cpu_count()
    meminfo = _read("/proc/meminfo")
    if meminfo:
        fields = {line.split(":")[0]: int(line.split()[1]) * 1024 for line in meminfo.splitlines() if line.split()[1:2]}
        if "MemTotal" in fields:
            info["memoryTotal"] = fields["MemTotal"]
            info["memoryAvailable"] = fields.get("MemAvailable")
    try:
        disk = shutil.disk_usage("/")
        info["diskTotal"], info["diskFree"] = disk.total, disk.free
    except OSError:
        pass
    temp = _read("/sys/class/thermal/thermal_zone0/temp")
    if temp:
        try:
            info["cpuTemperature"] = round(int(temp) / 1000, 1)
        except ValueError:
            pass
    throttled = _read("/sys/devices/platform/soc/soc:firmware/get_throttled")
    if throttled:
        info["throttled"] = throttled.strip()
    os_release = _read("/etc/os-release") or ""
    for line in os_release.splitlines():
        if line.startswith("PRETTY_NAME="):
            info["os"] = line.split("=", 1)[1].strip('"')
    info["kernel"] = platform.release()
    info["python"] = platform.python_version()
    if service:
        info["service"] = service
    return info


def _iowr(nr: int, size: int) -> int:
    return (3 << 30) | (size << 16) | (0xB4 << 8) | nr


class GpioLine:
    """One output line through the GPIO character device (uapi v2, linux/gpio.h), standard library only.

    The request is held while this object lives. When the process exits the kernel releases the
    line but leaves it an output at its last level (checked on the RP1), so the heater keeps its
    state across a program restart until the next agent claims it again.
    """

    _CHIP_INFO = (2 << 30) | (68 << 16) | (0xB4 << 8) | 0x01  # _IOR(0xB4, 0x01, struct gpiochip_info)
    _GET_LINE = _iowr(0x07, 592)    # struct gpio_v2_line_request
    _GET_VALUES = _iowr(0x0E, 16)   # struct gpio_v2_line_values
    _SET_VALUES = _iowr(0x0F, 16)
    _FLAG_OUTPUT = 1 << 3
    _ATTR_OUTPUT_VALUES = 2

    def __init__(self, offset: int, chip_label: str = "pinctrl-rp1", consumer: str = "heater", initial: bool = False):
        self.offset = offset
        self.chip = self._find_chip(chip_label)
        request = bytearray(592)
        struct.pack_into("I", request, 0, offset)                      # offsets[0]
        request[256:256 + len(consumer[:31])] = consumer[:31].encode()  # consumer
        struct.pack_into("Q", request, 288, self._FLAG_OUTPUT)          # config.flags
        struct.pack_into("I", request, 296, 1)                          # config.num_attrs
        struct.pack_into("IIQQ", request, 320, self._ATTR_OUTPUT_VALUES, 0, int(initial), 1)
        struct.pack_into("I", request, 560, 1)                          # num_lines
        chip_fd = os.open(self.chip, os.O_RDWR)
        try:
            reply = fcntl.ioctl(chip_fd, self._GET_LINE, bytes(request))
        finally:
            os.close(chip_fd)
        self.fd = struct.unpack_from("i", reply, 588)[0]

    @classmethod
    def _find_chip(cls, label: str) -> str:
        for path in sorted(glob.glob("/dev/gpiochip*")):
            try:
                fd = os.open(path, os.O_RDONLY)
            except OSError:
                continue
            try:
                info = fcntl.ioctl(fd, cls._CHIP_INFO, bytes(68))
            except OSError:
                continue
            finally:
                os.close(fd)
            if info[32:64].rstrip(b"\0").decode() == label:
                return path
        raise FileNotFoundError(f"no accessible GPIO chip labelled {label!r}")

    def set(self, on: bool) -> None:
        fcntl.ioctl(self.fd, self._SET_VALUES, struct.pack("QQ", int(on), 1))

    def get(self) -> bool:
        return bool(struct.unpack("QQ", fcntl.ioctl(self.fd, self._GET_VALUES, struct.pack("QQ", 0, 1)))[0] & 1)


class Heater:
    """Enclosure heater: AUTO (thermostat on the AHT20), ON or OFF, on its own thread.

    AUTO switches on below ``target - hysteresis`` and off above ``target + hysteresis`` and keeps
    each state at least ``min_dwell`` seconds, so the relay does not chatter; it turns off at once
    when the temperature cannot be read. Manual changes apply immediately. Mode and target are
    written to ``state_file`` and restored on start (first start: OFF, 24 °C).
    """

    MIN_TARGET, MAX_TARGET = -30.0, 60.0

    def __init__(self, line: Any, read_temperature: Callable[[], float | None], state_file: str, *,
                 interval: float = 5.0, hysteresis: float = 0.5, min_dwell: float = 60.0,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self.line = line
        self.read_temperature = read_temperature
        self.state_file = state_file
        self.interval = interval
        self.hysteresis = hysteresis
        self.min_dwell = min_dwell
        self.clock = clock
        self.mode, self.target = "OFF", 24.0
        saved = _read(state_file)
        if saved:
            try:
                state = json.loads(saved)
                self.mode, self.target = self.validate(state.get("mode"), state.get("target", self.target))
            except (ValueError, TypeError, AttributeError) as e:
                log.warning("ignoring %s: %s", state_file, e)
        self.on = False
        self.switched_at: float | None = None
        self.temperature: float | None = None
        self.error: str | None = None
        self.changed_at = time.time()
        self._lock = threading.Lock()

    @classmethod
    def validate(cls, mode: Any, target: Any) -> tuple[str, float]:
        mode = str(mode).upper()
        if mode not in HEATER_MODES:
            raise ValueError(f"mode must be one of {', '.join(HEATER_MODES)}")
        target = float(target)
        if not cls.MIN_TARGET <= target <= cls.MAX_TARGET:
            raise ValueError(f"target must be between {cls.MIN_TARGET:g} and {cls.MAX_TARGET:g} °C")
        return mode, round(target, 1)

    def start(self) -> "Heater":
        self.tick()
        threading.Thread(target=self._run, name="heater", daemon=True).start()
        log.info("heater on GPIO %s: %s, target %.1f °C", getattr(self.line, "offset", "?"), self.mode, self.target)
        return self

    def _run(self) -> None:
        while True:
            time.sleep(self.interval)
            try:
                self.tick()
            except Exception as e:  # never let the thermostat thread die
                log.warning("heater: %s", e)

    def set(self, mode: Any, target: Any = None) -> str:
        with self._lock:
            mode, target = self.validate(mode, self.target if target is None else target)
            self.mode, self.target = mode, target
            self.changed_at = time.time()
            tmp = self.state_file + ".tmp"
            with open(tmp, "w") as f:
                json.dump({"mode": mode, "target": target}, f)
            os.replace(tmp, self.state_file)
        self.tick(manual=True)
        return f"heater {mode}" + (f", target {target:.1f} °C" if mode == "AUTO" else "") + f"; now {'on' if self.on else 'off'}"

    def tick(self, manual: bool = False) -> None:
        with self._lock:
            want = self.on
            if self.mode == "ON":
                want, self.error = True, None
            elif self.mode == "OFF":
                want, self.error = False, None
            else:
                try:
                    temperature = self.read_temperature()
                    if temperature is None:
                        raise ValueError("no enclosure temperature")
                    self.temperature, self.error = float(temperature), None
                except Exception as e:
                    self.temperature, self.error = None, f"thermostat off: {type(e).__name__}: {e}"
                    want, manual = False, True  # fail safe, without waiting out the dwell
                else:
                    if self.temperature < self.target - self.hysteresis:
                        want = True
                    elif self.temperature > self.target + self.hysteresis:
                        want = False
            now = self.clock()
            dwelling = self.switched_at is not None and now - self.switched_at < self.min_dwell
            if self.switched_at is None or (want != self.on and (manual or not dwelling)):
                self.line.set(want)
                self.on, self.switched_at = want, now
                log.info("heater %s (%s%s)", "on" if want else "off", self.mode,
                         f", {self.temperature:.1f} °C" if self.mode == "AUTO" and self.temperature is not None else "")

    def report(self) -> dict[str, Any]:
        with self._lock:
            body: dict[str, Any] = {"mode": self.mode, "target": self.target, "on": self.on,
                                    "pin": getattr(self.line, "offset", None), "hysteresis": self.hysteresis,
                                    "changedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.changed_at))}
            if self.switched_at is not None:
                body["switchedSecondsAgo"] = round(self.clock() - self.switched_at)
            if self.error:
                body["error"] = self.error
        try:
            body["pinHigh"] = self.line.get()
        except Exception as e:
            body["pinHigh"], body["error"] = None, f"cannot read the pin: {e}"
        return body


HEATER_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "heater-state.json")


def heater_from_env(read_temperature: Callable[[], float | None]) -> Heater | None:
    """The heater on DEVICE_AGENT_HEATER_PIN (default 26), or None when off or the line is unavailable."""
    pin = os.environ.get("DEVICE_AGENT_HEATER_PIN", "26").strip()
    if not pin or pin.lower() == "none":
        return None
    state_file = os.environ.get("DEVICE_AGENT_HEATER_STATE", HEATER_STATE_FILE)
    try:
        saved_on = json.loads(_read(state_file) or "{}").get("mode") == "ON"
    except (ValueError, AttributeError):
        saved_on = False
    try:  # claimed at the saved level, so a restart does not flick a heater that is ON
        line = GpioLine(int(pin), os.environ.get("DEVICE_AGENT_HEATER_CHIP", "pinctrl-rp1"), initial=saved_on)
    except Exception as e:
        log.warning("no heater: cannot claim GPIO %s: %s", pin, e)
        return None
    return Heater(line, read_temperature, state_file)


class DeviceAgent:
    """Heartbeat thread. Construct, then ``start()``; it never raises into the caller."""

    def __init__(
        self,
        *,
        sensors: Callable[[], dict[str, Any]] | None = None,
        status: Callable[[], dict[str, Any]] | None = None,
        capture: Callable[[float], dict[str, Any]] | None = None,
        restart: Callable[[], None] | None = None,
        service: str | None = None,
        power_prefix: list[str] | None = None,
        capture_timeout: float = 180.0,
        heater: Heater | None | bool = True,
    ) -> None:
        """
        :param sensors: returns e.g. {"temperature": 21.3, "humidity": 40.2} (°C, %RH)
        :param status: returns program state shown as-is (camera id, last capture, exposure ...)
        :param capture: takes a timeout and blocks until the picture is uploaded; returns the
                        result fields (e.g. {"imgId": 123}) or raises
        :param restart: restarts the program; default ``systemctl restart <service>``
        :param service: systemd unit of the program, shown in the panel and used by the default restart
        :param power_prefix: put before ``systemctl reboot|poweroff`` (e.g. ["sudo", "-n"] when not root)
        :param heater: a ``Heater``; True = from the environment on ``start()`` (see ``heater_from_env``);
                       None/False = none
        """
        self.token = os.environ.get("DEVICE_AGENT_TOKEN", "").strip() or _token_from_file()
        self.backend = os.environ.get("DEVICE_AGENT_BACKEND", "https://armageddon.deepspace.ucsb.edu").rstrip("/")
        self.interval = float(os.environ.get("DEVICE_AGENT_INTERVAL", "5"))
        self.dry_run = os.environ.get("DEVICE_AGENT_DRY_RUN", "") == "1"
        self.ssl = ssl.create_default_context()
        if os.environ.get("DEVICE_AGENT_INSECURE", "") == "1":
            self.ssl = ssl._create_unverified_context()
        self.sensors = sensors
        self.status = status
        self.capture = capture
        self.restart = restart
        self.service = service
        if power_prefix is None:
            power_prefix = [] if os.geteuid() == 0 else ["sudo", "-n"]
        self.power_prefix = list(power_prefix)
        self.capture_timeout = capture_timeout
        self.heater: Heater | None = heater if isinstance(heater, Heater) else None
        self._heater_from_env = heater is True
        self.started = time.time()
        self._results: list[dict[str, Any]] = []
        self._seen: set[str] = set()
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._thread: threading.Thread | None = None
        self.last_error: str | None = None

    # ---- public ---------------------------------------------------------------------------

    def capabilities(self) -> list[str]:
        caps = [RESTART_SERVICE, REBOOT, SHUTDOWN]
        if self.restart is None and not self.service:
            caps.remove(RESTART_SERVICE)
        if self.heater is not None:
            caps.append(HEATER)
        return ([CAPTURE] if self.capture else []) + caps

    def start(self) -> "DeviceAgent":
        # The thermostat runs even without a token or backend: AUTO must keep regulating.
        if self._heater_from_env:
            self._heater_from_env = False
            self.heater = heater_from_env(self._enclosure_temperature)
        if self.heater is not None:
            try:
                self.heater.start()
            except Exception as e:
                log.warning("heater failed to start: %s", e)
                self.heater = None
        if not self.token:
            log.warning("DEVICE_AGENT_TOKEN is not set; remote control is off")
            return self
        self._thread = threading.Thread(target=self._run, name="device-agent", daemon=True)
        self._thread.start()
        log.info("device agent %s reporting to %s every %.0f s%s", VERSION, self.backend, self.interval,
                 " (dry run)" if self.dry_run else "")
        return self

    def _enclosure_temperature(self) -> float | None:
        reading = self.sensors() if self.sensors else {}
        value = reading.get("temperature") if isinstance(reading, dict) else None
        return float(value) if isinstance(value, (int, float)) else None

    # ---- loop -----------------------------------------------------------------------------

    def _run(self) -> None:
        backoff = self.interval
        while True:
            try:
                commands = self._heartbeat()
                self.last_error = None
                backoff = self.interval
                for command in commands:
                    self._dispatch(command)
            except Exception as e:  # network down, backend restarting, bad reply ...
                if str(e) != self.last_error:
                    log.warning("heartbeat failed: %s", e)
                self.last_error = str(e)
                backoff = min(backoff * 2, 60)
            self._wake.wait(backoff)
            self._wake.clear()

    def report(self) -> dict[str, Any]:
        body: dict[str, Any] = {
            "agentVersion": VERSION,
            "capabilities": self.capabilities(),
            "processUptimeSeconds": round(time.time() - self.started),
            "dryRun": self.dry_run,
        }
        try:
            body["host"] = host_info(self.service)
        except Exception as e:
            body["host"] = {"error": str(e)}
        for key, source in (("sensors", self.sensors), ("status", self.status),
                            ("heater", self.heater.report if self.heater else None)):
            if source is None:
                continue
            try:
                body[key] = source()
            except Exception as e:
                body[key] = {"error": f"{type(e).__name__}: {e}"}
        with self._lock:
            body["results"], self._results = self._results, []
        return body

    def _heartbeat(self) -> list[dict[str, Any]]:
        body = self.report()
        try:
            reply = self._post("/api/devices/heartbeat", body)
        except Exception:
            with self._lock:  # hand the results back for the next try
                self._results = body["results"] + self._results
            raise
        return [c for c in reply.get("commands", []) if isinstance(c, dict)]

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        request = urllib.request.Request(
            self.backend + path,
            data=json.dumps(body, default=str).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.token}"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=10, context=self.ssl) as response:
                return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"HTTP {e.code} {e.read()[:200]!r}") from None

    def _result(self, command_id: str, ok: bool, message: str, running: bool = False, **fields: Any) -> None:
        with self._lock:
            self._results.append({"id": command_id, "ok": ok, "message": message, "running": running, **fields})
        self._wake.set()

    # ---- commands -------------------------------------------------------------------------

    def _dispatch(self, command: dict[str, Any]) -> None:
        command_id, kind = str(command.get("id")), command.get("type")
        if command_id in self._seen:
            return
        self._seen.add(command_id)
        log.warning("remote command %s (%s)", kind, command_id)
        if kind == CAPTURE and self.capture:
            threading.Thread(target=self._do_capture, args=(command_id,), name="device-agent-capture", daemon=True).start()
        elif kind == RESTART_SERVICE:
            self._power(command_id, "restarting the capture program", self._restart_program)
        elif kind == REBOOT:
            self._power(command_id, "rebooting", lambda: self._systemctl("reboot"))
        elif kind == SHUTDOWN:
            self._power(command_id, "powering off", lambda: self._systemctl("poweroff"))
        elif kind == HEATER and self.heater is not None:
            try:
                self._result(command_id, True, self.heater.set(command.get("mode"), command.get("target")),
                             heater=self.heater.report())
            except Exception as e:
                self._result(command_id, False, f"{type(e).__name__}: {e}")
        else:
            self._result(command_id, False, f"unsupported command {kind!r}")

    def _do_capture(self, command_id: str) -> None:
        self._result(command_id, True, "queued; the picture starts once the current step finishes", running=True)
        try:
            fields = self.capture(self.capture_timeout) or {}
            self._result(command_id, True, fields.pop("message", "picture uploaded"), **fields)
        except Exception as e:
            self._result(command_id, False, f"{type(e).__name__}: {e}")

    def _power(self, command_id: str, what: str, action: Callable[[], None]) -> None:
        """Tells the backend it is about to happen (afterwards nobody can), then does it."""
        if self.dry_run:
            self._result(command_id, True, f"dry run: would be {what}")
            return
        self._result(command_id, True, what)
        try:
            self._post("/api/devices/heartbeat", self.report())
        except Exception as e:
            log.warning("could not report before %s: %s", what, e)
        try:
            action()
        except Exception as e:
            self._result(command_id, False, f"{what} failed: {type(e).__name__}: {e}")

    def _restart_program(self) -> None:
        if self.restart is not None:
            self.restart()
        else:
            # --no-block: systemd stops this very process as part of the job.
            self._run_checked(self.power_prefix + ["systemctl", "--no-block", "restart", self.service])

    def _systemctl(self, verb: str) -> None:
        self._run_checked(self.power_prefix + ["systemctl", verb])

    @staticmethod
    def _run_checked(argv: list[str]) -> None:
        done = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        if done.returncode != 0:
            raise RuntimeError(f"{' '.join(argv)} exited {done.returncode}: {(done.stderr or done.stdout).strip()[:300]}")


def restart_by_sigterm() -> None:
    """Restart for a unit with Restart=always: end this process cleanly and let systemd start it again."""
    os.kill(os.getpid(), signal.SIGTERM)


class CaptureRequests:
    """Hands "take a picture now" from the agent thread to a capture loop.

    The loop calls ``wait(seconds)`` instead of sleeping, which returns early when a picture is
    requested, then ``take()`` before exposing and ``finish(...)`` after the upload.
    """

    def __init__(self) -> None:
        self._cond = threading.Condition()
        self._pending: list[dict[str, Any]] = []
        self._in_progress: list[dict[str, Any]] = []

    def request(self, timeout: float) -> dict[str, Any]:
        slot: dict[str, Any] = {"done": False}
        with self._cond:
            self._pending.append(slot)
            self._cond.notify_all()
            if not self._cond.wait_for(lambda: slot["done"], timeout):
                self._pending = [s for s in self._pending if s is not slot]
                raise TimeoutError(f"no picture within {timeout:.0f} s (night exposures can take 40 s plus auto-exposure)")
        if slot.get("error"):
            raise RuntimeError(slot["error"])
        return slot["result"]

    def wait(self, seconds: float) -> bool:
        """Sleeps up to ``seconds``; True when a picture was requested."""
        with self._cond:
            return self._cond.wait_for(lambda: bool(self._pending), max(0.0, seconds))

    def take(self) -> int:
        """Claims the waiting requests for the picture about to be taken."""
        with self._cond:
            self._in_progress, self._pending = self._in_progress + self._pending, []
            return len(self._in_progress)

    def finish(self, result: dict[str, Any] | None = None, error: str | None = None) -> None:
        with self._cond:
            for slot in self._in_progress:
                slot.update(done=True, result=dict(result or {}), error=error)
            self._in_progress = []
            self._cond.notify_all()
