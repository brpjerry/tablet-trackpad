#!/usr/bin/env bash
# Plays multi-touch frames as real (non-injected) finger input on an emulator or a device.
#
#   scripts/touch/touch.sh setup    build mt-inject with the NDK and push it
#   scripts/touch/touch.sh serve    device: register a virtual touchscreen that stays until `stop`
#   scripts/touch/touch.sh stop     device: remove it again
#   python3 scripts/touch/frames.py '...' | scripts/touch/touch.sh
#
# Frames are "<delay_ms> [<id> <x> <y>]..." in screen pixels of the current rotation; see
# mt-inject.c. On an emulator they go straight into its touchscreen, which needs adb root.
# Production devices don't allow that, so there they go to a virtual touchscreen made with uinput,
# fed through a local fifo by one long-lived adb shell.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
bin=/data/local/tmp/mt-inject
state=${XDG_RUNTIME_DIR:-/tmp}/mt-inject-$(adb get-serialno 2>/dev/null </dev/null || echo default)
fifo=$state.fifo
# Queries must not read stdin: that is where the frames are coming in.
adb_() { adb "$@" </dev/null; }

is_emulator() { [[ $(adb_ shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]; }

# Width and height in the natural orientation, which is what the touchscreen axes follow.
natural_size() { adb_ shell wm size | awk '/Physical size/ {sub("x", " ", $3); print $3}'; }

case "${1:-}" in
    setup)
        ndk=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* | sort -V | tail -1)
        case $(adb_ shell getprop ro.product.cpu.abi | tr -d '\r') in
            arm64-v8a) arch=aarch64 ;;
            x86_64) arch=x86_64 ;;
            *) echo "unsupported ABI" >&2; exit 1 ;;
        esac
        cc=$(ls "$ndk"/toolchains/llvm/prebuilt/linux-x86_64/bin/$arch-linux-android3*-clang | sort -V | tail -1)
        out=$(mktemp -d)/mt-inject
        "$cc" -O2 -Wall -o "$out" "$here/mt-inject.c"
        if is_emulator; then
            adb_ root >/dev/null
            adb_ wait-for-device
        fi
        adb_ push "$out" $bin >/dev/null
        adb_ shell chmod 755 $bin
        exit 0
        ;;
    serve)
        "$0" stop
        read -r w h < <(natural_size)
        mkfifo "$fifo"
        # Each writer of the fifo ends one cat; the loop keeps the device's stdin open between them.
        setsid bash -c 'while :; do cat "$1"; done | adb shell "$2" uinput "$3" "$4" "$3" "$4"' \
            serve "$fifo" $bin $((w - 1)) $((h - 1)) >/dev/null 2>&1 &
        echo $! >"$state.pid"
        exit 0
        ;;
    stop)
        if [[ -f $state.pid ]]; then
            kill -- -"$(cat "$state.pid")" 2>/dev/null || true
            rm -f "$state.pid"
        fi
        rm -f "$fifo" "$state.ms"
        adb_ shell pkill -x mt-inject || true
        exit 0
        ;;
esac

if is_emulator; then
    # The emulator exposes one multi-touch device per display; display 0's is "..._1".
    dev=$(adb_ shell getevent -pl 2>/dev/null | awk '/add device/ {d=$4} /name:.*virtio_input_multi_touch_1"/ {print d; exit}')
    read -r w h < <(natural_size)
    rot=$(adb_ shell settings get system user_rotation | tr -d '\r')
    if [[ $rot == 1 || $rot == 3 ]]; then tmp=$w; w=$h; h=$tmp; fi
    exec adb shell $bin "$dev" 32767 32767 "$w" "$h"
fi

if ! adb_ shell pidof mt-inject >/dev/null; then
    echo "no virtual touchscreen; run: $0 serve" >&2
    exit 1
fi
read -r w h < <(natural_size)
rot=$(adb_ shell dumpsys window displays | awk -F'ROTATION_' '/mCurrentRotation/ {print $2+0; exit}')
# Screen pixels of the current rotation to the touchscreen's natural axes.
# Writing to the fifo returns at once, so wait out the frames' delays to return when they're played.
total=$state.ms
awk -v r="$rot" -v w="$w" -v h="$h" -v total="$total" '{
    ms += $1
    line = $1
    for (i = 2; i + 2 <= NF; i += 3) {
        x = $(i + 1); y = $(i + 2)
        if (r == 90)       { nx = w - 1 - y; ny = x }
        else if (r == 180) { nx = w - 1 - x; ny = h - 1 - y }
        else if (r == 270) { nx = y;         ny = h - 1 - x }
        else               { nx = x;         ny = y }
        line = line " " $i " " nx " " ny
    }
    print line
    fflush()
}
END { print ms > total }' >"$fifo"
sleep "$(awk '{print $1 / 1000 + 0.05}' "$total")"
# mt-inject stops at the first event it cannot write, so a missing process means frames were lost.
if ! adb_ shell pidof mt-inject >/dev/null; then
    echo "the virtual touchscreen went away while playing; frames may be lost. Run: $0 serve" >&2
    exit 1
fi
