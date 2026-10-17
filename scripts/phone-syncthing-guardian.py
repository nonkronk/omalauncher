#!/usr/bin/env python3
"""OmaLauncher Arch Syncthing watchdog, running *on the phone* without ADB.

Only supervises the existing local Syncthing process. No inbound network listener,
file copying, config rewriting, or app shutdown. It depends on OmaLauncher's
existing Linux foreground session remaining alive.
"""
import fcntl
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

HOME = Path("/root/.local/state/syncthing")
CONFIG = HOME / "config.xml"
LOCK = HOME / "guardian.lock"
LOG = HOME / "guardian.log"
DAEMON_LOG = HOME / "service.log"
DISABLED = HOME / "guardian.disabled"
INTERVAL = 120
FAILURES_TO_RECOVER = 3
COOLDOWN = 600

def log(message):
    stamp = time.strftime("%Y-%m-%d %H:%M:%S %Z")
    text = f"{stamp} {message}"
    print(text, flush=True)
    try:
        if LOG.exists() and LOG.stat().st_size > 1048576:
            LOG.replace(HOME / "guardian.log.previous")
        with LOG.open("a") as output:
            output.write(text + "\n")
    except OSError:
        pass

def run(args, timeout=10):
    return subprocess.run(args, text=True, capture_output=True, timeout=timeout)

def processes():
    result = run(["pgrep", "-x", "syncthing"], timeout=5)
    return [int(s) for s in result.stdout.split() if s.isdigit()]

def mac_peer():
    root = ET.parse(CONFIG).getroot()
    for device in root.findall("device"):
        if device.get("name") != "m4.local":
            continue
        for element in device.findall("address"):
            address = element.text or ""
            if address.startswith(("tcp4://", "tcp://")):
                parsed = urllib.parse.urlparse(address)
                if parsed.hostname and parsed.port:
                    return device.attrib["id"], parsed.hostname, parsed.port
    raise RuntimeError("Configured Mac Tailscale address unavailable; no network-triggered restart")

def connection(device_id):
    try:
        response = run(["syncthing", "cli", f"--home={HOME}", "show", "connections"], timeout=10)
        if response.returncode:
            return None
        return bool(json.loads(response.stdout)["connections"][device_id]["connected"])
    except (KeyError, ValueError, subprocess.TimeoutExpired):
        return None

def reachable(host, port):
    try:
        with socket.create_connection((host, port), timeout=4):
            return True
    except OSError:
        return False

def stop_existing():
    # Prefer graceful Syncthing shutdown, including for the older adb-started daemon.
    try:
        run(["syncthing", "cli", f"--home={HOME}", "operations", "shutdown"], timeout=12)
    except subprocess.TimeoutExpired:
        pass
    for _ in range(12):
        if not processes():
            return True
        time.sleep(1)
    # Do not use SIGKILL: politely terminate only Syncthing processes owned by this app UID.
    for pid in processes():
        try:
            if Path(f"/proc/{pid}/status").read_text().find(f"Uid:\t{os.getuid()}") >= 0:
                os.kill(pid, signal.SIGTERM)
        except (OSError, PermissionError):
            pass
    for _ in range(10):
        if not processes():
            return True
        time.sleep(1)
    log("Existing daemon still alive; refusing to create a second database owner")
    return False

def start_daemon():
    if processes():
        return False
    with DAEMON_LOG.open("a") as output:
        child = subprocess.Popen(
            ["syncthing", "serve", f"--home={HOME}", "--no-browser", "--no-restart"],
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    log(f"Started supervised Syncthing, pid={child.pid}")
    return True

def main():
    HOME.mkdir(parents=True, exist_ok=True)
    with LOCK.open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Guardian already running")
            return 0
        log("Phone-local guardian started (ADB not needed for monitoring)")
        misses = 0
        last_restart = 0.0
        last_state = ""
        once = "--once" in sys.argv
        while True:
            try:
                if DISABLED.exists():
                    state = "Guardian paused by marker file"
                    misses = 0
                elif not CONFIG.exists():
                    state = "Syncthing config missing; waiting"
                    misses = 0
                elif not processes():
                    state = "Syncthing exited; starting"
                    if time.monotonic() - last_restart >= COOLDOWN or last_restart == 0:
                        if not once and start_daemon():
                            last_restart = time.monotonic()
                    misses = 0
                else:
                    device_id, host, port = mac_peer()
                    conn = connection(device_id)
                    if conn is True:
                        misses = 0
                        state = "Mac connected"
                    else:
                        misses += 1
                        if misses < FAILURES_TO_RECOVER:
                            state = f"Mac disconnected, observing ({misses}/{FAILURES_TO_RECOVER})"
                        elif not reachable(host, port):
                            state = "Mac/Tailscale transport unreachable; retaining existing daemon"
                        elif time.monotonic() - last_restart < COOLDOWN:
                            state = "Peer disconnected; recovery cooldown"
                        else:
                            state = "Mac reachable but Syncthing peer disconnected; recovering"
                            if not once and stop_existing() and start_daemon():
                                last_restart = time.monotonic()
                            misses = 0
                if state != last_state:
                    log(state)
                    last_state = state
            except Exception as exc:
                if last_state != f"error:{type(exc).__name__}:{str(exc)}":
                    log(f"Guardian check failed: {type(exc).__name__}: {exc}")
                    last_state = f"error:{type(exc).__name__}:{str(exc)}"
            if once:
                return 0
            time.sleep(INTERVAL)

if __name__ == "__main__":
    sys.exit(main())
