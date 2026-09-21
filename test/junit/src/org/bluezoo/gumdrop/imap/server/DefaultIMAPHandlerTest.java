/*
 * DefaultIMAPHandlerTest.java
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

package org.bluezoo.gumdrop.imap.server;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link DefaultIMAPHandler} authorisation policy.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultIMAPHandlerTest {

    @Test
    public void testConnectedSendsReadyBanner() {
        DefaultIMAPHandler handler = new DefaultIMAPHandler();
        AtomicReference<String> banner = new AtomicReference<String>();
        AtomicReference<NotAuthenticatedHandler> next =
                new AtomicReference<NotAuthenticatedHandler>();

        handler.connected(new ConnectedState() {
            @Override
            public void acceptConnection(String message, NotAuthenticatedHandler notAuthenticatedHandler) {
                banner.set(message);
                next.set(notAuthenticatedHandler);
            }

            @Override
            public void acceptPreauth(String greeting, AuthenticatedHandler authenticatedHandler) {
            }

            @Override
            public void rejectConnection() {
            }

            @Override
            public void rejectConnection(String message) {
            }

            @Override
            public void serverShuttingDown() {
            }
        }, new NoOpEndpoint());

        assertEquals("IMAP4rev2 server ready", banner.get());
        assertSame(handler, next.get());
    }

    @Test
    public void testAuthenticateProceedsWithHandler() {
        DefaultIMAPHandler handler = new DefaultIMAPHandler();
        AtomicReference<AuthenticatedHandler> next = new AtomicReference<AuthenticatedHandler>();

        handler.authenticate(new AuthenticateState() {
            @Override
            public void proceed(AuthenticatedHandler authenticatedHandler) {
                next.set(authenticatedHandler);
            }

            @Override
            public void accept(org.bluezoo.gumdrop.mailbox.MailboxStore store,
                               AuthenticatedHandler authenticatedHandler) {
            }

            @Override
            public void accept(String message, org.bluezoo.gumdrop.mailbox.MailboxStore store,
                               AuthenticatedHandler authenticatedHandler) {
            }

            @Override
            public void reject(String message, NotAuthenticatedHandler notAuthenticatedHandler) {
            }

            @Override
            public void rejectAndClose(String message) {
            }

            @Override
            public void serverShuttingDown() {
            }
        }, new Principal() {
            @Override
            public String getName() {
                return "user";
            }
        }, new MailboxFactory() {
            @Override
            public org.bluezoo.gumdrop.mailbox.MailboxStore createStore() {
                return null;
            }
        });

        assertSame(handler, next.get());
    }

    @Test
    public void testSelectProceedsWithHandler() {
        DefaultIMAPHandler handler = new DefaultIMAPHandler();
        AtomicReference<SelectedHandler> next = new AtomicReference<SelectedHandler>();

        handler.select(new MinimalSelectState() {
            @Override
            public void proceed(SelectedHandler selectedHandler) {
                next.set(selectedHandler);
            }
        }, null, "INBOX");

        assertSame(handler, next.get());
    }

    @Test
    public void testGetQuotaWithoutManagerIsNotSupported() {
        DefaultIMAPHandler handler = new DefaultIMAPHandler();
        AtomicReference<Boolean> notSupported = new AtomicReference<Boolean>();

        handler.getQuota(new MinimalQuotaState() {
            @Override
            public void quotaNotSupported(AuthenticatedHandler authenticatedHandler) {
                notSupported.set(Boolean.TRUE);
            }
        }, null, null, "ROOT");

        assertEquals(Boolean.TRUE, notSupported.get());
    }

    @Test
    public void testGetQuotaProceedsWhenManagerPresent() {
        DefaultIMAPHandler handler = new DefaultIMAPHandler();
        AtomicReference<AuthenticatedHandler> next =
                new AtomicReference<AuthenticatedHandler>();

        handler.getQuota(new MinimalQuotaState() {
            @Override
            public void proceed(AuthenticatedHandler authenticatedHandler) {
                next.set(authenticatedHandler);
            }
        }, new RoleBasedQuotaManager(), null, "ROOT");

        assertSame(handler, next.get());
    }

    private abstract static class MinimalSelectState implements SelectState {
        @Override public void selectOk(Mailbox mailbox, boolean readOnly,
                java.util.Set<org.bluezoo.gumdrop.mailbox.Flag> flags,
                java.util.Set<org.bluezoo.gumdrop.mailbox.Flag> permanentFlags,
                int exists, int recent, long uidValidity, long uidNext,
                SelectedHandler handler) { }
        @Override public void selectOk(Mailbox mailbox, boolean readOnly,
                java.util.Set<org.bluezoo.gumdrop.mailbox.Flag> flags,
                SelectedHandler handler) { }
        @Override public void selectFailed(String message, AuthenticatedHandler handler) { }
        @Override public void mailboxNotFound(String message, AuthenticatedHandler handler) { }
        @Override public void accessDenied(String message, AuthenticatedHandler handler) { }
        @Override public void no(String message, AuthenticatedHandler handler) { }
        @Override public void serverShuttingDown() { }
    }

    private abstract static class MinimalQuotaState implements QuotaState {
        @Override public void proceed(AuthenticatedHandler handler) { }
        @Override public void quotaNotSupported(AuthenticatedHandler handler) { }
        @Override public void sendQuota(String quotaRoot, java.util.Map<String, long[]> resources,
                AuthenticatedHandler handler) { }
        @Override public void sendQuotaRoots(String mailboxName, java.util.List<String> quotaRoots,
                java.util.Map<String, java.util.Map<String, long[]>> quotas,
                AuthenticatedHandler handler) { }
        @Override public void quotaFailed(String message, AuthenticatedHandler handler) { }
        @Override public void serverShuttingDown() { }
    }

    private static final class NoOpEndpoint implements Endpoint {
        @Override public void send(ByteBuffer buf) {}
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() {}
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() throws IOException {}
        @Override public void pauseRead() {}
        @Override public void resumeRead() {}
        @Override public void onWriteReady(Runnable callback) {}
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void setTrace(Trace trace) {}
        @Override public Trace getTrace() { return null; }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    }
}
