/*
 * ArcSealerRoundTripTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MimeLocator;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.MimeVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

import java.time.OffsetDateTime;

/**
 * End-to-end unit test: {@link ArcSealer} produces a valid i=1 ARC set that
 * {@link ArcValidator} accepts (RFC 8617).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ArcSealerRoundTripTest {

    @Test
    public void testSignArcMinimalMessage() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        String p = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        FakeKeyResolver resolver = new FakeKeyResolver("v=DKIM1; k=rsa; p=" + p);

        List<String> messageHeaders = new ArrayList<String>();
        messageHeaders.add("From: sender@example.com\r\n");
        messageHeaders.add("To: recipient@example.com\r\n");
        messageHeaders.add("Subject: Test\r\n");

        byte[] body = "Body\r\n".getBytes(StandardCharsets.US_ASCII);
        DkimSigner signer = new DkimSigner(kp.getPrivate(), "example.com", "sel");
        signer.bodyLine(body, 0, body.length);
        signer.endBody();
        String bh = signer.computeBodyHashBase64();
        List<String> h = Arrays.asList("from", "to", "subject");
        String ams = signer.signArc(messageHeaders, "ARC-Message-Signature", 1,
                h, bh, null);

        StringBuilder raw = new StringBuilder();
        raw.append(ams);
        for (int i = 0; i < messageHeaders.size(); i++) {
            raw.append(messageHeaders.get(i));
        }
        raw.append("\r\n");
        raw.append(new String(body, StandardCharsets.US_ASCII));

        DkimMessageParser parser = new DkimMessageParser();
        parser.setMessageHandler(new NoopHandler());
        parser.receive(ByteBuffer.wrap(raw.toString().getBytes(StandardCharsets.US_ASCII)));
        parser.close();

        DkimSignature sig = parser.getArcMessageSignature();
        assertNotNull(sig);
        DkimValidator validator = new DkimValidator(resolver);
        validator.setMessageParser(parser);
        validator.setBodyHash(parser.getBodyHash());
        final DkimResult[] out = new DkimResult[1];
        final CountDownLatch latch = new CountDownLatch(1);
        validator.verifyHeaderSignature(sig, ams, new DkimCallback() {
            @Override
            public void dkimResult(DkimResult result, String d, String s) {
                out[0] = result;
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(DkimResult.PASS, out[0]);
    }

    @Test
    public void testArcSealAfterAmsAndAar() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        String p = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        FakeKeyResolver resolver = new FakeKeyResolver("v=DKIM1; k=rsa; p=" + p);

        List<String> messageHeaders = new ArrayList<String>();
        messageHeaders.add("From: sender@example.com\r\n");
        messageHeaders.add("To: recipient@example.com\r\n");
        messageHeaders.add("Subject: ARC test\r\n");

        byte[] body = "Hello ARC\r\n".getBytes(StandardCharsets.US_ASCII);
        DkimSigner signer = new DkimSigner(kp.getPrivate(), "example.com", "arcsel");
        signer.bodyLine(body, 0, body.length);
        signer.endBody();
        String bh = signer.computeBodyHashBase64();

        ArcAuthenticationResults authResults = new ArcAuthenticationResults();
        authResults.setInstance(1);
        authResults.setAuthservId("mx.example.com");
        authResults.setSpf(SpfResult.PASS, "example.com");
        authResults.setDkim(DkimResult.PASS, "example.com");
        authResults.setDmarc(DmarcResult.PASS, DmarcPolicy.NONE);
        String aar = authResults.formatHeaderLine();

        List<String> forAms = new ArrayList<String>();
        forAms.add(aar);
        forAms.addAll(messageHeaders);
        List<String> amsH = Arrays.asList("from", "to", "subject", "date", "message-id");
        String ams = signer.signArc(forAms, "ARC-Message-Signature", 1, amsH, bh, null);

        List<String> forAs = new ArrayList<String>(forAms);
        forAs.add(ams);
        List<String> sealH = Arrays.asList("arc-seal", "arc-message-signature",
                "arc-authentication-results");
        String as = signer.signArc(forAs, "ARC-Seal", 1, sealH, bh, ArcCvResult.NONE);

        StringBuilder raw = new StringBuilder();
        raw.append(aar);
        raw.append(ams);
        raw.append(as);
        for (int i = 0; i < messageHeaders.size(); i++) {
            raw.append(messageHeaders.get(i));
        }
        raw.append("\r\n");
        raw.append(new String(body, StandardCharsets.US_ASCII));

        DkimMessageParser parser = new DkimMessageParser();
        parser.setMessageHandler(new NoopHandler());
        parser.receive(ByteBuffer.wrap(raw.toString().getBytes(StandardCharsets.US_ASCII)));
        parser.close();

        DkimMessageParser.RawHeader sealHeader =
                parser.getRawHeader("arc-seal");
        String sealValue = ArcHeaderParser.headerValueAfterColon(
                sealHeader.asString());
        DkimSignature asSig = DkimSignature.parse(sealValue);
        DkimValidator validator = new DkimValidator(resolver);
        validator.setMessageParser(parser);
        validator.setBodyHash(parser.getBodyHash());
        final DkimResult[] out = new DkimResult[1];
        final CountDownLatch latch = new CountDownLatch(1);
        validator.verifyHeaderSignature(asSig, as, new DkimCallback() {
            @Override
            public void dkimResult(DkimResult result, String d, String s) {
                out[0] = result;
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(DkimResult.PASS, out[0]);
    }

    @Test
    public void testSealAndValidateFirstHop() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        String p = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        FakeKeyResolver resolver = new FakeKeyResolver("v=DKIM1; k=rsa; p=" + p);

        List<String> messageHeaders = new ArrayList<>();
        messageHeaders.add("From: sender@example.com\r\n");
        messageHeaders.add("To: recipient@example.com\r\n");
        messageHeaders.add("Subject: ARC test\r\n");

        byte[] body = "Hello ARC\r\n".getBytes(StandardCharsets.US_ASCII);

        ArcSealer sealer = new ArcSealer(kp.getPrivate(), "example.com", "arcsel");
        sealer.setAuthservId("mx.example.com");
        sealer.bodyLine(body, 0, body.length);
        sealer.endBody();

        ArcAuthenticationResults authResults = new ArcAuthenticationResults();
        authResults.setInstance(1);
        authResults.setAuthservId("mx.example.com");
        authResults.setSpf(SpfResult.PASS, "example.com");
        authResults.setDkim(DkimResult.PASS, "example.com");
        authResults.setDmarc(DmarcResult.PASS, DmarcPolicy.NONE);

        List<String> arcHeaders = sealer.seal(
                Collections.<ArcSet>emptyList(),
                messageHeaders,
                ArcCvResult.NONE,
                authResults);

        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < arcHeaders.size(); i++) {
            raw.append(arcHeaders.get(i));
        }
        for (int i = 0; i < messageHeaders.size(); i++) {
            raw.append(messageHeaders.get(i));
        }
        raw.append("\r\n");
        raw.append(new String(body, StandardCharsets.US_ASCII));

        ArcValidator validator = new ArcValidator(resolver);
        DkimMessageParser parser = new DkimMessageParser();
        parser.setMessageHandler(new NoopHandler());
        parser.setArcHeaderParser(validator.getArcHeaderParser());
        parser.receive(ByteBuffer.wrap(raw.toString().getBytes(StandardCharsets.US_ASCII)));
        parser.close();

        DkimSignature parsedAms = parser.getArcMessageSignature();
        assertNotNull(parsedAms);
        byte[] parserBodyHash = parser.getBodyHash();
        assertNotNull(parserBodyHash);
        String parserBhB64 = java.util.Base64.getEncoder().encodeToString(parserBodyHash);
        assertEquals("AMS bh= must match parser body hash", parsedAms.getBodyHash(),
                parserBhB64);

        final ArcValidationResult[] captured = new ArcValidationResult[1];
        final CountDownLatch done = new CountDownLatch(1);

        validator.setMessageParser(parser);
        validator.setBodyHash(parserBodyHash);
        validator.verify(new ArcCallback() {
            @Override
            public void arcResult(ArcValidationResult result) {
                captured[0] = result;
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(captured[0]);
        assertFalse(captured[0].isMalformed());
        assertEquals("chain cv", ArcCvResult.PASS, captured[0].getChainCv());
    }

    private static class FakeKeyResolver extends DnsResolver {
        private final String txtRecord;

        FakeKeyResolver(String txtRecord) {
            this.txtRecord = txtRecord;
        }

        @Override
        public void queryTXT(String name, DnsQueryCallback callback) {
            DnsResourceRecord rr = DnsResourceRecord.txt(name, 300, txtRecord);
            DnsMessage response = new DnsMessage(1,
                    DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA,
                    Collections.emptyList(), Collections.singletonList(rr),
                    Collections.emptyList(), Collections.emptyList());
            callback.onResponse(response);
        }
    }

    private static class NoopHandler implements MessageHandler {
        @Override public void setLocator(MimeLocator locator) { }
        @Override public void startEntity(String boundary) throws MimeParseException { }
        @Override public void contentType(ContentType ct) throws MimeParseException { }
        @Override public void contentDisposition(ContentDisposition cd) throws MimeParseException { }
        @Override public void contentTransferEncoding(String encoding) throws MimeParseException { }
        @Override public void contentID(ContentID cid) throws MimeParseException { }
        @Override public void contentDescription(String description) throws MimeParseException { }
        @Override public void mimeVersion(MimeVersion version) throws MimeParseException { }
        @Override public void endHeaders() throws MimeParseException { }
        @Override public void bodyContent(ByteBuffer data) throws MimeParseException { }
        @Override public void unexpectedContent(ByteBuffer data) throws MimeParseException { }
        @Override public void endEntity(String boundary) throws MimeParseException { }
        @Override public void header(String name, String value) throws MimeParseException { }
        @Override public void unexpectedHeader(String name, String value) throws MimeParseException { }
        @Override public void dateHeader(String name, OffsetDateTime date) throws MimeParseException { }
        @Override public void addressHeader(String name, List<EmailAddress> addresses) throws MimeParseException { }
        @Override public void messageIDHeader(String name, List<ContentID> contentIDs) throws MimeParseException { }
        @Override public void obsoleteStructure(ObsoleteStructureType type) throws MimeParseException { }
    }
}
