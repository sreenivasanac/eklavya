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

## Local (on-device) models

Asked for on 2026-11-25: gyms have poor signal, so each AI part should have an on-device option next to
the cloud one, chosen by the user. Researched the same day; nothing below has been run on the phone yet.

- Apple's on-device models run only on Apple hardware and their weights are not published. Not usable here.
- Google's Gemini Nano (AICore) is offered on Pixel 9 and later, not on the Pixel 7a.
- Decision model: Gemma 4 E2B on Google's LiteRT-LM runtime (2.58 GB, Apache-2.0) is the general pick;
  estimated 3 to 4 s per decision on the 7a's GPU, about 20 s on CPU. FunctionGemma 270M (288 MB) is far
  faster but needs fine-tuning on recorded screens. Small Qwen or Llama models on llama.cpp sit in between.
- Speech-to-text: Moonshine or Parakeet through sherpa-onnx, or Android's built-in offline recogniser.
- Text-to-speech: Android TextToSpeech; Piper through sherpa-onnx as the alternative.
- Wake word: sherpa-onnx keyword spotting (Apache-2.0, any typed phrase).
- No full speech-to-speech model runs on this phone; local voice is turn-based.
- Cheapest way to need fewer decisions offline: record each successful path through an app and replay it,
  asking a model only when a screen differs from the recording.
- The code already takes any OpenAI-style endpoint as the decision model (`Settings`, `ChatDecider`).

## Open

- 24GO's terms on automation have not been read.
- No timing yet from spoken request to video playing.
- Hevy has not been driven or called.
