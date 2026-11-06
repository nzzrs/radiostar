#!/usr/bin/env bash
# Play the dev build for a while and report the analysis tap cadence.
#
# Usage: spike/tap/measure.sh [seconds]   (default 45)
# Exit 0 only when every window is 10.0 s apart and nothing was dropped.
set -euo pipefail

PKG=${PKG:-link.nzrs.radiostar.dev}
PLAY_SECONDS=${1:-45}
ACTIVITY="$PKG/com.metrolist.music.MainActivity"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 2; }

# USB and Wi-Fi ADB often show the same phone twice. Pick one explicitly.
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
    # The Home screen's mini player has no accessibility label on its play
    # button. Tap the title area to open Now Playing, which does.
    adb shell input tap $((width * 45 / 100)) $((height * 84 / 100))
    sleep 2
    coords=$(find_play)
fi

if [ -z "${coords:-}" ]; then
    echo "could not find a Play control; start playback manually and rerun" >&2
    exit 3
fi

is_playing() {
    adb shell dumpsys media_session 2>/dev/null | grep -A12 "package=$PKG" | grep -q 'state=PLAYING'
}

other_playing() {
    adb shell dumpsys media_session 2>/dev/null | grep -A12 "package=com.metrolist.music" | grep -q 'state=PLAYING'
}

if other_playing; then
    echo "another media app (com.metrolist.music) is playing; pause it first" >&2
    exit 5
fi

adb logcat -c
if is_playing; then
    echo "already playing"
else
    started=0
    for attempt in 1 2; do
        # shellcheck disable=SC2086
        adb shell input tap $coords
        for _ in 1 2 3 4 5; do
            if is_playing; then
                started=1
                break 2
            fi
            sleep 1
        done
    done
    if [ "$started" -ne 1 ]; then
        adb shell input keyevent KEYCODE_MEDIA_PLAY >/dev/null 2>&1 || true
        for _ in 1 2 3 4 5; do
            if is_playing; then
                started=1
                break
            fi
            sleep 1
        done
    fi
    if [ "$started" -ne 1 ]; then
        echo "playback did not start" >&2
        adb shell dumpsys media_session 2>/dev/null | grep -A6 "package=$PKG" | head -8 >&2 || true
        exit 3
    fi
fi

echo "playing for ${PLAY_SECONDS}s (control at $coords)"
sleep "$PLAY_SECONDS"
adb shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null 2>&1 || true

adb logcat -d -s RadioStarTap | grep 'window=' > /tmp/opencode/radiostar-tap.log || true

if [ ! -s /tmp/opencode/radiostar-tap.log ]; then
    echo "no tap windows captured" >&2
    exit 4
fi

python3 - /tmp/opencode/radiostar-tap.log <<'PY'
import re
import sys

pattern = re.compile(
    r"(\d{2}):(\d{2}):(\d{2})\.(\d{3}).*window=(\d+) rms=([\d.]+) windows=(\d+) "
    r"dropped=(\d+)B rate=(\d+) ch=(\d+) underruns=(\d+)"
)

rows = []
with open(sys.argv[1], encoding="utf-8") as handle:
    for line in handle:
        match = pattern.search(line)
        if not match:
            continue
        (
            hours,
            minutes,
            seconds,
            millis,
            size,
            rms,
            count,
            dropped,
            rate,
            channels,
            underruns,
        ) = match.groups()
        timestamp = int(hours) * 3600 + int(minutes) * 60 + int(seconds) + int(millis) / 1000
        rows.append(
            (
                timestamp,
                int(size),
                float(rms),
                int(count),
                int(dropped),
                int(rate),
                int(channels),
                int(underruns),
            )
        )

if len(rows) < 3:
    print(f"not enough windows: {len(rows)}")
    sys.exit(1)

intervals = [b[0] - a[0] for a, b in zip(rows, rows[1:])]
dropped = rows[-1][4] - rows[0][4]
rate = rows[-1][5]
channels = rows[-1][6]
underruns = rows[-1][7]

print()
print(f"windows      : {len(rows) + 1}   rate={rate} ch={channels}")
print(f"intervals    : {' '.join(f'{value:.3f}' for value in intervals)}")
print(f"dropped      : {dropped} B over the measured span")
print(f"underruns    : {underruns}")
print(f"window size  : {rows[-1][1]} samples ({rows[-1][1] / rate:.3f} s)")

ok = (
    dropped == 0
    and underruns == 0
    and all(abs(value - 10.0) <= 0.2 for value in intervals)
)
print(f"verdict      : {'PASS' if ok else 'FAIL'}")
sys.exit(0 if ok else 1)
PY
