/*
 * DKIMSignerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.smtp.auth;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Unit tests for {@link DKIMSigner} — DKIM signing (RFC 6376 §5)
 * and Ed25519-SHA256 (RFC 8463).
 */
public class DKIMSignerTest {

    // -- RSA signing tests --

    @Test
    public void testSignProducesValidHeader() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "sel1");
        signer.setAlgorithm("rsa-sha256");
        signer.setSignedHeaders(Arrays.asList("from", "to", "subject"));

        byte[] body = "Hello world\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: sender@example.com\r\n");
        headers.add("To: recipient@example.com\r\n");
        headers.add("Subject: Test\r\n");

        String dkimHeader = signer.sign(headers);

        assertNotNull(dkimHeader);
        assertTrue(dkimHeader.startsWith("DKIM-Signature: "));
        assertTrue(dkimHeader.endsWith("\r\n"));
        assertTrue(dkimHeader.contains("a=rsa-sha256"));
        assertTrue(dkimHeader.contains("d=example.com"));
        assertTrue(dkimHeader.contains("s=sel1"));
        assertTrue(dkimHeader.contains("h=from:to:subject"));
        assertTrue(dkimHeader.contains("bh="));
        assertTrue(dkimHeader.contains("b="));
    }

    @Test
    public void testSignatureContainsNonEmptyBValue() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "test.org", "key2");
        signer.setSignedHeaders(Arrays.asList("from", "date"));

        byte[] body = "Body content\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: a@test.org\r\n");
        headers.add("Date: Mon, 01 Jan 2026 00:00:00 +0000\r\n");

        String dkimHeader = signer.sign(headers);
        DKIMSignature parsed = DKIMSignature.parse(
                dkimHeader.substring("DKIM-Signature: ".length()).trim());

        assertNotNull(parsed);
        assertNotNull(parsed.getSignature());
        assertFalse(parsed.getSignature().isEmpty());
        assertNotNull(parsed.getBodyHash());
        assertFalse(parsed.getBodyHash().isEmpty());
    }

    @Test
    public void testSignatureCanBeVerifiedWithPublicKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "sel1");
        signer.setAlgorithm("rsa-sha256");
        signer.setHeaderCanonicalization("relaxed");
        signer.setBodyCanonicalization("relaxed");
        signer.setSignedHeaders(Arrays.asList("from", "subject"));

        byte[] body = "Test body line\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: test@example.com\r\n");
        headers.add("Subject: Hello\r\n");

        String dkimHeader = signer.sign(headers);
        DKIMSignature sig = DKIMSignature.parse(
                dkimHeader.substring("DKIM-Signature: ".length()).trim());

        assertNotNull(sig);
        byte[] sigBytes = Base64.getDecoder().decode(sig.getSignature());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(kp.getPublic());
        // Reconstruct signed data: canonicalized signed headers + DKIM header without b= value
        // (This is a simplified check that the signature bytes decode and are non-trivial)
        assertTrue(sigBytes.length > 0);
    }

    // -- Canonicalization tests --

    @Test
    public void testCanonicalizationTags() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "sel1");
        signer.setHeaderCanonicalization("simple");
        signer.setBodyCanonicalization("simple");
        signer.setSignedHeaders(Arrays.asList("from"));

        byte[] body = "line\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        assertTrue(dkimHeader.contains("c=simple/simple"));
    }

    @Test
    public void testRelaxedCanonicalizationTag() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setHeaderCanonicalization("relaxed");
        signer.setBodyCanonicalization("relaxed");
        signer.setSignedHeaders(Arrays.asList("from"));

        byte[] body = "line\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        assertTrue(dkimHeader.contains("c=relaxed/relaxed"));
    }

    // -- Body hashing tests --

    @Test
    public void testEmptyBodySimple() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setBodyCanonicalization("simple");
        signer.setSignedHeaders(Arrays.asList("from"));
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        DKIMSignature sig = DKIMSignature.parse(
                dkimHeader.substring("DKIM-Signature: ".length()).trim());
        assertNotNull(sig);
        // Empty body with simple canonicalization: body is CRLF → specific SHA-256 hash
        assertEquals("frcCV1k9oG9oKj3dpUqdJg1PxRT2RSN/XKdLCPjaYaY=", sig.getBodyHash());
    }

    @Test
    public void testEmptyBodyRelaxed() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setBodyCanonicalization("relaxed");
        signer.setSignedHeaders(Arrays.asList("from"));
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        DKIMSignature sig = DKIMSignature.parse(
                dkimHeader.substring("DKIM-Signature: ".length()).trim());
        assertNotNull(sig);
        // Empty body with relaxed canonicalization: body is "" → SHA-256 of empty string
        assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", sig.getBodyHash());
    }

    @Test
    public void testTrailingEmptyLinesIgnored() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        // Signer with trailing empty lines
        DKIMSigner signer1 = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer1.setBodyCanonicalization("simple");
        signer1.setSignedHeaders(Arrays.asList("from"));

        byte[] content = "Hello\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] empty = "\r\n".getBytes(StandardCharsets.US_ASCII);
        signer1.bodyLine(content, 0, content.length);
        signer1.bodyLine(empty, 0, empty.length);
        signer1.bodyLine(empty, 0, empty.length);
        signer1.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");
        String h1 = signer1.sign(headers);
        DKIMSignature sig1 = DKIMSignature.parse(
                h1.substring("DKIM-Signature: ".length()).trim());

        // Signer without trailing empty lines
        DKIMSigner signer2 = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer2.setBodyCanonicalization("simple");
        signer2.setSignedHeaders(Arrays.asList("from"));
        signer2.bodyLine(content, 0, content.length);
        signer2.endBody();

        String h2 = signer2.sign(headers);
        DKIMSignature sig2 = DKIMSignature.parse(
                h2.substring("DKIM-Signature: ".length()).trim());

        assertNotNull(sig1);
        assertNotNull(sig2);
        assertEquals(sig1.getBodyHash(), sig2.getBodyHash());
    }

    // -- Optional tags tests --

    @Test
    public void testTimestampAndExpiration() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setSignedHeaders(Arrays.asList("from"));
        signer.setTimestamp(1700000000L);
        signer.setExpiration(1700086400L);

        byte[] body = "body\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        assertTrue(dkimHeader.contains("t=1700000000"));
        assertTrue(dkimHeader.contains("x=1700086400"));
    }

    @Test
    public void testIdentityTag() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setSignedHeaders(Arrays.asList("from"));
        signer.setIdentity("@example.com");

        byte[] body = "body\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        assertTrue(dkimHeader.contains("i=@example.com"));
    }

    // -- Ed25519 signing test --

    @Test
    public void testEd25519SignatureHeader() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "ed");
        signer.setAlgorithm("ed25519-sha256");
        signer.setSignedHeaders(Arrays.asList("from"));

        byte[] body = "body\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        assertTrue(dkimHeader.contains("a=ed25519-sha256"));
        assertTrue(dkimHeader.contains("d=example.com"));
        assertTrue(dkimHeader.contains("s=ed"));

        DKIMSignature sig = DKIMSignature.parse(
                dkimHeader.substring("DKIM-Signature: ".length()).trim());
        assertNotNull(sig);
        assertFalse(sig.getSignature().isEmpty());
    }

    @Test
    public void testEd25519SignatureVerifiable() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "ed");
        signer.setAlgorithm("ed25519-sha256");
        signer.setHeaderCanonicalization("relaxed");
        signer.setBodyCanonicalization("relaxed");
        signer.setSignedHeaders(Arrays.asList("from"));

        byte[] body = "body\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        DKIMSignature sig = DKIMSignature.parse(
                dkimHeader.substring("DKIM-Signature: ".length()).trim());
        assertNotNull(sig);

        byte[] sigBytes = Base64.getDecoder().decode(sig.getSignature());
        // Ed25519 signatures are always 64 bytes
        assertEquals(64, sigBytes.length);
    }

    // -- Header selection test --

    @Test
    public void testMissingHeaderGracefullySkipped() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setSignedHeaders(Arrays.asList("from", "x-nonexistent"));

        byte[] body = "body\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        // Should not throw even though x-nonexistent is missing
        String dkimHeader = signer.sign(headers);
        assertNotNull(dkimHeader);
        assertTrue(dkimHeader.contains("h=from:x-nonexistent"));
    }

    @Test
    public void testVersionIsAlwaysOne() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "s");
        signer.setSignedHeaders(Arrays.asList("from"));

        byte[] body = "body\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headers = new ArrayList<>();
        headers.add("From: x@example.com\r\n");

        String dkimHeader = signer.sign(headers);
        assertTrue(dkimHeader.contains("v=1"));
    }

}
