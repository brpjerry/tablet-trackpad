# Testing

Unit tests run with `./gradlew :app:testDebugUnitTest`. `core/` and the live scroll, drag and
pinch classes run on the JVM against a fake `Injector` and a manual clock. `GestureInjectorTest`
runs the real injector under Robolectric with Android 16's own stroke and gesture classes,
recording only what reaches `dispatchGesture`. `SettingsScreenTest` renders the settings screen
under Robolectric with Compose's test rule and checks the About and privacy card and its links.

## Verified on a tablet

On a Galaxy Tab S9 Ultra (SM-X910, One UI 8.5, Android 16), in portrait and landscape, driven with
multi-touch input from a virtual touchscreen (`scripts/touch/`, see below):

- Edge swipes from both sides, the hide button, moving and resizing the pad, rotation.
- Click, double-click and right click each register once; edge-zone taps reach the app.
- Live scroll in the test area's classic and Compose lists, Samsung Settings and Chrome: within
  about 1% of the expected distance, no restarts or cancellations. Flicks fling.
- Live drag follows the cursor. Tap, rest, then drag selects text in Chrome.
- Live pinch zooms 2× in Chrome and the test area, centered on the cursor. A 4× pinch re-grips once
  and delivers 4.00×, and 0.25× out takes three grips that start 694 px apart. The test area
  reads 3.7–4.0× in and 0.25–0.26× out, because Compose's transform detector drops the movement
  before its touch slop on each new grip.
- With the cursor on the navigation bar (y 2895, bar from 2876), a pinch puts both fingers down at
  y 2833 and zooms 2.01×, and a scroll presses at y 2833; nothing reaches the bar's buttons. With
  the cursor just above the bar, a scroll moved a list 463 px for 300 px of finger travel at 1.54×
  speed.
- Back-to-back flicks: a fling cut short by the next flick is dropped, not replayed.
- A pinch with the cursor beside the pad plays on lift, and live gestures stay on.
- Scrolling toward the bottom edge with the cursor on the navigation bar: the press starts 16 touch
  slops back from the edge, so a 300 px scroll needed one restart in 19 steps. Without that
  headroom every step pressed again (18 restarts in 20 steps).
- Three-finger swipes open Recents, go Home and go Back.
- With the cursor over the pad, a click and a scroll reach the app underneath.
- Scroll zone: a 200 px swipe scrolled 908 px (900 expected); a 600 px swipe that needed one
  stroke restart scrolled 2694 px (2700 expected).
- Setup on One UI: the Galaxy wording (Installed apps → Tablet Trackpad → switch → Allow) matches,
  setup comes back by itself after **Allow**, and the battery ("Stop optimizing battery usage?")
  and tile ("Add this button?") prompts appear. One UI opens the main Accessibility page without
  highlighting the entry, so the Installed apps step is needed.
- Injected strokes are **not** cancelled by touches on the pad, as on the emulator, so live
  gestures stay on. Samsung logs "wait for adding window timeout" whenever an overlay is added;
  it is harmless (the window is added in under 10 ms).

The touches came from a virtual touchscreen rather than the panel itself, which Android routes the
same way. The emulator runs (Android 17) found the same results; there a Compose list overshoots
by one touch slop per scroll, which the tablet's Compose did not.


## Still to check with real fingers

1. A few two-finger scrolls and a pinch while watching **Settings → Diagnostics** for *"Live
   gestures turned off"* or *"cancelled by the system"*. Neither appeared in testing.
2. Tune the defaults: pointer speed and acceleration, tap timing, edge zone size and position.
3. Samsung's Edge panel handle, if enabled, may sit over the right zone; move either one.
4. With gesture navigation (the tablet was tested with buttons), check that an edge swipe toggles
   the trackpad instead of going back.
5. The keyboard, split screen and the lock screen with the trackpad shown.

## Touch input for tests

`scripts/touch/` plays multi-touch frames as real finger input, so tests exercise the same path as
a hand on the screen. On an emulator it writes to the emulator's touchscreen (needs `adb root`).
Production devices don't allow that, so there it registers a virtual touchscreen through uinput,
which the adb shell user may do.

```sh
scripts/touch/touch.sh setup     # build mt-inject with the NDK and push it
scripts/touch/touch.sh serve     # device only: register the virtual touchscreen
python3 scripts/touch/frames.py "tap(2300, 1400)" | scripts/touch/touch.sh
python3 scripts/touch/frames.py \
  "stroke([(1,2200,1700),(2,2400,1700)], [(1,2200,1300),(2,2400,1300)], steps=40); lift()" \
  | scripts/touch/touch.sh
scripts/touch/touch.sh stop      # device only: remove it again
```

Coordinates are screen pixels in the current rotation, and `touch.sh` returns once the frames have
played. It fails if the virtual touchscreen went away meanwhile: `mt-inject` stops at the first
event it cannot write and exits non-zero. `frames.py` also has `goto(x, y, pad)`, which moves
the cursor to a point given the pad surface rectangle (below the top bar). Running `uiautomator dump` while testing unbinds every
accessibility service for a moment, so the trackpad disconnects and reconnects.
