#pragma once

// ---------------------------------------------------------------------------
// Valve-Guard prototype configuration
// Constants marked TUNE are expected to change during Phase 4/5 bench work.
// ---------------------------------------------------------------------------

// --- Pins (ESP32 DevKitC / WROOM-32U) --------------------------------------
#define PIN_PIEZO      34   // ADC1_CH6, input-only. ADC1 works with WiFi on.
#define PIN_RAIL_SENSE 35   // ADC1_CH7, 100k/8.2k divider from rectified rail
#define PIN_RELAY      25   // NPN base via 1k. HIGH = energize = TRIP (open NC)
#define PIN_LED        2    // onboard LED

// Optional IIS3DWB (SPI) — pinned out for the sensor bake-off, driver is a stub
#define PIN_DWB_CS     5
#define PIN_DWB_SCK    18
#define PIN_DWB_MISO   19
#define PIN_DWB_MOSI   23

// --- Sampling / feature extraction ------------------------------------------
#define SAMPLE_RATE_HZ     4000            // TUNE: 2-8 kHz plausible for piezo
#define SAMPLE_PERIOD_US   (1000000 / SAMPLE_RATE_HZ)
#define WINDOW_SAMPLES     1000            // 250 ms RMS windows
#define DC_ALPHA           0.001f          // DC-removal EMA (~high-pass)
#define BLOCK_WINDOWS      4               // 4 windows = 1 s decision blocks

// --- Fill / settle detection -------------------------------------------------
#define SETTLE_DELTA_DB    1.5f  // TUNE: block-to-block delta considered "flat"
#define SETTLE_HOLD_S      5     // flat this long => steady state
#define MAX_FILL_S         120   // TUNE: absolute cap on fill wait
#define FILL_BREAK_FACTOR  3     // never settles within 3x learned fill => break

// --- Learning ----------------------------------------------------------------
#define LEARN_CYCLES           5     // cycles spent learning before guarding
#define LEARN_SAMPLE_S         60    // steady seconds sampled per learning cycle
#define BASELINE_EMA_ALPHA     0.05f // slow adapt on clean cycles
#define ADAPT_MAX_DEV_DB       2.0f  // only adapt when |deviation| below this

// --- Trip logic ----------------------------------------------------------------
#define TRIP_MIN_THRESH_DB     6.0f  // TUNE: min excursion over baseline to trip
#define TRIP_MAD_MULT          4.0f  // threshold = max(6dB, 4*MAD)
#define TRIP_SUSTAIN_S         10    // excursion must persist this long
#define K_CONSECUTIVE_TRIPS    5     // then: fleet-informed relearn-or-escalate
#define FLEET_SHIFT_DB         3.0f  // |fleet median| above this = global shift
#define FLEET_MIN_PEERS        2

// --- Self test -----------------------------------------------------------------
#define SELFTEST_EVERY_N_CYCLES 14   // ~biweekly on daily watering
#define SELFTEST_RELAY_S        3
#define SELFTEST_MIN_DROP_DB    6.0f

// --- Power / supercap ------------------------------------------------------------
#define RAIL_DIVIDER_RATIO   13.2f   // (100k+8.2k)/8.2k
#define RAIL_LOW_VOLTS       15.0f   // rectified rail below this => on supercap
#define STUCK_LISTEN_S       30      // listen window after power loss
#define STUCK_PERSIST_S      10      // flow-level noise persisting => stuck valve
#define STUCK_BAND_DB        3.0f    // "still flowing" = within 3dB of steady level

// --- Network ---------------------------------------------------------------------
#define MQTT_PORT            1883
#define REPORT_PERIOD_S      60
#define PORTAL_TIMEOUT_S     180
#define PEER_MAX_AGE_S       (24 * 3600)
#define NTP_SERVER           "pool.ntp.org"

// --- NVS keys ----------------------------------------------------------------------
#define NVS_NAMESPACE  "vguard"
