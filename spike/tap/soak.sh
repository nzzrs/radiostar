#!/usr/bin/env bash
# Play the dev build for N minutes and report CPU, memory, drops and underruns.
#
# Usage: spike/tap/soak.sh [minutes]   (default 30)
set -euo pipefail

PKG=${PKG:-link.nzrs.radiostar.dev}
MINUTES=${1:-30}
ACTIVITY="$PKG/com.metrolist.music.MainActivity"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 2; }

if [ -z "${ANDROID_SERIAL:-}" ]; then
    ANDROID_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
    [ -n "$ANDROID_SERIAL" ] || { echo "no device" >&2; exit 2; }
    export ANDROID_SERIAL
fi
adb get-state >/dev/null 2>&1 || { echo "no device" >&2; exit 2; }

echo "device: $(adb shell getprop ro.product.model | tr -d '\r') [$ANDROID_SERIAL]"
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell cmd statusbar collapse >/dev/null 2>&1 || true
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 1
adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
sleep 3

size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
width=${size%x*}
height=${size#*x}

find_play() {
    adb shell uiautomator dump /sdcard/radiostar-ui.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/radiostar-ui.xml > /tmp/opencode/radiostar-ui.xml 2>/dev/null || true
    python3 "$(dirname "$0")/ui.py" /tmp/opencode/radiostar-ui.xml || true
}

coords=$(find_play)
if [ -z "${coords:-}" ]; then
    adb shell input tap $((width * 45 / 100)) $((height * 84 / 100))
    sleep 2
    coords=$(find_play)
fi
[ -n "${coords:-}" ] || { echo "could not find a Play control" >&2; exit 3; }

is_playing() {
    adb shell dumpsys media_session 2>/dev/null | grep -A12 "package=$PKG" | grep -q 'state=PLAYING'
}

if adb shell dumpsys media_session 2>/dev/null | grep -A12 "package=com.metrolist.music" | grep -q 'state=PLAYING'; then
    echo "another media app is playing; pause it first" >&2
    exit 5
fi

# shellcheck disable=SC2086
adb shell input tap $coords
for _ in $(seq 1 10); do
    is_playing && break
    sleep 1
done
is_playing || { echo "playback did not start" >&2; exit 3; }

pid=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$pid" ] || { echo "process not found" >&2; exit 3; }

# Realistic listening: screen off. With the Now Playing screen on, the UI alone
# dominates the process CPU and the tap cost cannot be read.
adb shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true

ticks() { adb shell cat "/proc/$pid/stat" 2>/dev/null | awk '{print $14 + $15}' | tr -d '\r'; }
pss() { adb shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/^ *TOTAL/{print $2; exit}' | tr -d '\r'; }
level() { adb shell dumpsys battery 2>/dev/null | awk '/^ *level:/{print $2; exit}' | tr -d '\r'; }
powered() { adb shell dumpsys battery 2>/dev/null | awk '/powered:/{print $1 $2; exit}' | tr -d '\r'; }

start_ticks=$(ticks)
start_pss=$(pss)
start_level=$(level)
start_epoch=$(date +%s)

echo "soak: ${MINUTES} min, pid=$pid, start pss=${start_pss} kB, battery=${start_level}% ($(powered))"
echo "minute  cpu%   pss_kB  battery"

for minute in $(seq 1 "$MINUTES"); do
    sleep 60
    current_ticks=$(ticks)
    current_pss=$(pss)
    current_level=$(level)
    elapsed=$(( $(date +%s) - start_epoch ))
    cpu=$(awk -v a="$start_ticks" -v b="$current_ticks" -v t="$elapsed" 'BEGIN { if (t <= 0) print 0; else printf "%.1f", (b - a) / 100.0 / t * 100.0 }')
    printf "%6d  %5s  %7s  %s%%\n" "$minute" "$cpu" "$current_pss" "$current_level"
done

adb shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null 2>&1 || true

end_level=$(level)
pss_growth=$(( $(pss) - start_pss ))
underruns=$(adb logcat -d -s RadioStarTap 2>/dev/null | grep -c 'underrun count=' || true)
last=$(adb logcat -d -s RadioStarTap 2>/dev/null | grep 'window=' | tail -1 || true)

echo
echo "summary"
echo "  pid            : $pid"
echo "  pss growth     : ${pss_growth} kB"
echo "  battery        : ${start_level}% -> ${end_level}% ($(powered))"
echo "  underruns      : ${underruns}"
echo "  last window    : ${last:-none}"

ok=1
[ "$pss_growth" -lt 20480 ] || ok=0
[ "$underruns" -eq 0 ] || ok=0
case "$last" in
    *"dropped=0B"*) ;;
    *) ok=0 ;;
esac

[ "$ok" -eq 1 ] && echo "  verdict        : PASS" || echo "  verdict        : FAIL"
[ "$ok" -eq 1 ]
