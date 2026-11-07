"""Builds touch frames for touch.sh from a small Python expression.

    python3 frames.py "tap(2300, 1400)"
    python3 frames.py "stroke([(1,2200,1700),(2,2400,1700)], [(1,2200,1300),(2,2400,1300)], steps=40); lift()"

Helpers: frame(delay, *fingers), stroke(from, to, steps, dt, first_delay), hold(fingers, ms),
lift(delay), tap(x, y, ms, first_delay), and goto(x, y, pad) which parks the cursor in the
top-left corner and then moves it to (x, y) using the pad surface rectangle `pad`.
"""
import sys

out = []


def frame(delay, *fingers):
    out.append(" ".join([str(int(delay))] + [f"{i} {int(x)} {int(y)}" for i, x, y in fingers]))


def lift(delay=16):
    frame(delay)


def stroke(fingers_from, fingers_to, steps=20, dt=16, first_delay=0):
    frame(first_delay, *fingers_from)
    for s in range(1, steps + 1):
        t = s / steps
        frame(dt, *[(a[0], a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t) for a, b in zip(fingers_from, fingers_to)])


def hold(fingers, ms, dt=16):
    for _ in range(int(ms // dt)):
        frame(dt, *fingers)


def tap(x, y, ms=50, first_delay=0):
    frame(first_delay, (1, x, y))
    frame(ms, (1, x, y))
    lift(16)


def goto(x, y, pad):
    """Moves the cursor to (x, y). Moves at 0.4 px/ms, where the default pointer gain is exactly 2."""
    x0, y0, x1, y1 = pad
    for _ in range(3):
        stroke([(1, x1 - 120, y1 - 120)], [(1, x0 + 80, y0 + 60)], steps=8, first_delay=60)
        lift()
    fx, fy = x / 2.0, y / 2.0
    pieces = max(1, int(max(abs(fx), abs(fy)) // 250) + 1)
    for _ in range(pieces):
        dx, dy = fx / pieces, fy / pieces
        steps = max(4, int((dx * dx + dy * dy) ** 0.5 / 0.4 / 16))
        stroke([(1, x0 + 60, y0 + 40)], [(1, x0 + 60 + dx, y0 + 40 + dy)], steps=steps, first_delay=80)
        lift()


exec(sys.argv[1])
print("\n".join(out))
