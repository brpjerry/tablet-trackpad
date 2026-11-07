# Privacy policy

Effective November 6, 2026. Applies to the Tablet Trackpad app (`dev.tablettrackpad`).

**Tablet Trackpad collects no data.** It has no internet access, sends nothing anywhere and
contains no analytics, advertising or crash-reporting code.

## What the app can access, and why

- **Accessibility service.** The trackpad runs as an Android accessibility service, which you
  switch on yourself in Accessibility settings. It uses the service to draw the trackpad and cursor
  over other apps, to tap, scroll, drag and pinch where the cursor is, and to go Back, Home or to
  Recents. It does not read what is on your screen: it does not ask Android for window content or
  accessibility events, so it never receives them.
- **Battery optimization exemption.** Setup asks you to let the app run without battery
  restrictions, so that the system does not switch the trackpad off. You can refuse.
- **Quick Settings tile.** Setup offers to add a tile that shows or hides the trackpad. You can
  refuse.

## What the app stores

Its settings (trackpad size and position, pointer speed, colors and so on) are kept on your device
in the app's private storage. They are excluded from Android's cloud backup and device-to-device
transfer. Uninstalling the app or clearing its data deletes them.

Settings → Diagnostics shows a log of recent trackpad events, such as gestures, cursor positions
and their outcomes, to help with troubleshooting. It is kept in memory, is cleared when the app
stops, and leaves the app only if you copy it to the clipboard yourself. The same entries also go
to Android's system log (logcat) on the device, as other apps' logs do; they never leave the
device from there unless you export that log yourself.

## Children

The app collects no data from anyone, children included.

## Changes and contact

Changes to this policy are published in this file, in the app's
[source repository](https://github.com/brpjerry/tablet-trackpad). Questions can be raised as an
issue there.
