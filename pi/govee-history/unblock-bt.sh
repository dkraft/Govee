#!/bin/sh
# The Pi boots with "options rfkill default_state=0", so hci0 stays
# soft-blocked and bluetoothd cannot power it. Wi-Fi is unblocked
# elsewhere. This only clears the bluetooth soft block.
set -eu
for d in /sys/class/rfkill/rfkill*; do
  if [ -f "$d/type" ] && [ "$(cat "$d/type")" = bluetooth ]; then
    echo 0 > "$d/soft"
  fi
done
# The onboard radio is shared with wlan0. Leave Wi-Fi power save off so a
# BLE connection attempt is less likely to stall the LAN.
if command -v iw >/dev/null 2>&1; then
  iw dev wlan0 set power_save off 2>/dev/null || true
fi
i=0
while [ "$i" -lt 10 ]; do
  if bluetoothctl show 2>/dev/null | grep -q "Powered: yes"; then
    exit 0
  fi
  bluetoothctl power on >/dev/null 2>&1 || true
  sleep 1
  i=$((i + 1))
done
echo "bluetooth adapter did not power on" >&2
exit 1
