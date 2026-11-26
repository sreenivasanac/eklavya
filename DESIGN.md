# Design

Rules for anything Eklavya draws. Read this before changing a screen; add a rule here when a design
choice is corrected.

## Constraints

- One screen (`MainActivity`) and one overlay pill. One primary action on the screen: start or end a session.
- One typeface (the system sans), two weights (regular, medium). Four text roles only: display, headline,
  body, caption (`res/values/styles.xml`).
- One accent colour. Dark only: the phone sits on a tripod in a gym and is read at a glance.
- Native Android views, no component library, no icon set.

## Thesis

- **Visual:** a scoreboard in a dim room. Near-black ground, chalk-white text, one amber light that means
  "the coach is with you".
- **Content:** what state the session is in, the one button, the three things it needs, the keys.
- **Interaction:** none decorative. The pill changes its dot colour and its words; nothing animates,
  because it is on screen for the whole workout.

## Tokens

| Token | Value | Used for |
| --- | --- | --- |
| `bg` | `#101214` | Screen background |
| `surface` / `overlay_bg` | `#1A1D21` (94% opaque in the pill) | The pill |
| `divider` | `#2A2E33` | Row dividers, the pill's outline, a disabled button |
| `text` | `#F2F0EA` | Primary text |
| `muted` | `#9BA1A6` | Secondary text, a setting's state, the idle dot |
| `accent` | `#F2A93B` | The primary button, quiet text buttons, the active dot |
| `on_accent` | `#17130A` | Text on the primary button |
| `danger` | `#EF7A70` | An error in the pill |

Spacing is 8, 16, 24 and 40 dp (`dimens.xml`); touch targets and rows are 56 dp; radius is 12 dp for
buttons and a full round for the pill.

## Patterns

- Sections and hairline dividers. No cards.
- A setup row shows its state when done ("On") or its action when not ("Turn on"), never both.
- More space above a section heading than below it.
- A saved key is never shown again; the field only says it is saved.
- The pill never takes a touch and hides itself while Eklavya's own screen is in front.
- The session's state is the largest text on the screen (display role); the app's name is a small label above it.
- Once both keys are saved the key fields fold away behind "Change"; a saved thing is said once.
- A quiet button is amber only while it can do something; with nothing to save it is grey.

## Never

- Gradients, glows, shadows under the pill, badges, emoji as icons.
- A second accent colour, or amber used for anything that is not an action or the live state.
- Animation on the pill or the status line.
- Showing key values, transcripts or anything personal on screen or in a screenshot.

## Copy

Plain product words, said the way the coach would: "Start session", "Screen control", "Say “Hey Drona”".
States are facts ("Allowed", "Saved"), actions are verbs ("Turn on", "Allow").
