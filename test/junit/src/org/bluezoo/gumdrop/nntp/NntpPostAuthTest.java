/*
 * NntpPostAuthTest.java
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

package org.bluezoo.gumdrop.nntp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubEndpoint;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * POST, STARTTLS and AUTHINFO (RFC 3977 section 6.3, RFC 4642, RFC 4643).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpPostAuthTest {

    private static final String ARTICLE =
            "From: a@example.com\r\nSubject: Hi\r\nNewsgroups: test.group,no.such\r\n"
            + "\r\nHello\r\n.dot\r\nlast\r\n";

    private static final class TestRealm implements Realm {
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.emptySet();
        }

        @Override
        public void passwordMatch(String user, String password, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.valueOf(user.equals("alice") && password.equals("secret")));
        }

        @Override
        public void getDigestHA1(String username, String realmName, RealmCallback<String> callback) {
            callback.completed(null);
        }

        @Override
        public void isUserInRole(String username, String role, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.FALSE);
        }
    }

    private static final class Listener extends NntpListener {
        boolean tls;

        @Override
        protected boolean isSTARTTLSAvailable() {
            return tls;
        }
    }

    private StubFactory factory;
    private Listener listener;
    private StubEndpoint endpoint;
    private NntpProtocolHandler handler;

    @Before
    public void setUp() {
        factory = new StubFactory();
        factory.add("test.group", "Subject: One\r\nFrom: x@example.com\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n");
        listener = new Listener();
        listener.mailboxFactory(factory);
    }

    private void connect() {
        handler = new NntpProtocolHandler(listener);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
        take();
    }

    private void feed(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private String take() {
        String s = endpoint.text();
        endpoint.sent.clear();
        return s;
    }

    private String exchange(String command) {
        take();
        feed(command + "\r\n");
        return take();
    }

    // ── POST ──

    @Test
    public void testPostRefusedWhenNotAllowed() {
        connect();
        assertEquals("440 Posting not permitted\r\n", exchange("POST"));
    }

    @Test
    public void testPostStoresTheArticleInEachExistingGroup() {
        listener.postingAllowed(true);
        connect();
        assertEquals("340 Send article to be posted\r\n", exchange("POST"));
        feed(ARTICLE.replace("\r\n.dot", "\r\n..dot") + ".\r\n");
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(2, factory.groups.get("test.group").size());
        assertEquals(ARTICLE, factory.groups.get("test.group").get(1));
        // and the connection carries on
        assertEquals("211 2 1 2 test.group\r\n", exchange("GROUP test.group"));
    }

    @Test
    public void testPostOneByteAtATime() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        String wire = ARTICLE.replace("\r\n.dot", "\r\n..dot") + ".\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(ARTICLE, factory.groups.get("test.group").get(1));
    }

    @Test
    public void testCommandPipelinedAfterAnArticleIsRun() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed(ARTICLE + ".\r\nDATE\r\n");
        String r = take();
        assertTrue(r, r.startsWith("240 Article received OK\r\n111 "));
    }

    @Test
    public void testPostWithoutRequiredHeadersIsNotPosted() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("Subject: no from\r\nNewsgroups: test.group\r\n\r\nx\r\n.\r\n");
        assertTrue(take().startsWith("441 "));
        assertEquals(1, factory.groups.get("test.group").size());
    }

    @Test
    public void testPostToNoExistingGroupIsNotPosted() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: no.such\r\n\r\nx\r\n.\r\n");
        assertEquals("441 None of the newsgroups exist\r\n", take());
    }

    @Test
    public void testCapabilitiesAdvertisePost() {
        listener.postingAllowed(true);
        connect();
        assertTrue(exchange("CAPABILITIES").contains("\r\nPOST\r\n"));
    }

    // ── STARTTLS ──

    @Test
    public void testStartTlsIsOnlyAdvertisedWhenAvailable() {
        connect();
        assertFalse(exchange("CAPABILITIES").contains("STARTTLS"));
        assertEquals("580 TLS not available\r\n", exchange("STARTTLS"));
        listener.tls = true;
        connect();
        assertTrue(exchange("CAPABILITIES").contains("\r\nSTARTTLS\r\n"));
    }

    @Test
    public void testStartTlsUpgradesAndForgetsTheGroup() {
        listener.tls = true;
        connect();
        exchange("GROUP test.group");
        assertEquals("382 Continue with TLS negotiation\r\n", exchange("STARTTLS"));
        assertEquals("412 No newsgroup selected\r\n", exchange("STAT"));
    }

    @Test
    public void testStartTlsWhenAlreadySecure() {
        listener.tls = true;
        connect();
        endpoint.secure = true;
        assertEquals("502 Already using TLS\r\n", exchange("STARTTLS"));
        assertFalse(exchange("CAPABILITIES").contains("STARTTLS"));
    }

    // ── AUTHINFO ──

    @Test
    public void testAuthinfoNeedsARealm() {
        connect();
        assertEquals("500 Unknown command\r\n", exchange("AUTHINFO USER alice"));
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO"));
    }

    @Test
    public void testAuthinfoUserPass() {
        listener.realm(new TestRealm());
        connect();
        assertTrue(exchange("CAPABILITIES").contains("\r\nAUTHINFO USER\r\n"));
        assertEquals("482 AUTHINFO USER required first\r\n", exchange("AUTHINFO PASS secret"));
        assertEquals("381 Password required\r\n", exchange("AUTHINFO USER alice"));
        assertEquals("281 Authentication accepted\r\n", exchange("AUTHINFO PASS secret"));
        assertEquals("502 Already authenticated\r\n", exchange("AUTHINFO USER alice"));
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO"));
    }

    @Test
    public void testAuthinfoWrongPassword() {
        listener.realm(new TestRealm());
        connect();
        exchange("AUTHINFO USER alice");
        assertEquals("481 Authentication failed\r\n", exchange("AUTHINFO PASS wrong"));
        // the user must be given again
        assertEquals("482 AUTHINFO USER required first\r\n", exchange("AUTHINFO PASS secret"));
    }

    @Test
    public void testStoreIsOpenedForTheAuthenticatedUser() {
        listener.realm(new TestRealm());
        connect();
        exchange("GROUP test.group");
        assertEquals("news", factory.lastStore.user);
        exchange("AUTHINFO USER alice");
        exchange("AUTHINFO PASS secret");
        assertTrue(factory.lastStore.closed);
        assertEquals("412 No newsgroup selected\r\n", exchange("STAT"));
        exchange("GROUP test.group");
        assertEquals("alice", factory.lastStore.user);
    }

    @Test
    public void testPasswordsRefusedInTheClearWhereTlsCanBeHad() {
        listener.realm(new TestRealm());
        listener.tls = true;
        connect();
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO USER"));
        assertEquals("483 Encryption required\r\n", exchange("AUTHINFO USER alice"));
        endpoint.secure = true;
        assertEquals("381 Password required\r\n", exchange("AUTHINFO USER alice"));
    }

    @Test
    public void testAuthenticationRequired() {
        listener.realm(new TestRealm());
        listener.authenticationRequired(true);
        connect();
        assertEquals("480 Authentication required\r\n", exchange("GROUP test.group"));
        assertEquals("480 Authentication required\r\n", exchange("LIST"));
        assertTrue(exchange("CAPABILITIES").startsWith("101 "));
        exchange("AUTHINFO USER alice");
        exchange("AUTHINFO PASS secret");
        assertEquals("211 1 1 1 test.group\r\n", exchange("GROUP test.group"));
    }
}
