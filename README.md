# WindBLE

An Android app that reads wind speed and direction from a BLE wind sensor and displays apparent wind, true wind, boat heading, and GPS data on a nautical compass.

## Features

- **BLE Connection** — Connects to wind sensors using service `ae00` / `ae30`, characteristic `ae02` (NOTIFY)
- **Apparent Wind** — Displays AWS (Apparent Wind Speed) and AWA (Apparent Wind Angle) from the sensor
- **True Wind Calculation** — Computes TWS / TWA / TWD using phone GPS (SOG + COG) and phone compass (heading) via vector decomposition
- **Dual View Modes** — Switch between **Compass View** (North-up, compass ring rotates) and **Boat View** (Bow-up, fixed orientation)
- **Auto-reconnect** — Automatically reconnects to the last paired device on startup
- **Speed Units** — Knots, m/s, or km/h (configurable in Settings)
- **Keep Screen On** — Optional wake lock while sailing

## BLE Packet Format

| Byte | Content |
|------|---------|
| 0    | Magic `0x57` ('W') |
| 1    | Flags |
| 2–3  | AWS: `uint16`, m/s × 100 (big-endian) |
| 4–5  | AWA: `uint16`, degrees × 100 (big-endian) |
| 6    | XOR checksum of bytes 0–5 |

## BLE Profile

- **Service UUID:** `0000ae00-0000-1000-8000-00805f9b34fb` *(or ae30)*
- **Characteristic UUID:** `0000ae02-0000-1000-8000-00805f9b34fb` — NOTIFY

## True Wind Algorithm

True wind is computed by vector subtraction of boat velocity from apparent wind:

```
TW_x = AW_x - BS_x    (boat frame: X = forward, Y = starboard)
TW_y = AW_y - BS_y
TWS  = √(TW_x² + TW_y²)
TWA  = atan2(TW_y, TW_x)          (relative to bow)
TWD  = (Heading + TWA + 180) % 360 (compass direction wind comes FROM)
```

## Building

### Requirements
- Android Studio Hedgehog or newer
- JDK 11+
- minSdk 19 / targetSdk 33

### Steps
1. Open the project root folder in Android Studio
2. Android Studio will download Gradle and all dependencies automatically
3. Connect an Android device (API 19+) with Bluetooth LE support
4. Run the `app` configuration

### gradle-wrapper.jar note
The `gradle-wrapper.jar` is not included in the repository. Android Studio downloads it automatically. If building from the command line, run:
```bash
gradle wrapper --gradle-version 7.5
```
then use `./gradlew assembleDebug`.

## Permissions

| Permission | Purpose |
|-----------|---------|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | BLE on API ≤ 30 |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | BLE on API 31+ |
| `ACCESS_FINE_LOCATION` | Required for BLE scan on API < 31 + GPS |

## Project Structure

```
app/src/main/java/com/windble/app/
├── ble/
│   ├── BleConstants.java    — UUIDs, action strings, state codes
│   └── BleService.java      — GATT client, notifications, auto-reconnect
├── gps/
│   └── GpsManager.java      — Location updates → SOG / COG
├── model/
│   └── WindData.java        — Packet parser + true wind math
└── ui/
    ├── WindViewModel.java   — Connects BLE + GPS + compass → LiveData
    ├── WindCompassView.java — Custom Canvas compass widget
    ├── MainActivity.java    — Main wind display
    ├── ScanActivity.java    — BLE device scanner
    └── SettingsActivity.java
```

## License

MIT
