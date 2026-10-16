# Authenticated bootstrap provenance

`BootstrapSignature` is a framework-independent detached OpenPGP verifier. Its streaming verification shape is adapted from TAWC's MIT-licensed `SignatureVerifier` at commit `64e3736699684a4fc0029e71bdfb7cc1e0884323`: https://github.com/wmww/tawc/tree/64e3736699684a4fc0029e71bdfb7cc1e0884323. The adaptation is intentionally limited to BouncyCastle object parsing, key selection, fingerprint pinning, and streaming verification; it does not copy Android, network, installer, or extraction code. Attribution is included in the Java source.

The bundled public keys are the pinned TAWC blobs from that commit:

* `archlinuxarm_signing_key.asc`: full primary fingerprint `68B3537F39A313B3E574D06777193F152BDBE6A6`, published by Arch Linux ARM at https://archlinuxarm.org/about/package-signing and https://archlinuxarm.org/about/downloads.
* `arch_signing_key.asc`: full primary fingerprint `3E80CA1A8B89F69CBA57D98A76A5EF9054449A5C`, published on Arch Linux's developer page at https://archlinux.org/people/developers/ and referenced by the official download guidance at https://archlinux.org/download/.

The verifier requires the supplied expected value to be a full 40-hex-digit primary fingerprint. It finds the signature issuer inside the supplied ring, checks that the issuer belongs to the pinned primary key, streams all artifact bytes with interruption checks, and then verifies the signature. It accepts exactly one detached signature object, rejects trailing objects, rejects compressed signature wrappers, and allows only binary-document signatures with SHA-256, SHA-384, or SHA-512. Signature and public-key inputs are bounded to 1 MiB.

Dependency evidence: `org.bouncycastle:bcpg-jdk18on:1.86` and `org.bouncycastle:bcprov-jdk18on:1.86` are the current release artifacts shown by Maven Central and the Bouncy Castle 1.86 release page as checked 2026-10-15:

* https://central.sonatype.com/artifact/org.bouncycastle/bcpg-jdk18on
* https://central.sonatype.com/artifact/org.bouncycastle/bcprov-jdk18on
* https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/

No global JCA provider registration is used. No network access, extraction, root execution, or Android context is part of this verifier.
