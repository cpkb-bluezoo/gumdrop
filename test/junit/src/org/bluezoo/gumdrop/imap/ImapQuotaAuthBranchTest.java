/*
 * ImapQuotaAuthBranchTest.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;

import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;

import static org.junit.Assert.*;

/**
 * Exercises quota-root name quoting, mailbox-name quoting helpers and the
 * SASL mechanisms (EXTERNAL, GSSAPI) that need a TLS client certificate or a
 * Kerberos service in {@link ImapProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapQuotaAuthBranchTest extends ImapSessionHarness {

    @Override
    protected void configureListener(ImapListener l) {
        l.realm(new IMAPSessionCoverageTest.AcceptingRealm(
                "editor", "editor", true));
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        qm.defaultQuota("10MB");
        l.quotaManager(qm);
    }

    @Test(timeout = 30000)
    public void quotaRootNamesAreQuotedWhenNeeded() throws Exception {
        login();
        ok("GETQUOTAROOT INBOX");
        assertSaw("* QUOTAROOT INBOX \"\"");
        ok("GETQUOTAROOT \"Sub Box\"");
        assertSaw("* QUOTAROOT \"Sub Box\" \"\"");
        ok("GETQUOTAROOT \"a(b\"");
        assertSaw("* QUOTAROOT \"a(b\" \"\"");
        ok("GETQUOTAROOT \"a)b\"");
        assertSaw("* QUOTAROOT \"a)b\" \"\"");
        ok("GETQUOTAROOT \"a{b\"");
        assertSaw("* QUOTAROOT \"a{b\" \"\"");
        ok("GETQUOTAROOT \"a\\\"b\"");
        assertSaw("* QUOTAROOT \"a\\\"b\" \"\"");
        ok("GETQUOTAROOT \"a\\\\b\"");
        assertSaw("* QUOTAROOT \"a\\\\b\" \"\"");
        bad("GETQUOTAROOT \"\"");
    }

    @Test(timeout = 30000)
    public void quotaResponsesCarryStorageAndMessageResources()
            throws Exception {
        login();
        ok("SETQUOTA \"user.editor\" (STORAGE 2048 MESSAGE 7)");
        ok("GETQUOTA \"user.editor\"");
        assertSaw("* QUOTA user.editor (STORAGE 0 2048 MESSAGE 0 7)");
    }

    @Test(timeout = 30000)
    public void mailboxNamesAreQuotedAndStrippedOfControlCharacters() {
        assertEquals("plain", handler.quoteMailboxName("plain"));
        assertEquals("\"two words\"", handler.quoteMailboxName("two words"));
        assertEquals("\"a\\\"b\"", handler.quoteMailboxName("a\"b"));
        assertEquals("\"a\\\\b\"", handler.quoteMailboxName("a\\b"));
        assertEquals("ab", handler.quoteMailboxName("a\u0001b"));
        assertEquals("ab", handler.quoteMailboxName("a\u007fb"));
        assertEquals("\"a b\"", handler.quoteMailboxName("a\n b\r"));
        assertEquals("", handler.quoteMailboxName("\u0000\u001f"));
        assertEquals("\"x\"", handler.quoteImapString("x"));
        assertEquals("NIL", handler.quoteImapString(null));
    }

    private static String encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(bytes);
    }

    @Test(timeout = 30000)
    public void externalAuthenticationNeedsTls() throws Exception {
        String line = no("AUTHENTICATE EXTERNAL");
        assertContains(line, "PRIVACYREQUIRED");
    }

    @Test(timeout = 30000)
    public void externalAuthenticationRejectsBadBase64() throws Exception {
        endpoint.setSecure(true);
        bad("AUTHENTICATE EXTERNAL !!!notbase64");
    }

    @Test(timeout = 30000)
    public void externalAuthenticationWithoutPeerCertificateFails()
            throws Exception {
        endpoint.setSecure(true);
        MockSecurityInfo info = new MockSecurityInfo();
        endpoint.setSecurityInfo(info);
        no("AUTHENTICATE EXTERNAL");
        info.certificates = new Certificate[0];
        no("AUTHENTICATE EXTERNAL");
        bad("LIST \"\" \"*\"");
    }

    @Test(timeout = 30000)
    public void externalAuthenticationUsesTheCertificateIdentity()
            throws Exception {
        CertificateRealm realm = new CertificateRealm();
        listener.realm(realm);
        reconnect();
        endpoint.setSecure(true);
        MockSecurityInfo info = new MockSecurityInfo();
        TestCertificates.Identity identity = TestCertificates.ec256();
        X509Certificate clientCert = identity.getCertificate();
        info.certificates = new Certificate[] { clientCert };
        endpoint.setSecurityInfo(info);
        realm.accept = false;
        no("AUTHENTICATE EXTERNAL");
        realm.accept = true;
        realm.authorize = false;
        no("AUTHENTICATE EXTERNAL " + encode("other"));
        realm.authorize = true;
        String line = ok("AUTHENTICATE EXTERNAL " + encode("editor"));
        assertContains(line, "OK");
        ok("LIST \"\" \"*\"");
    }

    @Test(timeout = 30000)
    public void gssapiWithoutAServiceIsUnavailable() throws Exception {
        endpoint.setSecure(true);
        String line = no("AUTHENTICATE GSSAPI");
        String lower = line.toLowerCase();
        assertContains(lower, "gssapi");
        no("AUTHENTICATE GSSAPI dG9rZW4=");
        ok("NOOP");
    }

    /** Mock TLS session reporting configurable peer certificates. */
    static final class MockSecurityInfo implements SecurityInfo {
        Certificate[] certificates;

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
            return certificates;
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

    /** Realm that maps every client certificate to the user "editor". */
    static final class CertificateRealm
            extends IMAPSessionCoverageTest.AcceptingRealm {
        boolean accept = true;
        boolean authorize = true;

        CertificateRealm() {
            super("editor", "editor");
        }

        @Override
        public CertificateAuthenticationResult authenticateCertificate(
                X509Certificate certificate) {
            if (accept) {
                return CertificateAuthenticationResult.success("editor");
            }
            return CertificateAuthenticationResult.failure();
        }

        @Override
        public boolean authorizeAs(String authenticatedUser,
                String requestedUser) {
            return authorize && authenticatedUser.equals(requestedUser);
        }
    }
}
