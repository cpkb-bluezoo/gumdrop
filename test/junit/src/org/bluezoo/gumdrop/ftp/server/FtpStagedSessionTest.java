/*
 * FtpStagedSessionTest.java
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

package org.bluezoo.gumdrop.ftp.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpListener;
import org.bluezoo.gumdrop.ftp.FtpProtocolHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Drives the staged FTP session pipeline ({@link DefaultFtpHandler}) through
 * {@link FtpProtocolHandler} against an in-memory file system.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpStagedSessionTest {

    private Path root;
    private BasicFTPFileSystem fs;
    private DefaultFtpHandler session;
    private FtpProtocolHandler handler;
    private CaptureEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        fs = new BasicFTPFileSystem(root, false);
        session = new DefaultFtpHandler(fs, null, null, "Hello staged");
        handler = new FtpProtocolHandler(new FtpListener(), session);
        endpoint = new CaptureEndpoint();
        handler.connected(endpoint);
    }

    private String cmd(String command) {
        endpoint.sent.clear();
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
        StringBuilder sb = new StringBuilder();
        for (byte[] b : endpoint.sent) {
            sb.append(new String(b, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    private void expect(String code, String command) {
        String r = cmd(command);
        assertTrue(command + " -> " + r, r.startsWith(code));
    }

    @Test
    public void testGreetingUsesWelcomeMessage() {
        StringBuilder sb = new StringBuilder();
        for (byte[] b : endpoint.sent) {
            sb.append(new String(b, StandardCharsets.US_ASCII));
        }
        String s = sb.toString();
        assertTrue(s, s.startsWith("220"));
        assertTrue(s, s.contains("Hello staged"));
    }

    @Test
    public void testUserThenPassLogsIn() {
        expect("331", "USER alice");
        expect("230", "PASS secret");
        expect("257", "PWD");
        expect("257", "MKD sub");
        expect("250", "CWD sub");
        expect("250", "CDUP");
        assertEquals("alice", session.getFileSystem() == fs ? "alice" : "x");
    }

    @Test
    public void testEmptyPasswordRejected() {
        expect("331", "USER alice");
        String r = cmd("PASS  ");
        assertTrue(r, r.startsWith("530") || r.startsWith("501"));
    }

    @Test
    public void testSiteHelpAndUnknown() {
        expect("331", "USER alice");
        expect("230", "PASS secret");
        String r = cmd("SITE HELP");
        assertTrue(r, r.startsWith("2"));
        r = cmd("SITE FROB");
        assertTrue(r, r.startsWith("5"));
    }

    @Test
    public void testNoFileAccessBeforeLogin() {
        expect("530", "PWD");
        expect("530", "MKD x");
    }

    @Test
    public void testDisconnectedNotifiesSession() {
        handler.disconnected();
    }

    @Test
    public void testEvaluateAuthentication() {
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                session.evaluateAuthentication(null, null, null));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                session.evaluateAuthentication("  ", "x", null));
        assertEquals(FtpAuthenticationResult.NEED_PASSWORD,
                session.evaluateAuthentication("bob", null, null));
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                session.evaluateAuthentication("bob", "  ", null));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                session.evaluateAuthentication("bob", "pw", null));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                session.evaluateAuthentication("bob", null, "acct"));
    }

    @Test
    public void testHandleSiteCommandDirect() {
        assertEquals(FtpFileOperationResult.SUCCESS,
                session.handleSiteCommand("help me"));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                session.handleSiteCommand("zzz"));
    }

    @Test
    public void testTransferCallbacksDoNotThrow() {
        session.transferStarting("/a", true, 10);
        session.transferStarting("/a", false, -1);
        session.transferProgress("/a", true, ByteBuffer.allocate(1), 1024 * 1024);
        session.transferProgress("/a", false, ByteBuffer.allocate(1), 5);
        session.transferCompleted("/a", true, 10, true);
        session.transferCompleted("/a", false, 10, false);
        assertNull(session.getQuotaManager());
        session.disconnected();
    }

    @Test
    public void testAdapterDelegation() {
        org.bluezoo.gumdrop.ftp.FtpConnectionMetadata md =
                new org.bluezoo.gumdrop.ftp.FtpConnectionMetadata(
                        null, null, false, null, null, null, 0L, "ftp");
        AuthenticatedHandlerConnectionAdapter adapter =
                new AuthenticatedHandlerConnectionAdapter(session, md);
        assertNull(adapter.connected(md));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                adapter.authenticate("u", "p", null, md));
        assertSame(fs, adapter.getFileSystem(md));
        adapter.transferStarting("/x", true, 1, md);
        adapter.transferProgress("/x", true, ByteBuffer.allocate(1), 1, md);
        adapter.transferCompleted("/x", true, 1, true, md);
        assertEquals(FtpFileOperationResult.SUCCESS,
                adapter.handleSiteCommand("HELP", md));
        adapter.disconnected(md);
        assertNull(adapter.getQuotaManager());
        adapter.canStore("u", 10, md);
        adapter.getQuota("u", md);
        adapter.recordBytesAdded("u", 1, md);
        adapter.recordBytesRemoved("u", 1, md);
    }

    @Test(expected = NullPointerException.class)
    public void testAdapterNullHandler() {
        new AuthenticatedHandlerConnectionAdapter(null, null);
    }

    @Test
    public void testLegacyAdapter() {
        try {
            new LegacyConnectionHandlerAdapter(null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
        assertNull(LegacyConnectionHandlerAdapter.unwrap(session));
        LegacyConnectionHandlerAdapter a = new LegacyConnectionHandlerAdapter(
                new org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler(fs));
        a.connected(null, null);
        a.disconnected();
        assertNotNull(a.getDelegate());
        assertNotNull(a.getConnectionHandler());
    }

    @Test
    public void testAnonymousProvider() {
        AnonymousFtpSessionProvider p = FtpServerSessionProviders.anonymous();
        ClientConnected c = p.openSession(new FtpListener());
        assertNotNull(c);
        p.rootDirectory(root).welcomeMessage("  hi  ");
        p.start();
        c = p.openSession(new FtpListener());
        assertTrue(c instanceof LegacyConnectionHandlerAdapter);
        p.stop();
    }

    @Test
    public void testFileSystemProviderOptions() {
        FileSystemFtpSessionProvider p = FtpServerSessionProviders.fileSystem();
        p.rootDirectory(root).readOnly(true).welcomeMessage("w");
        p.start();
        ClientConnected c = p.openSession(new FtpListener());
        assertTrue(c instanceof DefaultFtpHandler);
        p.stop();
    }

    @Test
    public void testRoleBasedProviderValidation() {
        RoleBasedFtpSessionProvider p = FtpServerSessionProviders.roleBased();
        try {
            p.start();
            fail("expected ISE");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private FtpProtocolHandler realmSession() {
        DefaultFtpHandler h = new DefaultFtpHandler(fs, new TinyRealm());
        FtpProtocolHandler ph = new FtpProtocolHandler(new FtpListener(), h);
        endpoint = new CaptureEndpoint();
        ph.connected(endpoint);
        return ph;
    }

    @Test
    public void testRealmLoginSuccess() {
        handler = realmSession();
        expect("331", "USER alice");
        expect("230", "PASS wonderland");
        expect("257", "PWD");
    }

    @Test
    public void testRealmBadPassword() {
        handler = realmSession();
        expect("331", "USER alice");
        expect("530", "PASS wrong");
        expect("530", "PWD");
    }

    @Test
    public void testRealmUnknownUser() {
        handler = realmSession();
        expect("331", "USER nobody");
        expect("530", "PASS x");
    }

    @Test
    public void testRealmEvaluate() {
        DefaultFtpHandler h = new DefaultFtpHandler(fs, new TinyRealm());
        assertEquals(FtpAuthenticationResult.NEED_PASSWORD,
                h.evaluateAuthentication("alice", null, "acct"));
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                h.evaluateAuthentication("alice", "bad", null));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                h.evaluateAuthentication(" alice ", "wonderland", null));
        assertNull(h.getQuota("alice"));
        assertTrue(h.canStore("alice", 1));
    }

    @Test
    public void testRoleBasedProviderWithRealm() {
        RoleBasedFtpSessionProvider p = FtpServerSessionProviders.roleBased();
        p.realm(new TinyRealm()).rootDirectory(root).readOnly(false)
                .welcomeMessage("w").filesystemEnforcement(true);
        p.start();
        ClientConnected c = p.openSession(new FtpListener());
        assertTrue(c instanceof LegacyConnectionHandlerAdapter);
        p.stop();
        RoleBasedFtpSessionProvider p2 = FtpServerSessionProviders.roleBased();
        p2.realm(new TinyRealm()).fileSystem(fs);
        p2.start();
        assertNotNull(p2.openSession(new FtpListener()));
        RoleBasedFtpSessionProvider p3 = FtpServerSessionProviders.roleBased();
        p3.realm(new TinyRealm());
        try {
            p3.start();
            fail("expected ISE");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testFtpServerConfiguration() {
        FtpListener l = new FtpListener();
        FtpServer server = FtpServer.compose().listener(l)
                .realm(new TinyRealm()).requireTLSForData(true)
                .sessionProvider(FtpServerSessionProviders.fileSystem()
                        .rootDirectory(root))
                .server();
        assertTrue(server.isRequireTLSForData());
        assertNotNull(server.getRealm());
        server.setRequireTLSForData(false);
        assertFalse(server.isRequireTLSForData());
        server.setRealm(null);
        assertNull(server.getRealm());
        assertEquals(1, server.getListeners().size());
        List<Object> extra = new ArrayList<Object>();
        extra.add(new FtpListener());
        extra.add("not a listener");
        server.setListeners(extra);
        assertEquals(2, server.getListeners().size());
        FtpListener dyn = new FtpListener();
        server.addDynamicListener(dyn);
        assertEquals(3, server.getListeners().size());
        server.removeDynamicListener(dyn);
        assertEquals(2, server.getListeners().size());
        assertNotNull(server.openSession(l));
        assertNull(server.createHandler(l));
        server.stop();
    }

    @Test
    public void testFtpServerComposeValidation() {
        try {
            FtpServer.compose().server();
            fail("expected ISE");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            FtpServer.compose().listener(null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            FtpServer.compose().sessionProvider(null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
        FtpListener l = new FtpListener();
        l.sessionProvider(FtpServerSessionProviders.fileSystem());
        FtpServer s = FtpServer.compose().listener(l).server();
        assertNotNull(s.openSession(l));
        FtpServer s2 = FtpServer.compose().listener(new FtpListener())
                .sessionPerConnection(new java.util.function.Supplier<ClientConnected>() {
                    @Override
                    public ClientConnected get() {
                        return new DefaultFtpHandler(fs, null);
                    }
                }).build();
        assertNotNull(s2.openSession(null));
    }

    static class TinyRealm implements Realm {
        @Override public Realm forSelectorLoop(SelectorLoop loop) { return this; }
        @Override public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return new HashSet<SaslMechanism>();
        }
        @Override public boolean passwordMatch(String u, String p) {
            return "alice".equals(u) && "wonderland".equals(p);
        }
        @Override public String getDigestHA1(String u, String r) { return null; }
        @Override public String getPassword(String u) { return null; }
        @Override public boolean isUserInRole(String u, String r) { return true; }
    }

    static class CaptureEndpoint implements Endpoint {
        final List<byte[]> sent = new ArrayList<byte[]>();
        boolean open = true;

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sent.add(bytes);
        }

        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 21);
        }
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() {}
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() {}
                @Override public boolean isCancelled() { return false; }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) {}
        @Override public TelemetryConfig getTelemetryConfig() { return org.bluezoo.gumdrop.testsupport.StubTelemetry.CONFIG; }
        @Override public void pauseRead() {}
        @Override public void resumeRead() {}
        @Override public void onWriteReady(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
    }
}
