# Install Omarchy inside the Android app

This is a development installation flow. Use an APK built with the graphics
backend for your ABI and the [installer assets](BUILDING.md#include-installer-assets).
The temporary **core validation** CI APK lacks those graphics libraries. There is
no established signed public release/update channel yet; obtain the APK from the
maintainer or build it from source.

The application supports Android 10/API 29 or newer on ARM64 and x86_64. Real
hardware still needs an Android-supported external display connection to show the
desktop. Linux is stored in the app's private data directory; allow room for the
base archive, a staging installation, and downloaded packages. Optional Shizuku
authorization is for Android app windows, not for running Linux or displaying
Omarchy.

## 1. Choose a matching official Linux base

Use ARM64/AArch64 for an `arm64-v8a` phone and x86_64 for an x86_64 emulator. The
importer accepts tar or gzip-compressed tar with the filesystem at its top level
(`bin`, `etc`, `usr`, and so on). It checks the `/bin/sh` ELF architecture before
activation. An ISO, disk image, zstd archive, or the unnormalized Arch bootstrap
wrapper cannot be selected directly.

The APK does not download the distribution image for you. Download it separately,
verify its publisher signature before importing it, then copy the verified
archive into Android's Files/Downloads storage. The preparation stage later
downloads signed distribution packages, so it needs internet access.

### ARM64 phone: Arch Linux ARM

Use the official [Generic AArch64 base](https://archlinuxarm.org/platforms/armv8/generic),
not another board's image. Only its userspace is imported; the platform page's
disk/boot installation instructions do not apply to Android.

On a computer with curl and GnuPG:

```sh
curl --fail --location --proto '=https' -O https://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz
curl --fail --location --proto '=https' -O https://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz.sig
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 68B3537F39A313B3E574D06777193F152BDBE6A6
gpg --fingerprint 68B3537F39A313B3E574D06777193F152BDBE6A6
gpg --verify ArchLinuxARM-aarch64-latest.tar.gz.sig ArchLinuxARM-aarch64-latest.tar.gz
```

Compare the full fingerprint with the current **Arch Linux ARM Build System** key
on the official [package-signing page](https://archlinuxarm.org/about/package-signing).
As checked on 2026-10-15, it is
`68B3 537F 39A3 13B3 E574 D067 7719 3F15 2BDB E6A6`.
Continue only after verifying the detached signature with that key. Keep a
SHA-256 of the verified archive with your installation notes, because `latest`
changes. The APK's import hash records the selected bytes; it does not replace
publisher-signature verification.

### x86_64 emulator: Arch bootstrap

Download the bootstrap tarball, its matching `.sig`, and checksum list from one
release linked by [Arch Linux Downloads](https://archlinux.org/download/). Keep all
three from the same dated release. Follow that page's current release-key/WKD
instructions and verify the **bootstrap** signature against the downloaded
bootstrap file, not an ISO signature. Check the full key fingerprint linked from
the official download page; the 2026-10-01 release links
`3E80 CA1A 8B89 F69C BA57 D98A 76A5 EF90 5444 9A5C`.

The bootstrap uses zstd compression and a `root.x86_64/` wrapper. On Windows,
normalize the already verified archive with Python 3.11+ and Windows' bundled tar:

```powershell
python tools/prepare-arch-emulator.py .\archlinux-bootstrap-2026.10.01-x86_64.tar.zst `
  --sha256 <SHA256_FROM_THE_OFFICIAL_RELEASE_LIST> --out .\arch-emulator.tar.gz
```

Replace the sample release filename and checksum with the release you verified.
This helper checks the supplied checksum, removes the wrapper, and converts to
gzip without running the image. It does not perform the preceding PGP check.
Copy the resulting `arch-emulator.tar.gz` to the emulator's file picker storage.

## 2. Install and open the APK

Install the prepared debug APK using Android's package installer, or use an
explicitly selected development device with `adb -s <serial> install -r <apk>`.
If Android reports a signing-certificate conflict, use a build signed with the
existing installation's key. Uninstalling the application deletes its private
Linux installation, so it is not a routine update workaround.

Open **Omarchy Mobile / Android host**. Choose **Install Omarchy from Linux
archive** and select the verified, matching tar/tar.gz using Android's document
picker. The application extracts to staging, prepares the signed Linux package
installation, applies the packaged shell setup, and activates the installation
only after preparation succeeds. Progress appears in the host controls. Keep the
application available while the first package download/upgrade completes.

Use **Cancel operation** to stop setup. On failure, read the reported phase and relevant console output. A failed new
installation is not activated over an existing rootfs. An existing Linux
installation is retained rather than merged with or silently replaced by another
archive. Do not clear application storage to diagnose a small setup failure.

After installation, **Export Linux user data** writes a gzip-compressed tar archive
through Android's document picker. It contains only `/root` and `/home` (including
their file modes and symlinks); package files, `/usr`, and Android-private metadata
are excluded. The export walks live files at one point in time; stop graphical and
Linux sessions first if you need a quiescent user-data copy. Keep the export with
the archive fingerprint and verify the resulting file before moving it. A failed
copy can leave an incomplete newly-created document in the selected provider; do
not use that file.

To recover an export, choose **Recover user data as separate copy** and select the
gzip archive. Recovery validates that every entry belongs to `/root` or `/home`,
extracts into private staging, and publishes a new UUID directory under the
Linux guest's `/root/Recovered/<UUID>/` only after the complete archive has been
read. The existing Linux installation is left in place; cancellation, malformed paths, and
unsafe links remove private staging without publishing a partial copy. Links that
point outside `/root` or `/home` are re-rooted as dangling links inside the
recovered copy, so they cannot reach the live guest root. Recovery
does not run package commands or replace the active rootfs. The operation and its
result are recorded in the app's persistent `linux/install.log`.

Unpublished recovery data is staged outside the guest home in app-private
storage. Existing home contents, including older recovered copies, are included
in future exports; remove copies you no longer need before exporting again.
Cancellation closes the selected input stream. A document provider stalled
while opening the archive can delay cancellation until that open returns.

## 3. Start phone and desktop sessions

After preparation completes, choose **Start Omarchy screens**. The phone uses
Omarchy Mobile; one eligible external Android display uses the Omarchy desktop.
They share installed Linux applications and home files while keeping separate
compositor sessions. Windows do not move between those sessions.

Use **Android displays** in the host controls to inspect outputs or select the
external target. A saved target that is missing or indistinguishable does not
silently fall back to a different display. Apply a different preference with
**Stop graphical sessions**, then **Start Omarchy screens**. The desktop's upstream display
panel opens with **Super + Ctrl + D**; phone/external scale behavior and other
limits are described in [GRAPHICS.md](GRAPHICS.md).

For optional Android app windows, follow [ANDROID-APPS.md](ANDROID-APPS.md) and
authorize Shizuku in ADB mode explicitly. No Android root, bootloader unlock,
flashing, or permission-policy modification is part of the Linux installation.

## Validation status

The new flow passed on a disposable API35 x86_64 emulator: an empty installation
became both real graphical shells in 215.296s, including packaged-asset checks.
The actual Android document picker, foreground service, setup wake lock and
cancellation during signed package downloads were exercised separately. Cancel
left no active setup processes or unpublished rootfs. Service completion/retry
regressions also pass. See [VERIFICATION.md](VERIFICATION.md) for evidence scope.
Fresh ARM64 installation is still unverified; previous manually prepared physical
sessions do not establish this new installation path on a phone.


### User-data export verification - 2026-10-15

On disposable API35 emulator-5556, the three targeted LinuxDataExportTest
checks passed again in 20.980 seconds after the abandoned-staging fix. They
verify user-tree scope, fixture bytes/mode/symlink, unchanged installed data,
cancellation/private-temp cleanup, persistent logs, rejected-service cleanup,
and reclamation of an abandoned archive while retaining an unrelated sentinel.
These in-process tests use file URIs and do not themselves prove SAF grants.

A separate actual Export control -> Android Downloads picker -> Save flow
completed through the document provider. Inspection of the resulting 98,904,258
byte tar.gz found 1,256 regular files and 13 symlinks, all under root/ or home/;
the foreground LinuxService had exited after completion. No archive or private
Linux data is committed to the repository. Restore/replacement and authenticated
image download remain open in issue #3.
