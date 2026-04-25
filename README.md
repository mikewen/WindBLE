# WindBLE

An Android app that reads wind speed and direction from a BLE wind sensor and displays apparent wind, true wind, boat heading, and GPS data on a nautical compass. Wind data can also be served to any WiFi device (Kindle, tablet, PC) via a built-in HTTP server.

## Features

### Wind Display
- **BLE Wind Sensor** — Connects to sensors using service `ae00` / `ae30`, characteristic `ae02` (NOTIFY)
- **Apparent Wind** — AWS (Apparent Wind Speed) and AWA (Apparent Wind Angle) decoded from sensor packets
- **Real-time Filtering** — Applies EMA (Exponential Moving Average) smoothing to AWS and AWA; rejects abnormal spikes and zeroed-out sensor error packets
- **Direction Gating** — AWA direction is held steady when AWS is below 0.5 knots to avoid erratic rotations in calm air
- **True Wind Calculation** — Computes TWS / TWA / TWD via vector decomposition using SOG + heading
- **Compass View** — North-up; compass ring counter-rotates with boat heading
- **Boat View** — Bow-up; wind arrows relative to the boat
- **Large Number View** — Full-screen single value for reading from the helm; tap to cycle TWA / TWS / TWD / AWA / AWS / SOG; supports landscape rotation

### GPS
- **Phone GPS** — SOG and COG from device location provider
- **BLE GPS** — Connect a separate BLE GPS module (Nordic UART / NUS); reads `$GNRMC` NMEA sentences; takes priority over phone GPS when connected; falls back automatically on disconnect

### Alerts & Logging
- **Wind Shift Alerts** — Detects True Wind Direction shifts beyond a configurable threshold (5 / 10 / 15 / 20°); vibrates on lift (two short pulses) or header (one long pulse); EMA smoothing prevents false alerts in gusts
- **Trip Recording** — Logs wind + GPS data to CSV in app storage; start/stop from the menu; row counter shown while recording

### Display Modes
- **Night Mode** — Red-tinted display preserves night vision; applied via hardware colour matrix
- **Auto-reconnect** — Reconnects to last wind sensor and BLE GPS on startup
- **Speed Units** — Knots, m/s, or km/h (Settings)
- **Keep Screen On** — Optional wake lock (Settings)

### WiFi Display Server
- Built-in HTTP server (port **8888**) serves a self-contained wind display page to any browser on the same WiFi network
- Toggle on/off via **⋮ → WiFi Display Server**; URL shown via **⋮ → Show Server URL…**
- Uses **Server-Sent Events** for ~1 Hz live updates; falls back to 2 s polling for older browsers
- Add `?mode=ink` to the URL for **e-ink / Kindle** mode — black on white, high contrast, thicker strokes

---

## BLE Packet Format

| Byte | Content |
|------|---------|
| 0    | Frame ID `0x57` (`'W'`) |
| 1    | Flags — bit 0 = valid fix |
| 2–3  | AWS `uint16` big-endian, m/s × 100 |
| 4–5  | AWA `uint16` big-endian, degrees × 100 |
| 6    | XOR checksum of bytes 0–5 |

Packets with an invalid checksum, `flags & 0x01 == 0`, or zeroed-out AWS/AWA sensor errors are silently discarded.

## BLE Profile

| | UUID |
|--|--|
| Service | `0000ae00-0000-1000-8000-00805f9b34fb` *(also tries ae30)* |
| Characteristic | `0000ae02-0000-1000-8000-00805f9b34fb` — NOTIFY |

## Wind Terminology

| Term | Meaning |
|------|---------|
| **AWS** | Apparent Wind Speed — wind speed as felt on the moving boat |
| **AWA** | Apparent Wind Angle — wind direction relative to bow (0° = ahead, 180° = astern) |
| **TWS** | True Wind Speed — actual wind speed over ground |
| **TWA** | True Wind Angle — wind direction relative to bow, corrected for boat speed |
| **TWD** | True Wind Direction — compass bearing the wind comes *from* |
| **SOG** | Speed Over Ground |
| **COG** | Course Over Ground |
| **HDG** | Heading — compass bearing the bow points |

## True Wind Algorithm

```
// Boat frame: X = forward, Y = starboard
AW_x = AWS × cos(AWA)
AW_y = AWS × sin(AWA)

TW_x = AW_x − SOG        // subtract boat velocity
TW_y = AW_y

TWS  = √(TW_x² + TW_y²)
TWA  = atan2(TW_y, TW_x)
TWD  = (Heading + TWA + 180) % 360   // compass bearing wind comes FROM
```

---

## Building

### Requirements
- Android Studio Hedgehog or newer
- JDK 17+
- minSdk 19 (Android 4.4) / targetSdk 34

### Steps
1. Open the project root folder in Android Studio
2. Android Studio will sync Gradle and download the wrapper automatically
3. Connect an Android device (API 19+) with Bluetooth LE support
4. Run the `app` configuration

### gradle-wrapper.jar
Not included in the repository. Android Studio downloads it on first sync. For command-line builds:
```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | BLE on API ≤ 30 |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | BLE on API 31+ |
| `ACCESS_FINE_LOCATION` | BLE scan (API < 31) + phone GPS |
| `INTERNET`, `ACCESS_WIFI_STATE` | WiFi display HTTP server |
| `VIBRATE` | Wind shift alert haptics |
| `POST_NOTIFICATIONS` | Wind shift notification (API 33+) |

---

## Project Structure

```
app/src/main/
├── assets/
│   └── wind_display.html        — Self-contained WiFi display page (SSE + canvas compass)
└── java/com/windble/app/
    ├── alert/
    │   └── WindShiftAlert.java  — EMA smoothing, threshold detection, vibration
    ├── ble/
    │   ├── BleConstants.java    — UUIDs, broadcast actions, state codes
    │   └── BleService.java      — GATT client, CCCD notify, auto-reconnect
    ├── gps/
    │   ├── BleGpsManager.java   — BLE GPS GATT client, NUS service, NMEA reassembly
    │   ├── GpsManager.java      — Phone GPS → SOG / COG
    │   └── NmeaParser.java      — $GNRMC / $GPRMC parser with checksum validation
    ├── logger/
    │   └── TripLogger.java      — CSV trip recording to external storage
    ├── model/
    │   └── WindData.java        — Packet parser (flags, checksum, big-endian) + true wind math
    ├── server/
    │   └── WindHttpServer.java  — HTTP server: SSE stream, snapshot endpoint, asset serving
    └── ui/
        ├── LargeNumberActivity.java — Full-screen single value, tap to cycle, rotation support
        ├── MainActivity.java        — Main compass display, all feature wiring
        ├── ScanActivity.java        — BLE scan (modern + legacy API 19 fallback)
        ├── SettingsActivity.java    — Preferences fragment
        ├── WindCompassView.java     — Custom Canvas nautical compass
        └── WindViewModel.java       — LiveData hub: BLE + GPS + compass + server + logger; implements real-time EMA filtering and sensor data validation
```

## License

MIT