# Govee Off-Grid

An Android app that reads Govee sensors over Bluetooth Low Energy. Long history lives on a Raspberry Pi on the LAN (`10.0.0.69`). The app does not use the Govee cloud.

The Pi manages the H5151. A sensor bound to that gateway is the gateway's sensor: the phone does not store or upload it. The phone BLE-reads sensors that are not bound and posts those readings to the Pi. Graphs on the phone request the Pi. Six ranges: 3 min, 30 min, 3 day, 3 month, 1 year, 3 year.

The Pi sends only the existing status query (`0x33`, command `0xAA`). It does not send power, color, reset, or Wi-Fi provisioning frames. A bind command is not sent until a captured notify from this gateway names a sub-device. Until then the bound set stays empty, the phone uploads every sensor it decodes, and `/health` says the gateway is not supplying readings.

Version 0.2.5. Temperatures on the phone and on the Pi page are Fahrenheit. The stored samples stay Celsius. The device list is a grid of tiles. Hold the grip to drag a tile. Wide makes a tile span the row. Scan starts a reading and becomes Stop while it is running. The gear holds the history-server token and CSV export. Long-press a tile to name that sensor. The name is saved on the Pi and shown instead of the advertised name. The edit screen still shows the advertised name, address, and readings.

A tile with both temperature and humidity shows air VPD in kPa: saturation vapor pressure from the Magnus-Tetens equation, times (1 − relative humidity). The same pair of history series draws a VPD chart. Leaf temperature is not estimated.

## What you can read

Advertisements are decoded for the public packet layouts of:

- Thermo-hygrometers: H5072, H5074, H5075, H5100, H5101, H5102, H5103, H5104, H5105, H5108, H5174, H5177, H5178, H5179, H5051, H5052, H5071, H5110
- Air: H5106 (temperature, humidity, PM2.5), H5140 (temperature, humidity, CO2)
- Meat probes: H5181, H5182, H5183, H5184, H5185, H5198
- Contact and presence: encrypted H5121–H5126 and H5130 frames, H5127 presence
- Gateway: H5151, recognized by name. Its short advertisement is presence, not a temperature.

Other Govee devices that are heard, including lights such as the H7021, are listed as seen. The app does not send power, color, reset, or Wi-Fi provisioning commands.

A row goes stale two minutes after the last advertisement. The phone still keeps a local SQLite buffer for 14 days, for alarms and CSV export. High and low temperature alarms are stored on the phone and raise a local notification. CSV export uses the Android share sheet and the phone's own rows, which are the unbound sensors.

## History server

`pi/govee-history/` is installed on the Pi at `10.0.0.69` (hostname `mqtt`, Raspberry Pi 3 Model B Plus, Debian 13). The database is `/var/lib/govee-history/history.db` on the root disk (`/dev/sda2`, hundreds of gigabytes free). The HTTP process listens only on `10.0.0.69:8787`. Open that address in a browser to see the sensors. Reads do not need a token. Uploads do. The token is in `/etc/govee-history/token` (mode 600, user `govee`) and is entered once in the app under **Server**. The phone refuses any other host. The MQTT broker on port 1883 is still running and is not used by this service.

Raw samples are 10-second buckets kept 36 hours. Rollups are 15 minutes (100 days), 6 hours (400 days), and 1 day (3 years and 40 days). The 3-year chart averages those days into 3-day buckets.

The gateway process is the only Bluetooth connection to the H5151. It holds the link, writes the status query, then once a minute scans briefly for sensors to bind later. Nothing is marked bound until a notify layout is known. The phone polls `GET /v1/bound` and skips those addresses.

The onboard adapter `hci0` is present and nothing else uses it. It boots soft-blocked because `/etc/modprobe.d/rfkill_default.conf` sets `default_state=0`. `govee-bt.service` clears that block and powers the adapter. Boot config is unchanged.

The H5151 `Govee_H5151_2036` (`C8:6B:84:C6:20:36`) is in range. The gateway process asks the controller for a 100–200 ms connection interval and subscribes to the thermometer service when the gateway publishes it. The light service is used only when that is the service present. A failed attempt waits 60 seconds before the next one, so the shared Wi-Fi radio is not hammered. Nothing is marked bound until a notify layout is known. The phone uploads the sensors it hears.

## Gateway

The phone no longer connects to the H5151. The Pi sends the status query on the service the gateway published. The thermometer service is `494e5445-4c4c-495f-524f-434b535f4857`. The light service `00010203-0405-0607-0809-0a0b0c0d1910` is the fallback. One 20-byte status frame, XOR checksum.

The H5151 that was in range while this was written advertised manufacturer id `0x8801` with a 4-byte payload and did not carry a temperature. Expect the same. If the control service is absent, the Pi reports that and the phone keeps uploading what it hears.

## Sideload

The release APK is debug-signed so it installs on a phone you control. It is not a Play Store upload key. Play Protect blocks a browser install of that debug signature. Install it with adb.

https://github.com/dkraft/Govee/releases/download/v0.2.5/govee-offgrid-0.2.5.apk

```
adb install -r govee-offgrid-0.2.5.apk
```

On first launch the app asks for Bluetooth scan and connect, and for the history-server token. On Android 12 and older it asks for fine location instead, because that is how those versions gate BLE scans. Notifications are used for the ongoing scan and for alarms. Grant them if you want alarms while the screen is off. The app has an Internet permission so it can reach `10.0.0.69`. It does not call any other host.

Leave the scan running. Android keeps it in a foreground service so the history continues when the screen is locked. Stop ends the scan.

## Build

Requires JDK 17+ and Android SDK 34.

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew test assembleDebug
```

The sideload APK is `app/build/outputs/apk/debug/app-debug.apk`. `local.properties`, keystores, and built APKs are gitignored.

`./gradlew test` runs the decoder checks, including an H5075 packet of 21.5 °C / 45% RH, a negative temperature, an H5074 packet, and an H5151 advertisement that must stay a gateway. It also checks that a bound address is not uploaded.

```
cd pi/govee-history && python3 test_server.py && python3 test_decode.py && python3 test_gateway.py
```

Those checks cover the six ranges, bound-address rejection, the same four advertisements, and the rule that discovery does not bind a sensor and does not send any command except the status query.

## License

MIT. See [LICENSE](LICENSE).

Packet layouts are reimplemented from publicly described Govee advertisements. This project is not affiliated with Govee or Shenzhen Intellirocks.
