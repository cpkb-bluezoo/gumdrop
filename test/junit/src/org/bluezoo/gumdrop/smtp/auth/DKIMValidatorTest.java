/*
 * DKIMValidatorTest.java
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MIMELocator;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.client.DNSResolver;

/**
 * Unit tests for {@link DKIMValidator} — RFC 6376 §6 verifier actions.
 *
 * <p>Covers GHSA-w9c7-pj22-vfw7: a DKIM signature whose {@code h=} tag
 * does not cover the {@code From} header must not be reported as PASS,
 * since a PASS is used elsewhere (DMARCValidator) to authenticate the
 * message's From domain.
 */
public class DKIMValidatorTest {

    /** No-op message handler — the test only needs the raw-header capture DKIMMessageParser already does. */
    private static class NoopMessageHandler implements MessageHandler {
        @Override public void setLocator(MIMELocator locator) { }
        @Override public void startEntity(String boundary) throws MIMEParseException { }
        @Override public void contentType(ContentType ct) throws MIMEParseException { }
        @Override public void contentDisposition(ContentDisposition cd) throws MIMEParseException { }
        @Override public void contentTransferEncoding(String encoding) throws MIMEParseException { }
        @Override public void contentID(ContentID cid) throws MIMEParseException { }
        @Override public void contentDescription(String description) throws MIMEParseException { }
        @Override public void mimeVersion(MIMEVersion version) throws MIMEParseException { }
        @Override public void endHeaders() throws MIMEParseException { }
        @Override public void bodyContent(ByteBuffer data) throws MIMEParseException { }
        @Override public void unexpectedContent(ByteBuffer data) throws MIMEParseException { }
        @Override public void endEntity(String boundary) throws MIMEParseException { }
        @Override public void header(String name, String value) throws MIMEParseException { }
        @Override public void unexpectedHeader(String name, String value) throws MIMEParseException { }
        @Override public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException { }
        @Override public void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException { }
        @Override public void messageIDHeader(String name, List<ContentID> contentIDs) throws MIMEParseException { }
        @Override public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException { }
    }

    /** Fake resolver that answers any TXT query with a fixed public-key record, synchronously. */
    private static class FakeKeyResolver extends DNSResolver {
        private final String txtRecord;

        FakeKeyResolver(String txtRecord) {
            this.txtRecord = txtRecord;
        }

        @Override
        public void queryTXT(String name, DNSQueryCallback callback) {
            DNSResourceRecord rr = DNSResourceRecord.txt(name, 300, txtRecord);
            DNSMessage response = new DNSMessage(1, DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA,
                    Collections.emptyList(), Collections.singletonList(rr),
                    Collections.emptyList(), Collections.emptyList());
            callback.onResponse(response);
        }
    }

    /** Builds a raw RFC 5322 message (DKIM-Signature + given headers + body), signs it, and verifies it. */
    private DKIMResult verifySignedMessage(List<String> signedHeaderNames) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        DKIMSigner signer = new DKIMSigner(kp.getPrivate(), "example.com", "sel1");
        signer.setSignedHeaders(signedHeaderNames);

        byte[] body = "Hello world\r\n".getBytes(StandardCharsets.US_ASCII);
        signer.bodyLine(body, 0, body.length);
        signer.endBody();

        List<String> headerLines = new ArrayList<>();
        headerLines.add("From: sender@example.com\r\n");
        headerLines.add("To: recipient@example.com\r\n");
        headerLines.add("Subject: Test\r\n");

        String dkimHeader = signer.sign(headerLines);

        StringBuilder raw = new StringBuilder();
        raw.append(dkimHeader);
        for (String line : headerLines) {
            raw.append(line);
        }
        raw.append("\r\n");
        raw.append(new String(body, StandardCharsets.US_ASCII));

        DKIMMessageParser parser = new DKIMMessageParser();
        parser.setMessageHandler(new NoopMessageHandler());
        parser.receive(ByteBuffer.wrap(raw.toString().getBytes(StandardCharsets.US_ASCII)));
        parser.close();

        // Build the matching public-key DNS TXT record.
        String p = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        FakeKeyResolver resolver = new FakeKeyResolver("v=DKIM1; k=rsa; p=" + p);

        DKIMValidator validator = new DKIMValidator(resolver);
        validator.setMessageParser(parser);
        byte[] bodyHash = parser.getBodyHash();
        if (bodyHash != null) {
            validator.setBodyHash(bodyHash);
        }

        final DKIMResult[] result = new DKIMResult[1];
        validator.verify(new DKIMCallback() {
            @Override
            public void dkimResult(DKIMResult r, String signingDomain, String selector) {
                result[0] = r;
            }
        });

        assertNotNull("verify() must call back synchronously in this test setup", result[0]);
        return result[0];
    }

    @Test
    public void testVerifyFailsWhenFromNotInSignedHeaders() throws Exception {
        // A signature that never covers From is cryptographically valid but
        // must not be usable to authenticate the message's From address.
        DKIMResult result = verifySignedMessage(Arrays.asList("to", "subject"));
        assertNotEquals("a DKIM signature that does not sign From must not PASS",
                DKIMResult.PASS, result);
    }

    @Test
    public void testVerifyPassesWhenFromIsSigned() throws Exception {
        // Sanity/non-regression companion: a normal signature that does
        // cover From must still verify successfully.
        DKIMResult result = verifySignedMessage(Arrays.asList("from", "to", "subject"));
        assertEquals(DKIMResult.PASS, result);
    }
}
