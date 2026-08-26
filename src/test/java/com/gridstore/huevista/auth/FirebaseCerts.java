package com.gridstore.huevista.auth;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stand-in for Google's Firebase certificate endpoint.
 *
 * <p>The tests need to mint ID tokens that {@link com.gridstore.huevista.auth.service.FirebaseTokenVerifier}
 * genuinely accepts, which means signing them with a key it can genuinely look up. So
 * rather than reaching into the verifier and handing it a key map — which would test a
 * back door instead of the code — this generates a keypair, wraps the public half in a
 * self-signed X.509 certificate, and serves it in Google's exact JSON shape
 * ({@code {"<kid>": "<PEM>"}}) from a real HTTP server on localhost. The verifier then
 * runs its whole fetch → parse → cache → verify path unchanged.
 *
 * <p>Self-signing needs a certificate builder. The JDK has no public one, so this uses
 * the shortest thing that is always present: it hand-assembles the DER of a minimal
 * certificate. Only the SubjectPublicKeyInfo is ever read back (the verifier calls
 * {@code getPublicKey()} and nothing else — it does not build a chain or check validity
 * of the certificate itself), so a minimal structure is enough and staying inside the
 * JDK keeps a test-only crypto library off the build.
 */
public final class FirebaseCerts implements AutoCloseable {

    public static final String KID = "test-key-1";

    private final KeyPair keyPair;
    private final HttpServer server;
    /** How many times the certificates have actually been fetched. */
    private final java.util.concurrent.atomic.AtomicInteger fetches =
            new java.util.concurrent.atomic.AtomicInteger();

    public FirebaseCerts() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        this.keyPair = generator.generateKeyPair();

        String body = "{\"" + KID + "\":\"" + pem().replace("\n", "\\n") + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            fetches.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=3600");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    /** Outbound fetches served so far — how the refresh throttle is observed. */
    public int fetchCount() {
        return fetches.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** The public key wrapped in a self-signed certificate, PEM-encoded. */
    private String pem() throws Exception {
        byte[] der = certificateDer();
        // Sanity check: if this doesn't parse, the tests would fail far from the cause.
        X509Certificate parsed = (X509Certificate) java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        if (!parsed.getPublicKey().equals(keyPair.getPublic())) {
            throw new IllegalStateException("Self-signed test certificate lost its public key");
        }
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }

    // ---- Minimal DER assembly ---------------------------------------------
    // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }

    private byte[] certificateDer() throws Exception {
        byte[] tbs = tbsCertificate();
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        return sequence(tbs, sha256WithRsaAlgorithmId(), bitString(signature));
    }

    private byte[] tbsCertificate() {
        byte[] version = tagged(0, integer(BigInteger.valueOf(2)));          // v3
        byte[] serial = integer(BigInteger.valueOf(1));
        byte[] issuer = name();
        byte[] validity = sequence(utcTime("250101000000Z"), utcTime("350101000000Z"));
        byte[] subject = name();
        byte[] spki = keyPair.getPublic().getEncoded();                       // already DER
        return sequence(version, serial, sha256WithRsaAlgorithmId(), issuer, validity, subject, spki);
    }

    /** RDNSequence with a single CN=huevista-test. */
    private static byte[] name() {
        byte[] cnOid = der((byte) 0x06, new byte[]{0x55, 0x04, 0x03});        // 2.5.4.3
        byte[] cnValue = der((byte) 0x0C, "huevista-test".getBytes(StandardCharsets.UTF_8));
        byte[] attribute = sequence(cnOid, cnValue);
        byte[] rdn = der((byte) 0x31, attribute);                             // SET
        return sequence(rdn);
    }

    private static byte[] sha256WithRsaAlgorithmId() {
        // 1.2.840.113549.1.1.11 sha256WithRSAEncryption, plus the NULL parameters
        // RFC 4055 requires be present for RSA.
        byte[] oid = der((byte) 0x06, new byte[]{
                0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B});
        return sequence(oid, new byte[]{0x05, 0x00});
    }

    private static byte[] integer(BigInteger value) {
        return der((byte) 0x02, value.toByteArray());
    }

    private static byte[] utcTime(String value) {
        return der((byte) 0x17, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] bitString(byte[] value) {
        byte[] withPadCount = new byte[value.length + 1];
        System.arraycopy(value, 0, withPadCount, 1, value.length);            // 0 unused bits
        return der((byte) 0x03, withPadCount);
    }

    private static byte[] tagged(int tag, byte[]... contents) {
        return der((byte) (0xA0 | tag), concat(contents));
    }

    private static byte[] sequence(byte[]... contents) {
        return der((byte) 0x30, concat(contents));
    }

    /** tag || length (short or long form) || content */
    private static byte[] der(byte tag, byte[] content) {
        byte[] length;
        if (content.length < 0x80) {
            length = new byte[]{(byte) content.length};
        } else {
            byte[] size = BigInteger.valueOf(content.length).toByteArray();
            if (size[0] == 0) size = java.util.Arrays.copyOfRange(size, 1, size.length);
            length = new byte[size.length + 1];
            length[0] = (byte) (0x80 | size.length);
            System.arraycopy(size, 0, length, 1, size.length);
        }
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /** The {@code firebase} claim of a phone sign-in token. */
    public static Map<String, Object> phoneProviderClaim(String phone) {
        Map<String, Object> identities = new LinkedHashMap<>();
        identities.put("phone", java.util.List.of(phone));
        Map<String, Object> firebase = new LinkedHashMap<>();
        firebase.put("identities", identities);
        firebase.put("sign_in_provider", "phone");
        return firebase;
    }
}
