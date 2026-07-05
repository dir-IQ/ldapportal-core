// ---------------------------------------------------------------------------
// Valve-Guard: acoustic broken-sprinkler detector — $40 dev-board prototype
//
// Powered from the zone's own 24VAC (device is alive only while the zone
// runs). Listens to flow noise via a piezo strapped to the valve, learns the
// zone's normal steady-state signature, and energizes an NC relay to cut the
// solenoid when it hears a sustained large excursion (broken head / line).
//
// Libraries: WiFiManager (tzapu), PubSubClient, ArduinoJson (v7)
// Status: complete logic, NOT yet flashed to hardware — expect TUNE passes.
// ---------------------------------------------------------------------------

#include <WiFi.h>
#include <WiFiManager.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <time.h>
#include "config.h"

// ---------------------------------------------------------------- state types
enum RunState { ST_FILL, ST_LEARN, ST_GUARD, ST_TRIPPED, ST_POWERLOSS, ST_IDLE };
enum RunMode  { MODE_LOG, MODE_GUARD };

static const char* stateName(RunState s) {
  switch (s) {
    case ST_FILL: return "fill";      case ST_LEARN: return "learn";
    case ST_GUARD: return "guard";    case ST_TRIPPED: return "tripped";
    case ST_POWERLOSS: return "powerloss"; default: return "idle";
  }
}

// ---------------------------------------------------------------- globals
Preferences   prefs;
WiFiClient    wifiClient;
PubSubClient  mqtt(wifiClient);

char   devId[16];                 // "vg" + MAC tail
String zoneName = "zone";
String brokerHost;

RunState state = ST_FILL;
RunMode  mode  = MODE_LOG;

// Learned model (persisted)
uint32_t cycleCount = 0;
float    baseDb = NAN, madDb = 1.0f, learnedFillS = 30.0f;
uint16_t consecutiveTrips = 0;
float    learnSamples[LEARN_CYCLES];

// Per-cycle working state
uint32_t bootMs, steadyAtMs = 0, excursionStartMs = 0, lastReportMs = 0;
float    steadyLevelDb = NAN;     // this cycle's steady RMS (slow EMA)
float    fleetG = 0.0f;           // fleet common-mode adjustment (dB)
int      fleetPeers = 0;
bool     trippedThisCycle = false, learnedThisCycle = false;
bool     selftestPending = false, selftestFailed = false;

// ------------------------------------------------------- sampler (core 0 task)
// 4 kHz ADC loop -> DC-removal high-pass -> 250 ms RMS windows in dB.
static volatile float latestWindowDb = -120.0f;
static volatile uint32_t windowSeq = 0;

void samplerTask(void*) {
  analogSetPinAttenuation(PIN_PIEZO, ADC_11db);
  int64_t next = esp_timer_get_time();
  double acc = 0; uint32_t n = 0; float dc = 2048.0f;
  for (;;) {
    int raw = analogRead(PIN_PIEZO);
    dc += (raw - dc) * DC_ALPHA;
    float ac = raw - dc;
    acc += (double)ac * ac; n++;
    if (n >= WINDOW_SAMPLES) {
      float rms = sqrtf(acc / n);
      latestWindowDb = 20.0f * log10f(rms > 0.1f ? rms : 0.1f);
      windowSeq++;
      acc = 0; n = 0;
    }
    next += SAMPLE_PERIOD_US;
    int64_t now = esp_timer_get_time();
    if (next > now) delayMicroseconds((uint32_t)(next - now));
    else next = now;  // fell behind (WiFi burst) — resync rather than spiral
  }
}

// 1 s decision blocks: median of the last BLOCK_WINDOWS windows.
static float blockBuf[BLOCK_WINDOWS];
static int   blockFill = 0;
static uint32_t lastSeq = 0;
static float lastBlockDb = NAN, prevBlockDb = NAN;
static uint32_t settleFlatMs = 0;

static float medianOf(float* v, int n) {
  float t[16]; memcpy(t, v, n * sizeof(float));
  for (int i = 1; i < n; i++)
    for (int j = i; j > 0 && t[j-1] > t[j]; j--) { float x=t[j]; t[j]=t[j-1]; t[j-1]=x; }
  return (n & 1) ? t[n/2] : 0.5f * (t[n/2 - 1] + t[n/2]);
}

// Returns true once per completed 1 s block.
bool pollBlock() {
  if (windowSeq == lastSeq) return false;
  lastSeq = windowSeq;
  blockBuf[blockFill++] = latestWindowDb;
  if (blockFill < BLOCK_WINDOWS) return false;
  blockFill = 0;
  prevBlockDb = lastBlockDb;
  lastBlockDb = medianOf(blockBuf, BLOCK_WINDOWS);
  return true;
}

// ---------------------------------------------------------------- persistence
void loadModel() {
  prefs.begin(NVS_NAMESPACE, false);
  cycleCount       = prefs.getUInt("cycles", 0);
  baseDb           = prefs.getFloat("base", NAN);
  madDb            = prefs.getFloat("mad", 1.0f);
  learnedFillS     = prefs.getFloat("fill", 30.0f);
  consecutiveTrips = prefs.getUShort("ktrips", 0);
  mode             = (RunMode)prefs.getUChar("mode", MODE_LOG);
  zoneName         = prefs.getString("zone", "zone");
  brokerHost       = prefs.getString("broker", "");
  for (int i = 0; i < LEARN_CYCLES; i++)
    learnSamples[i] = prefs.getFloat((String("l") + i).c_str(), NAN);
}

void saveLearnSample(int idx, float v) { prefs.putFloat((String("l")+idx).c_str(), v); }

void finalizeBaseline() {
  float vals[LEARN_CYCLES]; int n = 0;
  for (int i = 0; i < LEARN_CYCLES; i++) if (!isnan(learnSamples[i])) vals[n++] = learnSamples[i];
  if (n < LEARN_CYCLES) return;
  baseDb = medianOf(vals, n);
  float devs[LEARN_CYCLES];
  for (int i = 0; i < n; i++) devs[i] = fabsf(vals[i] - baseDb);
  madDb = fmaxf(medianOf(devs, n), 0.5f);
  prefs.putFloat("base", baseDb);
  prefs.putFloat("mad", madDb);
}

void clearBaseline() {
  baseDb = NAN; consecutiveTrips = 0;
  prefs.putFloat("base", NAN);
  prefs.putUShort("ktrips", 0);
  prefs.putUInt("cycles", cycleCount = 0);
  for (int i = 0; i < LEARN_CYCLES; i++) { learnSamples[i] = NAN; saveLearnSample(i, NAN); }
}

// ---------------------------------------------------------------- power sense
float railVolts() {
  return analogReadMilliVolts(PIN_RAIL_SENSE) / 1000.0f * RAIL_DIVIDER_RATIO;
}

// ---------------------------------------------------------------- relay & LED
void relayTrip(bool on) { digitalWrite(PIN_RELAY, on ? HIGH : LOW); }

void ledUpdate() {
  uint32_t t = millis() % 2000;
  bool on;
  if (selftestFailed)                 on = (t < 120) || (t > 300 && t < 420);          // double-flash
  else if (consecutiveTrips >= K_CONSECUTIVE_TRIPS)
                                      on = (t<120)||(t>300&&t<420)||(t>600&&t<720);    // triple-flash
  else if (state == ST_TRIPPED)       on = (millis() / 100) & 1;                       // fast
  else if (state == ST_GUARD)         on = true;                                       // solid
  else                                on = (millis() / 500) & 1;                       // slow
  digitalWrite(PIN_LED, on);
}

// ---------------------------------------------------------------- MQTT / fleet
static float peerDevs[16]; static int nPeerDevs = 0;

void mqttCallback(char* topic, byte* payload, unsigned int len) {
  JsonDocument doc;
  if (deserializeJson(doc, payload, len)) return;
  String t(topic);

  if (t.endsWith("/cmd") && t.indexOf(devId) >= 0) {
    if (doc["relearn"] == true) { clearBaseline(); publishEvent("relearned", "by command"); }
    if (doc["selftest"] == true) selftestPending = true;
    const char* m = doc["mode"];
    if (m) { mode = strcmp(m, "guard") == 0 ? MODE_GUARD : MODE_LOG; prefs.putUChar("mode", mode); }
    return;
  }

  // Peer retained report (skip our own)
  if (t.endsWith("/report") && t.indexOf(devId) < 0 && nPeerDevs < 16) {
    time_t now = time(nullptr);
    long ts = doc["ts"] | 0L;
    if (now > 1600000000 && ts > 0 && (now - ts) > PEER_MAX_AGE_S) return; // stale
    if (doc["dev_db"].is<float>()) peerDevs[nPeerDevs++] = doc["dev_db"].as<float>();
  }
}

void computeFleetG() {
  fleetPeers = nPeerDevs;
  fleetG = (nPeerDevs >= FLEET_MIN_PEERS) ? medianOf(peerDevs, nPeerDevs) : 0.0f;
}

bool mqttEnsure() {
  if (WiFi.status() != WL_CONNECTED || brokerHost.length() == 0) return false;
  if (mqtt.connected()) return true;
  mqtt.setServer(brokerHost.c_str(), MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  if (!mqtt.connect(devId)) return false;
  mqtt.subscribe("valveguard/+/report");           // fleet notes
  mqtt.subscribe((String("valveguard/") + devId + "/cmd").c_str());
  return true;
}

void publishReport() {
  if (!mqttEnsure()) return;
  JsonDocument doc;
  doc["id"] = devId;               doc["zone"] = zoneName;
  doc["state"] = stateName(state); doc["cycle"] = cycleCount;
  doc["rms_db"] = lastBlockDb;     doc["base_db"] = baseDb;
  doc["dev_db"] = (!isnan(baseDb) && !isnan(steadyLevelDb)) ? steadyLevelDb - baseDb : 0.0f;
  doc["fill_s"] = learnedFillS;    doc["trip"] = trippedThisCycle;
  doc["trips_run"] = consecutiveTrips;
  doc["rssi"] = WiFi.RSSI();       doc["ts"] = (long)time(nullptr);
  char buf[384]; size_t n = serializeJson(doc, buf);
  mqtt.publish((String("valveguard/") + devId + "/report").c_str(), (uint8_t*)buf, n, true);
}

void publishEvent(const char* type, const char* detail) {
  if (!mqttEnsure()) return;
  JsonDocument doc;
  doc["id"] = devId; doc["zone"] = zoneName; doc["type"] = type;
  doc["detail"] = detail; doc["ts"] = (long)time(nullptr);
  char buf[256]; size_t n = serializeJson(doc, buf);
  mqtt.publish((String("valveguard/") + devId + "/event").c_str(), (uint8_t*)buf, n, false);
}

// ---------------------------------------------------------------- self test
// Open the relay 3 s mid-guard; flow noise must drop => sensor+relay+valve OK.
void runSelfTest() {
  float before = lastBlockDb;
  relayTrip(true);
  delay(SELFTEST_RELAY_S * 1000);
  // grab a fresh window after the valve has closed
  uint32_t seq = windowSeq; while (windowSeq < seq + 2) delay(50);
  float during = latestWindowDb;
  relayTrip(false);
  bool ok = (before - during) >= SELFTEST_MIN_DROP_DB;
  selftestFailed = !ok;
  publishEvent(ok ? "selftest_ok" : "selftest_fail",
               ok ? "chain verified" : "no noise drop when valve cut — check sensor mount / relay");
}

// ---------------------------------------------------------------- trip
void doTrip() {
  trippedThisCycle = true;
  state = ST_TRIPPED;
  relayTrip(true);                                   // valve closes; we stay powered
  consecutiveTrips++;
  prefs.putUShort("ktrips", consecutiveTrips);

  char d[128];
  snprintf(d, sizeof d, "flow %+.1f dB over normal (fleet g=%+.1f, peers=%d) — likely broken head/line",
           steadyLevelDb - baseDb, fleetG, fleetPeers);
  publishEvent("trip", d);

  if (consecutiveTrips >= K_CONSECUTIVE_TRIPS) {
    if (fleetPeers >= FLEET_MIN_PEERS && fabsf(fleetG) >= FLEET_SHIFT_DB) {
      // whole fleet shifted -> baseline is stale, not a break: relearn, pass through
      publishEvent("relearned", "fleet-wide shift detected; baseline reset, passthrough restored");
      clearBaseline();
      relayTrip(false);
      state = ST_LEARN;
      trippedThisCycle = false;
    } else {
      publishEvent("persistent_trip", "tripped 5+ consecutive cycles, fleet steady — repair needed");
    }
  }
  publishReport();
}

// ---------------------------------------------------------------- power loss
// Rail died: controller ended the cycle (or wiring cut). On supercap now.
// If flow noise persists near the steady level, the valve is stuck open.
void handlePowerLoss() {
  state = ST_POWERLOSS;
  relayTrip(false);                       // never leave coil burning the supercap
  // end-of-cycle bookkeeping (one NVS write)
  if (!trippedThisCycle && !isnan(baseDb) && !isnan(steadyLevelDb)) {
    consecutiveTrips = 0; prefs.putUShort("ktrips", 0);
    float dev = steadyLevelDb - baseDb - fleetG;
    if (fabsf(dev) < ADAPT_MAX_DEV_DB) {  // slow-adapt on clean cycles
      baseDb += BASELINE_EMA_ALPHA * (steadyLevelDb - fleetG - baseDb);
      prefs.putFloat("base", baseDb);
    }
  }
  publishReport();

  uint32_t t0 = millis(), persistMs = 0;
  float flowRef = isnan(steadyLevelDb) ? lastBlockDb : steadyLevelDb;
  uint32_t lastCheck = millis();
  while (millis() - t0 < STUCK_LISTEN_S * 1000UL) {
    mqtt.loop(); ledUpdate();
    if (pollBlock()) {
      persistMs = (lastBlockDb > flowRef - STUCK_BAND_DB) ? persistMs + (millis()-lastCheck) : 0;
      lastCheck = millis();
      if (persistMs >= STUCK_PERSIST_S * 1000UL) {
        publishEvent("stuck_valve", "flow continuing after zone de-energized — valve stuck open, manual attention needed");
        break;
      }
    }
    if (railVolts() > RAIL_LOW_VOLTS + 3) { ESP.restart(); }  // power came back: treat as new cycle
    delay(20);
  }
  mqtt.disconnect();
  esp_deep_sleep_start();                 // drain quietly; next 24VAC = fresh boot
}

// ---------------------------------------------------------------- setup
void setup() {
  Serial.begin(115200);
  pinMode(PIN_RELAY, OUTPUT); relayTrip(false);     // passthrough, always, first
  pinMode(PIN_LED, OUTPUT);
  bootMs = millis();

  uint64_t mac = ESP.getEfuseMac();
  snprintf(devId, sizeof devId, "vg%04X", (uint16_t)(mac >> 32));

  loadModel();
  cycleCount++; prefs.putUInt("cycles", cycleCount);

  xTaskCreatePinnedToCore(samplerTask, "sampler", 4096, nullptr, 2, nullptr, 0);

  // WiFi: captive portal ONLY if never provisioned; otherwise non-blocking
  // reconnect — guarding must not wait on the network.
  WiFiManager wm;
  wm.setConfigPortalTimeout(PORTAL_TIMEOUT_S);
  WiFiManagerParameter pBroker("broker", "MQTT broker IP", brokerHost.c_str(), 40);
  WiFiManagerParameter pZone("zone", "Zone name", zoneName.c_str(), 24);
  wm.addParameter(&pBroker); wm.addParameter(&pZone);
  if (WiFi.SSID().length() == 0) {
    char ap[24]; snprintf(ap, sizeof ap, "ValveGuard-%s", devId);
    wm.autoConnect(ap);                              // blocks ≤ PORTAL_TIMEOUT_S
    brokerHost = pBroker.getValue(); zoneName = pZone.getValue();
    prefs.putString("broker", brokerHost); prefs.putString("zone", zoneName);
  } else {
    WiFi.mode(WIFI_STA); WiFi.begin();               // stored creds, non-blocking
  }
  configTime(0, 0, NTP_SERVER);

  state = ST_FILL;
  Serial.printf("[vg] %s cycle=%u mode=%s base=%.1f fill=%.0fs ktrips=%u\n",
                devId, cycleCount, mode == MODE_GUARD ? "guard" : "log",
                baseDb, learnedFillS, consecutiveTrips);
}

// ---------------------------------------------------------------- main loop
void loop() {
  mqtt.loop();
  ledUpdate();

  // one-shot: after connect, harvest peer reports briefly then compute g
  static bool fleetDone = false;
  static uint32_t fleetT0 = 0;
  if (!fleetDone && mqttEnsure()) {
    if (fleetT0 == 0) fleetT0 = millis();
    if (millis() - fleetT0 > 3000) { computeFleetG(); fleetDone = true; }
  }

  // supercap detection — highest priority, any state
  if (millis() - bootMs > 2000 && railVolts() < RAIL_LOW_VOLTS && state != ST_POWERLOSS) {
    handlePowerLoss();                                // does not return
  }

  if (millis() - lastReportMs > REPORT_PERIOD_S * 1000UL) {
    lastReportMs = millis(); publishReport();
  }

  // serial helpers for the bench: t=selftest, r=relearn, g/l=mode
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 't') selftestPending = true;
    if (c == 'r') clearBaseline();
    if (c == 'g') { mode = MODE_GUARD; prefs.putUChar("mode", mode); }
    if (c == 'l') { mode = MODE_LOG;   prefs.putUChar("mode", mode); }
  }

  if (!pollBlock()) { delay(10); return; }            // everything below runs at 1 Hz
  Serial.printf("[vg] %s rms=%.1fdB base=%.1f g=%+.1f\n",
                stateName(state), lastBlockDb, baseDb, fleetG);

  switch (state) {

    case ST_FILL: {
      // wait for flow noise to flatten: |Δblock| < SETTLE_DELTA_DB held SETTLE_HOLD_S
      if (!isnan(prevBlockDb) && fabsf(lastBlockDb - prevBlockDb) < SETTLE_DELTA_DB) {
        if (settleFlatMs == 0) settleFlatMs = millis();
        if (millis() - settleFlatMs >= SETTLE_HOLD_S * 1000UL) {
          steadyAtMs = millis();
          steadyLevelDb = lastBlockDb;
          float fillS = (steadyAtMs - bootMs) / 1000.0f;
          learnedFillS = 0.8f * learnedFillS + 0.2f * fillS;   // slow-learn fill profile
          prefs.putFloat("fill", learnedFillS);
          state = isnan(baseDb) ? ST_LEARN : ST_GUARD;
        }
      } else settleFlatMs = 0;

      // "never settles" IS the break signal once we know this zone's fill time
      float waited = (millis() - bootMs) / 1000.0f;
      bool overdue = (cycleCount > LEARN_CYCLES && waited > FILL_BREAK_FACTOR * learnedFillS)
                     || waited > MAX_FILL_S;
      if (overdue) {
        steadyLevelDb = lastBlockDb;
        if (mode == MODE_GUARD && !isnan(baseDb)) doTrip();
        else { publishEvent("fill_timeout", "flow never settled (LOG mode: no action)"); state = ST_LEARN; }
      }
      break;
    }

    case ST_LEARN: {
      // EMA the steady level for LEARN_SAMPLE_S, then store this cycle's sample
      steadyLevelDb = 0.9f * steadyLevelDb + 0.1f * lastBlockDb;
      if (!learnedThisCycle && millis() - steadyAtMs > LEARN_SAMPLE_S * 1000UL) {
        learnedThisCycle = true;
        int idx = min((int)cycleCount - 1, LEARN_CYCLES - 1) % LEARN_CYCLES;
        learnSamples[idx] = steadyLevelDb;
        saveLearnSample(idx, steadyLevelDb);
        finalizeBaseline();                            // sets baseDb once 5 samples exist
        publishEvent("learned", "cycle sample stored");
        if (!isnan(baseDb)) state = ST_GUARD;
      }
      break;
    }

    case ST_GUARD: {
      steadyLevelDb = 0.9f * steadyLevelDb + 0.1f * lastBlockDb;
      if (selftestPending || (cycleCount % SELFTEST_EVERY_N_CYCLES == 0 && !selftestPending
                              && millis() - steadyAtMs > 30000 && !trippedThisCycle)) {
        static bool doneThisCycle = false;
        if (!doneThisCycle) { doneThisCycle = true; selftestPending = false; runSelfTest(); }
      }
      float threshold = fmaxf(TRIP_MIN_THRESH_DB, TRIP_MAD_MULT * madDb);
      float dev = lastBlockDb - baseDb - fleetG;       // fleet common-mode rejection
      if (dev > threshold) {
        if (excursionStartMs == 0) excursionStartMs = millis();
        if (millis() - excursionStartMs >= TRIP_SUSTAIN_S * 1000UL) {
          if (mode == MODE_GUARD) doTrip();
          else publishEvent("would_trip", "excursion sustained (LOG mode: no action)");
          excursionStartMs = 0;
        }
      } else excursionStartMs = 0;
      break;
    }

    case ST_TRIPPED:   // latched: hold relay open until power dies (cycle end)
    default:
      break;
  }
}
