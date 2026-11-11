# Govee Off-Grid

An Android app that reads Govee sensors over Bluetooth Low Energy and keeps the history on the phone. It does not use the Govee cloud, and the app has no Internet permission.

The phone takes the place of the Wi-Fi uplink. A gateway such as the H5151 is still useful hardware: the app shows that it is nearby, reads its Device Information service, and, when the standard Govee control service is present, sends one status query. Sensor values come from the sensors' own advertisements, which is what keeps working when the gateway has no route off the site.

Version 0.1.0.

## What you can read

Advertisements are decoded for the public packet layouts of:

- Thermo-hygrometers: H5072, H5074, H5075, H5100, H5101, H5102, H5103, H5104, H5105, H5108, H5174, H5177, H5178, H5179, H5051, H5052, H5071, H5110
- Air: H5106 (temperature, humidity, PM2.5), H5140 (temperature, humidity, CO2)
- Meat probes: H5181, H5182, H5183, H5184, H5185, H5198
- Contact and presence: encrypted H5121–H5126 and H5130 frames, H5127 presence
- Gateway: H5151, recognized by name. Its short advertisement is presence, not a temperature.

Other Govee devices that are heard, including lights such as the H7021, are listed as seen. The app does not send power, color, reset, or Wi-Fi provisioning commands.

A row goes stale two minutes after the last advertisement. History is kept in a local SQLite database for 14 days. High and low temperature alarms are stored on the phone and raise a local notification. CSV export uses the Android share sheet.

## Gateway link

Tap **Link gateway** on an H5151 row. The phone connects over BLE and reads manufacturer, model, serial, hardware, and firmware when the device publishes them.

If the control service `00010203-0405-0607-0809-0a0b0c0d1910` is present, the app writes one 20-byte status frame (`0x33`, command `0xAA`, XOR checksum) and shows the notify that comes back. If that service is absent, the gateway only uplinks over Wi-Fi. The phone keeps reading the sensors directly.

The H5151 that was in range while this was written advertised manufacturer id `0x8801` with a 4-byte payload and did not carry a temperature. Expect the same.

## Sideload

The release asset is a debug-signed APK, `govee-offgrid-0.1.0.apk`. Debug signing is enough to install on a phone you control. It is not a Play Store upload key.

```
adb install -r govee-offgrid-0.1.0.apk
```

On first launch the app asks for Bluetooth scan and connect. On Android 12 and older it asks for fine location instead, because that is how those versions gate BLE scans. Notifications are used for the ongoing scan and for alarms. Grant them if you want alarms while the screen is off.

Leave the scan running. Android keeps it in a foreground service so the history continues when the screen is locked. Stop ends the scan.

## Build

Requires JDK 17+ and Android SDK 34.

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew test assembleDebug
```

The sideload APK is `app/build/outputs/apk/debug/app-debug.apk`. `local.properties`, keystores, and built APKs are gitignored.

`./gradlew test` runs the decoder checks, including an H5075 packet of 21.5 °C / 45% RH, a negative temperature, an H5074 packet, and an H5151 advertisement that must stay a gateway.

## License

MIT. See [LICENSE](LICENSE).

Packet layouts are reimplemented from publicly described Govee advertisements. This project is not affiliated with Govee or Shenzhen Intellirocks.
