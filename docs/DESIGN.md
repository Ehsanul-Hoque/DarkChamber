# DarkChamber — Design System

> The visual language of the app. Normative for all UI work; tokens here map 1:1 to code in `core:designsystem`. Companion: [`ARCHITECTURE.md`](./ARCHITECTURE.md) §1 (where the module lives), [`THREAT_MODEL.md`](./THREAT_MODEL.md) (copy may never promise more than it allows).

## 1. Concept

DarkChamber's imagery comes from the darkroom, not from "cybersecurity." Three ideas carry the whole system:

- **The chamber** — dark mode is the app's native state: a warm, sealed near-black, like the inside of a bellows camera. Not blue-black "hacker dark"; the darkness here is protective, not ominous.
- **The safelight** — the single accent is darkroom amber: the lamp that lets you work *without exposing the film*. Amber marks interaction and attention — light entering the chamber — which is why it also marks moments of exposure (unlocking, exporting, opening outside the vault).
- **The aperture** — the one signature element (§7). Circular iris forms for the lock state, the shutter button, and unlock progress. Nothing else in the app gets decorative treatment.

**Register:** calm, precise, honest. A utility, not a spectacle. When in doubt, remove one thing.

## 2. Color

Tokens are semantic; components never reference raw hex. Both themes must hold WCAG AA (≥ 4.5:1 body text, ≥ 3:1 large text/UI icons); the pairs below were chosen to pass.

### 2.1 Dark theme — "the chamber" (default)

| Token             | Hex             | Use                                                                  |
|-------------------|-----------------|----------------------------------------------------------------------|
| `background`      | `#131110`       | app background (warm near-black)                                     |
| `surface`         | `#1C1917`       | cards, sheets, bars                                                  |
| `surfaceRaised`   | `#26221F`       | dialogs, menus, raised cards                                         |
| `outline`         | `#3B3632`       | hairline borders, dividers                                           |
| `textPrimary`     | `#EDE7DE`       | primary text (warm paper-white)                                      |
| `textSecondary`   | `#A79E93`       | secondary text, captions                                             |
| `accent`          | `#E3A144`       | safelight amber — CTAs, focus, active states, links                  |
| `onAccent`        | `#201300`       | text/icons on accent                                                 |
| `accentContainer` | `#3D2C0F`       | tinted chips, selected states; pair with `onAccentContainer #F1C67E` |
| `sealed`          | `#8FB896`       | encrypted/protected status (calm sage)                               |
| `sealedContainer` | `#1F2E23`       | "encrypted" badges; pair with `sealed` text                          |
| `danger`          | `#E06A5E`       | destructive: shred, panic wipe, integrity failure                    |
| `onDanger`        | `#2B0906`       | text on danger fills                                                 |
| `scrim`           | `#000000 @ 60%` | behind dialogs/sheets                                                |

### 2.2 Light theme — "the print room"

Neutral photographic paper, not cream; the amber deepens to hold contrast.

> **Specified and built, not yet reachable.** These tokens exist as `darkChamberLightColors()` and are
> tested, but `DarkChamberTheme` is still pinned to dark and nothing selects between the two, so the
> app is dark-only on both platforms today. Switching themes is waiting on the settings screen that
> the session work introduces; until then the Android system bars are pinned to light-on-dark to
> match. Treat this table as the contract for that work, not as a description of what ships now.

| Token             | Hex             | Use                                                          |
|-------------------|-----------------|--------------------------------------------------------------|
| `background`      | `#FAF9F7`       | app background                                               |
| `surface`         | `#FFFFFF`       | cards, sheets, bars                                          |
| `surfaceRaised`   | `#FFFFFF`       | dialogs (elevation separates, §5)                            |
| `outline`         | `#E2DED8`       | hairline borders, dividers                                   |
| `textPrimary`     | `#1C1917`       | primary text                                                 |
| `textSecondary`   | `#5F594F`       | secondary text, captions                                     |
| `accent`          | `#7A5200`       | safelight deep — CTAs, focus, links                          |
| `onAccent`        | `#FFFFFF`       | text/icons on accent                                         |
| `accentContainer` | `#F4E3C2`       | tinted chips/selected; pair with `onAccentContainer #4A3200` |
| `sealed`          | `#2E6B44`       | encrypted/protected status                                   |
| `sealedContainer` | `#DCEBE0`       | "encrypted" badges                                           |
| `danger`          | `#B3372C`       | destructive actions                                          |
| `onDanger`        | `#FFFFFF`       | text on danger fills                                         |
| `scrim`           | `#1C1917 @ 40%` | behind dialogs/sheets                                        |

### 2.3 Semantic rules

- **Amber = light = attention.** Unlock actions, focus rings, and *exposure warnings* ("this file is leaving the vault") all use `accent` — the metaphor is literal, and it means the app needs no separate warning-yellow.
- **Sealed green appears only when content is genuinely protected.** Never use it decoratively; it is a status, and status colors that lie erode the product's honesty.
- **Danger red is reserved for irreversible acts** (crypto-shred, panic wipe) and integrity failures. Destructive confirmations pair red action + neutral cancel; never two red buttons.
- Media viewing surfaces (photo/video) sit on pure `#000000` regardless of theme, like every gallery.

## 3. Typography

One superfamily, two voices — **IBM Plex Sans** for UI and **IBM Plex Mono** for machine truth. Chosen deliberately: the pair shares design DNA (mono never looks bolted-on), it's SIL-licensed for GPL bundling, and Plex ships companion scripts including **Bengali** (IBM Plex Sans Bengali) — first-class Bangla localization without a second design language.

**Mono is a semantic register, not a style:** recovery keys, fingerprints, hashes, version strings, and the metadata-scrubber diff render in Plex Mono. If it's data the user might verify character-by-character, it's mono; nothing else is.

| Role         | Face / weight      | Size / line      | Use                                                    |
|--------------|--------------------|------------------|--------------------------------------------------------|
| `display`    | Plex Sans SemiBold | 32 / 40          | onboarding, empty states                               |
| `titleLarge` | Plex Sans SemiBold | 22 / 28          | screen titles                                          |
| `title`      | Plex Sans Medium   | 18 / 24          | section headers, dialog titles                         |
| `bodyLarge`  | Plex Sans Regular  | 16 / 24          | primary reading text                                   |
| `body`       | Plex Sans Regular  | 14 / 20          | default UI text, list items                            |
| `label`      | Plex Sans Medium   | 12 / 16          | buttons, tabs, badges (sentence case — never all-caps) |
| `caption`    | Plex Sans Regular  | 11 / 16          | timestamps, helper text                                |
| `mono`       | Plex Mono Regular  | 14 / 22, +0.02em | keys, hashes, diffs                                    |
| `monoKey`    | Plex Mono Medium   | 18 / 28, +0.08em | recovery-key display (grouped: `XXXX-XXXX-…`)          |

No thin/light weights anywhere — they read as fragile, and this app sells solidity.

## 4. Shape & corner radii

Soft-but-serious: rounded enough to feel handheld, never bubbly.

| Token        | Value  | Use                                                         |
|--------------|--------|-------------------------------------------------------------|
| `radiusSm`   | 6 dp   | thumbnails, chips, text fields                              |
| `radiusMd`   | 10 dp  | buttons, list cards                                         |
| `radiusLg`   | 14 dp  | cards, dialogs                                              |
| `radiusXl`   | 20 dp  | bottom sheets (top corners)                                 |
| `radiusFull` | circle | aperture elements only: shutter button, lock indicator, FAB |

The full circle is *reserved* for the aperture family (§7) — its rarity is what makes it read as the brand.

## 5. Elevation & shadows

- **Dark theme: no shadows.** Shadows are invisible on near-black; depth comes from **surface steps** (`background → surface → surfaceRaised`) plus a 1 dp `outline` hairline on raised elements. Disable Material tonal-elevation tinting — surfaces take exactly the token colors above.
- **Light theme: three soft shadow levels**, low-alpha and tight (utility, not "floating cards"):
    - `e1` cards: y 1 dp, blur 3 dp, `#1C1917 @ 8%`
    - `e2` sheets/menus: y 4 dp, blur 12 dp, `@ 10%`
    - `e3` dialogs: y 8 dp, blur 24 dp, `@ 14%`

## 6. Spacing & layout

4 dp grid: **4, 8, 12, 16, 24, 32, 48** (`spaceXxs … spaceXxl`). Screen margins 16 dp (compact) / 24 dp (≥ 600 dp). Minimum touch target 48×48 dp. Vault grid: 3 columns compact with 2 dp gutters (dense, like a contact sheet), thumbnails square-cropped, `radiusSm`.

## 7. The signature: the aperture

One motif, three appearances, nowhere else:

1. **Lock state indicator** — an iris that is *open* when the vault is unlocked and *closes* on lock. It doubles as the honest status light: open iris = session live.
2. **Shutter button** — the camera's capture control: `radiusFull`, 72 dp, `accent` ring on `surface`; press = brief iris blink.
3. **Unlock progress** — while keys unwrap, the iris opens from closed; on failure it stays shut and shakes 2 px.

The app icon is the closed iris on chamber black — the seal itself.

## 8. Motion

Fast and functional; motion explains state, never decorates.

| Token        | Value  | Use                             |
|--------------|--------|---------------------------------|
| `motionFast` | 120 ms | presses, toggles, focus         |
| `motionBase` | 200 ms | navigation, dialogs, iris blink |
| `motionSlow` | 320 ms | lock/unlock iris, sheet slide   |

Standard decelerate easing; **lock is never animated slower than `motionBase`** (locking must feel instant, and the animation may never delay the actual session teardown — the crypto locks first, the iris catches up). All motion respects the OS reduced-motion setting: replace with cross-fades.

## 9. Iconography & imagery

- Stroke icons, 2 dp, rounded caps, 24 dp grid (Lucide or Material Symbols rounded — pick one set at implementation and never mix).
- Domain icons prefer photographic vocabulary: iris/aperture for lock, film-frame for vault items, contact-sheet for grid, darkroom-tray for import.
- **No stock "security" clichés:** no padlocks-with-glow, no shields, no green matrix. The only lock imagery is our own iris.

## 10. Component notes

- **Buttons:** filled = `accent`/`onAccent` (one per screen, the primary action); tonal = `accentContainer`; text buttons for the rest; destructive = filled `danger`, always paired with a neutral cancel.
- **Secure-screen scaffold:** every screen inside `UnlockedComponent` uses the design system's `SecureSurface` wrapper (applies `FLAG_SECURE`/snapshot obscuring — see ARCHITECTURE.md §7); the design system owns this so no feature forgets it.
- **Exposure moments** (export, open-in, share): amber banner + explicit verb ("Decrypt and share") — never a generic "OK".
- **Empty states:** an invitation to act ("Take your first photo" / "Import files"), display type, quiet illustration at most.
- **Vault tiles without a preview:** a tile showing only its type badge is an **ordinary tile, not a broken one**. Only images get a preview; a PDF, a note, an audio file and an image the platform's decoder declines all look the same, and they are all fine. So the badge carries no warning colour, no placeholder frame and no retry — it is simply what that item looks like. (The one tile that *does* say something is wrong is the unverifiable one, whose metadata would not open; that is a separate state and reads as such.)
- **Recovery-key screen:** `monoKey` groups on `surfaceRaised`, one screen, no competing actions; the copy states plainly that loss is unrecoverable.

## 11. Voice

Sentence case everywhere, including buttons. Plain verbs that say what happens: "Encrypt 3 files", "Shred forever", "Unlock". Errors state what happened and what to do next; they never apologize and never say "oops". Honesty rules from the threat model apply to microcopy: the shredder's external-file mode says "reduces the chance of recovery", never "permanently erases".

## 12. Accessibility floor

AA contrast on every token pair (verified in a Roborazzi/token test, not by eye); 48 dp targets; visible focus (amber 2 dp ring); TalkBack/VoiceOver labels on every aperture element (they're custom drawings — they *must* carry contentDescription); reduced-motion honored; dark/light follow the system by default with an in-app override.
