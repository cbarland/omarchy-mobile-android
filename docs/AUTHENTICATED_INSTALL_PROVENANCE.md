# Authenticated bootstrap provenance

## Publisher and verifier provenance

`BootstrapSignature` is a framework-independent detached OpenPGP verifier. Its
streaming verification shape is adapted from TAWC's MIT-licensed
`SignatureVerifier` at commit
[`64e3736699684a4fc0029e71bdfb7cc1e0884323`](https://github.com/wmww/tawc/tree/64e3736699684a4fc0029e71bdfb7cc1e0884323).
The adaptation is limited to Bouncy Castle packet parsing, primary-key
fingerprint pinning, and streaming verification; it does not copy Android,
network, installer, or extraction code. Attribution is included in the Java
source and the TAWC MIT notice remains in the repository.

The APK bundles the pinned public-key blobs used by the automatic path:

* `archlinuxarm_signing_key.asc`: primary fingerprint
  `68B3537F39A313B3E574D06777193F152BDBE6A6`, published by [Arch Linux
  ARM](https://archlinuxarm.org/about/package-signing) and linked from its
  [downloads](https://archlinuxarm.org/about/downloads).
* `arch_signing_key.asc`: primary fingerprint
  `3E80CA1A8B89F69CBA57D98A76A5EF9054449A5C`, published through the [Arch Linux
  developer listing](https://archlinux.org/people/developers/) and official
  [download guidance](https://archlinux.org/download/).

The verifier requires a full 40-hex-digit expected primary fingerprint. It
resolves only the primary key, requires the signature issuer key ID to equal that
primary key ID, and compares the full primary fingerprint. It does not accept an
arbitrary ring member or an unbound subkey. Artifact bytes are streamed with
interrupt checks. Exactly one raw binary detached signature is accepted; ASCII
armor, compressed or concatenated signature objects, trailing bytes, malformed
input, weak/unsupported digest and unsupported signature type are rejected.
Signature and public-key inputs are bounded to 1 MiB.

## Automatic download and archive boundary

`BootstrapDownload` obtains the detached signature before accepting the archive,
then verifies the archive bytes before handing them to `RootfsImporter`. It uses
HTTPS-only requests and HTTPS-only redirects, requests identity encoding, rejects
unexpected content encoding, enforces response and `Content-Length` limits, checks
EOF/truncation, retries a failed request once, and removes its temporary work tree
on cancellation or signature/download failure. `Verified.close()` removes the
unpublished work tree. The archive limit is 2 GiB and the detached signature limit
is 1 MiB. The x86_64 `ARCH_BOOTSTRAP` layout uses the official zstd
`root.x86_64/` wrapper; ARM uses the official gzip rootfs. Extraction and publish
remain downstream of signature verification.

The automatic path has not yet received final device/emulator runtime proof in
this checkout. Existing manual archive/import/setup evidence remains valid and is
reported separately in `INSTALLING.md` and `VERIFICATION.md`.

## Resolved dependency and license evidence

The verifier resolves Bouncy Castle Java 1.86 from Maven Central:
`bcpg-jdk18on:1.86` and `bcprov-jdk18on:1.86`, with `bcutil-jdk18on:1.86`
resolved transitively by the OpenPGP module. The exact JAR notices are retained
in `app/src/main/assets/licenses/bouncycastle-bcpg.txt`,
`app/src/main/assets/licenses/bouncycastle-bcprov.txt`, and
`app/src/main/assets/licenses/bouncycastle-bcutil.txt`. The official release and artifact records are:

* [Bouncy Castle Java 1.86 release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/)
* [bcpg-jdk18on on Maven Central](https://central.sonatype.com/artifact/org.bouncycastle/bcpg-jdk18on)
* [bcprov-jdk18on on Maven Central](https://central.sonatype.com/artifact/org.bouncycastle/bcprov-jdk18on)
* [bcutil-jdk18on on Maven Central](https://central.sonatype.com/artifact/org.bouncycastle/bcutil-jdk18on)

The archive wrapper uses `com.github.luben:zstd-jni:1.5.7-23`, whose resolved POM
declares the zstd-jni BSD-2-Clause license. Its Android archive and JVM artifact
are retained as build inputs; exact notices are in
`app/src/main/assets/licenses/zstd-jni.txt` and
`app/src/main/assets/licenses/zstd.txt`. The bundled native Zstandard
implementation carries its upstream BSD-3-Clause notice from the [official zstd
source](https://github.com/facebook/zstd/blob/dev/LICENSE). These notices are
copied as assets by the build; no corresponding-source claim is made here.

No global JCA provider registration is used. No network access, extraction, root
execution, or Android context is part of `BootstrapSignature`.
