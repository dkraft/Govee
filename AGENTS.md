# Agent notes

This file is for the next person or agent changing the app or the Pi. The user-facing description is `README.md`. Do not put the Pi password or the history token in the repo, in this file, or in memory notes.

Version is `0.2.0` (`versionCode` 2). Package `dev.offgrid.govee`. minSdk 26, compile/target 34. Kotlin, view binding. No chart library. Do not add Android dependencies.

## What the system is

The phone reads Govee sensors over BLE. Long history lives on a Raspberry Pi on the LAN. The app does not use the Govee cloud, the Govee LAN API, or MQTT.

The Pi is the only BLE central for the H5151. It auto-binds a sensor only after a captured notify names that sensor. There is no user prompt. Lights and other non-sensors are not bound and are not commanded.

The phone does not open GATT to the H5151. `GatewayLink` stays in the tree as the frame reference and is not started. The phone scans, uploads readings for addresses that are not in the Pi's bound set, and graphs every sensor from the Pi.

Until a notify layout is known, `parse_notify` returns nothing, the bound set stays empty, and the phone uploads every sensor it decodes. That is the current state, not a bug to paper over.

## Do not

- Send any BLE opcode other than the existing status query: 20 bytes, byte 0 `0x33`, byte 1 `0xAA`, byte 19 the XOR of the first 19. Empty payload checksum is `0x99`. No power, color, reset, Wi-Fi provisioning, or invented bind command.
- Mark a device bound because it was advertised or because its name looks like a sensor. Binding happens only when `parse_notify` returns a reading.
- Let the phone and the Pi both hold the H5151. One radio, one central.
- Restart mosquitto, publish to MQTT, or decide that port 1883 should carry readings. The broker is in use and was left alone.
- Edit `/boot/config.txt` or `/boot/firmware/config.txt`. There is no `disable-bt` overlay. Do not add one.
- Tight-loop H5151 connects. The Pi 3 shares one Broadcom radio with `wlan0`. A burst of failed connects stalled Wi-Fi for about a minute on 2026-11-12. The gateway waits 60 seconds after a failed connect (`FAIL_BACKOFF_SECONDS`). Keep that backoff.
- Commit `/etc/govee-history/token`, the Pi password, or an SSH private key.
- Treat an H5151 advertisement as a temperature. Manufacturer id `0x8801` with a short payload is presence. The decoder tests lock this in.

## Pi

Observed 2026-11-12. Re-check with SSH before assuming it is still true.

| Fact | Value |
| --- | --- |
| Address | `10.0.0.69` on `wlan0` |
| Hostname | `mqtt` |
| Hardware | Raspberry Pi 3 Model B Plus, 1 GB RAM |
| OS | Debian 13 (trixie), aarch64, kernel 6.18 |
| SSH | user `pi`, key `~/.ssh/id_ed25519`, `BatchMode` and `IdentitiesOnly` |
| Privilege | `sudo -n` works. Do not store the password. |
| Disk | `/dev/sda2`, hundreds of gigabytes free. Not a small SD card. |
| Python | `/usr/bin/python3` is 3.13 |
| MQTT | mosquitto on `0.0.0.0:1883`, running since boot |
| History | `10.0.0.69:8787` only. `127.0.0.1:8787` is closed on purpose. |
| Other listeners | ssh 22, and an unrelated `python3 /usr/local/sbin/hdmi-inverter.py` on 8088. Home Assistant `8123` was closed. |
| Bluetooth | `hci0` present, address `B8:27:EB:62:70:86`. `bluetooth.service` enabled. No `hciuart` unit. |

`/etc/modprobe.d/rfkill_default.conf` is `options rfkill default_state=0`, so `hci0` boots soft-blocked (`PowerState: off-blocked`, hard block 0). Wi-Fi is unblocked by something else. `govee-bt.service` runs `/opt/govee-history/unblock-bt.sh`, which clears only the bluetooth soft block and runs `bluetoothctl power on`. The `iw` power-save line in that script does nothing until `iw` is installed.

Login:

```
ssh -o BatchMode=yes -o IdentitiesOnly=yes -i ~/.ssh/id_ed25519 pi@10.0.0.69
```

Installed layout, user `govee`, copies of `pi/govee-history/`:

- Code in `/opt/govee-history/`
- Database `/var/lib/govee-history/history.db` (WAL)
- Token `/etc/govee-history/token`, mode 600, owner `govee`
- Units: `govee-history.service`, `govee-gateway.service`, `govee-bt.service`, `govee-history-rollup.timer` (every 15 minutes)

`python3-dbus` and `python3-gi` are installed. The `sqlite3` shell is not. Query the database with `sudo -n -u govee python3`. BlueZ D-Bus policy allows the default context to send to `org.bluez`, so the `govee` user can run the gateway without being root.

Redeploy by copying the changed files to `/opt/govee-history/` and restarting only the unit that imports them. Restarting `govee-gateway` starts another connect attempt. Do not also restart `bluetooth` in the same minute.

## H5151, as heard

Name `Govee_H5151_2036`, address `C8:6B:84:C6:20:36`, random static, RSSI about −34 to −38 dBm. Flags `0x06` (general discoverable, BR/EDR not supported). Manufacturer `34817` (`0x8801`), payload `ec 00 01 01`. The advertisement is connectable undirected.

A connect does complete. `btmon` then shows `LE Read Remote Used Features` failing with status `0x3e` (Connection Failed to be Established) about 300 ms later, and BlueZ reports `org.bluez.Error.Failed: le-connection-abort-by-local`. Supervision timeout on the create-connection command was the kernel default, 420 ms (`0x002a`), interval about 49 ms. GATT never appears, so the status query is not sent and `/health` stays `gateway=down` with that detail. The bound table stays empty.

Writing `MinConnectionInterval`, `MaxConnectionInterval`, and `ConnectionSupervisionTimeout` into `/etc/bluetooth/main.conf` loaded those numbers into `/sys/kernel/debug/bluetooth/hci0/`, and the HCI create-connection command still used `0x0018` / `0x0028` / `0x002a`. That edit was reverted. Do not treat a main.conf change as a fix until `btmon` shows different create-connection parameters and the link survives long enough for the control service.

`StopDiscovery` from a second process returns `No discovery started` while the gateway owns the discovery session. Do not run a second central "to test" while `govee-gateway` is in its connect or scan window.

Devices also heard that day, and not bound:

- `GVH5100_3A6A` `D0:C8:46:06:3A:6A`
- `GVH5100_7031` `D0:C8:45:86:70:31`
- `GVH5100_1D41` `C8:6B:82:86:1D:41`
- Light `Govee_H7021_2C52` `C8:36:35:30:2C:52` (ignored by the binder)

The gateway duty cycle is about 50 seconds connected, then 8 seconds of passive LE scan. One adapter cannot do both at once. During the scan, and during the 60 second failure backoff, bound-sensor history has a gap. The phone must not fill that gap for a bound address.

`gateway_bluez.py` must import the GLib main loop with `from dbus.mainloop.glib import DBusGMainLoop`. `import dbus` alone has no `dbus.mainloop` attribute, and that `AttributeError` crash-looped the unit until it hit the systemd start limit. `make_radio` only catches `ImportError`.

## History data

Raw buckets are 10 seconds, kept 36 hours. Rollups recompute from the next finer table before old rows are deleted, so the job is idempotent:

| Range | Window | Bucket | Table |
| --- | --- | --- | --- |
| `3m` | 3 min | 10 s | `raw` |
| `30m` | 30 min | 10 s | `raw` |
| `3d` | 3 days | 15 min | `roll_15m`, kept 100 days |
| `3mo` | 92 days | 6 h | `roll_6h`, kept 400 days |
| `1y` | 366 days | 1 day | `roll_1d`, kept 3 years + 40 days |
| `3y` | 1096 days | 3 days | `roll_1d`, folded in Python |

Metrics: `temp_c`, `humidity_pct`, `battery_pct`, `co2_ppm`, `pm25`, plus `probe_N` from extra text `probe {id}={temp}` or a `probes` list. One thermo-hygrometer reading stores two points. Tests expect `stored == 2`.

Phone source does not overwrite an existing bucket. Gateway source replaces a phone bucket and, when the existing row is already gateway, merges min, max, sum, and count. HTTP posts with `source` other than missing or `phone` are dropped and the response is still 200 with `stored: 0`. A phone post for a bound address increments `dropped_bound` and writes nothing. There is no HTTP way to insert `source=gateway`. The gateway process writes SQLite itself.

`/health` fields used by the phone are `gateway` (`up`, `scanning`, `connecting`, `down`, `no-control-service`) and `bound`. `gateway_detail` is in the `status` table and is not in the JSON. An empty status row makes `/health` report `down`.

GET is open on the LAN, including `GET /`, which is a plain HTML list of sensors. A browser has no token, and that used to answer `{"error":"unauthorized"}` for every path. POST still requires the bearer token. `hmac.compare_digest` rejects a missing or wrong token. An empty token file fails closed for writes. `--rollup-only` does not read the token file.

Temperatures are stored as Celsius (`temp_c`). The phone and the Pi page show Fahrenheit. Alarm thresholds are entered in Fahrenheit and saved as Celsius. Do not change the stored unit.

A display name is separate from the advertised name. `aliases` is keyed by uppercased address. `GET /v1/names` is open. `POST /v1/names` needs the bearer token and stores at most 48 characters; a blank name deletes the row. The phone long-press editor shows the advertised name, address, and readings, then saves the typed name. The list, graph title, alarm title, and Pi page show that name instead of the advertisement. Do not write the alias over `latest.name`. The advertisement is how the decoder recognizes the model.

The phone list is the sensors it is hearing, plus every sensor in `GET /v1/latest` that it is not hearing. `fromBound` on those rows means "from the Pi", and the row says `history` rather than a signal strength. Graphs load without a token. Uploads still need one. The 3-minute and 30-minute charts read raw buckets, so they are empty when the newest sample is older than the window. The 3-day chart reads `roll_15m`.

Android cleartext domain-config does not apply to an IP literal. `network_security_config.xml` allows cleartext in the base config, and `HistoryClient` is the allowlist: host `10.0.0.69`, port `8787`, and a `check` that the host string stays that value.

## Phone

`ScanService` syncs 3 seconds after start, then every 30 seconds. If the token is blank, the board says history needs a token. Otherwise it loads `/health`, replaces the bound set from `GET /v1/bound`, loads `GET /v1/latest` for bound addresses, and posts up to 200 pending phone rows.

`PhoneUpload.shouldKeep` is address membership. An empty bound set uploads every decoded sensor. A bound address is not written to the phone database and is not updated from a BLE advertisement. Live rows for bound sensors come from the server. Local alarms and CSV export use the phone database, so bound history is not in the CSV. Phone retention is 14 days. Schema version 2 adds `upload_state`. Upgrade marks gateway-like empty rows as not pending.

The history screen is a custom `View` (`HistoryChart`), not a library. Ranges are 3 min, 30 min, 3 day, 3 month, 1 year, 3 year. The root row of a sensor opens that screen.

Colors already in the layouts: background `#12160F`, card `#1C2416`, text `#E7ECDF`, muted `#8B937E`, accent `#D6F25C`, on-accent `#1A2208`, warn `#E07A3D`.

The phone app has not been run on a device from this machine. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. `govee-offgrid-0.1.0.apk` at the repo root is the old build.

## Build and test

System Java on this machine is a JRE. `javac` is not on `PATH`, and `/usr/lib/jvm/java-21-openjdk-amd64` has no compiler. Gradle needs:

```
JAVA_HOME=/home/a/opt/jdk-21 ./gradlew test assembleDebug
```

`--offline` works. `GatewayLink` still warns about deprecated `BluetoothGatt` calls. Those warnings are not test failures.

Python, from `pi/govee-history/`:

```
python3 test_server.py && python3 test_decode.py && python3 test_gateway.py
```

Decoder fixtures that must match on both sides:

- H5075 `00 03 49 9A 50 00` at id `0xEC88` is 21.5 °C, 45%, battery 80
- Negative `00 80 C4 7C 64 00` is −5.0 °C, 30%, battery 100
- H5074 `00 66 08 94 11 5A 00` is 21.5 °C, 45%, battery 90
- Name `Govee_H5151_2036`, id `0x8801`, payload `EC 00 01 01` is a gateway with null temperature and humidity

H7021 is `OTHER`. Apple `0x004C` and unknown names are ignored.

`schema.sql` has no `PRAGMA`. `executescript` mishandles `PRAGMA` result rows. Journal mode and `busy_timeout` are set in `History.__init__`.

## When a real notify finally arrives

Store the hex in `status` (`last_notify`, `last_notify_ms`) the way `apply_notify` already does. Name the layout in `parse_notify` only after the bytes identify a sub-device. Returning readings from `parse_notify` is what inserts into `bound`. Still no new opcode, and still no prompt. A phone post for that address must then be rejected, and the phone must stop recording it.
