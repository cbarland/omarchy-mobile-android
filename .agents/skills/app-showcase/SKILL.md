---
name: app-showcase
description: Create release and social showcase media from real application screenshots or recordings, with a reproducible composition and accurate capability claims. Use for app launch graphics, phone/desktop collages, demo media, and accompanying post drafts.
---

# App showcase

Show the working product. Use real captures by default; concept art belongs only
in an explicitly requested, clearly labeled concept deliverable.

## Capture an honest scene

- Identify the exact build, environment, display topology, and demonstrated
  workflow. Distinguish emulator/device and architecture. A screenshot proves
  appearance, not performance, compatibility, audibility, or security.
- Prefer existing project capture tools. Discover actual display IDs; preserve
  bytes with binary-safe transfers. Android's `adb exec-out screencap -p -d ID`
  captures the selected output. Explicitly select the approved target; do not
  assume the first ADB device or touch a phone removed from the session.
- Arrange genuine apps with useful, nonsensitive content. Read-only project files
  and an actual fetch command make good scenes. Do not fake system details, logs,
  performance, or app state. Label purpose-made example content as a demo. Use
  fresh browser profiles when needed to avoid exposing accounts/history/autofill.
- Inspect raw captures first. Address obstructing dialogs, error banners, and
  unsuitable windows through the application or choose a different honest scene.
  Do not paint them out, regenerate them, or recreate UI.
- Preserve originals and record timestamp, build/hash, environment, capture
  hashes, and scene changes. Separate public provenance from private logs/backups.
  Restore owned scene changes afterward. This skill grants no install/reset,
  permission, publishing, or messaging authority.

## Compose without changing the product

Keep whole screenshots at their original aspect ratios. Put framing, headings,
feature labels, goals, credits, and calls to action outside screen images.
Use deterministic HTML/CSS or SVG, not generative recreation of the screens.
Disclose any explicitly requested redaction/crop and retain untouched originals
privately. Do not turn emulator captures into physical-device claims or show
future goals as implemented features.

Optional helpers here create self-contained HTML and a PNG using an existing
Playwright/browser installation. They install nothing, use no network, do not
capture live apps, and do not publish.

1. Write a JSON spec with `title`, `subtitle`, `context`, `captures` (one to three
   objects with `path`, `label`, optional `note`), `demonstrated` and `goals`
   (one to three short lines each), `cta`, `url`, and `credits`. PNG paths resolve
   relative to the spec. Full feature caveats belong in linked release notes.
2. Run `python scripts/compose.py spec.json --output showcase.html`. It embeds
   exact PNG bytes and writes a capture-hash manifest beside the HTML.
3. Run `node scripts/render.cjs showcase.html --output showcase.png`. If needed,
   supply `--module-root /absolute/node_modules` and `--browser /absolute/browser`.
   Reuse project/bundled dependencies; request missing tools only when necessary.
4. Open the final PNG at full size and as a social thumbnail. Check readability,
   screenshot integrity, accurate claims, privacy, credits, and complete text.
   The renderer checks decoding, aspect ratios and overflow; inspect visually too.

## Deliver a share kit

Keep PNG, editable HTML/spec, original captures, and sanitized provenance in the
requested workspace. Draft a platform-appropriate post with concrete tester or
developer actions and an accurate release/install link. Account for X's URL
weighting when checking length. Publishing/posting needs existing task authority;
drafting does not authorize sending. Verify an authorized release's actual tag
and assets afterward, and preserve binary distribution gates.
