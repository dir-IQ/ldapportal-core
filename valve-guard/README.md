# Valve-Guard — acoustic broken-sprinkler detector ($40 prototype)

A per-zone device that lives in the irrigation valve box, powers itself from the
24 VAC solenoid wiring, listens to flow noise through the valve body, learns the
zone's normal signature, and cuts the solenoid for the rest of the cycle when it
hears a big break (sheared head, burst lateral). Alerts arrive on your phone via
MQTT → ntfy. No batteries, no pipe cutting, tool-free install.

> **Note:** this folder is a standalone hardware side project and is unrelated to
> the ldapportal codebase. It lives here for convenience only.

---

## Scope & safety rules (read first)

- **Municipal-pressure systems only.** Do **not** install on well/lake pump-fed
  systems — closing the only open zone valve while a pump-start relay keeps the
  pump running can dead-head and damage the pump.
- **Continuous 24 VAC controllers only.** Battery timers use DC latching
  solenoids (pulse-driven) — there is no continuous power to harvest. Verify your
  controller uses a plug-in transformer.
- 24 VAC is Class-2 low voltage — safe to handle, but the transformer can source
  enough current to melt a shorted wire. Keep the MOV in place, fuse if paranoid.
- **Fail-to-passthrough is a hard rule:** the relay is wired so a dead/unpowered
  device passes 24 VAC straight through. Device failure loses *protection*, never
  *irrigation*. Do not "simplify" the relay to a triac or NO contact.
- During firmware development, power the ESP32 from **USB only** — disconnect the
  24 VAC feed (pull the 5V jumper) before plugging in USB, to avoid backfeeding
  the buck converter through the dev board.

Known detection limits (accepted scope): catches large flow increases (sheared
head, burst line). Will miss small underground seeps and anything that doesn't
change flow through the valve. A valve stuck open after the cycle ends is
detected best-effort via supercap ride-through, but cannot be shut off (the
solenoid is already de-energized).

---

## System overview

```
      one per zone, in the valve box                     in the house
┌────────────────────────────────────────┐      ┌───────────────────────────┐
│  Valve-Guard node                      │      │  Hub (any always-on box / │
│  ESP32 DevKitC-32U + piezo on valve    │ WiFi │  Raspberry Pi)            │
│  24VAC harvest · NC relay · supercap   ├─────►│  Mosquitto (MQTT broker)  │──► ntfy → phone push
│  local guard logic (works offline)     │◄─────┤  retained peer reports    │
└────────────────────────────────────────┘ reads│  alert bridge script      │
     powered ONLY while its zone runs     peers └───────────────────────────┘
```

Design principles carried through the firmware:

1. **Trip decision is local.** WiFi/broker down → device still guards. Network
   only delivers alerts and supplies fleet context.
2. **Fail toward watering.** Unpowered relay = passthrough; after
   `K_CONSECUTIVE_TRIPS` the device assumes baseline shift, relearns, and reverts
   to passthrough (escalating alerts instead of silently killing the lawn) —
   *unless* fleet data says peers are steady, in which case it keeps tripping
   (likely a real unrepaired break).
3. **Learn the profile, not a scalar.** Fill/settle time is learned per zone;
   guarding starts only after the flow noise stabilizes, and "never settles" is
   itself the break signal.

---

## Bill of materials (~$40, qty-1 pricing)

Core build (piezo path):

| # | Item | Search term / example part | Est. |
|---|------|---------------------------|------|
| 1 | ESP32 dev board w/ external antenna | "ESP32 DevKitC WROOM-32U" + u.FL→SMA pigtail + 2.4 GHz antenna | $11–14 |
| 2 | Buck converter, **input rating ≥ 50 V** | "LM2596HV DC-DC buck 60V" or XL7015 module — set output to **5.0 V** before connecting anything | $3–4 |
| 3 | Bridge rectifier | DB107 / KBP206 (or 4× 1N4007) | $1 |
| 4 | Bulk + filter caps | 2× 470 µF 50 V electrolytic, 100 µF + 10 µF + 0.1 µF | $2 |
| 5 | MOV surge suppressor | S10K35 or 07D680K (~35 VAC rated) across the 24 VAC input | $0.50 |
| 6 | Relay, SPDT 5 V sugar cube | SRD-05VDC-SL-C (use the **NC** contact) | $1.50 |
| 7 | Relay driver | 2N2222/PN2222, 1N4148 flyback, 1 kΩ base resistor | $0.50 |
| 8 | Piezo contact sensor | 27 mm piezo discs with leads (10-pack) | $6 |
| 9 | Analog front end | 2× 1 MΩ, 100 kΩ, 8.2 kΩ, 2× 1N4148, LM358 (optional gain stage) | $1 |
| 10 | Supercap ride-through | 1 F 5.5 V supercap + 10 Ω + 1N5817 Schottky ×2 | $3 |
| 11 | Perfboard + screw terminals + hookup wire | | $4 |
| 12 | Waterproof splices | Gel-filled connectors, 3M DBY/DBR style (×4) | $3 |
| 13 | Enclosure | Small IP65/IP66 junction box + cable glands (prototype; pot the final rev) | $6 |

Optional / bench:

| Item | Why | Est. |
|------|-----|------|
| ST IIS3DWB eval (STEVAL-MKI208V1K) | Vibration-grade accelerometer, second sensor for the bake-off (SPI header is pinned out in `config.h`; driver not yet implemented) | $28 |
| 24 VAC wall transformer (20–40 VA) | Bench development without standing over a valve box | $12–15 |

Hub: any always-on Linux box you already own ($0), or Pi Zero 2 W + PSU + SD (~$35).

---

## Wiring

Four field wires: the zone-valve **hot** pair (in from controller, out to
solenoid) and the **common** (tapped for power, passed through unswitched).

```
FROM CONTROLLER                                          TO SOLENOID
 zone hot ────●──────────────────[RELAY COM]──[RELAY NC]──── solenoid hot
              │                    (de-energized = CLOSED = passthrough;
              │                     energize coil to TRIP/open)
 common ──────┼──●──────────────────────────────────────────  solenoid common
              │  │
        ┌─────┴──┴─────┐
        │  MOV S10K35  │   (across hot–common, surge clamp)
        └─────┬──┬─────┘
              │  │
        ┌─────▼──▼─────┐      ┌──────────────┐   D1 1N5817   ┌─────────────┐
        │ BRIDGE DB107 │─(+)──│ 470µF 50V ×2 │──► LM2596HV ──►──●── 5V_SW ──│ ESP32 VIN  │
        │  AC  AC      │─(–)──│  bulk caps   │   set to 5.0V │  │           │ (GND common)│
        └──────────────┘      └──────────────┘               │  │           └─────────────┘
                     (+) also ──► 100k ──●── 8.2k ── GND     │  ├─ 10Ω ─┬─ SUPERCAP 1F 5.5V ─ GND
                                         │                   │  │       └─ 1N5817 (anode at cap)
                                 GPIO35 (rail sense,         │  │          across the 10Ω
                                  ~2.9V when rail ≈ 38V)     │  └──────────(discharge path)
                                                             │
    RELAY COIL: 5V (buck side of D1, NOT supercap side) ──► coil ──► 2N2222 C
                GPIO25 ──1kΩ──► 2N2222 B ; E──GND ; 1N4148 flyback across coil

    PIEZO (glue/clamp brass face to valve bonnet):
      lead A ── GND
      lead B ──●── 100k ──●── GPIO34 (ADC1)
               │          ├── 1N4148 → 3.3V   (clamps)
              1MΩ         └── 1N4148 → GND
               │
        bias node: 3.3V ──1MΩ──●──1MΩ── GND, + 10µF to GND  → ~1.65 V
        (lead B's 1MΩ returns to this bias node)

    OPTIONAL LM358 ×11 non-inverting gain stage between piezo and ADC —
    leave unpopulated first; the valve may be loud enough bare.
```

Wiring notes:

- **Power tap is on the controller side of the relay** — after a trip you stay
  powered for the rest of the cycle (alerting, holding the valve off).
- **Relay coil feeds from the buck side of D1**, not the supercap rail, so a
  trip can never drain the ride-through reserve.
- Rectified 24 VAC peaks ≈ 34–39 V (transformers run high unloaded) — hence the
  ≥50 V buck and 50 V caps. **Set the buck to 5.0 V before first connection.**
- GPIO34/35 are ADC1 + input-only: correct choice, ADC1 keeps working while
  WiFi is on (ADC2 does not).
- Sensor mounting: superglue or epoxy the piezo's brass face to the valve
  bonnet (best coupling), or clamp it firmly with a hose clamp + thin foam
  backing. The exact level doesn't matter — the algorithm is relative — but the
  mount must not move after learning.

## Field install (per zone)

1. At the controller, note which terminal feeds the zone; at the valve box,
   identify that valve.
2. Unscrew the existing wire nut on the solenoid **hot** wire. Splice the device
   in-line (controller wire → device "IN", solenoid wire → device "OUT") with
   gel-filled connectors. Tap the common with a third gel splice.
3. Mount the piezo on the valve bonnet; strap the enclosure high in the box,
   antenna near the (plastic) lid.
4. Run the zone manually from the controller and do the phone provisioning step
   (below) while it's powered.

---

## Firmware

Location: `firmware/valveguard/` — Arduino sketch for ESP32 (tested target:
arduino-esp32 core 3.x).

Libraries (Library Manager): **WiFiManager** (tzapu), **PubSubClient**,
**ArduinoJson**. Increase PubSubClient's `MQTT_MAX_PACKET_SIZE` to 512 if your
reports get truncated.

> **Status: written but not yet flashed to hardware.** Expect to tune the
> constants marked `// TUNE` in `config.h` and possibly fix small compile issues
> against your exact core version. The state machine, learning, and MQTT logic
> are complete; the IIS3DWB path is a stub.

### Provisioning & modes

- First boot ever: opens a captive-portal AP `ValveGuard-XXXX` (180 s timeout —
  if you skip it, the device runs offline-local). Join it from a phone, enter
  WiFi credentials, broker IP, and zone name.
- **MODE_LOG** (default from factory): measures and publishes everything,
  **never trips**. Use this for the data-collection weeks.
- **MODE_GUARD**: full protection. Switch modes over MQTT:
  `mosquitto_pub -t valveguard/<id>/cmd -m '{"mode":"guard"}'`

### State machine (per power-up = per watering cycle)

```
BOOT ─► FILL (wait for flow noise to settle; learned per zone; "never settles"
              within 3× learned fill time = break) ─► LEARN (first N cycles)
                                                   └► GUARD ─► TRIP (latched,
                                                                relay energized,
                                                                alert published)
 any state ─► POWERLOSS (rail sense drops → supercap: if flow noise persists
              ≥10 s after de-energize → stuck-valve alert, then deep sleep)
```

Guard logic: deviation = steady RMS (dB) − learned baseline − fleet common-mode
`g` (median of peer deviations from retained reports). Trip when deviation >
max(6 dB, 4×MAD) sustained `TRIP_SUSTAIN_S`. Clean cycles slow-adapt the
baseline (EMA) and reset the consecutive-trip counter. `K_CONSECUTIVE_TRIPS`
consecutive tripped cycles → if the fleet also shifted, auto-relearn and pass
through; if the fleet is steady, keep tripping and escalate alerts. Every
`SELFTEST_EVERY_N_CYCLES` clean cycles, a 3 s relay-open self-test verifies the
sensor+relay+valve chain end-to-end (noise must drop ≥6 dB).

### LED codes (GPIO2, onboard)

| Pattern | Meaning |
|---|---|
| slow blink (1 Hz) | learning / logging |
| solid | armed, guarding, all normal |
| fast blink (5 Hz) | TRIPPED this cycle |
| double-flash pause | fault: self-test failed / sensor suspect |
| triple-flash pause | persistent trip — needs repair or relearn |

### MQTT schema

| Topic | Retained | Payload |
|---|---|---|
| `valveguard/<id>/report` | yes | `{"id","zone","state","cycle","rms_db","base_db","dev_db","fill_s","trip","trips_run","rssi","ts"}` refreshed every 60 s while running |
| `valveguard/<id>/event` | no | `{"type":"trip"\|"stuck_valve"\|"selftest_fail"\|"persistent_trip"\|"relearned","detail":...}` |
| `valveguard/<id>/cmd` | — | `{"mode":"log"\|"guard"}`, `{"relearn":true}`, `{"selftest":true}` |

Fleet common-mode: each device subscribes to `valveguard/+/report` on connect
and medians the peers' `dev_db` (reports younger than 24 h, needs ≥2 peers).

---

## Bench & field test plan

**Phase 0 — firmware smoke (USB only, no 24 VAC).** Flash, provision, verify
MQTT reports arrive, tap the piezo and watch `rms_db` jump.

**Phase 1 — power stage.** Bench transformer → MOV/bridge/caps/buck. Verify
5.0 V ± 0.2 under ESP32 WiFi load, no brownout on connect (scope or listen for
boot loops). Verify rail-sense ADC reads ~2.5–3.0 V.

**Phase 2 — relay & fail-safe.** With device **unpowered**: continuity
controller-hot → solenoid-hot must be CLOSED (passthrough). Powered + forced
trip (`{"selftest":true}` or serial `t`): contact opens, valve would close.
Kill 24 VAC mid-trip: contact must return closed.

**Phase 3 — supercap.** Power up, connect WiFi, cut 24 VAC: device must stay
alive ≥15 s and publish a final report. If it dies early, bigger cap or trim
radio use.

**Phase 4 — field data collection (MODE_LOG, ≥1–2 weeks).** Install on one
zone. Collect ≥5 normal cycles. Then simulate breaks while logging: pull one
spray nozzle; open a hose bib teed after the valve; unscrew one head entirely.
Log everything: `mosquitto_sub -h <hub> -t 'valveguard/#' -v | ts '%s' >> log.txt`

**Phase 5 — analysis.** `python tools/plot_logs.py log.txt` — you're looking
for clear separation (≥6 dB) between normal steady-state and simulated-break
RMS, and consistent fill/settle times. **This is the go/no-go gate for the
whole acoustic approach.** If separation is marginal, try the LM358 gain stage,
a better mount, then the IIS3DWB before giving up.

**Phase 6 — arm it.** `{"mode":"guard"}`, then re-run the pulled-head test and
confirm: trip within ~15 s of settle, phone notification, valve held closed for
the cycle, auto-retest next cycle.

---

## Hub setup

Any always-on Linux box. See `hub/`:

```bash
cd hub
docker compose up -d          # Mosquitto on :1883 (edit mosquitto/passwd first)
pip install paho-mqtt requests
python alert_bridge.py --broker localhost --ntfy-topic <your-secret-topic>
```

Subscribe your phone to `https://ntfy.sh/<your-secret-topic>` (ntfy app). The
bridge forwards every `event` and flags zones that miss their expected
schedule. Home Assistant users: point HA's MQTT integration at the same broker
and skip the bridge.

---

## Roadmap

- **v1 (this):** dev-board prototype, one zone, sensor bake-off, logging → guard.
- **v2 (cost-down, ~$10/node):** ESP32-C3 SuperMini, custom PCB (JLC), potted;
  requires OTA (keep it in firmware from day one) and the Phase-5 data to prove
  the bare piezo suffices. See git history / design notes in the PR description.
