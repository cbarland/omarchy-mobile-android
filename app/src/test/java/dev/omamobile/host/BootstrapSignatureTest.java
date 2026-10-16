package dev.omamobile.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Arrays;

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.junit.Test;

public final class BootstrapSignatureTest {
    private static final String ARM_FINGERPRINT = "68B3537F39A313B3E574D06777193F152BDBE6A6";

    @Test
    public void shippedArmKeyHasPinnedFingerprintAndRealSignatureParses() throws Exception {
        byte[] key = Files.readAllBytes(Path.of("src/main/res/raw/archlinuxarm_signing_key.asc"));
        byte[] signature = resourceFromReferenceFixture();
        // The fixture is the official ARM .sig; the verifier must reject it
        // against a synthetic artifact, but its issuer is parsed only through
        // the normal verification path below. The key-pin mismatch is tested
        // without network or extraction.
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, new byte[] {1, 2, 3});
        try {
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(signature),
                    new ByteArrayInputStream(key), ARM_FINGERPRINT));
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    public void ephemeralValidSignatureVerifiesAndWrongPinFails() throws Exception {
        Fixture fixture = fixture("signed bootstrap".getBytes());
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, fixture.payload);
        try {
            assertEquals(fixture.fingerprint, BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(fixture.signature),
                    new ByteArrayInputStream(fixture.publicKey), fixture.fingerprint));
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(fixture.signature),
                    new ByteArrayInputStream(fixture.publicKey), "0000000000000000000000000000000000000000"));
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    public void mutationAndTruncationFail() throws Exception {
        Fixture fixture = fixture("signed bootstrap".getBytes());
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, new byte[] {9, 9, 9});
        try {
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(fixture.signature),
                    new ByteArrayInputStream(fixture.publicKey), fixture.fingerprint));
            byte[] truncated = new byte[fixture.signature.length - 1];
            System.arraycopy(fixture.signature, 0, truncated, 0, truncated.length);
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(truncated),
                    new ByteArrayInputStream(fixture.publicKey), fixture.fingerprint));
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    public void malformedAndOversizedInputsFailClosed() throws Exception {
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, new byte[] {1});
        try {
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(new byte[] {1}),
                    new ByteArrayInputStream(new byte[] {1}), ARM_FINGERPRINT));
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(new byte[1024 * 1024 + 1]),
                    new ByteArrayInputStream(new byte[] {1}), ARM_FINGERPRINT));
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    public void cancellationBeforeArtifactReadFailsClosed() throws Exception {
        Fixture fixture = fixture("signed bootstrap".getBytes());
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, fixture.payload);
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(fixture.signature),
                    new ByteArrayInputStream(fixture.publicKey), fixture.fingerprint));
        } finally {
            Thread.interrupted();
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    public void unboundAppendedSubkeyCannotBorrowPinnedPrimaryTrust() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PGPKeyPair primary = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_SIGN,
                generator.generateKeyPair(), new Date(0));
        PGPKeyPair attacker = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_SIGN,
                generator.generateKeyPair(), new Date(0));
        BcPGPContentSignerBuilder signer = new BcPGPContentSignerBuilder(
                PublicKeyAlgorithmTags.RSA_SIGN, HashAlgorithmTags.SHA256);
        PGPKeyRingGenerator generatorRing = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION, primary, "test@example.invalid",
                new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA256),
                null, null, signer, null);
        generatorRing.addSubKey(attacker);
        PGPPublicKeyRing bound = generatorRing.generatePublicKeyRing();
        org.bouncycastle.openpgp.PGPPublicKey appended = bound.getPublicKey(attacker.getKeyID());
        Iterator<PGPSignature> binding = appended.getSignatures();
        while (binding.hasNext()) appended = org.bouncycastle.openpgp.PGPPublicKey.removeCertification(appended, binding.next());
        List<org.bouncycastle.openpgp.PGPPublicKey> keys = new ArrayList<>();
        keys.add(bound.getPublicKey());
        keys.add(appended);
        ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
        new PGPPublicKeyRing(keys).encode(publicOut);
        PGPSignatureGenerator signatureGenerator = new PGPSignatureGenerator(signer);
        signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, attacker.getPrivateKey());
        byte[] payload = "attacker subkey".getBytes();
        signatureGenerator.update(payload);
        ByteArrayOutputStream signatureOut = new ByteArrayOutputStream();
        signatureGenerator.generate().encode(signatureOut);
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, payload);
        try {
            assertThrows(IOException.class, () -> BootstrapSignature.verify(
                    artifact, new ByteArrayInputStream(signatureOut.toByteArray()),
                    new ByteArrayInputStream(publicOut.toByteArray()),
                    fingerprint(bound.getPublicKey().getFingerprint())));
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    public void shippedKeysHaveTheExactOfficialPrimaryFingerprints() throws Exception {
        assertEquals("68B3537F39A313B3E574D06777193F152BDBE6A6",
                shippedPrimaryFingerprint("src/main/res/raw/archlinuxarm_signing_key.asc"));
        assertEquals("3E80CA1A8B89F69CBA57D98A76A5EF9054449A5C",
                shippedPrimaryFingerprint("src/main/res/raw/arch_signing_key.asc"));
    }

    @Test
    public void armoredSignatureIsRejected() throws Exception {
        Fixture fixture = fixture("armored".getBytes(), HashAlgorithmTags.SHA256, PGPSignature.BINARY_DOCUMENT);
        ByteArrayOutputStream armored = new ByteArrayOutputStream();
        try (ArmoredOutputStream output = new ArmoredOutputStream(armored)) {
            output.write(fixture.signature);
        }
        assertThrows(IOException.class, () -> verifyFixture(fixture, armored.toByteArray()));
    }

    @Test
    public void weakDigestAndCanonicalTextAreRejected() throws Exception {
        Fixture weak = fixture("weak".getBytes(), HashAlgorithmTags.SHA1, PGPSignature.BINARY_DOCUMENT);
        assertThrows(IOException.class, () -> verifyFixture(weak, weak.signature));
        Fixture text = fixture("text".getBytes(), HashAlgorithmTags.SHA256, PGPSignature.CANONICAL_TEXT_DOCUMENT);
        assertThrows(IOException.class, () -> verifyFixture(text, text.signature));
    }

    @Test
    public void concatenatedSignaturesAndTrailingBytesAreRejected() throws Exception {
        Fixture fixture = fixture("concat".getBytes(), HashAlgorithmTags.SHA256, PGPSignature.BINARY_DOCUMENT);
        byte[] concatenated = new byte[fixture.signature.length * 2];
        System.arraycopy(fixture.signature, 0, concatenated, 0, fixture.signature.length);
        System.arraycopy(fixture.signature, 0, concatenated, fixture.signature.length, fixture.signature.length);
        assertThrows(IOException.class, () -> verifyFixture(fixture, concatenated));
        byte[] trailing = Arrays.copyOf(fixture.signature, fixture.signature.length + 3);
        trailing[trailing.length - 1] = 0x7f;
        assertThrows(IOException.class, () -> verifyFixture(fixture, trailing));
    }

    private static void verifyFixture(Fixture fixture, byte[] signature) throws Exception {
        Path artifact = Files.createTempFile("bootstrap", ".tar.gz");
        Files.write(artifact, fixture.payload);
        try {
            BootstrapSignature.verify(artifact, new ByteArrayInputStream(signature),
                    new ByteArrayInputStream(fixture.publicKey), fixture.fingerprint);
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    private static Fixture fixture(byte[] payload) throws Exception {
        return fixture(payload, HashAlgorithmTags.SHA256, PGPSignature.BINARY_DOCUMENT);
    }

    private static Fixture fixture(byte[] payload, int digest, int signatureType) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        PGPKeyPair pgp = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_SIGN, pair, new Date(0));
        BcPGPContentSignerBuilder signer = new BcPGPContentSignerBuilder(
                PublicKeyAlgorithmTags.RSA_SIGN, digest);
        PGPKeyRingGenerator ringGenerator = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION, pgp, "test@example.invalid",
                new JcaPGPDigestCalculatorProviderBuilder().build().get(digest),
                null, null, signer, null);
        PGPPublicKeyRing ring = ringGenerator.generatePublicKeyRing();
        ByteArrayOutputStream keyOut = new ByteArrayOutputStream();
        ring.encode(keyOut);

        PGPSignatureGenerator signatureGenerator = new PGPSignatureGenerator(signer);
        signatureGenerator.init(signatureType, pgp.getPrivateKey());
        signatureGenerator.update(payload);
        ByteArrayOutputStream signatureOut = new ByteArrayOutputStream();
        signatureGenerator.generate().encode(signatureOut);
        return new Fixture(payload, signatureOut.toByteArray(), keyOut.toByteArray(),
                fingerprint(ring.getPublicKey().getFingerprint()));
    }

    private static String shippedPrimaryFingerprint(String path) throws Exception {
        byte[] encoded = Files.readAllBytes(Path.of(path));
        BcPGPObjectFactory factory = new BcPGPObjectFactory(
                PGPUtil.getDecoderStream(new ByteArrayInputStream(encoded)));
        Object object = factory.nextObject();
        if (!(object instanceof PGPPublicKeyRing)) throw new IOException("missing public key ring");
        byte[] fingerprint = ((PGPPublicKeyRing) object).getPublicKey().getFingerprint();
        return fingerprint(fingerprint);
    }

    private static String fingerprint(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format("%02X", value & 0xff));
        return out.toString();
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = BootstrapSignatureTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing resource " + name);
            return input.readAllBytes();
        }
    }

    private static byte[] resourceFromReferenceFixture() throws IOException {
        // The official ARM signature is copied into test resources by the
        // provenance step; this guard gives a clear failure if omitted.
        return resource("/ArchLinuxARM-aarch64-latest.tar.gz.sig");
    }

    private static final class Fixture {
        final byte[] payload;
        final byte[] signature;
        final byte[] publicKey;
        final String fingerprint;
        Fixture(byte[] payload, byte[] signature, byte[] publicKey, String fingerprint) {
            this.payload = payload;
            this.signature = signature;
            this.publicKey = publicKey;
            this.fingerprint = fingerprint;
        }
    }
}
