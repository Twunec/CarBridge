#!/usr/bin/env bash
# Installs a patched car copy on a running emulator and checks, separately:
#   1. installation, 2. the Android Auto launcher entry, 3. that the copy starts with the
#   parked-only lock, and 4. that the lock follows GPS on a second display standing in for the car.
# Usage: CAR_PACKAGE=<package> scripts/emulator-check.sh <signed base.apk> [split.apk ...]
# Writes logcat and screenshots to emulator-check/. Exits non-zero if any check fails.
set -uo pipefail
exec 2>&1
echo "Running from $(pwd) with: $*"

OUT=emulator-check
mkdir -p "$OUT"
FAILED=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILED=1; }

PKG=${CAR_PACKAGE:?set CAR_PACKAGE to the package name of the copy}
echo "Package: $PKG"

adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'

# 1. Installation (a signature or split problem fails here)
if [ $# -gt 1 ]; then out=$(adb install-multiple "$@" 2>&1); else out=$(adb install "$1" 2>&1); fi
echo "$out"
if echo "$out" | grep -q Success; then pass "installed"; else fail "install: $out"; fi
adb shell pm list packages | grep -q "package:$PKG\$" && pass "package listed" || fail "package not listed"

# 2. What Android Auto queries to list the app
if adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.CAR_LAUNCHER \
    | grep -q "$PKG"; then pass "CAR_LAUNCHER activity resolves"; else fail "no CAR_LAUNCHER activity"; fi
adb shell dumpsys package "$PKG" > "$OUT/dumpsys-package.txt"
grep -q "org.carbridge.guard.ParkedGuardProvider" "$OUT/dumpsys-package.txt" \
  && pass "lock provider registered" || fail "lock provider not registered"

COMPONENT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER "$PKG" | tail -1 | tr -d '\r')
echo "Launcher: $COMPONENT"

# A second display stands in for Android Auto's virtual car display
adb shell settings put global overlay_display_devices 1280x720/160
sleep 5
DISPLAY_ID=$(adb shell dumpsys display | grep -oE 'mDisplayId=[1-9][0-9]*' | head -1 | cut -d= -f2 || true)
DISPLAY_ID=${DISPLAY_ID:-}
echo "Car stand-in display: ${DISPLAY_ID:-none}"
if [ -n "$DISPLAY_ID" ]; then pass "second display created"; else fail "no second display"; adb shell dumpsys display | head -60; exit 1; fi

guard_log() { adb logcat -d -s CarBridgeGuard:I | tr -d '\r'; }
launch() {
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start --display "$DISPLAY_ID" -n "$COMPONENT" >/dev/null
  sleep 6
}
expect_lock() { # $1 = expected lock state, $2 = description; a cold first start can take a while
  for _ in $(seq 1 20); do
    if guard_log | grep -q "Car screen lock: $1"; then pass "$2 ($1)"; return; fi
    sleep 1
  done
  fail "$2: expected $1"; guard_log | tail -5
}
shot() { adb shell screencap -d "$DISPLAY_ID" -p > "$OUT/$1.png" 2>/dev/null || adb exec-out screencap -p > "$OUT/$1.png"; }

# 3. Starts with the lock; without location it stays locked
adb shell pm revoke "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell pm revoke "$PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
launch
guard_log | grep -q "Parked-only lock active" && pass "lock started with the app" || fail "lock didn't start"
if adb shell pidof "$PKG" >/dev/null; then pass "copy is running"; else fail "copy isn't running (crashed?)"; fi
expect_lock NO_PERMISSION "locked without location permission"
shot 1-no-permission

# 4. With location: locked until GPS shows the car still, then locked again when it moves
adb shell cmd location set-location-enabled true
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
launch
expect_lock CHECKING "locked until a GPS fix"
for _ in $(seq 1 10); do adb emu geo fix 14.4378 50.0755 200 8 0 >/dev/null; sleep 1; done
expect_lock NONE "unlocked when parked"
shot 2-parked
lon=14.4378
for i in $(seq 1 6); do
  lon=$(echo "$lon + 0.0005" | bc)
  adb emu geo fix "$lon" 50.0755 200 8 40 >/dev/null
  sleep 1
done
expect_lock MOVING "locked when moving"
shot 3-moving

# The phone's own screen is never locked
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start --display 0 -n "$COMPONENT" >/dev/null
sleep 5
if guard_log | grep -q "Car screen lock"; then fail "phone screen was locked"; else pass "phone screen left alone"; fi
shot 4-phone

adb logcat -d > "$OUT/logcat.txt"
adb shell settings delete global overlay_display_devices
exit $FAILED
