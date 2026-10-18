# Updating an existing Linux integration

After installing an updated graphical APK, stop Linux screens and command
sessions, then choose **Update Linux integration** in the host controls. The
operation updates the APK-provided shell and adapter files; it does not reinstall
Linux, erase home directories, or run a package-manager transaction. Existing
profiles, widget layouts, theme selection, and additional programs
are preserved. Missing initial profile/home files may be seeded from defaults.

The update authenticates the payload against the installed APK's manifest and
extracts it into a separate staging directory. It compares the installed
per-file ownership manifest with the new one. Only changed stock files and new
files are candidates for replacement; customized files whose upstream content
did not change are left alone. A conflict on a file that needs changing aborts
before any live file is changed. Linked paths are preserved rather than followed.
Legacy manifests without permission fields are supported conservatively; new
APK assets must include file modes from `prepare-install-assets.py`.

An exclusive installation lease prevents application-owned Linux sessions from
running during the update. The previous version's bytes and modes, the new files,
and a journal are staged before publishing the transaction. Each changed file
is replaced atomically, with the installed ownership manifest committed last.
This is process-interruption recovery, not a claim of whole-filesystem atomicity
or power-loss durability.

If an update is interrupted, normal Linux startup and helper refresh are blocked
while its journal is active. Choose **Update Linux integration** again to recover
and retry. Recovery checks all live files and rollback copies before restoring
anything. A newer edit is preserved and blocks automatic rollback; the operation
log identifies the conflict for reconciliation. A completed transaction is
removed from the active location before its temporary files are deleted, so an
interruption during cleanup cannot make a committed installation appear partial.
An interrupted cleanup may leave app-private staging files for later reclamation.

The lease coordinates application owners. It is not a filesystem transaction
against an uncooperative same-UID editor. Keep an exported backup before a major
upgrade. The updater does not replace Omarchy's own migrations or package-manager
updates, and future upstream pin changes need their own compatibility checks.

JVM behavior tests cover stock replacement, conflict preflight, customized
unchanged files, removal/addition rollback, process-interruption recovery, newer
edits, committed recovery, path traversal, and symlink preservation. An adapter
test prevents execution/refresh against an interrupted update. On a prepared
API 35 x86_64 emulator, the visible controls rejected updates while Linux was
running, preserved a deliberate stock-file conflict without changing the
ownership manifest, updated 16 changed stock paths plus one missing profile,
and made a repeated update a zero-file operation. Existing data, profiles and
Android grants were checked unchanged, and the original installation was
restored after testing. The updated payload also displayed Mobile widgets on the
phone and the desktop bar/background on the external output after reconnect.
This does not establish arbitrary upstream-version or ARM64 upgrade safety.
