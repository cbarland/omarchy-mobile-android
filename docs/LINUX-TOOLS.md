# Optional Linux CLI preparation

The graphics image is a minimal Arch installation, not the full Omarchy
package set. Its upstream agent account panel calls `mise use -g codex`
when Codex is absent. Without `mise`, it reports "Installing the Codex CLI
didn't finish" before sign-in starts. This is a missing Linux prerequisite,
not an Android or Shizuku permission failure.

For Codex, use the standalone installer documented in the
[official OpenAI CLI guide](https://learn.chatgpt.com/docs/codex/cli).
`tools/linux/install-codex` downloads that installer over HTTPS, requires the
exact SHA-256 reviewed during this device preparation, and lets it verify
the selected release's archive. It installs the normal command in
`/usr/local/bin`, which both Android-hosted Linux profiles already search.
An installer change fails the digest check and requires review before the
pin is updated. The digest records the inspected script; it is not an
independently published signature.

Run the helper inside the prepared Linux environment, or pass its text to
the development command runner. Its optional argument selects an explicit
release; the default follows the official latest channel. It does not
modify upstream Omarchy's account scripts, start sign-in, access account
credentials, or run an agent task.

The Nubia installation used the reviewed installer SHA-256
`150e3cf675682efeaac115aa3747add3f27887896d04ce6d0b56478d8b428bf6`.
It selected Linux ARM64 and installed `codex-cli 0.162.1`. The command's
version, login help and discovery by `omarchy-cmd-missing` were checked;
both phone and desktop profiles resolve `/usr/local/bin/codex`.
Account sign-in remains the user's action in Omarchy's account panel.
Model requests, the CLI's sandbox and general agent execution are not
verified by these installation checks. Other providers still need their
normal Linux prerequisites, including mise where applicable.
