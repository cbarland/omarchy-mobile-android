# Preview 2 sharing kit

`omarchy-mobile-android-preview-2.png` combines real captures from an API 35
x86_64 emulator: Mobile widgets on the phone and Foot/Neovim on the shared
Hyprland desktop. Entire captures are embedded without retouching or cropping.
Borders, labels, goals and credits sit outside the screens. This replaces the
initial concept poster. No simulated product UI is used.

The raw PNGs are in `captures/`; `capture-context.json` records the environment,
exact APK and limitations. `showcase.json` preserves the editable composition;
the generated self-contained HTML is a release asset, regenerated rather than
duplicated in Git. The `.captures.json` binds raw screenshot hashes and dimensions
to that HTML. Actual emulator architecture and unknown
Android status icons remain visible; neither was altered for presentation.

Rebuild with the repository's reusable app-showcase skill:

```sh
python .agents/skills/app-showcase/scripts/compose.py docs/media/showcase.json --output docs/media/omarchy-mobile-android-preview-2.html
node .agents/skills/app-showcase/scripts/render.cjs docs/media/omarchy-mobile-android-preview-2.html --output docs/media/omarchy-mobile-android-preview-2.png
```

The renderer needs an existing Playwright/browser installation; optional
`--module-root` and `--browser` select local installations. It blocks network
requests and checks decoding, aspect ratio and layout overflow. Inspect the final
PNG too. Use the release notes for verification limits; do not advertise a public
graphical APK.

Attach the PNG to the first post below. These are drafts; they have not been
posted to X.

## Main post

Omarchy Mobile, on stock Android. No root.

A phone touch UI + real Hyprland desktop, sharing one Linux environment.

Source developer preview: testers & developers wanted, especially ARM64 devices and upstream integration.

https://github.com/cbarland/omarchy-mobile-android

## Optional follow-up

Built on Omarchy, Derek Ross's Omarchy Mobile, TAWC and anhyprland.

Emulator: shared cursor, Squeekboard, audio transport and recoverable updates. ARM64/audio validation continues.

Next: upstream upgrades, broader devices, GPU/power.

## Release

https://github.com/cbarland/omarchy-mobile-android/releases/tag/v0.1.0-preview.2
