/*
 * LdapClientProtocolFlowTest.java
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

package org.bluezoo.gumdrop.ldap.client;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.ldap.asn1.BerEncoder;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Flow tests for LdapClientProtocolHandler: every request type is sent
 * and answered with a forged response so that the encoders, the response
 * dispatch and the error branches are exercised without a socket.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LdapClientProtocolFlowTest {

    private LDAPClientProtocolHandlerTest.RecordingHandler handler;
    private LdapClientProtocolHandler protocol;
    private LDAPClientProtocolHandlerTest.StubEndpoint endpoint;
    private AllHandler all;

    @Before
    public void setUp() {
        handler = new LDAPClientProtocolHandlerTest.RecordingHandler();
        protocol = new LdapClientProtocolHandler(handler, false);
        endpoint = new LDAPClientProtocolHandlerTest.StubEndpoint();
        protocol.connected(endpoint);
        all = new AllHandler();
    }

    // ---- requests ----

    @Test
    public void testSimpleBindSuccess() {
        protocol.bind("cn=a", "pw", all);
        assertEquals(1, endpoint.sentBuffers.size());
        protocol.receive(result(1, 0x61, 0, "", "", null, null));
        assertNotNull(all.session);
        assertNull(all.failure);
    }

    @Test
    public void testBindNullArgumentsAndFailure() {
        protocol.bind(null, null, all);
        protocol.receive(result(1, 0x61, 49, "", "bad", null, null));
        assertNotNull(all.failure);
        assertEquals(LdapResultCode.INVALID_CREDENTIALS, all.failure.getResultCode());
    }

    @Test
    public void testBindAnonymousAndRebind() {
        protocol.bindAnonymous(all);
        protocol.receive(result(1, 0x61, 0, "", "", null, null));
        assertNotNull(all.session);
        all.session = null;
        protocol.rebind("cn=b", "x", all);
        protocol.receive(result(2, 0x61, 0, "", "", null, null));
        assertNotNull(all.session);
    }

    @Test
    public void testBindFailureWithReferrals() {
        protocol.bind("cn=a", "pw", all);
        List<String> refs = new ArrayList<String>();
        refs.add("ldap://other/");
        refs.add("ldap://third/");
        protocol.receive(result(1, 0x61, 10, "", "refer", refs, null));
        assertTrue(all.failure.hasReferrals());
        assertEquals(2, all.failure.getReferrals().size());
    }

    @Test
    public void testSaslMultiStepSuccess() {
        ScriptedSasl sasl = new ScriptedSasl(true, false);
        protocol.bindSASL(sasl, all);
        assertEquals(1, endpoint.sentBuffers.size());
        protocol.receive(result(1, 0x61, 14, "", "", null, new byte[]{1, 2}));
        assertEquals(2, endpoint.sentBuffers.size());
        sasl.complete = true;
        protocol.receive(result(2, 0x61, 0, "", "", null, new byte[]{3}));
        assertNotNull(all.session);
        assertEquals(3, sasl.challenges.size());
    }

    @Test
    public void testSaslIncompleteAfterServerSuccess() {
        ScriptedSasl sasl = new ScriptedSasl(false, false);
        protocol.rebindSASL(sasl, all);
        protocol.receive(result(1, 0x61, 0, "", "", null, null));
        assertNotNull(all.failure);
        assertEquals(LdapResultCode.OTHER, all.failure.getResultCode());
    }

    @Test
    public void testSaslInitialResponseFailure() {
        ScriptedSasl sasl = new ScriptedSasl(true, true);
        protocol.bindSASL(sasl, all);
        assertNotNull(all.failure);
        assertEquals(LdapResultCode.OTHER, all.failure.getResultCode());
    }

    @Test
    public void testSaslChallengeFailure() {
        ScriptedSasl sasl = new ScriptedSasl(false, false);
        protocol.bindSASL(sasl, all);
        sasl.fail = true;
        protocol.receive(result(1, 0x61, 14, "", "", null, new byte[]{1}));
        assertNotNull(all.failure);
    }

    @Test
    public void testSaslFinalChallengeFailure() {
        ScriptedSasl sasl = new ScriptedSasl(false, false);
        protocol.bindSASL(sasl, all);
        sasl.fail = true;
        protocol.receive(result(1, 0x61, 0, "", "", null, new byte[]{1}));
        assertNotNull(all.failure);
    }

    @Test
    public void testSaslServerRejects() {
        ScriptedSasl sasl = new ScriptedSasl(false, false);
        protocol.bindSASL(sasl, all);
        protocol.receive(result(1, 0x61, 49, "", "no", null, null));
        assertEquals(LdapResultCode.INVALID_CREDENTIALS, all.failure.getResultCode());
    }

    @Test
    public void testSearchWithEntriesReferencesAndDone() {
        protocol.search(search("(cn=x)"), all);
        Map<String, List<byte[]>> attrs = new LinkedHashMap<String, List<byte[]>>();
        attrs.put("cn", Collections.singletonList("x".getBytes(StandardCharsets.UTF_8)));
        attrs.put("empty", new ArrayList<byte[]>());
        protocol.receive(entry(1, "cn=x,dc=e", attrs));
        protocol.receive(reference(1, "ldap://r1/", "ldap://r2/"));
        protocol.receive(result(1, 0x65, 0, "", "", null, null));
        assertEquals(1, all.entries.size());
        assertEquals("cn=x,dc=e", all.entries.get(0).getDN());
        assertEquals(2, all.references.length);
        assertNotNull(all.doneResult);
    }

    @Test
    public void testSearchEntryCarriesControls() {
        protocol.search(search("(cn=x)"), all);
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(4, true);
        e.writeOctetString("cn=x");
        e.beginSequence();
        e.endSequence();
        e.endApplication();
        e.beginContext(0, true);
        e.beginSequence();
        e.writeOctetString("1.2.3");
        e.writeBoolean(true);
        e.writeOctetString(new byte[]{9});
        e.endSequence();
        e.beginSequence();
        e.endSequence();
        e.endContext();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        assertEquals(1, all.entries.size());
        assertEquals(1, protocol.getResponseControls().size());
        assertTrue(protocol.getResponseControls().get(0).isCritical());
    }

    @Test
    public void testSearchFiltersEncode() {
        String[] filters = new String[] {
            "(&(cn=a)(sn=b))", "(|(cn=a)(sn=b))", "(!(cn=a))", "(cn=*)",
            "(cn~=a)", "(age>=3)", "(age<=3)", "(cn=a*b*c)", "(cn=*b*)",
            "(cn=a*)", "(cn=*c)", "(cn=a)", "cn", "(cn:dn:2.5.13.5:=x)",
            "(:dn:2.5.13.5:=x)", "(cn:=x)", "(:2.5.13.5:=x)"
        };
        for (int i = 0; i < filters.length; i++) {
            endpoint.sentBuffers.clear();
            protocol.search(search(filters[i]), all);
            assertEquals(filters[i], 1, endpoint.sentBuffers.size());
        }
    }

    @Test
    public void testRequestControlsEncodedOnceForEachOperation() {
        List<Control> controls = new ArrayList<Control>();
        controls.add(new Control("1.2.3", true, new byte[]{1}));
        controls.add(new Control("1.2.4", false));
        protocol.setRequestControls(controls);
        protocol.modify("cn=a", Collections.singletonList(Modification.add("mail", "a@b")), all);
        int withControls = endpoint.sentBuffers.get(0).remaining();
        protocol.modify("cn=a", Collections.singletonList(Modification.add("mail", "a@b")), all);
        int without = endpoint.sentBuffers.get(1).remaining();
        assertTrue(withControls > without);
    }

    @Test
    public void testModifyAddDeleteCompareModifyDnExtended() {
        List<Modification> mods = new ArrayList<Modification>();
        mods.add(Modification.replace("cn", "z"));
        mods.add(Modification.delete("mail"));
        protocol.modify("cn=a", mods, all);
        protocol.receive(result(1, 0x67, 0, "", "", null, null));
        Map<String, List<byte[]>> attrs = new LinkedHashMap<String, List<byte[]>>();
        attrs.put("cn", Collections.singletonList(new byte[]{'a'}));
        protocol.add("cn=a", attrs, all);
        protocol.receive(result(2, 0x69, 0, "", "", null, null));
        protocol.delete("cn=a", all);
        protocol.receive(result(3, 0x6B, 0, "", "", null, null));
        protocol.modifyDN("cn=a", "cn=b", true, all);
        protocol.receive(result(4, 0x6D, 0, "", "", null, null));
        protocol.modifyDN("cn=a", "cn=b", false, "dc=x", all);
        protocol.receive(result(5, 0x6D, 0, "", "", null, null));
        assertEquals(1, all.modifyResults);
        assertEquals(1, all.addResults);
        assertEquals(1, all.deleteResults);
        assertEquals(2, all.modifyDnResults);
    }

    @Test
    public void testCompareOutcomes() {
        protocol.compare("cn=a", "cn", new byte[]{'a'}, all);
        protocol.receive(result(1, 0x6F, 6, "", "", null, null));
        protocol.compare("cn=a", "cn", new byte[]{'a'}, all);
        protocol.receive(result(2, 0x6F, 5, "", "", null, null));
        protocol.compare("cn=a", "cn", new byte[]{'a'}, all);
        protocol.receive(result(3, 0x6F, 32, "", "", null, null));
        assertEquals("TFX", all.compareLog.toString());
    }

    @Test
    public void testExtendedWithNameAndValue() {
        protocol.extended("1.2.3", new byte[]{1}, all);
        protocol.extended("1.2.4", null, all);
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(24, true);
        e.writeEnumerated(0);
        e.writeOctetString("");
        e.writeOctetString("");
        e.writeContext(10, "1.2.3");
        e.writeContext(11, new byte[]{7, 8});
        e.endApplication();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        assertEquals("1.2.3", all.extName);
        assertEquals(2, all.extValue.length);
    }

    @Test
    public void testUnbindClosesAndIsIdempotent() {
        protocol.unbind();
        assertFalse(protocol.isOpen());
        assertFalse(endpoint.open);
        protocol.close();
        endpoint.sentBuffers.clear();
        protocol.abandon(7);
        assertTrue(endpoint.sentBuffers.isEmpty());
    }

    @Test
    public void testAbandonLargeMessageIds() {
        int[] ids = new int[] {5, 200, 40000, 9000000};
        for (int i = 0; i < ids.length; i++) {
            endpoint.sentBuffers.clear();
            protocol.abandon(ids[i]);
            assertEquals(1, endpoint.sentBuffers.size());
        }
    }

    // ---- STARTTLS ----

    @Test
    public void testStartTlsSuccessThenSecurityEstablished() {
        protocol.startTLS(all);
        protocol.receive(result(1, 0x78, 0, "", "", null, null));
        protocol.securityEstablished(new MockSecurityInfo());
        assertTrue(all.tlsEstablished);
    }

    @Test
    public void testStartTlsRefused() {
        protocol.startTLS(all);
        protocol.receive(result(1, 0x78, 2, "", "no", null, null));
        assertNotNull(all.failure);
    }

    @Test
    public void testStartTlsEndpointFailureReportsToCallback() {
        FailingTlsEndpoint ep = new FailingTlsEndpoint();
        LdapClientProtocolHandler p = new LdapClientProtocolHandler(handler, false);
        p.connected(ep);
        p.startTLS(all);
        p.receive(result(1, 0x78, 0, "", "", null, null));
        assertNotNull(all.failure);
    }

    @Test
    public void testStartTlsEndpointFailureWithoutCallbackReportsError() {
        FailingTlsEndpoint ep = new FailingTlsEndpoint();
        LdapClientProtocolHandler p = new LdapClientProtocolHandler(handler, false);
        p.connected(ep);
        p.startTLS(null);
        p.receive(result(1, 0x78, 0, "", "", null, null));
        assertNotNull(handler.lastError);
    }

    @Test
    public void testSecurityEstablishedWithoutPendingStartTls() {
        LdapClientProtocolHandler p = new LdapClientProtocolHandler(handler, true);
        p.connected(endpoint);
        p.securityEstablished(new MockSecurityInfo());
        assertFalse(all.tlsEstablished);
    }

    // ---- inbound edge cases ----

    @Test
    public void testNonSequenceMessageIsProtocolError() {
        BerEncoder e = new BerEncoder();
        e.writeInteger(5);
        protocol.receive(e.toByteBuffer());
        assertNotNull(handler.lastError);
        assertFalse(protocol.isOpen());
    }

    @Test
    public void testShortMessageIsProtocolError() {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        assertNotNull(handler.lastError);
    }

    @Test
    public void testShortResultIsProtocolError() {
        protocol.bind("a", "b", all);
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(1, true);
        e.writeEnumerated(0);
        e.endApplication();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        assertNotNull(handler.lastError);
    }

    @Test
    public void testShortSearchEntryIsProtocolError() {
        protocol.search(search("(cn=x)"), all);
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(4, true);
        e.writeOctetString("dn");
        e.endApplication();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        assertNotNull(handler.lastError);
    }

    @Test
    public void testUnknownTagAndMismatchedCallbacksAreIgnored() {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(30, true);
        e.endApplication();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        protocol.receive(result(9, 0x61, 0, "", "", null, null));
        protocol.receive(result(9, 0x65, 0, "", "", null, null));
        protocol.receive(result(9, 0x67, 0, "", "", null, null));
        protocol.receive(result(9, 0x69, 0, "", "", null, null));
        protocol.receive(result(9, 0x6B, 0, "", "", null, null));
        protocol.receive(result(9, 0x6D, 0, "", "", null, null));
        protocol.receive(result(9, 0x6F, 0, "", "", null, null));
        protocol.receive(result(9, 0x78, 0, "", "", null, null));
        protocol.receive(entry(9, "cn=x", new LinkedHashMap<String, List<byte[]>>()));
        protocol.receive(reference(9, "ldap://x/"));
        assertNull(handler.lastError);
        assertTrue(protocol.isOpen());
    }

    @Test
    public void testUnsolicitedNotificationOtherThanDisconnection() {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(0);
        e.beginApplication(24, true);
        e.writeEnumerated(0);
        e.writeOctetString("");
        e.writeOctetString("");
        e.writeContext(10, "9.9.9");
        e.endApplication();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        assertTrue(protocol.isOpen());
        assertNull(handler.lastError);
    }

    @Test
    public void testIntermediateResponseWithoutHandlerAndWithValue() {
        protocol.bind("a", "b", all);
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(25, true);
        e.writeContext(0, "1.2");
        e.writeContext(1, new byte[]{4});
        e.endApplication();
        e.endSequence();
        protocol.receive(e.toByteBuffer());
        protocol.search(search("(cn=x)"), all);
        BerEncoder f = new BerEncoder();
        f.beginSequence();
        f.writeInteger(2);
        f.beginApplication(25, true);
        f.writeContext(0, "1.2");
        f.writeContext(1, new byte[]{4});
        f.endApplication();
        f.endSequence();
        protocol.receive(f.toByteBuffer());
        assertEquals("1.2", all.intermediateName);
        assertEquals(1, all.intermediateValue.length);
    }

    @Test
    public void testLifecycleCallbacks() {
        protocol.error(new IOException("boom"));
        assertEquals("boom", handler.lastError.getMessage());
        protocol.disconnected();
        assertFalse(protocol.isOpen());
        assertTrue(protocol.getResponseControls().isEmpty());
    }

    @Test
    public void testFragmentedMessageReassembled() {
        protocol.bind("a", "b", all);
        ByteBuffer whole = result(1, 0x61, 0, "", "", null, null);
        byte[] bytes = new byte[whole.remaining()];
        whole.get(bytes);
        for (int i = 0; i < bytes.length; i++) {
            protocol.receive(ByteBuffer.wrap(new byte[]{bytes[i]}));
        }
        assertNotNull(all.session);
    }

    // ---- helpers ----

    private SearchRequest search(String filter) {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter(filter);
        req.setAttributes("cn", "sn");
        return req;
    }

    private ByteBuffer result(int id, int appTag, int code, String matched,
                              String diag, List<String> referrals, byte[] saslCreds) {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(id);
        e.beginApplication(appTag & 0x1F, true);
        e.writeEnumerated(code);
        e.writeOctetString(matched);
        e.writeOctetString(diag);
        if (referrals != null) {
            e.beginContext(3, true);
            for (int i = 0; i < referrals.size(); i++) {
                String r = referrals.get(i);
                e.writeOctetString(r);
            }
            e.endContext();
        }
        if (saslCreds != null) {
            e.writeContext(7, saslCreds);
        }
        e.endApplication();
        e.endSequence();
        return e.toByteBuffer();
    }

    private ByteBuffer entry(int id, String dn, Map<String, List<byte[]>> attrs) {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(id);
        e.beginApplication(4, true);
        e.writeOctetString(dn);
        e.beginSequence();
        for (Map.Entry<String, List<byte[]>> en : attrs.entrySet()) {
            e.beginSequence();
            e.writeOctetString(en.getKey());
            e.beginSet();
            List<byte[]> values = en.getValue();
            for (int i = 0; i < values.size(); i++) {
                byte[] v = values.get(i);
                e.writeOctetString(v);
            }
            e.endSet();
            e.endSequence();
        }
        e.endSequence();
        e.endApplication();
        e.endSequence();
        return e.toByteBuffer();
    }

    private ByteBuffer reference(int id, String... urls) {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(id);
        e.beginApplication(19, true);
        for (int i = 0; i < urls.length; i++) {
            e.writeOctetString(urls[i]);
        }
        e.endApplication();
        e.endSequence();
        return e.toByteBuffer();
    }

    private static class ScriptedSasl implements SaslClientMechanism {
        final boolean initial;
        final boolean failInitial;
        boolean complete;
        boolean fail;
        final List<byte[]> challenges = new ArrayList<byte[]>();

        ScriptedSasl(boolean initial, boolean failInitial) {
            this.initial = initial;
            this.failInitial = failInitial;
        }

        @Override
        public String getMechanismName() {
            return "X-TEST";
        }

        @Override
        public boolean hasInitialResponse() {
            return initial;
        }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) throws IOException {
            if (failInitial || fail) {
                throw new IOException("sasl failure");
            }
            challenges.add(challenge);
            return new byte[]{5};
        }

        @Override
        public boolean isComplete() {
            return complete;
        }
    }

    private static class MockSecurityInfo implements SecurityInfo {
        @Override
        public String getProtocol() {
            return "TLSv1.3";
        }

        @Override
        public String getCipherSuite() {
            return "TLS_AES_128_GCM_SHA256";
        }

        @Override
        public int getKeySize() {
            return 128;
        }

        @Override
        public Certificate[] getPeerCertificates() {
            return null;
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
            return 0;
        }

        @Override
        public boolean isSessionResumed() {
            return false;
        }
    }

    private static class FailingTlsEndpoint extends LDAPClientProtocolHandlerTest.StubEndpoint {
        @Override
        public void startTLS() throws IOException {
            throw new IOException("no tls");
        }
    }

    private static class AllHandler implements BindResultHandler, SearchResultHandler,
            IntermediateResponseHandler, ModifyResultHandler, AddResultHandler,
            DeleteResultHandler, ModifyDNResultHandler, CompareResultHandler,
            ExtendedResultHandler, StartTLSResultHandler {
        LdapSession session;
        LdapResult failure;
        LdapResult doneResult;
        final List<SearchResultEntry> entries = new ArrayList<SearchResultEntry>();
        String[] references;
        int modifyResults;
        int addResults;
        int deleteResults;
        int modifyDnResults;
        final StringBuilder compareLog = new StringBuilder();
        String extName;
        byte[] extValue;
        boolean tlsEstablished;
        String intermediateName;
        byte[] intermediateValue;

        @Override
        public void handleBindSuccess(LdapSession s) {
            session = s;
        }

        @Override
        public void handleBindFailure(LdapResult result, LdapConnected connection) {
            failure = result;
        }

        @Override
        public void handleEntry(SearchResultEntry entry) {
            entries.add(entry);
        }

        @Override
        public void handleReference(String[] referralUrls) {
            references = referralUrls;
        }

        @Override
        public void handleDone(LdapResult result, LdapSession s) {
            doneResult = result;
        }

        @Override
        public void handleIntermediateResponse(String responseName, byte[] responseValue) {
            intermediateName = responseName;
            intermediateValue = responseValue;
        }

        @Override
        public void handleModifyResult(LdapResult result, LdapSession s) {
            modifyResults++;
        }

        @Override
        public void handleAddResult(LdapResult result, LdapSession s) {
            addResults++;
        }

        @Override
        public void handleDeleteResult(LdapResult result, LdapSession s) {
            deleteResults++;
        }

        @Override
        public void handleModifyDNResult(LdapResult result, LdapSession s) {
            modifyDnResults++;
        }

        @Override
        public void handleCompareTrue(LdapSession s) {
            compareLog.append('T');
        }

        @Override
        public void handleCompareFalse(LdapSession s) {
            compareLog.append('F');
        }

        @Override
        public void handleCompareFailure(LdapResult result, LdapSession s) {
            compareLog.append('X');
        }

        @Override
        public void handleExtendedResult(LdapResult result, String responseName,
                                         byte[] responseValue, LdapSession s) {
            extName = responseName;
            extValue = responseValue;
        }

        @Override
        public void handleTLSEstablished(LdapPostTLS postTLS) {
            tlsEstablished = true;
        }

        @Override
        public void handleStartTLSFailure(LdapResult result, LdapConnected connection) {
            failure = result;
        }
    }
}
