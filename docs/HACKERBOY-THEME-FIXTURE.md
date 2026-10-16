# Fixed Hackerboy public fixture

The opt-in `verify-theme-download-persistence --public-hackerboy` path is fixed
to the public repository [ggregoro/hackerboy](https://github.com/ggregoro/hackerboy)
at commit
`add711288b2549ad0b017618cb6256053391d8fa`. Before the unchanged upstream
`omarchy-theme-install` command runs, the harness checks that the remote `HEAD`
still equals that commit. After the install it verifies the complete 14-file
tree, regular-file modes, and SHA-256 hashes recorded in the harness.

The repository contains palette data, icon/background/preview images, README,
and LICENSE only. It contains no Lua, shell, hook, terminal, desktop, service,
or plugin files. Its MIT notice covers the theme files; its LICENSE explicitly
excludes bundled background images, including a credited Unsplash image. This
fixture therefore records image provenance without redistributing or repackaging
the assets.

The normal phased sequence remains:

```text
prepare /tmp/omamobile-theme-download-<id> --public-hackerboy
install /tmp/omamobile-theme-download-<id>
check /tmp/omamobile-theme-download-<id> --stage installed
check /tmp/omamobile-theme-download-<id> --stage reopened --require-restart
restore /tmp/omamobile-theme-download-<id>
```

`restore` first applies the original built-in theme, checks the saved snapshots,
then verifies and removes only the owned `hackerboy` checkout. The successful
result is the only point at which `publicHttpsVerified` becomes true.
