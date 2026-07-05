#!/usr/bin/env python3
"""Valve-Guard alert bridge: MQTT events -> ntfy push notifications.

Also watches retained reports and warns when a zone that has reported before
goes quiet for --quiet-hours (dead device, WiFi rot, or changed schedule).

Usage:
    pip install paho-mqtt requests
    python alert_bridge.py --broker localhost --ntfy-topic my-secret-topic
Subscribe your phone to https://ntfy.sh/<topic> with the ntfy app.
"""
import argparse
import json
import threading
import time

import paho.mqtt.client as mqtt
import requests

FRIENDLY = {
    "trip": ("Zone shut off - possible broken head", "rotating_light"),
    "stuck_valve": ("Valve stuck open - needs manual attention", "rotating_light"),
    "persistent_trip": ("Zone tripping every cycle - repair needed", "warning"),
    "selftest_fail": ("Self-test failed - check sensor/relay", "warning"),
    "relearned": ("Baseline relearned", "information_source"),
    "fill_timeout": ("Flow never settled during fill", "warning"),
    "would_trip": ("LOG mode: would have tripped", "eyes"),
    "learned": ("Learning cycle stored", "seedling"),
    "selftest_ok": ("Self-test passed", "white_check_mark"),
}
QUIET_TYPES = {"learned", "selftest_ok"}  # log-only, no push

last_seen: dict[str, float] = {}
zone_names: dict[str, str] = {}


def notify(args, title: str, body: str, tags: str, priority: str = "default"):
    try:
        requests.post(
            f"{args.ntfy_server}/{args.ntfy_topic}",
            data=body.encode(),
            headers={"Title": title, "Tags": tags, "Priority": priority},
            timeout=10,
        )
    except requests.RequestException as e:
        print(f"[bridge] ntfy send failed: {e}")


def on_message(client, args, msg):
    try:
        doc = json.loads(msg.payload)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return
    dev = doc.get("id", "?")
    zone = doc.get("zone", dev)
    zone_names[dev] = zone

    if msg.topic.endswith("/report"):
        last_seen[dev] = time.time()
        print(f"[report] {zone}: {json.dumps(doc)}")
        return

    etype = doc.get("type", "unknown")
    title, tags = FRIENDLY.get(etype, (f"Event: {etype}", "grey_question"))
    detail = doc.get("detail", "")
    print(f"[event] {zone}: {etype} - {detail}")
    if etype not in QUIET_TYPES:
        prio = "high" if etype in ("trip", "stuck_valve", "persistent_trip") else "default"
        notify(args, f"{zone}: {title}", detail, tags, prio)


def quiet_watchdog(args):
    """Alert once when a known zone hasn't reported in --quiet-hours."""
    alerted: set[str] = set()
    while True:
        time.sleep(600)
        now = time.time()
        for dev, ts in last_seen.items():
            quiet = now - ts > args.quiet_hours * 3600
            if quiet and dev not in alerted:
                alerted.add(dev)
                notify(args, f"{zone_names.get(dev, dev)}: no report in {args.quiet_hours}h",
                       "Device offline, WiFi problem, or schedule changed.", "zzz")
            elif not quiet:
                alerted.discard(dev)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--broker", default="localhost")
    p.add_argument("--port", type=int, default=1883)
    p.add_argument("--ntfy-server", default="https://ntfy.sh")
    p.add_argument("--ntfy-topic", required=True)
    p.add_argument("--quiet-hours", type=float, default=48)
    args = p.parse_args()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata=args)
    client.user_data_set(args)
    client.on_message = on_message
    client.connect(args.broker, args.port)
    client.subscribe("valveguard/#")

    threading.Thread(target=quiet_watchdog, args=(args,), daemon=True).start()
    print(f"[bridge] forwarding valveguard/# on {args.broker} -> ntfy.sh/{args.ntfy_topic}")
    client.loop_forever()


if __name__ == "__main__":
    main()
