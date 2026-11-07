# Tablet Trackpad

Use a large Android tablet with one hand. Tablet Trackpad puts a small translucent trackpad where
your hand already is and moves a cursor over the whole screen, so you can reach every corner
without shifting your grip or switching hands. It helps whenever one hand is all you have free, and
it is made with people who don't have full use of one hand in mind.

The pad works like a laptop's: tap to click, drag, scroll with momentum, pinch to zoom and swipe
with three fingers for Back, Home and Recents. Swipe in from a screen edge to show or hide it, drag
its top bar to move it, and drag its corner to resize it.

It runs as an accessibility service, which is how Android lets an app draw over other apps and
touch the screen for you. It does not read what is on your screen and collects no data; see
[Privacy](#privacy).

Requires Android 12 or later. It was built for and tested on a Galaxy Tab S9 Ultra (Android 16).

## Gestures

| On the pad | Does |
| --- | --- |
| One finger | Move the cursor (with acceleration) |
| Tap | Click |
| Tap twice | Double-click |
| Tap, then touch and move | Drag. Rest before moving to long-press first (pick up icons, select text) |
| Two-finger tap | Long press, Android's right click (default) |
| Tap, then touch and hold | Long press instead, when chosen in Settings → Clicking; keep holding and move to drag what you pressed. Two-finger taps then do nothing |
| Two-finger drag | Scroll, with momentum when you flick |
| One finger up or down in the scroll zone | Scroll faster (off by default; see below) |
| Pinch | Zoom around the cursor |
| Three-finger swipe up / down / left / right | Recents / Home / Back / Back (configurable) |
| Three-finger tap | Nothing by default (configurable) |

Around the pad:

- **Edge swipe**: swipe inward from the marked zone on the left or right screen edge to toggle the
  trackpad. A plain tap on the zone goes through to the app underneath. Where the trackpad lies over
  a zone, an inward swipe there still toggles it; taps and other movement there go to the trackpad.
- **Top bar**: drag to move the trackpad; `⋯` opens settings; `×` hides it.
- **Bottom-right corner**: drag to resize.
- **Quick Settings tile** "Trackpad" toggles it too.
- **Scroll zone** (Settings → Scrolling): a strip along the left or right side of the pad, marked
  with chevrons. One finger sliding up or down in it scrolls at a multiple of the normal scroll
  speed (3× by default). Moving sideways out of it moves the cursor as usual, and a tap still clicks.
- **Auto-hide** (Settings → Auto-hide). Hidden parts stay in place and keep working; touching the
  trackpad brings it and the cursor back.
  - *Hide during fullscreen content* (off by default): while an app hides the system bars, as videos
    and games do, the trackpad, cursor and edge zone markers fade out at once, whatever the idle
    settings say, and come back when the app leaves fullscreen. Edge swipes keep working.
  - *Hide trackpad when idle* (off by default) and *Hide cursor when idle* (on, 3 s): fade out after
    a while without a touch. An edge swipe over a faded trackpad brings it back; a second one hides
    it as usual.

## Settings

Settings (launcher icon, or `⋯` on the pad) cover size, pointer speed and acceleration, cursor
size, auto-hide, which gesture long-presses, tap-and-drag, scroll speed and direction, momentum,
live scroll, drag and pinch, the scroll zone, the three-finger actions, and the edge zones.
**Appearance** sets colors and opacities:

- **Trackpad:** one color for the surface, top bar and scroll zone, each with its own opacity;
  the outline, icons and finger marks each have a color and an opacity.
- **Cursor:** one opacity for the arrow, its outline and click and gesture feedback; the colors
  are fixed.
- **Screen edges:** a color and an opacity for the edge zone markers.

The color picker has presets including the system theme color, hue, saturation and brightness,
and hex. The overlays change as you edit; Cancel restores the previous color. **Diagnostics**
shows recent events, **Test area** has targets for every gesture plus a monitor of the touch
events it receives, and **About and privacy** covers what the app accesses, the privacy policy and
the licenses.

## Install and set up

Build the APK (see [Building](#building)) and install it, for example with
`adb install app/build/outputs/apk/debug/app-debug.apk`. The first launch opens a short setup,
which can be rerun from Settings → Setup guide:

1. **Turn on the service.** Opens Accessibility settings (highlighting the entry on stock Android)
   with the steps for the device: on Galaxy devices, Installed apps → Tablet Trackpad → switch on →
   Allow. Once the service connects, it brings setup back to the front by itself. Android greys the
   entry out as a restricted setting when the APK was installed from a file; the step then explains
   App info → ⋮ → Allow restricted settings. Installs over adb are not restricted.
2. **Keep it running.** Asks for unrestricted battery use, since app sleeping on some devices (One
   UI's, for one) can switch accessibility services off.
3. **Quick Settings tile.** Adds the tile with the system's own prompt (Android 13+).
4. **Try it.** Shows where to swipe and confirms when the trackpad appears, with the gesture list
   and a link to the test area.

Each step shows when it is already done and moves on by itself once it is completed.

## Privacy

Tablet Trackpad collects no data. It has no internet access, never reads what is on your screen,
and keeps its settings on the device, out of Android's backups and device-to-device transfers. The
full policy is in [PRIVACY.md](PRIVACY.md), and the same summary is in Settings → About and privacy.

## How it works

Accessibility services cannot send mouse events, so every action is played as touch gestures at
the cursor with `dispatchGesture`. Scroll, drag and pinch are *live*: injected fingers stay
pressed at the cursor and follow your fingers, so apps see an ordinary drag or pinch and fling as
usual. Swipes and pinches stay clear of the system bars; clicks reach the whole screen.
[docs/how-it-works.md](docs/how-it-works.md) has the details, and
[docs/testing.md](docs/testing.md) what was verified on a device and how.

## Building

You need the Android SDK (platform 37) and JDK 17 or newer to run Gradle. The build compiles with
Java 17 and runs the unit tests on Java 21 (Robolectric needs it for Android 16); Gradle downloads
either if it is missing.

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug            # Android lint
```

Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing
`sdk.dir=/path/to/Android/Sdk`. `./gradlew :app:assembleRelease` builds an unsigned release APK;
sign it with your own key to install it.

For working against a connected device:

```sh
scripts/dev.sh install     # build and install the debug APK
scripts/dev.sh enable      # switch the accessibility service on over adb
scripts/dev.sh logs        # follow the app's log (tag TabletTrackpad)
scripts/dev.sh info        # Android version, display and input flags of the device
```

`dev.sh` uses the system `gradle` when it is installed and falls back to `./gradlew`. `dev.sh
enable` switches the service on without going through setup; clearing the app's data switches it
off again. `scripts/touch/` plays real (non-injected) multi-touch input on a device or emulator for
testing; see [docs/testing.md](docs/testing.md#touch-input-for-tests).

```
app/src/main/kotlin/dev/tablettrackpad/
  core/        Pure Kotlin, unit tested: gesture recognizer, acceleration, scroll axis lock,
               velocity estimation, and the planner that turns actions into touch strokes
  inject/      dispatchGesture plumbing, plus live scroll, drag and pinch
  overlay/     Overlay windows: trackpad, cursor layer, edge strips
  service/     The accessibility service, the controller wiring gestures to actions, the QS tile
  settings/    SharedPreferences-backed settings and the Compose settings and setup screens
  playground/  The test area
  debug/       In-memory event log shown in Settings → Diagnostics
scripts/
  dev.sh       Build, install, enable, logs
  touch/       Real (non-injected) multi-touch input for tests on an emulator or a device
```

The screens use Material 3 Expressive, which needs Material 3 1.5: `gradle/libs.versions.toml`
pins `material3 = "1.5.0-rc01"` ahead of the Compose BOM's 1.4.0 until the stable 1.5.0 ships.

## License

Copyright (C) 2026 brpjerry

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License, version 3, as published by the Free Software Foundation. It is distributed
in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

The app is built with AndroidX, Jetpack Compose and the Kotlin libraries, which are licensed under
the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
