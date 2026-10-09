/*
 * SmtpHandlerEdgeCasesTest.java
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

package org.bluezoo.gumdrop.smtp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the less common branches of {@link SmtpProtocolHandler} with
 * hand-written mocks: encoding errors on the command channel, metrics
 * recording, session telemetry, EXTERNAL and OAUTHBEARER edge cases, XCLIENT
 * attribute handling, connection metadata and the rejected-connection state.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpHandlerEdgeCasesTest {

    private MetricsListener listener;
    private SmtpProtocolHandler handler;
    private TeleEndpoint endpoint;
    private CapturingConfig config;

    @Before
    public void setUp() {
        config = new CapturingConfig();
        listener = new MetricsListener();
        endpoint = new TeleEndpoint(config);
        handler = new SmtpProtocolHandler(listener, null);
    }

    private void connect() {
        handler.connected(endpoint);
        endpoint.sentData.clear();
    }

    private void raw(byte[] data) {
        handler.receive(ByteBuffer.wrap(data));
    }

    private void send(String command) {
        raw((command + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private String last() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    private void expect(String command, String code) {
        endpoint.sentData.clear();
        send(command);
        String response = last();
        assertTrue(command + " -> " + response, response.startsWith(code));
    }

    private void ehlo() {
        connect();
        expect("EHLO client.example.com", "250");
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean anyContains(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // -- command channel encoding and unknown verbs --

    @Test
    public void testInvalidEncodingInUnknownKeyword() {
        connect();
        endpoint.sentData.clear();
        raw(new byte[] {(byte) 0xC3, (byte) 0xA9, 'X', 'Y', 'Z', '\r', '\n'});
        String reply = last();
        assertTrue(reply, reply.startsWith("500"));
    }

    @Test
    public void testInvalidEncodingInTextRejected() {
        connect();
        endpoint.sentData.clear();
        raw(new byte[] {'N', 'O', 'O', 'P', ' ', (byte) 0xFF, (byte) 0xFE, '\r', '\n'});
        String reply = last();
        assertTrue(reply, reply.startsWith("500"));
        expect("NOOP", "250");
    }

    @Test
    public void testNonAsciiTextWithoutUtf8IsRejectedAsEncoding() {
        connect();
        endpoint.sentData.clear();
        raw("VRFY café\r\n".getBytes(StandardCharsets.UTF_8));
        String reply = last();
        assertTrue(reply, reply.startsWith("500"));
    }

    @Test
    public void testMailNonAsciiAddressWithoutSmtputf8Rejected() {
        ehlo();
        endpoint.sentData.clear();
        raw("MAIL FROM:<jürgen@example.com>\r\n".getBytes(StandardCharsets.UTF_8));
        String reply = last();
        assertTrue(reply, reply.startsWith("553"));
        expect("MAIL FROM:<a@example.com>", "250");
    }

    @Test
    public void testMailNonAsciiAddressWithSmtputf8Accepted() {
        ehlo();
        endpoint.sentData.clear();
        raw("MAIL FROM:<jürgen@example.com> SMTPUTF8\r\n".getBytes(StandardCharsets.UTF_8));
        String reply = last();
        assertTrue(reply, reply.startsWith("250"));
    }

    @Test
    public void testMailInvalidUtf8Rejected() {
        ehlo();
        endpoint.sentData.clear();
        raw(new byte[] {'M', 'A', 'I', 'L', ' ', 'F', 'R', 'O', 'M', ':', '<', (byte) 0xC3, '>', '\r', '\n'});
        String reply = last();
        assertTrue(reply, reply.startsWith("500"));
    }

    @Test
    public void testInvalidEncodingInsideAuthContinuation() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH LOGIN", "334");
        endpoint.sentData.clear();
        raw(new byte[] {(byte) 0xFF, (byte) 0xFE, '\r', '\n'});
        String reply = last();
        assertTrue(reply, reply.startsWith("500"));
    }

    @Test
    public void testUnknownFourByteVerbAndOddBytes() {
        connect();
        expect("FOOB", "500");
        expect("{AB~", "500");
        expect("XCLIENX", "500");
        expect("STARTTLX", "500");
    }

    @Test
    public void testRawBytesIsIgnored() {
        connect();
        endpoint.sentData.clear();
        handler.rawBytes(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertTrue(endpoint.sentData.isEmpty());
    }

    @Test
    public void testErrorBeforeConnectDoesNotFail() {
        handler.error(new IOException("boom"));
        assertTrue(endpoint.open);
    }

    @Test
    public void testErrorClosesEndpoint() {
        connect();
        handler.error(new IOException("boom"));
        assertFalse(endpoint.open);
    }

    // -- rejected connection state --

    @Test
    public void testRejectedConnectionAnswersEverythingWith554ExceptQuit() {
        connect();
        handler.rejectConnection("busy");
        endpoint.sentData.clear();
        send("NOOP");
        String reply = last();
        assertTrue(reply, reply.startsWith("554"));
        expect("QUIT", "221");
    }

    // -- STARTTLS --

    @Test
    public void testStarttlsFromInitialStateAndRepeatIsRefused() {
        TlsListener tls = new TlsListener();
        handler = new SmtpProtocolHandler(tls, null);
        connect();
        expect("STARTTLS", "220");
        expect("STARTTLS", "500");
        handler.securityEstablished(null);
        endpoint.sentData.clear();
        assertTrue(endpoint.sentData.isEmpty());
    }

    @Test
    public void testStarttlsOnSecureEndpointIsUnrecognised() {
        TlsListener tls = new TlsListener();
        handler = new SmtpProtocolHandler(tls, null);
        endpoint.secure = true;
        handler.connected(endpoint);
        handler.securityEstablished(null);
        endpoint.sentData.clear();
        expect("STARTTLS", "500");
    }

    @Test
    public void testStarttlsFailureReports454() {
        TlsListener tls = new TlsListener();
        handler = new SmtpProtocolHandler(tls, null);
        endpoint.failStartTls = true;
        connect();
        endpoint.sentData.clear();
        send("STARTTLS");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.toString(), anyContains(responses, "454"));
    }

    @Test
    public void testSecurityEstablishedMidSessionWithoutStarttlsIsQuiet() {
        ehlo();
        endpoint.sentData.clear();
        handler.securityEstablished(null);
        assertTrue(endpoint.sentData.isEmpty());
    }

    @Test
    public void testStarttlsAdvertisedAndHelpMentionsIt() {
        TlsListener tls = new TlsListener();
        handler = new SmtpProtocolHandler(tls, null);
        connect();
        send("EHLO c.example.com");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.toString(), anyContains(responses, "STARTTLS"));
        endpoint.sentData.clear();
        send("HELP");
        responses = endpoint.getResponses();
        assertTrue(responses.toString(), anyContains(responses, "  STARTTLS"));
        assertTrue(tls.startTlsChecks > 0);
    }

    // -- EHLO advertisement --

    @Test
    public void testEhloOmitsLimitsWhenUnbounded() {
        listener.setMaxRecipients(0);
        listener.setMaxTransactionsPerSession(0);
        connect();
        send("EHLO c.example.com");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.toString(), anyContains(responses, "LIMITS"));
        assertFalse(responses.toString(), anyContains(responses, "RCPTMAX"));
        assertFalse(responses.toString(), anyContains(responses, "MAILMAX"));
    }

    @Test
    public void testEhloAuthLineHidesTlsOnlyMechanismsBeforeTls() {
        TlsListener tls = new TlsListener();
        tls.setRealm(new EdgeRealm());
        handler = new SmtpProtocolHandler(tls, null);
        connect();
        send("EHLO c.example.com");
        List<String> responses = endpoint.getResponses();
        String authLine = null;
        for (int i = 0; i < responses.size(); i++) {
            String line = responses.get(i);
            if (line.contains("AUTH ")) {
                authLine = line;
            }
        }
        assertNotNull(responses.toString(), authLine);
        assertFalse(authLine, authLine.contains("EXTERNAL"));
        assertFalse(authLine, authLine.contains("PLAIN"));
        assertTrue(authLine, authLine.contains("SCRAM-SHA-256"));
    }

    @Test
    public void testEhloAuthLineOffersExternalOverTls() {
        EdgeRealm realm = new EdgeRealm();
        listener.setRealm(realm);
        endpoint.secure = true;
        handler.connected(endpoint);
        endpoint.sentData.clear();
        send("EHLO c.example.com");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.toString(), anyContains(responses, "EXTERNAL"));
        assertTrue(responses.toString(), anyContains(responses, "REQUIRETLS"));
    }

    // -- AUTH argument handling --

    @Test
    public void testAuthRequiresEncryptionWhenNoStarttls() {
        listener.setRealm(new EdgeRealm());
        ehlo();
        expect("AUTH PLAIN", "538");
    }

    @Test
    public void testAuthEmptyArgumentIsSyntaxError() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH ", "501");
    }

    @Test
    public void testAuthTabSeparatedInitialResponse() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN\t" + b64("\0u\0p"), "235");
    }

    @Test
    public void testEqualsInitialResponseRequestsContinuation() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN =", "334");
        expect("*", "501");
        expect("AUTH LOGIN =", "334");
        expect("*", "501");
        expect("AUTH SCRAM-SHA-256 =", "334");
        expect("*", "501");
        expect("AUTH OAUTHBEARER =", "334");
        expect("*", "501");
    }

    @Test
    public void testLoginEmptyContinuationFields() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH LOGIN", "334");
        expect("", "535");
        expect("AUTH LOGIN", "334");
        expect(b64("u"), "334");
        expect("", "535");
    }

    @Test
    public void testLoginSuccessNotifiesMetrics() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH LOGIN", "334");
        expect(b64("u"), "334");
        expect(b64("p"), "235");
        assertEquals("LOGIN", listener.metrics.successes.get(0));
        expect("AUTH LOGIN", "503");
    }

    @Test
    public void testPlainFailureNotifiesMetrics() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN " + b64("\0u\0wrong"), "535");
        assertEquals(1, listener.metrics.failures.size());
    }

    @Test
    public void testCramMd5UnknownUserFails() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH CRAM-MD5", "334");
        expect(b64("nobody 0123456789abcdef0123456789abcdef"), "535");
        assertEquals("CRAM-MD5", listener.metrics.failures.get(0));
    }

    @Test
    public void testDigestMd5ResponseWithoutRealmParameterFails() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH DIGEST-MD5", "334");
        expect(b64("username=\"u\",nonce=\"abc\",nc=00000001,cnonce=\"x\",qop=auth,"
                + "digest-uri=\"smtp/localhost\",response=00"), "535");
    }

    // -- OAUTHBEARER --

    @Test
    public void testOAuthUserMismatchIsRejected() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        String payload = "n,a=other,\u0001auth=Bearer good\u0001\u0001";
        expect("AUTH OAUTHBEARER " + b64(payload), "535");
    }

    @Test
    public void testOAuthExpiredTokenIssuesErrorChallenge() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        String payload = "n,,\u0001auth=Bearer old\u0001\u0001";
        expect("AUTH OAUTHBEARER " + b64(payload), "334");
        expect(b64("\u0001"), "535");
    }

    @Test
    public void testOAuthMatchingUserAccepted() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        String payload = "n,a=u,\u0001auth=Bearer good\u0001\u0001";
        expect("AUTH OAUTHBEARER " + b64(payload), "235");
    }

    // -- EXTERNAL --

    @Test
    public void testExternalWithCertificateSucceeds() throws Exception {
        EdgeRealm realm = new EdgeRealm();
        listener.setRealm(realm);
        endpoint.secure = true;
        endpoint.securityInfo = new CertInfo(TestCertificates.ec256().getCertificate());
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH EXTERNAL", "235");
        assertEquals("certuser", realm.lastCertUser);
    }

    @Test
    public void testExternalWithAuthzidAndInvalidBase64() throws Exception {
        EdgeRealm realm = new EdgeRealm();
        listener.setRealm(realm);
        endpoint.secure = true;
        endpoint.securityInfo = new CertInfo(TestCertificates.ec256().getCertificate());
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH EXTERNAL !!!", "501");
        expect("AUTH EXTERNAL " + b64("certuser"), "235");
    }

    @Test
    public void testExternalWithoutCertificateFails() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        endpoint.securityInfo = new CertInfo(null);
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH EXTERNAL =", "535");
        assertEquals("EXTERNAL", listener.metrics.failures.get(0));
    }

    @Test
    public void testExternalRejectedByRealmFails() throws Exception {
        EdgeRealm realm = new EdgeRealm();
        realm.rejectCertificates = true;
        listener.setRealm(realm);
        endpoint.secure = true;
        endpoint.securityInfo = new CertInfo(TestCertificates.ec256().getCertificate());
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH EXTERNAL", "535");
    }

    // -- sender authorisation --

    @Test
    public void testAuthenticatedLocalPartMaySendFromItsDomain() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN " + b64("\0u\0p"), "235");
        expect("MAIL FROM:<u@example.org>", "250");
    }

    @Test
    public void testAdministratorMaySendAsAnyone() {
        EdgeRealm realm = new EdgeRealm();
        realm.adminUsers = true;
        listener.setRealm(realm);
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN " + b64("\0u\0p"), "235");
        expect("MAIL FROM:<anyone@example.org>", "250");
    }

    @Test
    public void testAuthRequiredAcceptsAuthenticatedSender() {
        listener.setRealm(new EdgeRealm());
        listener.setAuthRequired(true);
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<u@example.org>", "530");
        expect("AUTH PLAIN " + b64("\0u\0p"), "235");
        expect("MAIL FROM:<u@example.org>", "250");
        expect("RCPT TO:<r@example.org>", "250");
    }

    // -- MAIL and RCPT parameter edges --

    @Test
    public void testMailParametersSeparatedByMultipleSpaces() {
        ehlo();
        expect("MAIL FROM:<a@example.com> SIZE=10   BODY=7BIT", "250");
    }

    @Test
    public void testMailEnvidWithoutRetCreatesRequirements() {
        ehlo();
        expect("MAIL FROM:<a@example.com> ENVID=abc", "250");
    }

    @Test
    public void testMailEnvidXtextEdges() {
        ehlo();
        expect("MAIL FROM:<a@example.com> ENVID=a+zzb", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<a@example.com> ENVID=ab+", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<a@example.com> ENVID=a+2Bb", "250");
    }

    @Test
    public void testMailRetThenRequireTlsOverTls() {
        endpoint.secure = true;
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<a@example.com> RET=FULL REQUIRETLS", "250");
        assertTrue(handler.isRequireTls());
        assertNotNull(handler.getDSNEnvelopeParameters());
    }

    @Test
    public void testMailPriorityOutOfRange() {
        ehlo();
        expect("MAIL FROM:<a@example.com> MT-PRIORITY=10", "501");
        expect("MAIL FROM:<a@example.com> MT-PRIORITY=-10", "501");
        expect("MAIL FROM:<a@example.com> MT-PRIORITY=-9", "250");
    }

    @Test
    public void testMailHolduntilAndByWithoutPriorRequirements() {
        ehlo();
        expect("MAIL FROM:<a@example.com> HOLDUNTIL=2999-01-01T00:00:00Z", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<a@example.com> BY=60;N", "250");
    }

    @Test
    public void testMailDeclaredSizeAllowedWhenUnbounded() {
        listener.setMaxMessageSize(0);
        ehlo();
        expect("MAIL FROM:<a@example.com> SIZE=99999999999", "250");
    }

    // -- ETRN and HELP --

    @Test
    public void testEtrnWithoutNode() {
        ehlo();
        expect("ETRN", "458");
    }

    // -- XCLIENT --

    @Test
    public void testXclientAttributesAndMetadata() {
        XListener x = new XListener();
        handler = new SmtpProtocolHandler(x, null);
        connect();
        expect("EHLO c.example.com", "250");
        expect("XCLIENT PORT=2525", "220");
        expect("XCLIENT ADDR=192.0.2.7", "220");
        InetSocketAddress client = handler.getClientAddress();
        assertEquals(2525, client.getPort());
        assertEquals("192.0.2.7", client.getAddress().getHostAddress());
        expect("XCLIENT DESTPORT=587", "220");
        expect("XCLIENT DESTADDR=192.0.2.9", "220");
        InetSocketAddress server = handler.getServerAddress();
        assertEquals(587, server.getPort());
        expect("XCLIENT NAME=host.example PROTO=ESMTP HELO=other.example LOGIN=bob", "220");
        expect("XCLIENT NAME=[UNAVAILABLE] PROTO=SMTP", "220");
    }

    @Test
    public void testXclientInvalidValues() {
        XListener x = new XListener();
        handler = new SmtpProtocolHandler(x, null);
        connect();
        expect("EHLO c.example.com", "250");
        expect("XCLIENT PORT=70000", "501");
        expect("XCLIENT PORT=-1", "501");
        expect("XCLIENT PORT=abc", "501");
        expect("XCLIENT DESTPORT=70000", "501");
        expect("XCLIENT DESTPORT=-5", "501");
        expect("XCLIENT DESTPORT=abc", "501");
        expect("XCLIENT PROTO=UUCP", "501");
        expect("XCLIENT BOGUS=1", "501");
        expect("XCLIENT ", "501");
        expect("XCLIENT ADDR=[UNAVAILABLE] DESTADDR=[UNAVAILABLE]", "220");
    }

    @Test
    public void testXclientRefusedDuringTransaction() {
        XListener x = new XListener();
        handler = new SmtpProtocolHandler(x, null);
        connect();
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<a@example.com>", "250");
        expect("XCLIENT NAME=x", "503");
    }

    // -- metadata --

    @Test
    public void testMetadataWithoutXclient() throws Exception {
        TestCertificates.Identity identity = TestCertificates.ec256();
        endpoint.secure = true;
        endpoint.securityInfo = new CertInfo(identity.getCertificate());
        connect();
        assertTrue(handler.isSecure());
        InetSocketAddress client = handler.getClientAddress();
        assertEquals(54321, client.getPort());
        InetSocketAddress server = handler.getServerAddress();
        assertEquals(25, server.getPort());
        X509Certificate[] certs = handler.getClientCertificates();
        assertEquals(1, certs.length);
        assertEquals("TLSv1.3", handler.getProtocolVersion());
        assertEquals("TLS_TEST", handler.getCipherSuite());
        assertTrue(handler.getConnectionTimeMillis() > 0);
        assertFalse(handler.isRequireTls());
        assertNull(handler.getDSNEnvelopeParameters());
        assertNull(handler.getDSNRecipientParameters(null));
    }

    @Test
    public void testMetadataWithoutSecurityInfo() {
        connect();
        assertNull(handler.getClientCertificates());
        assertNull(handler.getCipherSuite());
        assertNull(handler.getProtocolVersion());
        assertFalse(handler.isSecure());
    }

    @Test
    public void testMetadataPeerCertificatesAbsent() {
        endpoint.securityInfo = new CertInfo(null);
        connect();
        assertNull(handler.getClientCertificates());
    }

    // -- metrics --

    @Test
    public void testConnectionAndMessageMetrics() {
        connect();
        assertEquals(1, listener.metrics.opened);
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<a@example.com>", "250");
        expect("RCPT TO:<b@example.com>", "250");
        expect("DATA", "354");
        endpoint.sentData.clear();
        raw("hi\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
        String reply = last();
        assertTrue(reply, reply.startsWith("250"));
        assertEquals(1, listener.metrics.messages);
        assertEquals(1, listener.metrics.lastRecipients);
        handler.disconnected();
        assertEquals(1, listener.metrics.closed);
    }

    @Test
    public void testStarttlsMetric() {
        TlsListener tls = new TlsListener();
        handler = new SmtpProtocolHandler(tls, null);
        connect();
        expect("STARTTLS", "220");
        assertEquals(1, tls.metrics.upgrades);
    }

    // -- telemetry --

    @Test
    public void testSessionSpanRecordsCommandsAndEndsInErrorOnAbruptClose() {
        listener.setRealm(new EdgeRealm());
        endpoint.secure = false;
        connect();
        assertEquals(1, config.traces.size());
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<a@example.com>", "250");
        expect("RCPT TO:<b@example.com>", "250");
        handler.disconnected();
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(config.traces.get(0).getRootSpan(), events, attrs);
        assertTrue(events.toString(), events.contains("EHLO"));
        assertTrue(attrs.toString(), attrs.contains("smtp.ehlo"));
        assertTrue(attrs.toString(), attrs.contains("net.peer.ip"));
    }

    @Test
    public void testSessionSpanRestartedByRsetAndHelo() {
        connect();
        expect("HELO c.example.com", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<a@example.com>", "250");
        expect("QUIT", "221");
        handler.disconnected();
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(config.traces.get(0).getRootSpan(), events, attrs);
        assertTrue(events.toString(), events.contains("HELO"));
        assertTrue(attrs.toString(), attrs.contains("smtp.helo"));
    }

    @Test
    public void testAuthSuccessRecordedInSpan() {
        TlsListener tls = new TlsListener();
        tls.setRealm(new EdgeRealm());
        handler = new SmtpProtocolHandler(tls, null);
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN " + b64("\0u\0p"), "235");
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(config.traces.get(0).getRootSpan(), events, attrs);
        assertTrue(events.toString(), events.contains("AUTH success: PLAIN"));
        assertTrue(attrs.toString(), attrs.contains("smtp.auth_user"));
    }

    @Test
    public void testConnectionWithRealLoopUsesPerLoopRealm() {
        EdgeRealm realm = new EdgeRealm();
        listener.setRealm(realm);
        endpoint.secure = true;
        endpoint.loop = new InlineSelectorLoop();
        handler.connected(endpoint);
        expect("EHLO c.example.com", "250");
        expect("AUTH PLAIN " + b64("\0u\0p"), "235");
        assertTrue(realm.forLoopCalls > 0);
    }

    @Test
    public void testTelemetryEnabledWithoutConfigOrTrace() {
        TeleEndpoint noConfig = new TeleEndpoint(null);
        noConfig.enabled = true;
        handler.connected(noConfig);
        assertTrue(noConfig.getTrace() == null);
        TeleEndpoint noTrace = new TeleEndpoint(new NullTraceConfig());
        noTrace.enabled = true;
        SmtpProtocolHandler other = new SmtpProtocolHandler(listener, null);
        other.connected(noTrace);
        assertTrue(noTrace.getTrace() == null);
    }

    private static void collect(Span span, List<String> events, List<String> attrs) {
        for (SpanEvent e : span.getEvents()) {
            events.add(e.getName());
        }
        for (Attribute a : span.getAttributes()) {
            attrs.add(a.getKey());
        }
        for (Span child : span.getChildren()) {
            collect(child, events, attrs);
        }
    }

    // -- mocks --

    /** Config that remembers the traces it created. */
    private static final class CapturingConfig extends TelemetryConfig {
        final List<Trace> traces = new ArrayList<Trace>();

        CapturingConfig() {
            exporter(new RecordingExporter());
        }

        @Override
        public Trace createTrace(String rootSpanName, SpanKind kind) {
            Trace trace = super.createTrace(rootSpanName, kind);
            if (trace != null) {
                traces.add(trace);
            }
            return trace;
        }
    }

    /** Config that declines to create traces. */
    private static final class NullTraceConfig extends TelemetryConfig {
        @Override
        public Trace createTrace(String rootSpanName, SpanKind kind) {
            return null;
        }
    }

    /** Metrics mock recording what the handler reports. */
    private static final class RecordingMetrics extends SmtpServerMetrics {
        int opened;
        int closed;
        int messages;
        int lastRecipients;
        int upgrades;
        final List<String> attempts = new ArrayList<String>();
        final List<String> successes = new ArrayList<String>();
        final List<String> failures = new ArrayList<String>();

        RecordingMetrics() {
            super(new TelemetryConfig());
        }

        @Override
        public void connectionOpened() {
            opened++;
        }

        @Override
        public void connectionClosed(double durationMs) {
            closed++;
        }

        @Override
        public void messageReceived(long sizeBytes, int recipientCount) {
            messages++;
            lastRecipients = recipientCount;
        }

        @Override
        public void authAttempt(String mechanism) {
            attempts.add(mechanism);
        }

        @Override
        public void authSuccess(String mechanism) {
            successes.add(mechanism);
        }

        @Override
        public void authFailure(String mechanism) {
            failures.add(mechanism);
        }

        @Override
        public void starttlsUpgraded() {
            upgrades++;
        }
    }

    /** Listener exposing recording metrics. */
    private static class MetricsListener extends SmtpListener {
        final RecordingMetrics metrics = new RecordingMetrics();

        @Override
        public SmtpServerMetrics getMetrics() {
            return metrics;
        }
    }

    /** Listener with STARTTLS available. */
    private static final class TlsListener extends MetricsListener {
        int startTlsChecks;

        @Override
        protected boolean isSTARTTLSAvailable() {
            startTlsChecks++;
            return true;
        }
    }

    /** Listener authorising XCLIENT. */
    private static final class XListener extends MetricsListener {
        @Override
        protected boolean isXclientAuthorized(InetAddress addr) {
            return true;
        }
    }

    /** Endpoint with telemetry, a settable security info and optional STARTTLS failure. */
    private static final class TeleEndpoint extends SMTPProtocolHandlerTest.StubEndpoint {
        private final TelemetryConfig config;
        private Trace trace;
        boolean enabled = true;
        boolean failStartTls;
        SecurityInfo securityInfo;
        SelectorLoop loop;

        TeleEndpoint(TelemetryConfig config) {
            this.config = config;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return config;
        }

        @Override
        public Trace getTrace() {
            return trace;
        }

        @Override
        public void setTrace(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void startTLS() {
            if (failStartTls) {
                throw new IllegalStateException("no tls");
            }
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return securityInfo;
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }
    }

    /** Security info carrying at most one peer certificate. */
    private static final class CertInfo implements SecurityInfo {
        private final Certificate certificate;

        CertInfo(Certificate certificate) {
            this.certificate = certificate;
        }

        @Override
        public String getProtocol() {
            return "TLSv1.3";
        }

        @Override
        public String getCipherSuite() {
            return "TLS_TEST";
        }

        @Override
        public int getKeySize() {
            return 256;
        }

        @Override
        public Certificate[] getPeerCertificates() {
            if (certificate == null) {
                return null;
            }
            return new Certificate[] {certificate};
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public String getApplicationProtocol() {
            return null;
        }

        @Override
        public long getHandshakeDurationMs() {
            return 0L;
        }

        @Override
        public boolean isSessionResumed() {
            return false;
        }
    }

    /** Realm with user u/p, bearer tokens good (valid) and old (expired), and certificate login. */
    private static final class EdgeRealm implements Realm {
        boolean rejectCertificates;
        boolean adminUsers;
        String lastCertUser;
        int forLoopCalls;

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            forLoopCalls++;
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN,
                    SaslMechanism.CRAM_MD5, SaslMechanism.DIGEST_MD5,
                    SaslMechanism.SCRAM_SHA_256, SaslMechanism.OAUTHBEARER,
                    SaslMechanism.EXTERNAL);
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return "u".equals(username) && "p".equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return adminUsers && "admin".equals(role);
        }

        @Override
        public String getCramMD5Response(String username, String challenge) {
            if ("u".equals(username)) {
                return SaslUtils.computeCramMD5Response("p", challenge);
            }
            return null;
        }

        @Override
        public TokenValidationResult validateBearerToken(String token) {
            if ("good".equals(token)) {
                return TokenValidationResult.success("u", new String[0], "Bearer");
            }
            if ("old".equals(token)) {
                return TokenValidationResult.success("u", new String[0], "Bearer", 1L);
            }
            return TokenValidationResult.failure();
        }

        @Override
        public CertificateAuthenticationResult authenticateCertificate(X509Certificate certificate) {
            if (rejectCertificates) {
                return CertificateAuthenticationResult.failure();
            }
            lastCertUser = "certuser";
            return CertificateAuthenticationResult.success("certuser");
        }

        @Override
        public boolean authorizeAs(String authenticatedUser, String requestedUser) {
            return authenticatedUser.equals(requestedUser);
        }
    }
}
