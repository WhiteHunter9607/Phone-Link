# PhoneLink

Control an Android phone over Bluetooth Low Energy (BLE) from a PC (and, later, an ESP32-S3 or another phone).

- Set media, ringtone, and alarm volume, and mute
- Turn Wi-Fi, Location, and the flashlight on or off
- **Find my phone**: ring it loudly (max alarm volume, vibration, flashlight) from a controller
- The phone asks before it accepts a new controller, and can remember trusted ones

Built for an Android 9 phone (Vivo Y11). Wi-Fi and Bluetooth toggling only work like this on Android 9 and older; Android 10+ blocks them for normal apps.

## How it works

```
 PC controller (.exe)  ──BLE──►  Phone app (.apk)
 ESP32-S3 (planned)    ──BLE──►  (BLE server + actions)
```

The phone is the BLE **server**: it advertises a custom service and waits. Controllers connect as BLE clients and send short text commands. The phone shows an approval dialog for new controllers, executes allowed commands, and sends its real state back so the controller always mirrors the phone.

## Repo layout

| Path | What it is |
|---|---|
| `android/` | Android app (Kotlin, no external libraries) |
| `pc/` | PC controller: a web page wrapped in Electron |
| `.github/workflows/build.yml` | GitHub Actions: builds the APK and the EXE |

## Get the apps

1. Open the **Actions** tab and click the latest successful run.
2. Download **PhoneLink-apk** and **PhoneLink-exe** from *Artifacts* and extract each zip.
3. Push a tag such as `v0.1` to also publish both files as a GitHub Release.

> The Actions artifact for the APK may be saved as `app-debug.zip`. If so, rename it to `app-debug.apk` before installing.

## Phone setup (Android 9)

1. Copy `app-debug.apk` to the phone, open it, and allow installs from unknown sources.
2. Open **PhoneLink** and tap **Start service**. The status should read *Ready — advertising*.
3. For the Location toggle, run this once from a PC with USB debugging enabled:
   ```
   adb shell pm grant com.example.phonelink android.permission.WRITE_SECURE_SETTINGS
   ```
4. On Vivo (Funtouch OS), allow **Autostart** and **background pop-ups** for the app, and set battery usage to **no restrictions**. Otherwise the service and the approval dialogs get killed.

## PC setup

1. Turn on Bluetooth on the PC.
2. Run `PhoneLink-PC.exe`. Windows SmartScreen may warn because the file is unsigned: click **More info → Run anyway**.
3. Make sure the phone's service is running, then click **Connect**.
4. On the phone, choose **Allow once**, **Always allow**, or **Deny**.
   *Always allow* stores a secret key, so the PC reconnects silently afterward.

The same page also runs in Chrome or Edge from `pc/index.html` over HTTPS or localhost (Web Bluetooth).

## Security model

- New controllers need approval on the phone every time, unless trusted.
- **Always allow** creates a per-controller secret key. Later connections prove they hold it with an HMAC challenge-response, so copying a controller's name or ID is not enough.
- Each controller type has its own list of allowed functions (`Proto.defaultProfile()` in `Proto.kt`). Commands outside the list are rejected.
- The phone app has a master **Accept controllers** switch and a **Revoke** button per trusted controller.
- The ring function is pre-authorized for trusted controllers, since you can't tap Allow on a phone you've lost.

BLE traffic itself is not encrypted in this version. Add BLE bonding if you need protection against nearby sniffing.

## Protocol

Text lines, fields separated by `|`, every message ends with `\n`.

Service UUID `6e5a0001-7b1c-4f0e-9a52-1c0d5e7a0001`; command characteristic `...0002` (write), event characteristic `...0003` (notify).

| Direction | Message |
|---|---|
| Controller → phone | `HELLO\|id\|name\|type` (`pc`, `esp32`, `phone`) |
| Controller → phone | `AUTH\|hmacHex` |
| Controller → phone | `VOL\|media/ring/alarm\|0-100`, `MUTE`, `WIFI\|1/0`, `LOC\|1/0`, `TORCH\|1/0`, `RING\|1/0`, `GET` |
| Phone → controller | `PENDING`, `CHAL\|nonce`, `TOKEN\|key\|allowed`, `OK\|allowed` |
| Phone → controller | `STATE\|m=..;r=..;a=..;wifi=..;loc=..;torch=..;ring=..`, `ERR\|reason` |

## Troubleshooting

| Problem | Try this |
|---|---|
| PC says **No device chosen** | The phone isn't advertising. Open the phone app, tap **Start service**, check it says *Ready — advertising*, keep the phone close, then click Connect again. |
| Status says *This phone can't advertise over BLE* | The phone's Bluetooth chip can't act as a BLE server. A fallback design (the ESP32 as BLE peripheral) is needed. |
| Status says *Bluetooth is off* | Turn Bluetooth on, then stop and start the service. |
| No approval dialog appears | Allow background pop-ups for the app (Vivo setting), or tap the notification "Controller wants access". |
| Location toggle does nothing | Run the `adb shell pm grant ...` command above. |
| Service stops after a while | Disable battery optimization and allow Autostart for the app. |
| "Key rejected" on PC | The phone revoked this PC. Press Reconnect and approve again. |

## Limitations

- BLE range is roughly 10–30 m indoors.
- Android 10 and newer: Wi-Fi and Bluetooth toggling is blocked for normal apps.
- If Do Not Disturb is set to *Total silence*, the find-my-phone sound is blocked.
- The APK is a debug build signed with a debug key, which is fine for personal use.

## Roadmap

- ESP32-S3 controller (knob, buttons, LEDs, optional OLED)
- Controller mode inside the phone app (use a second phone as the controller)
- BLE signal-strength "hot/cold" finder
