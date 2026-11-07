# How it works

Accessibility services cannot send mouse events, so every action is played as touch gestures at
the cursor with `dispatchGesture`:

- **Clicks** are taps; a long press is a press held past Android's long-press timeout; double-click
  is two taps 90 ms apart. Double-tap and hold starts the long press after 0.3 s of holding still,
  as a live drag that has not moved yet, so lifting long-presses and moving drags.
- **Scroll, drag and pinch are live.** One injected finger (two for pinch) stays pressed at the
  cursor and is extended with `continueStroke` as your fingers move, so the app sees one ordinary
  drag: the touch slop is paid once, and lifting with your fingers' speed gives the app's own
  fling. Pinch keeps the injected fingers between Android's minimum scaling span and three times
  that, and re-grips when it runs out of room. The fingers spread horizontally, vertically or
  diagonally, whichever has room, so the pinch stays centered on the cursor even near an edge.
- **Rest before dragging** to long-press first: the injected finger holds for the long-press
  timeout before it moves or lifts, even if your finger lifts sooner.
- **One gesture at a time.** Dispatching a gesture cancels the injected one still playing, so each
  new gesture of the trackpad's is a new *job*, and a chain of continued strokes stays in the job
  that opened it. A gesture ends *done*, *cancelled* (by Android) or *replaced* (by a newer job of
  ours, such as the next flick cutting the last one's fling short). Older chains stop at their next
  step, and only cancellations are made up for. A live scroll, drag or pinch is over for good once
  any other gesture starts: it sends no more steps, re-grips, releases, remainders or replays.
  Hiding or resetting the trackpad ends it too, lifting its fingers where they are. A release
  already under way then still plays to the end, so no injected finger is left down, but nothing
  is replayed afterwards, even if Android cancels it.
- **Fallbacks.** If Android cancels an injected stroke, scrolls restart at the cursor and drags and
  pinches replay when you lift, from what that gesture recorded when the fingers lifted. A
  replaced gesture is never replayed. If Android cancels the *trackpad's own* touch right after a
  live step, the app turns **Live scroll, drag and pinch** off and plays those gestures on lift
  from then on; the switch is in Settings → Scrolling. A cancellation that follows an injected
  touch landing on the pad does not count.
- **Pinch re-grips** lift the fingers at the end of the range and grip again once the lift has
  played (40 ms), carrying the zoom delivered so far. Zooming in grips at the narrow end; zooming
  out grips at twice that rather than at the widest spacing, which takes more grips but makes it
  likelier that a small zoomable view gets both fingers. Only likelier: the trackpad knows the
  screen, not the view's bounds. A live pinch only starts when its fingers would touch down clear
  of the trackpad's windows at both of those spacings; otherwise it plays on lift. A live scroll
  checks each press the same way, and plays the rest on lift if fitting the stroke on screen
  would start it on the pad.
- **System bars.** Swipes and pinches start inside the visible status and navigation bars and
  display cutouts, read again as each gesture starts, so a finger never lands on system UI. Clicks
  and the cursor still reach the whole screen, navigation buttons included.
- **Gestures that start over the trackpad itself** (cursor on top of the pad) make the pad
  untouchable for the duration of the gesture, so the touch reaches the app underneath. These are
  always played on lift.
- **Release without fling**: a held injected finger reports no motion, which leaves Android's
  velocity tracker on the last drag speed, so before lifting the finger wiggles one pixel and back
  for 150 ms.
- **Live scroll presses leave room to travel.** Near the edge a scroll moves toward, the press
  moves back from the cursor to leave up to 16 touch slops of travel beyond its own step, as far
  as the swipe area has room, so later steps can continue the same stroke instead of pressing
  again each time.
- **Fullscreen detection.** Our overlays sit above the system bars and get no bar insets, so
  fullscreen content is detected by asking the window manager about once a second, only while
  *Hide during fullscreen content* is on.

## Code map

- `core/GestureRecognizer`: raw multi-touch on the pad to trackpad gestures.
- `core/GesturePlanner`: those gestures to timed touch strokes on screen, inside the swipe area.
- `inject/GestureInjector`: plays strokes with `dispatchGesture` and keeps track of jobs and
  outcomes (`Injector`, `Outcome`, `Chain`, `Ownership`).
- `inject/LiveScroller`, `LiveDragger`, `LivePincher`: the live gestures.
- `service/TrackpadController`: wires recognized gestures to the cursor and the injector.
