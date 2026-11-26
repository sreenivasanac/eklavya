# Requirements and decisions

Decisions taken with the owner on 2026-11-25. Change them here when they change.

## Product

- A voice coach used through earphones with the phone on a tripod at the gym.
- For the owner alone at first (24 Hour Fitness, the 24GO app); sideloaded.
- Phase 1: talk to the coach, plan the workout, log sets by voice, and
  control 24GO's screens (find an exercise, play its video).
- Phase 2: front-camera rep counting and form correction.
- The coach speaks on its own at set moments (rest over, next exercise) and
  otherwise waits for a wake word.
- 24GO stays in front; the coach is a floating overlay.

## Technical

- Android only, native Kotlin. Test phone: Pixel 7a, Android 16.
- Other apps are driven through an accessibility service (this repo's
  `a11y/` and `screen/` modules), to be published as a library.
- Voice: speech-to-speech (OpenAI GPT-Live), opened per exchange.
- Decisions: TypeSafe Jev chooses the tool and each navigation step; the
  voice model only talks and listens.
- Workout history lives in Hevy (its API creates a finished workout, so sets
  are journaled on the phone and posted when the session ends).
- The exercise list is read from 24GO.
- Model access: the owner's own API keys. Budget: up to $1 per one-hour session,
  which rules out leaving a voice session open for the whole hour.

## Findings

- 24GO (1.94.0, package `io.a24go.android.dev`) exposes labelled elements;
  nearly every tappable element has text, a description or a view id.
- 24GO search runs only when the keyboard's search key is pressed, and needs
  24GO's own exercise names ("lat pulldown" finds nothing, "squat" does).
- A demo page carries the form cues as plain text ("What to expect").
- A full-screen layer inside the same window (the open search) leaves the
  screen under it in the tree; drawing order is needed to leave it out.
- The phone locks after 30 seconds; the app must keep the screen on during
  a session.

## Open

- 24GO's terms on automation have not been read.
- No timing yet from spoken request to video playing.
- Hevy has not been driven or called.
