package dev.omamobile.host;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/**
 * Pure detached OpenPGP verification for publisher-signed bootstrap bytes.
 *
 * Adapted from TAWC's MIT-licensed SignatureVerifier mechanism at commit
 * 64e3736699684a4fc0029e71bdfb7cc1e0884323 (https://github.com/wmww/tawc).
 * The verifier deliberately has no Android, network, extraction, or global
 * provider-registration dependency.
 */
public final class BootstrapSignature {
    private static final int MAX_SIGNATURE_BYTES = 1024 * 1024;
    private static final int MAX_KEY_BYTES = 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;

    private BootstrapSignature() {}

    /**
     * Verifies an artifact against one detached signature and a pinned key.
     * The return value is the normalized full primary-key fingerprint.
     */
    public static String verify(
            Path artifact,
            InputStream detachedSignature,
            InputStream publicKey,
            String expectedFingerprint) throws IOException {
        if (artifact == null || detachedSignature == null || publicKey == null) {
            throw new NullPointerException("artifact, detachedSignature, and publicKey are required");
        }
        String expected = normalizeFingerprint(expectedFingerprint);
        byte[] signatureBytes = readBounded(detachedSignature, MAX_SIGNATURE_BYTES, "detached signature");
        byte[] keyBytes = readBounded(publicKey, MAX_KEY_BYTES, "public key");

        PGPSignature signature = parseSignature(signatureBytes);
        if (signature.getSignatureType() != PGPSignature.BINARY_DOCUMENT) {
            throw new IOException("unsupported OpenPGP signature type: " + signature.getSignatureType());
        }
        if (!supportedDigest(signature.getHashAlgorithm())) {
            throw new IOException("unsupported OpenPGP digest: " + signature.getHashAlgorithm());
        }

        List<PGPPublicKeyRing> rings = parseKeyRings(keyBytes);
        PGPPublicKey signingKey = null;
        String signerFingerprint = null;
        for (PGPPublicKeyRing ring : rings) {
            PGPPublicKey primary = ring.getPublicKey();
            String fingerprint = fingerprint(primary);
            if (!fingerprint.equals(expected)) continue;
            // Bootstrap signatures are intentionally pinned to the primary
            // publisher key. Do not accept an arbitrary subkey merely because
            // its issuer ID appears in the ring; an unbound/appended subkey
            // must never inherit the primary key's pin.
            if (primary.getKeyID() != signature.getKeyID()) continue;
            if (signingKey != null) {
                throw new IOException("ambiguous signing key for pinned fingerprint " + expected);
            }
            signingKey = primary;
            signerFingerprint = fingerprint;
        }
        if (signingKey == null) {
            throw new IOException("signature issuer is absent from the pinned public-key ring");
        }
        try {
            signature.init(new BcPGPContentVerifierBuilderProvider(), signingKey);
            try (InputStream input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                for (;;) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("bootstrap signature verification cancelled");
                    }
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    signature.update(buffer, 0, count);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("bootstrap signature verification cancelled");
            }
            if (!signature.verify()) {
                throw new IOException("bootstrap signature verification failed");
            }
            return signerFingerprint;
        } catch (PGPException e) {
            throw new IOException("OpenPGP verification failed", e);
        }
    }

    private static PGPSignature parseSignature(byte[] bytes) throws IOException {
        BcPGPObjectFactory factory = new BcPGPObjectFactory(
                    PGPUtil.getDecoderStream(new ByteArrayInputStream(bytes)));
        Object object = factory.nextObject();
        if (!(object instanceof PGPSignatureList)) {
            throw new IOException("detached signature is not a signature list");
        }
        PGPSignatureList list = (PGPSignatureList) object;
        if (list.size() != 1) {
            throw new IOException("detached signature must contain exactly one signature");
        }
        if (factory.nextObject() != null) {
            throw new IOException("detached signature has trailing OpenPGP objects");
        }
        return list.get(0);
    }

    private static List<PGPPublicKeyRing> parseKeyRings(byte[] bytes) throws IOException {
        List<PGPPublicKeyRing> result = new ArrayList<>();
        BcPGPObjectFactory factory = new BcPGPObjectFactory(
                    PGPUtil.getDecoderStream(new ByteArrayInputStream(bytes)));
        for (;;) {
            Object object = factory.nextObject();
            if (object == null) break;
            if (!(object instanceof PGPPublicKeyRing)) {
                throw new IOException("public-key input contains an unexpected OpenPGP object");
            }
            result.add((PGPPublicKeyRing) object);
        }
        if (result.isEmpty()) throw new IOException("public-key input is empty");
        return result;
    }

    private static byte[] readBounded(InputStream input, int limit, String label) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("reading " + label + " cancelled");
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0) continue;
            if (count > limit - total) throw new IOException(label + " exceeds " + limit + " bytes");
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static boolean supportedDigest(int digest) {
        return digest == HashAlgorithmTags.SHA256
                || digest == HashAlgorithmTags.SHA384
                || digest == HashAlgorithmTags.SHA512;
    }

    private static String fingerprint(PGPPublicKey key) {
        byte[] bytes = key.getFingerprint();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        return result.toString();
    }

    private static String normalizeFingerprint(String fingerprint) throws IOException {
        if (fingerprint == null) throw new IOException("expected fingerprint is required");
        String normalized = fingerprint.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{40}")) {
            throw new IOException("expected a 40-hex-digit primary fingerprint");
        }
        return normalized;
    }
}
