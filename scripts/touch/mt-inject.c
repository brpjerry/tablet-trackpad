// Plays multi-touch frames into an evdev touchscreen (protocol B), so tests see real touchscreen
// input rather than injected events.
//
//   mt-inject /dev/input/eventN <abs_max_x> <abs_max_y> <screen_w> <screen_h> < frames
//   mt-inject uinput <abs_max_x> <abs_max_y> <screen_w> <screen_h> < frames
//
// Writing to an existing touchscreen needs root (adb root on emulator images). "uinput" creates a
// virtual touchscreen instead, which the adb shell user may do on production devices. It stays
// registered until the frames end.
//
// Each input line is one frame:  <delay_ms> [<id> <x> <y>]...
// listing every finger that is down after the frame, in screen pixels. Fingers missing from a
// frame are lifted. An empty finger list lifts everything.
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <unistd.h>

#define SLOTS 10

static int fd;
static int slot_id[SLOTS];
static int tracking = 100;
// Set once an event could not be written; the frames stop there and the exit status says so.
static int failed;

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (failed) return;
    if (write(fd, &ev, sizeof(ev)) != sizeof(ev)) {
        perror("write");
        failed = 1;
    }
}

static int abs_axis(int code, int max) {
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = code;
    abs.absinfo.maximum = max;
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0 || ioctl(fd, UI_ABS_SETUP, &abs) < 0) {
        perror("abs setup");
        return -1;
    }
    return 0;
}

// A direct-touch device on the virtual bus, which Android treats as an internal touchscreen.
static int create_uinput(int max_x, int max_y) {
    fd = open("/dev/uinput", O_WRONLY);
    if (fd < 0) { perror("open /dev/uinput"); return -1; }
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 || ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0 || ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        perror("uinput setup");
        return -1;
    }
    if (abs_axis(ABS_X, max_x) < 0 || abs_axis(ABS_Y, max_y) < 0 ||
        abs_axis(ABS_MT_SLOT, SLOTS - 1) < 0 || abs_axis(ABS_MT_TRACKING_ID, 65535) < 0 ||
        abs_axis(ABS_MT_POSITION_X, max_x) < 0 || abs_axis(ABS_MT_POSITION_Y, max_y) < 0 ||
        abs_axis(ABS_MT_TOUCH_MAJOR, 255) < 0) {
        return -1;
    }
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x18d1;
    setup.id.product = 0x4d54;
    strcpy(setup.name, "mt-inject");
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("uinput create");
        return -1;
    }
    // Give Android time to pick the device up before the first frame.
    usleep(1000 * 1000);
    return 0;
}

static double sx, sy;
static int touching;

static void apply_frame(char *p) {
    int n;
    long delay;
    if (sscanf(p, "%ld%n", &delay, &n) != 1) return;
    p += n;
    if (delay > 0) usleep(delay * 1000);

    int ids[SLOTS], xs[SLOTS], ys[SLOTS], count = 0;
    while (count < SLOTS && sscanf(p, "%d %d %d%n", &ids[count], &xs[count], &ys[count], &n) == 3) {
        p += n;
        count++;
    }
    // Lift fingers that are gone.
    for (int s = 0; s < SLOTS; s++) {
        if (slot_id[s] < 0) continue;
        int still = 0;
        for (int i = 0; i < count; i++) if (ids[i] == slot_id[s]) still = 1;
        if (!still) {
            emit(EV_ABS, ABS_MT_SLOT, s);
            emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
            slot_id[s] = -1;
        }
    }
    // Place or move the rest.
    for (int i = 0; i < count; i++) {
        int s = -1;
        for (int k = 0; k < SLOTS; k++) if (slot_id[k] == ids[i]) s = k;
        emit(EV_ABS, ABS_MT_SLOT, s >= 0 ? s : 0);
        if (s < 0) {
            for (int k = 0; k < SLOTS; k++) if (slot_id[k] < 0) { s = k; break; }
            emit(EV_ABS, ABS_MT_SLOT, s);
            emit(EV_ABS, ABS_MT_TRACKING_ID, tracking++);
            // Devices with a pressure axis report zero-pressure contacts as hovering.
            emit(EV_ABS, ABS_MT_PRESSURE, 512);
            emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 8);
            slot_id[s] = ids[i];
        }
        emit(EV_ABS, ABS_MT_POSITION_X, (int)(xs[i] * sx));
        emit(EV_ABS, ABS_MT_POSITION_Y, (int)(ys[i] * sy));
    }
    if (count > 0 && !touching) emit(EV_KEY, BTN_TOUCH, 1);
    if (count == 0 && touching) emit(EV_KEY, BTN_TOUCH, 0);
    touching = count > 0;
    emit(EV_SYN, SYN_REPORT, 0);
}

static void play(FILE *in) {
    char line[1024];
    while (!failed && fgets(line, sizeof(line), in)) apply_frame(line);
    // Never leave a finger down when the frames run out.
    char none[] = "0";
    if (touching && !failed) apply_frame(none);
}

int main(int argc, char **argv) {
    if (argc != 6) {
        fprintf(stderr, "usage: %s dev|uinput abs_max_x abs_max_y screen_w screen_h < frames\n", argv[0]);
        return 2;
    }
    if (strcmp(argv[1], "uinput") == 0) {
        if (create_uinput(atoi(argv[2]), atoi(argv[3])) < 0) return 1;
    } else {
        fd = open(argv[1], O_RDWR);
        if (fd < 0) { perror("open"); return 1; }
    }
    sx = atof(argv[2]) / atof(argv[4]);
    sy = atof(argv[3]) / atof(argv[5]);
    for (int i = 0; i < SLOTS; i++) slot_id[i] = -1;

    play(stdin);
    if (strcmp(argv[1], "uinput") == 0) ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return failed ? 1 : 0;
}
