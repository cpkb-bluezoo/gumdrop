/*
 * FtpMiscTest.java
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

package org.bluezoo.gumdrop.ftp;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.junit.Test;

import org.bluezoo.gumdrop.ftp.server.ClientConnected;
import org.bluezoo.gumdrop.ftp.server.FtpServer;
import org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider;
import org.bluezoo.gumdrop.ftp.server.FtpServerSessionProviders;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * Tests for FTP metrics, listener configuration, connection metadata and
 * the default methods of {@link FtpConnectionHandler} and
 * {@link FtpFileSystem}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpMiscTest {

    @Test
    public void testMetricsAcceptAllUpdates() {
        FtpServerMetrics m = new FtpServerMetrics(new TelemetryConfig());
        m.connectionOpened();
        m.dataConnectionOpened();
        m.commandExecuted("RETR");
        m.authAttempt();
        m.authSuccess();
        m.authFailure();
        m.fileUploaded(100, 5.0);
        m.fileDownloaded(200, 6.0);
        m.directoryListed("LIST");
        m.directoryCreated();
        m.fileDeleted();
        m.authTlsUpgraded();
        m.dataConnectionClosed();
        m.connectionClosed(100.0);
    }

    @Test
    public void testListenerSettersAndFluentApi() {
        FtpListener l = new FtpListener();
        assertSame(l, l.port(2121));
        assertEquals(2121, l.getPort());
        assertSame(l, l.bindWildcard());
        assertSame(l, l.addresses(new java.net.InetAddress[0]));
        assertSame(l, l.secure(false));
        l.requireTLSForData(true);
        assertTrue(l.isRequireTLSForData());
        l.allowActiveModeBounce(true);
        assertTrue(l.isAllowActiveModeBounce());
        l.pasvMinPort(5000);
        l.pasvMaxPort(5010);
        assertEquals(5000, l.getPasvMinPort());
        assertEquals(5010, l.getPasvMaxPort());
        assertNull(l.getRealm());
        assertFalse(l.isSTARTTLSAvailable());
        assertNull(l.getMetrics());
        l.stop();
        assertNull(l.getServer());
        assertNull(l.getHandlerFactory());
        l.handlerFactory(null);
    }

    @Test
    public void testOpenApplicationSessionWithoutProviders() {
        FtpListener l = new FtpListener();
        assertNull(l.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionProviderFailureFallsBack() {
        FtpListener l = new FtpListener();
        l.sessionProvider(new FtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(
                    org.bluezoo.gumdrop.TcpListener listener) {
                throw new IllegalStateException("boom");
            }
        });
        assertNull(l.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionViaServer() {
        FtpListener l = new FtpListener();
        FtpServer server = FtpServer.compose().listener(l)
                .sessionProvider(FtpServerSessionProviders.fileSystem())
                .server();
        l.server(server);
        assertSame(server, l.getServer());
        ClientConnected c = l.openApplicationSession();
        assertNotNull(c);
    }

    @Test
    public void testOpenApplicationSessionServerFailure() {
        FtpListener l = new FtpListener();
        FtpServer server = FtpServer.compose().listener(l)
                .sessionProvider(new FtpServerSessionProvider() {
                    @Override
                    public ClientConnected openSession(
                            org.bluezoo.gumdrop.TcpListener listener) {
                        throw new IllegalStateException("boom");
                    }
                }).server();
        l.server(server);
        assertNull(l.openApplicationSession());
    }

    @Test
    public void testCreateHandlerVariants() {
        FtpListener l = new FtpListener();
        assertNotNull(l.createHandler());
        l.handlerFactory(new FtpConnectionHandlerFactory() {
            @Override
            public FtpConnectionHandler createHandler() throws Exception {
                return new StubHandler();
            }
        });
        assertNotNull(l.createHandler());
        l.handlerFactory(new FtpConnectionHandlerFactory() {
            @Override
            public FtpConnectionHandler createHandler() throws Exception {
                throw new Exception("nope");
            }
        });
        assertNotNull(l.createHandler());

        FtpListener l2 = new FtpListener();
        l2.sessionProvider(FtpServerSessionProviders.fileSystem());
        assertNotNull(l2.createHandler());

        FtpListener l3 = new FtpListener();
        FtpServer server = FtpServer.compose().listener(l3)
                .sessionProvider(FtpServerSessionProviders.connectionHandler(
                        new java.util.function.Supplier<FtpConnectionHandler>() {
                            @Override
                            public FtpConnectionHandler get() {
                                return new StubHandler();
                            }
                        })).server();
        l3.server(server);
        assertNotNull(l3.createHandler());
    }

    @Test
    public void testMetadataAccessors() {
        FtpConnectionMetadata md = new FtpConnectionMetadata(
                new InetSocketAddress("127.0.0.1", 4000),
                new InetSocketAddress("127.0.0.1", 21),
                false, null, null, null, 1000L, "ftp");
        assertEquals(21, md.getServerAddress().getPort());
        assertEquals(4000, md.getClientAddress().getPort());
        assertFalse(md.isSecureConnection());
        assertEquals("ftp", md.getConnectorDescription());
        assertEquals(1000L, md.getConnectionStartTimeMillis());
        assertTrue(md.getConnectionDurationMillis() >= 0);
        assertTrue(md.isStandardFTPPort());
        assertFalse(md.isSecureFTPPort());
        assertFalse(md.isAuthenticated());
        assertNull(md.getAuthenticatedUser());
        md.setAuthenticated(true);
        md.setAuthenticatedUser("u");
        assertTrue(md.isAuthenticated());
        assertEquals("u", md.getAuthenticatedUser());
        md.setCurrentDirectory("/x");
        assertEquals("/x", md.getCurrentDirectory());
        md.setTransferMode(FtpConnectionMetadata.FtpTransferMode.STREAM);
        assertEquals(FtpConnectionMetadata.FtpTransferMode.STREAM,
                md.getTransferMode());
        md.setTransferType(FtpConnectionMetadata.FtpTransferType.BINARY);
        assertEquals(FtpConnectionMetadata.FtpTransferType.BINARY,
                md.getTransferType());
        md.setDataConnection("10.0.0.1", 2000, true);
        assertEquals("10.0.0.1", md.getDataHost());
        assertEquals(2000, md.getDataPort());
        assertTrue(md.isPassiveMode());
        md.setLocalByteSize(8);
        assertEquals(8, md.getLocalByteSize());
        md.setSecureConnection(true);
        md.setCipherSuite("C");
        md.setProtocolVersion("TLSv1.3");
        md.setClientCertificates(null);
        assertTrue(md.isSecureConnection());
        assertEquals("C", md.getCipherSuite());
        assertEquals("TLSv1.3", md.getProtocolVersion());
        assertNull(md.getClientCertificates());
        md.setClientAddress(new InetSocketAddress("127.0.0.1", 1));
        md.setServerAddress(new InetSocketAddress("127.0.0.1", 990));
        assertFalse(md.isStandardFTPPort());
        assertTrue(md.isSecureFTPPort());
        md.setSiteCommandResponse("resp");
        assertEquals("resp", md.getSiteCommandResponse());
        md.clearSiteCommandResponse();
        assertNull(md.getSiteCommandResponse());
        FtpConnectionMetadata.FtpTransferMode[] modes =
                FtpConnectionMetadata.FtpTransferMode.values();
        assertTrue(modes.length > 0);
        FtpConnectionMetadata.FtpTransferType[] types =
                FtpConnectionMetadata.FtpTransferType.values();
        assertTrue(types.length > 0);
    }

    @Test
    public void testHandlerDefaultsWithoutQuota() {
        StubHandler h = new StubHandler();
        assertTrue(h.isAuthorized(FtpOperation.READ, "/", null));
        assertNull(h.getQuotaManager());
        assertTrue(h.canStore("u", 10, null));
        assertNull(h.getQuota("u", null));
        h.recordBytesAdded("u", 1, null);
        h.recordBytesRemoved("u", 1, null);
    }

    @Test
    public void testHandlerDefaultsWithQuota() {
        final StubQuota q = new StubQuota();
        StubHandler h = new StubHandler() {
            @Override
            public QuotaManager getQuotaManager() {
                return q;
            }
        };
        assertFalse(h.canStore("u", 10, null));
        assertNull(h.getQuota("u", null));
        h.recordBytesAdded("u", 5, null);
        h.recordBytesRemoved("u", 3, null);
        assertEquals(5L, q.added);
        assertEquals(3L, q.removed);
        assertEquals(1, q.canStoreCalls);
    }

    @Test
    public void testFileSystemDefaults() {
        FtpFileSystem fs = new FtpFileSystem() {
            public java.util.List<FtpFileInfo> listDirectory(String p,
                    FtpConnectionMetadata m) { return null; }
            public DirectoryChangeResult changeDirectory(String p, String c,
                    FtpConnectionMetadata m) { return null; }
            public FtpFileInfo getFileInfo(String p, FtpConnectionMetadata m) {
                return null;
            }
            public FtpFileOperationResult createDirectory(String p,
                    FtpConnectionMetadata m) { return null; }
            public FtpFileOperationResult removeDirectory(String p,
                    FtpConnectionMetadata m) { return null; }
            public FtpFileOperationResult deleteFile(String p,
                    FtpConnectionMetadata m) { return null; }
            public FtpFileOperationResult rename(String a, String b,
                    FtpConnectionMetadata m) { return null; }
            public java.nio.channels.ReadableByteChannel openForReading(
                    String p, long o, FtpConnectionMetadata m) { return null; }
            public java.nio.channels.WritableByteChannel openForWriting(
                    String p, boolean a, FtpConnectionMetadata m) {
                return null;
            }
            public UniqueNameResult generateUniqueName(String b, String s,
                    FtpConnectionMetadata m) { return null; }
        };
        assertNull(fs.resolvePathForAsyncRead("/", 0, null));
        assertNull(fs.resolvePathForAsyncWrite("/", false, null));
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.allocateSpace("/", 1, null));
        FtpFileSystem.UniqueNameResult r = new FtpFileSystem.UniqueNameResult(
                FtpFileOperationResult.SUCCESS, "/u");
        assertEquals("/u", r.getUniquePath());
        assertEquals(FtpFileOperationResult.SUCCESS, r.getResult());
    }

    static class StubHandler implements FtpConnectionHandler {
        @Override public String connected(FtpConnectionMetadata m) { return null; }
        @Override public void authenticate(String u, String p,
                String a, FtpConnectionMetadata m,
                org.bluezoo.gumdrop.auth.RealmCallback<FtpAuthenticationResult> cb) {
            cb.completed(FtpAuthenticationResult.SUCCESS);
        }
        @Override public FtpFileSystem getFileSystem(FtpConnectionMetadata m) {
            return null;
        }
        @Override public void transferStarting(String p, boolean up, long s,
                FtpConnectionMetadata m) { }
        @Override public void transferProgress(String p, boolean up,
                ByteBuffer d, long t, FtpConnectionMetadata m) { }
        @Override public void transferCompleted(String p, boolean up, long t,
                boolean ok, FtpConnectionMetadata m) { }
        @Override public FtpFileOperationResult handleSiteCommand(String c,
                FtpConnectionMetadata m) {
            return FtpFileOperationResult.NOT_SUPPORTED;
        }
        @Override public void disconnected(FtpConnectionMetadata m) { }
    }

    static class StubQuota implements QuotaManager {
        long added;
        long removed;
        int canStoreCalls;
        @Override public Quota getQuota(String u) { return null; }
        @Override public void recalculateUsage(String u) { }
        @Override public boolean canStore(String u, long b) {
            canStoreCalls++;
            return false;
        }
        @Override public boolean canStoreMessage(String u) { return true; }
        @Override public void recordBytesAdded(String u, long b) { added += b; }
        @Override public void recordBytesRemoved(String u, long b) { removed += b; }
        @Override public void recordMessageAdded(String u, long s) { }
        @Override public void recordMessageRemoved(String u, long s) { }
        @Override public void setUserQuota(String u, long s, long m) { }
        @Override public void loadUsageData() { }
        @Override public void saveUsageData() { }
        @Override public void clearUserQuota(String u) { }
        @Override public boolean hasUserQuota(String u) { return false; }
    }
}
