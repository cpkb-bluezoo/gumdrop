/*
 * ArcRfc8617Test
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ARC (RFC 8617) interoperability. The headers here are produced by an
 * independent signer inside this test, written straight from the RFC: the
 * ARC-Message-Signature has the DKIM tag set without v=, the ARC-Seal has
 * i=, a=, cv=, d=, s=, b= and neither h= nor bh=, and the seal signs every
 * ARC set up to and including its own instance in relaxed canonicalization
 * with its own b= empty. {@link ArcValidator} must accept them, enforce the
 * cv= and instance rules, and {@link ArcSealer} must emit the same shapes.
 * The RFC's Appendix B signatures cannot be reproduced offline, so these are
 * RFC-shaped (including Gmail-style folded lines) and signed with a fresh key.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ArcRfc8617Test {

    private static KeyPair keys;
    private static String keyRecord;

    private static final String BODY = "Hello ARC\r\nsecond line\r\n";
    private static final String[] BASE_HEADERS = {
        "From: Sender <sender@example.com>\r\n",
        "To: recipient@example.net\r\n",
        "Subject: ARC test\r\n"
    };

    @BeforeClass
    public static void generateKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        keyRecord = "v=DKIM1; k=rsa; p="
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    // ---- independent RFC-style signer ----

    private static String relaxed(String line) {
        int colon = line.indexOf(':');
        String name = line.substring(0, colon).trim().toLowerCase();
        String value = line.substring(colon + 1);
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        boolean started = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (started) {
                    space = true;
                }
            } else {
                if (space) {
                    sb.append(' ');
                    space = false;
                }
                sb.append(c);
                started = true;
            }
        }
        return name + ":" + sb.toString() + "\r\n";
    }

    private static String sign(String data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keys.getPrivate());
        sig.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static String bodyHash(String body) throws Exception {
        // relaxed body: this test body has no trailing whitespace
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String aar(int i, String results) {
        return "ARC-Authentication-Results: i=" + i + "; mx" + i + ".example.org;\r\n"
                + "\t" + results + "\r\n";
    }

    /** AMS over from/to/subject plus itself with b= empty, relaxed. */
    private static String ams(int i, String[] headers, String bh) throws Exception {
        String tags = "i=" + i + "; a=rsa-sha256; c=relaxed/relaxed; d=example.org; s=arc;"
                + " t=1700000000;\r\n\th=from:to:subject; bh=" + bh + ";\r\n\tb=";
        StringBuilder data = new StringBuilder();
        data.append(relaxed(headers[0]));
        data.append(relaxed(headers[1]));
        data.append(relaxed(headers[2]));
        String self = relaxed("ARC-Message-Signature: " + tags);
        data.append(self.substring(0, self.length() - 2));
        return "ARC-Message-Signature: " + tags + sign(data.toString()) + "\r\n";
    }

    private static String sig(String data) throws Exception {
        return sign(data);
    }

    private static String stripWhitespace(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** ARC-Seal: signs sets 1..i (AAR, AMS, AS) with own b= empty, relaxed. */
    private static String seal(int i, String cv, List<String> priorSetLines,
                               String aarLine, String amsLine) throws Exception {
        String tags = "i=" + i + "; a=rsa-sha256; t=1700000000; cv=" + cv
                + "; d=example.org; s=arc;\r\n\tb=";
        StringBuilder data = new StringBuilder();
        for (int k = 0; k < priorSetLines.size(); k++) {
            data.append(relaxed(priorSetLines.get(k)));
        }
        data.append(relaxed(aarLine));
        data.append(relaxed(amsLine));
        String self = relaxed("ARC-Seal: " + tags);
        data.append(self.substring(0, self.length() - 2));
        return "ARC-Seal: " + tags + sig(data.toString()) + "\r\n";
    }

    /** One hop's three lines in AAR, AMS, AS order. */
    private static List<String> hop(int i, String cv, List<String> prior,
                                    String[] headers, String body,
                                    String results) throws Exception {
        String aarLine = aar(i, results);
        String amsLine = ams(i, headers, bodyHash(body));
        String asLine = seal(i, cv, prior, aarLine, amsLine);
        List<String> out = new ArrayList<String>();
        out.add(aarLine);
        out.add(amsLine);
        out.add(asLine);
        return out;
    }

    // ---- harness ----

    private static MessageHandler noop() {
        return (MessageHandler) Proxy.newProxyInstance(
                MessageHandler.class.getClassLoader(),
                new Class<?>[] {MessageHandler.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
    }

    private StubDnsTable dns() {
        StubDnsTable dns = new StubDnsTable();
        dns.txt("arc._domainkey.example.org", keyRecord);
        return dns;
    }

    /** Message with the newest ARC set on top, as relays prepend them. */
    private static String message(List<List<String>> hopsOldestFirst,
                                  String[] headers, String body) {
        StringBuilder sb = new StringBuilder();
        for (int i = hopsOldestFirst.size() - 1; i >= 0; i--) {
            List<String> h = hopsOldestFirst.get(i);
            for (int k = 0; k < h.size(); k++) {
                sb.append(h.get(k));
            }
        }
        for (int i = 0; i < headers.length; i++) {
            sb.append(headers[i]);
        }
        sb.append("\r\n").append(body);
        return sb.toString();
    }

    private ArcValidationResult validate(String raw) throws Exception {
        ArcValidator validator = new ArcValidator(dns());
        DkimMessageParser parser = new DkimMessageParser();
        parser.setMessageHandler(noop());
        parser.setArcHeaderParser(validator.getArcHeaderParser());
        parser.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        parser.close();
        validator.setMessageParser(parser);
        validator.setBodyHash(parser.getBodyHash());
        final ArcValidationResult[] out = new ArcValidationResult[1];
        validator.verify(new ArcCallback() {
            @Override
            public void arcResult(ArcValidationResult result) {
                out[0] = result;
            }
        });
        assertNotNull("validation must complete synchronously with a table resolver", out[0]);
        return out[0];
    }

    private List<List<String>> chain(String... cvs) throws Exception {
        List<List<String>> hops = new ArrayList<List<String>>();
        List<String> prior = new ArrayList<String>();
        for (int i = 1; i <= cvs.length; i++) {
            List<String> h = hop(i, cvs[i - 1], prior, BASE_HEADERS, BODY,
                    "spf=pass smtp.mailfrom=sender@example.com");
            hops.add(h);
            prior.addAll(h);
        }
        return hops;
    }

    // ---- parsing of the real header forms ----

    @Test
    public void realFormsParseWithoutVHOrBh() throws Exception {
        List<String> h = hop(1, "none", new ArrayList<String>(), BASE_HEADERS, BODY, "spf=pass");
        String amsValue = ArcHeaderParser.headerValueAfterColon(h.get(1));
        String asValue = ArcHeaderParser.headerValueAfterColon(h.get(2));
        DkimSignature ams = DkimSignature.parseArc(amsValue, false);
        DkimSignature as = DkimSignature.parseArc(asValue, true);
        assertNotNull(ams);
        assertNotNull(as);
        assertEquals("example.org", ams.getDomain());
        assertEquals(3, ams.getSignedHeaders().size());
        assertEquals("rsa-sha256", as.getAlgorithm());
        assertTrue(as.getSignedHeaders().isEmpty());
        assertNull(as.getBodyHash());
    }

    @Test
    public void arcParsingStillRequiresTheTagsTheRfcMandates() {
        assertNull(DkimSignature.parseArc("i=1; a=rsa-sha256; d=e.org; s=k; b=AA", false));
        assertNull(DkimSignature.parseArc("i=1; a=rsa-sha256; d=e.org; s=k; h=from; bh=AA", false));
        assertNull(DkimSignature.parseArc("i=1; a=rsa-sha256; d=e.org; s=k; cv=none", true));
        assertNull(DkimSignature.parseArc("i=1; d=e.org; s=k; b=AA; cv=none", true));
        assertNull(DkimSignature.parse("i=1; a=rsa-sha256; d=e.org; s=k; h=from; bh=AA; b=AA"));
    }

    @Test
    public void messageParserFindsAmsWithoutV() throws Exception {
        List<List<String>> hops = chain("none");
        DkimMessageParser parser = new DkimMessageParser();
        parser.setMessageHandler(noop());
        String raw = message(hops, BASE_HEADERS, BODY);
        parser.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        parser.close();
        assertNotNull(parser.getArcMessageSignature());
        assertEquals(bodyHash(BODY), Base64.getEncoder().encodeToString(parser.getBodyHash()));
    }

    // ---- validation of independently built chains ----

    @Test
    public void singleHopChainPasses() throws Exception {
        ArcValidationResult r = validate(message(chain("none"), BASE_HEADERS, BODY));
        assertFalse(r.isMalformed());
        assertEquals(ArcCvResult.PASS, r.getChainCv());
    }

    @Test
    public void threeHopChainPasses() throws Exception {
        ArcValidationResult r = validate(message(chain("none", "pass", "pass"), BASE_HEADERS, BODY));
        assertEquals(ArcCvResult.PASS, r.getChainCv());
        assertEquals(3, r.getSets().size());
    }

    @Test
    public void onlyTheNewestMessageSignatureMustStillVerify() throws Exception {
        // hop 1 signed the original body; hop 2 changed the subject and body
        // and re-signed, so hop 1's AMS no longer verifies - legal per RFC 8617
        List<String> prior = new ArrayList<String>();
        List<String> h1 = hop(1, "none", prior, BASE_HEADERS, BODY, "spf=pass");
        prior.addAll(h1);
        String[] changed = {BASE_HEADERS[0], BASE_HEADERS[1], "Subject: [list] ARC test\r\n"};
        String newBody = "Hello ARC\r\nsecond line\r\n-- footer\r\n";
        List<String> h2 = hop(2, "pass", prior, changed, newBody, "arc=pass");
        List<List<String>> hops = new ArrayList<List<String>>();
        hops.add(h1);
        hops.add(h2);
        ArcValidationResult r = validate(message(hops, changed, newBody));
        assertEquals(ArcCvResult.PASS, r.getChainCv());
    }

    @Test
    public void tamperedEarlierAuthenticationResultsBreakTheSeal() throws Exception {
        List<List<String>> hops = chain("none", "pass");
        String original = hops.get(0).get(0);
        hops.get(0).set(0, original.replace("spf=pass", "spf=fail"));
        assertEquals(ArcCvResult.FAIL, validate(message(hops, BASE_HEADERS, BODY)).getChainCv());
    }

    @Test
    public void tamperedNewestSealSignatureFails() throws Exception {
        List<List<String>> hops = chain("none");
        String as = hops.get(0).get(2);
        int b = as.lastIndexOf("b=") + 2;
        char c = as.charAt(b) == 'A' ? 'B' : 'A';
        hops.get(0).set(2, as.substring(0, b) + c + as.substring(b + 1));
        assertEquals(ArcCvResult.FAIL, validate(message(hops, BASE_HEADERS, BODY)).getChainCv());
    }

    @Test
    public void modifiedBodyBreaksTheNewestMessageSignature() throws Exception {
        List<List<String>> hops = chain("none");
        ArcValidationResult r = validate(message(hops, BASE_HEADERS, BODY + "extra\r\n"));
        assertEquals(ArcCvResult.FAIL, r.getChainCv());
    }

    @Test
    public void modifiedSignedHeaderBreaksTheNewestMessageSignature() throws Exception {
        List<List<String>> hops = chain("none");
        String[] altered = {BASE_HEADERS[0], BASE_HEADERS[1], "Subject: different\r\n"};
        assertEquals(ArcCvResult.FAIL, validate(message(hops, altered, BODY)).getChainCv());
    }

    @Test
    public void firstSealMustCarryCvNone() throws Exception {
        assertEquals(ArcCvResult.FAIL,
                validate(message(chain("pass"), BASE_HEADERS, BODY)).getChainCv());
    }

    @Test
    public void laterSealsMustCarryCvPass() throws Exception {
        assertEquals(ArcCvResult.FAIL,
                validate(message(chain("none", "none"), BASE_HEADERS, BODY)).getChainCv());
    }

    @Test
    public void newestSealWithCvFailFailsTheChainEvenWithValidSignatures() throws Exception {
        assertEquals(ArcCvResult.FAIL,
                validate(message(chain("none", "fail"), BASE_HEADERS, BODY)).getChainCv());
    }

    @Test
    public void missingInstanceIsMalformed() throws Exception {
        List<List<String>> hops = chain("none", "pass", "pass");
        hops.remove(1);
        ArcValidationResult r = validate(message(hops, BASE_HEADERS, BODY));
        assertTrue(r.isMalformed());
        assertEquals(ArcCvResult.FAIL, r.getChainCv());
    }

    @Test
    public void duplicateInstanceIsMalformed() throws Exception {
        List<List<String>> hops = chain("none");
        hops.add(hops.get(0));
        ArcValidationResult r = validate(message(hops, BASE_HEADERS, BODY));
        assertTrue(r.isMalformed());
        assertEquals(ArcCvResult.FAIL, r.getChainCv());
    }

    @Test
    public void messageWithoutArcHeadersHasNoChain() throws Exception {
        assertEquals(ArcCvResult.NONE,
                validate(message(new ArrayList<List<String>>(), BASE_HEADERS, BODY)).getChainCv());
    }

    // ---- sealer output ----

    private List<String> sealFirstHop(ArcSealer sealer) throws Exception {
        feedBody(sealer);
        ArcAuthenticationResults results = new ArcAuthenticationResults();
        results.setSpf(SpfResult.PASS, "example.com");
        List<String> headers = new ArrayList<String>();
        for (int i = 0; i < BASE_HEADERS.length; i++) {
            headers.add(BASE_HEADERS[i]);
        }
        return sealer.seal(Collections.<ArcSet>emptyList(), headers, ArcCvResult.NONE, results);
    }

    /** Feeds the body to a sealer one line at a time, as the pipeline does. */
    private static void feedBody(ArcSealer sealer) {
        int start = 0;
        while (start < BODY.length()) {
            int end = BODY.indexOf('\n', start) + 1;
            byte[] line = BODY.substring(start, end).getBytes(StandardCharsets.US_ASCII);
            sealer.bodyLine(line, 0, line.length);
            start = end;
        }
        sealer.endBody();
    }

    private ArcSealer newSealer() {
        ArcSealer sealer = new ArcSealer(keys.getPrivate(), "example.org", "arc");
        sealer.setAuthservId("mx1.example.org");
        return sealer;
    }

    @Test
    public void sealerEmitsRfcShapedTags() throws Exception {
        List<String> lines = sealFirstHop(newSealer());
        String aarLine = lines.get(0);
        String amsLine = lines.get(1);
        String asLine = lines.get(2);
        assertTrue(aarLine, aarLine.startsWith("ARC-Authentication-Results: i=1; mx1.example.org"));
        assertTrue(amsLine, amsLine.startsWith("ARC-Message-Signature: i=1;"));
        assertFalse(amsLine, amsLine.contains("v=1"));
        assertTrue(amsLine, amsLine.contains("h=") && amsLine.contains("bh="));
        assertTrue(asLine, asLine.startsWith("ARC-Seal: i=1;"));
        assertTrue(asLine, asLine.contains("cv=none"));
        assertFalse(asLine, asLine.contains("v=1"));
        assertFalse(asLine, asLine.contains(" h="));
        assertFalse(asLine, asLine.contains("bh="));
    }

    @Test
    public void sealerOutputIsAcceptedByTheValidator() throws Exception {
        List<String> lines = sealFirstHop(newSealer());
        List<List<String>> hops = new ArrayList<List<String>>();
        hops.add(lines);
        ArcValidationResult r = validate(message(hops, BASE_HEADERS, BODY));
        assertEquals(ArcCvResult.PASS, r.getChainCv());
    }

    @Test
    public void sealerSealMatchesTheIndependentSealSignature() throws Exception {
        List<String> lines = sealFirstHop(newSealer());
        String asLine = lines.get(2);
        StringBuilder data = new StringBuilder();
        data.append(relaxed(lines.get(0)));
        data.append(relaxed(lines.get(1)));
        int b = asLine.lastIndexOf("b=") + 2;
        String withoutSig = asLine.substring(0, b);
        String self = relaxed(withoutSig);
        data.append(self.substring(0, self.length() - 2));
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keys.getPublic());
        verify.update(data.toString().getBytes(StandardCharsets.UTF_8));
        String sigB64 = stripWhitespace(asLine.substring(b));
        assertTrue(verify.verify(Base64.getDecoder().decode(sigB64)));
    }

    @Test
    public void sealerSecondHopExtendsAValidChain() throws Exception {
        List<String> first = sealFirstHop(newSealer());
        List<List<String>> hops = new ArrayList<List<String>>();
        hops.add(first);
        // validate hop 1 to obtain the parsed sets the second sealer extends
        ArcValidator v1 = new ArcValidator(dns());
        DkimMessageParser p1 = new DkimMessageParser();
        p1.setMessageHandler(noop());
        p1.setArcHeaderParser(v1.getArcHeaderParser());
        String raw1 = message(hops, BASE_HEADERS, BODY);
        p1.receive(ByteBuffer.wrap(raw1.getBytes(StandardCharsets.US_ASCII)));
        p1.close();
        v1.setMessageParser(p1);
        v1.setBodyHash(p1.getBodyHash());
        final ArcValidationResult[] got = new ArcValidationResult[1];
        v1.verify(new ArcCallback() {
            @Override
            public void arcResult(ArcValidationResult result) {
                got[0] = result;
            }
        });
        assertEquals(ArcCvResult.PASS, got[0].getChainCv());

        ArcSealer second = newSealer();
        feedBody(second);
        ArcAuthenticationResults results = new ArcAuthenticationResults();
        results.setSpf(SpfResult.PASS, "example.com");
        List<String> headers = new ArrayList<String>();
        for (int i = 0; i < BASE_HEADERS.length; i++) {
            headers.add(BASE_HEADERS[i]);
        }
        List<String> second2 = second.seal(got[0].getSets(), headers, ArcCvResult.PASS, results);
        assertTrue(second2.get(2), second2.get(2).contains("cv=pass"));
        hops.add(second2);
        ArcValidationResult r = validate(message(hops, BASE_HEADERS, BODY));
        assertEquals(ArcCvResult.PASS, r.getChainCv());
        assertEquals(2, r.getSets().size());
    }

    @Test
    public void sealingAFailedChainSignsOnlyTheNewSet() throws Exception {
        // existing chain is garbage; incoming validation said fail
        List<List<String>> bad = chain("none");
        ArcSet broken = new ArcSet(1, bad.get(0).get(0), bad.get(0).get(1),
                bad.get(0).get(2), ArcCvResult.NONE, null, null);
        ArcSealer sealer = newSealer();
        feedBody(sealer);
        ArcAuthenticationResults results = new ArcAuthenticationResults();
        List<String> headers = new ArrayList<String>();
        for (int i = 0; i < BASE_HEADERS.length; i++) {
            headers.add(BASE_HEADERS[i]);
        }
        List<String> lines = sealer.seal(Collections.singletonList(broken), headers,
                ArcCvResult.FAIL, results);
        String asLine = lines.get(2);
        assertTrue(asLine, asLine.startsWith("ARC-Seal: i=2;"));
        assertTrue(asLine, asLine.contains("cv=fail"));
        StringBuilder data = new StringBuilder();
        data.append(relaxed(lines.get(0)));
        data.append(relaxed(lines.get(1)));
        int b = asLine.lastIndexOf("b=") + 2;
        String self = relaxed(asLine.substring(0, b));
        data.append(self.substring(0, self.length() - 2));
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keys.getPublic());
        verify.update(data.toString().getBytes(StandardCharsets.UTF_8));
        String sigB64 = stripWhitespace(asLine.substring(b));
        assertTrue(verify.verify(Base64.getDecoder().decode(sigB64)));
    }
}
