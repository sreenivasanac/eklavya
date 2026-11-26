# Eklavya

A voice fitness coach for Android that operates the fitness apps already on
your phone. You talk to it through earphones with the phone on a tripod; it
plans the session, tells you what is next, and can open an exercise's demo
video in your gym's app when you ask.

The name is from the Mahabharata: Eklavya had no access to the teacher he
wanted, so he trained himself in front of a stand-in.

## Status

Early. What exists today is the layer everything else stands on: reading
another app's screen and acting on it.

| Part | State |
| --- | --- |
| Screen model and text form for a decision model (`screen/`) | Works, tested on the JVM against real 24GO screens |
| Reading and driving screens through an accessibility service (`a11y/`) | Written; device test in progress |
| Fixed route: search 24GO and play a demo video (`app/`) | Written; device test in progress |
| Voice, planning, Hevy logging, camera rep counting | Not started |

## How a screen looks to the model

A 100-node accessibility tree becomes a few numbered lines. Elements hidden
under something drawn on top of them are left out, and icon buttons without
text are named from their view id.

```text
app: io.a24go.android.dev | 1080x2400
[1] ScrollView "detail coordinator" scrollable (540,1227) #detailCoordinator
[2] FrameLayout "Show player controls" clickable (540,446) #detailsVideo
[3] ImageButton "fab back" clickable (73,191) #fabBack
[8] TextView "Barbell Front Squat" clickable (540,961) #detailTitle
[13] TextView "1. Start with Barbell On Shoulders 2. Position Feet Shoulder Width ..." (540,1566)
[14] ViewGroup "Start Demo Exercise" clickable (540,2242) #ctaPrimaryActionView
```

## Modules

- `screen/` is plain Kotlin with no Android classes: `Screen`, `UiNode` and
  `ScreenText`. It can be tested with `adb shell uiautomator dump` files.
- `a11y/` is an Android library: `ScreenReader` copies the accessibility
  windows into a `Screen`, and `ScreenDriver` taps, types, scrolls and waits
  for the screen to settle. Each action finds the live element again first
  and refuses to act if the screen has changed.
- `app/` is the Eklavya app: the accessibility service, a 24GO route, and
  debug commands for driving the service from a computer.

## Build and try

Needs JDK 17 to 21 and the Android SDK (platform 36, build tools 36.0.0).

```bash
./gradlew :screen:test
```

```bash
./gradlew :app:installDebug
```

Turn the service on in Settings → Accessibility → Eklavya. On a sideloaded
install Android greys the switch out until you open the app's info page and
choose "Allow restricted settings" from the ⋮ menu.

Debug builds accept commands from adb (and only from adb):

```bash
adb shell am broadcast -a com.pragnyalabs.eklavya.DEBUG -p com.pragnyalabs.eklavya --es cmd dump
```

```bash
adb shell am broadcast -a com.pragnyalabs.eklavya.DEBUG -p com.pragnyalabs.eklavya --es cmd demo --es arg "squat"
```

```bash
adb logcat -s Eklavya
```

## What the app can see

An accessibility service can read everything on screen in every app. Eklavya
reads the screen only while carrying out a request, never copies the contents
of password fields, and sends nothing off the phone in this version. Release
builds have no remote-control door; the debug commands exist only in debug
builds and only for senders holding Android's `DUMP` permission (adb).

## Licence

MIT. See [NOTICE.md](NOTICE.md) for third-party credits.
