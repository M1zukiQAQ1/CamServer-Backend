"""Drive the enclosure heater line LOW. Run by systemd after the capture program has stopped.

The device agent holds the heater GPIO while the program runs. When the program exits, the
kernel leaves the line an output at its last level, so a heater that was on stays on with nothing
regulating it. A systemd drop-in runs this file as ``ExecStopPost=``, which covers a clean stop, a
crash and a kill alike:

    [Service]
    ExecStopPost=/usr/bin/python3 /home/pi/allSkyCamera/heater_off.py

On a restart the heater is therefore off for the few seconds until the new program claims the line
again at its saved mode (heater-state.json is not touched here).

Uses the same DEVICE_AGENT_HEATER_PIN / DEVICE_AGENT_HEATER_CHIP as the agent. Always exits 0: a
failure here must not mark the service failed or block its restart. Standard library only.
"""

from __future__ import annotations

import os
import sys
import time


def heater_off(line_factory=None, attempts: int = 5, delay: float = 0.5) -> str:
    """Claims the line LOW and returns what happened. Retries while the dying program still holds it."""
    pin = os.environ.get("DEVICE_AGENT_HEATER_PIN", "26").strip()
    if not pin or pin.lower() == "none":
        return "no heater configured"
    if line_factory is None:
        from device_agent import GpioLine
        line_factory = GpioLine
    chip = os.environ.get("DEVICE_AGENT_HEATER_CHIP", "pinctrl-rp1")
    error: Exception | None = None
    for attempt in range(attempts):
        if attempt:
            time.sleep(delay)
        try:
            line = line_factory(int(pin), chip, consumer="heater-off", initial=False)
            line.set(False)
            return f"GPIO {pin} driven low (heater off), pin reads {'HIGH' if line.get() else 'low'}"
        except Exception as e:  # EBUSY while the old process is still going away, no chip, bad pin ...
            error = e
    return f"could not drive GPIO {pin} low: {type(error).__name__}: {error}"


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        print("heater_off:", heater_off(), flush=True)
    except Exception as e:
        print("heater_off: failed:", e, flush=True)
    sys.exit(0)
