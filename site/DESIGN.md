# Site design

Rules for the website in this folder (`index.html`, `privacy.html`, `styles.css`). The app's own rules are in
`../DESIGN.md`; the site takes its colours from there and nothing else. Add a rule here when a design choice
is corrected.

## Constraints

- One page plus a privacy page. One H1. Seven sections: hero, the log, counting a set, how it decides, what it sends, status, the name. (Six was the limit until the app learned a second thing worth a section of its own.)
- Two typefaces, both self-hosted: Bricolage Grotesque (weights 400 and 600) and IBM Plex Mono (400).
- One accent colour (amber). Dark only, like the app.
- One primary action: "View on GitHub". There is no download.
- Plain HTML and CSS, about ten lines of JavaScript, no build step, no external requests.
- Every sentence is a fact from `../README.md`, `../DESIGN.md` or `../docs/REQUIREMENTS.md`.

## Thesis

- **Visual:** the same dim room as the app, laid out as a training ledger: chalk-white type on near-black,
  hairline rules, a label column on the left and the entry on the right, one amber light.
- **Content:** the spoken request is the headline; the real session log is the proof; then how it chooses,
  what leaves the phone, what is untested, and where the name comes from.
- **Interaction:** two motions. The log's lines arrive in order the first time it scrolls into view; the
  button gives under a press. Nothing else moves.

## Tokens

Colours are the app's (`../DESIGN.md`), under the same names in `styles.css`.

| Token | Value | Used for |
| --- | --- | --- |
| `--bg` | `#101214` | Page background |
| `--surface` | `#1A1D21` | The one boxed element: the screen as the model reads it |
| `--divider` | `#2A2E33` | Hairline rules |
| `--text` | `#F2F0EA` | Primary text |
| `--muted` | `#9BA1A6` | Notes, captions, times, labels |
| `--accent` | `#F2A93B` | The button, links, the dot beside the coach's lines and the brand |
| `--on-accent` | `#17130A` | Text on the button |

Type roles: display (the H1 only), headline (H2), body, caption, and mono for anything the app itself printed.
Spacing is 8, 16, 24, 40, 64 and 104 px. Radius is 12 px (button, code block) and 28 px on a phone screen.
Easing is `cubic-bezier(0.23, 1, 0.32, 1)`.

## Patterns

- Sections and hairlines, no cards. A section is a label column (heading) and an entry column.
- The log's lines are the README's, unchanged, set in mono; the notes beside it are in the sans and muted.
- Screenshots are the real ones from `../docs/screenshots/`, with a 1 px light outline. No device frame.
- The one chart is drawn in the app's colours on the page background, so it needs no frame: chalk line, amber
  dots for counted reps, muted grid and labels. It is made by a script from a recorded set, not drawn by hand.
- What the phone says during a set uses the log's spoken-line style with `phone` as the speaker; the amber dot
  marks a correction.
- Amber means an action or the coach: never decoration.
- Links in running text are amber and underlined; header and footer links are plain.
- A fact is said once. A note beside the log never repeats a rule or a line that appears further down.
- The hero's screenshot starts level with the H1 and is cut by the rule under the hero.

## Never

- Gradients, glows, glass, shadows, a terminal window with three dots, a typing cursor, a pulsing dot.
- Three matching feature cards, icons in tiles, an eyebrow label above a heading, a coloured word in a headline.
- Illustrations of people, stock images, a drawn Eklavya or Drona. The story is told in two sentences of text.
- A number, quote or claim that is not in the repo's own documents.
- Analytics, cookies, hosted fonts or any other request to another site.

## Where the choices came from (kosha bookmarks)

- **Item 2191** (cloudxdev's landing-page prompt): state direction, fonts and palette before code; no
  hero / features / testimonials order, no system fonts, no blobs or stock images. The page is ordered around
  the log instead.
- **Item 42** (Dionis's fifteen design resources): Fontshare for fonts and Lucide for icons. Fontshare's faces
  were passed over because their licence does not clearly allow committing the files to a public repo, so the
  fonts are SIL OFL faces instead. No icon set is used; if one is ever needed, Lucide.
- **The `ui-craft` skill's font list** (built from the bookmarks): Bricolage Grotesque (the sans the owner's
  DeskRecall and CallEnvoy sites already use) and IBM Plex Mono for code and numbers.
- **Item 1518** (Slop.md) and the `ui-craft` tells list: the checklist under "Never".
- **Item 3302** (Anime.js) was the bookmarked option for motion without React; two CSS transitions did the job,
  so no library. The easing curve is the one in the `ui-craft` motion notes.
- **Item 2653** (curated.design, onepagelove, component.gallery): not browsed. The references were the app's
  `DESIGN.md` and the owner's own sites (DeskRecall, Sandhyāvandanam).

## Fonts and images

- `fonts/bricolage-grotesque.woff2` (variable, Latin) and `fonts/ibm-plex-mono-400.woff2` (Latin): SIL Open
  Font License 1.1, see `fonts/LICENSE.txt`.
- `images/og.png` (1200×630) is a picture of `og.html`. To remake it after a change:
  `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless --hide-scrollbars --window-size=1200,630 --screenshot=images/og.png og.html`
- `images/apple-touch-icon.png` is `favicon.svg` at 180 px.
