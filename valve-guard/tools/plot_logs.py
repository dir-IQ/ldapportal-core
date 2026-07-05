#!/usr/bin/env python3
"""Phase-5 analysis: plot Valve-Guard RMS logs and check break separation.

Input: a log captured with
    mosquitto_sub -h <hub> -t 'valveguard/#' -v | ts '%s' >> log.txt
(each line: "<epoch> <topic> <json>"; plain `mosquitto_sub -v` output without
timestamps also works, plotted against line number instead of time).

Usage:
    pip install matplotlib
    python plot_logs.py log.txt [--device vg1A2B]

The go/no-go question this answers: is there >= 6 dB of clear air between
normal steady-state rms_db and the simulated-break cycles?
"""
import argparse
import json
import sys
from collections import defaultdict

import matplotlib.pyplot as plt


def parse(path, only_device=None):
    series = defaultdict(lambda: {"t": [], "rms": [], "base": [], "events": []})
    with open(path) as f:
        for i, line in enumerate(f):
            parts = line.strip().split(None, 2)
            if len(parts) < 2:
                continue
            # with `ts` prefix: [epoch, topic, json]; without: [topic, json]
            if parts[0].lstrip("-").isdigit() and len(parts) == 3:
                t, topic, payload = float(parts[0]), parts[1], parts[2]
            else:
                t, topic = float(i), parts[0]
                payload = parts[1] if len(parts) == 2 else parts[1] + " " + parts[2]
            if not topic.startswith("valveguard/"):
                continue
            dev = topic.split("/")[1]
            if only_device and dev != only_device:
                continue
            try:
                doc = json.loads(payload)
            except json.JSONDecodeError:
                continue
            s = series[dev]
            if topic.endswith("/report") and "rms_db" in doc:
                s["t"].append(t)
                s["rms"].append(doc["rms_db"])
                s["base"].append(doc.get("base_db"))
            elif topic.endswith("/event"):
                s["events"].append((t, doc.get("type", "?")))
    return series


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile")
    ap.add_argument("--device", help="only this device id")
    args = ap.parse_args()

    series = parse(args.logfile, args.device)
    if not series:
        sys.exit("no valveguard messages found in log")

    fig, axes = plt.subplots(len(series), 1, figsize=(12, 4 * len(series)), squeeze=False)
    for ax, (dev, s) in zip(axes.flat, sorted(series.items())):
        ax.plot(s["t"], s["rms"], marker=".", lw=1, label="rms_db")
        if any(b is not None for b in s["base"]):
            ax.plot(s["t"], s["base"], ls="--", label="baseline")
        for t, etype in s["events"]:
            ax.axvline(t, color="red" if "trip" in etype else "orange", alpha=0.5)
            ax.text(t, ax.get_ylim()[1], etype, rotation=90, fontsize=7, va="top")
        ax.set_title(f"{dev}  ({len(s['rms'])} reports, {len(s['events'])} events)")
        ax.set_ylabel("dB")
        ax.legend(loc="lower right")
        ax.grid(alpha=0.3)

        rms = [r for r in s["rms"] if r is not None]
        if rms:
            lo, hi = min(rms), max(rms)
            print(f"{dev}: rms range {lo:.1f} .. {hi:.1f} dB (spread {hi - lo:.1f} dB)")
    print("go/no-go: simulated-break cycles should sit >= 6 dB above normal steady state")

    plt.tight_layout()
    out = args.logfile.rsplit(".", 1)[0] + ".png"
    plt.savefig(out, dpi=120)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
