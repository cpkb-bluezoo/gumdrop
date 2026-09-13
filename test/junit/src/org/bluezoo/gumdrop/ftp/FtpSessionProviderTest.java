/*
 * FtpSessionProviderTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.ftp.client.FtpClient;
import org.bluezoo.gumdrop.ftp.client.FtpClientSessionProvider;
import org.bluezoo.gumdrop.ftp.client.FtpClientSessionProviders;
import org.bluezoo.gumdrop.ftp.client.handler.RemoteGreeting;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.handler.ClientConnected;
import org.bluezoo.gumdrop.ftp.handler.DefaultFtpHandler;
import org.bluezoo.gumdrop.ftp.handler.LegacyConnectionHandlerAdapter;
import org.bluezoo.gumdrop.ftp.server.FileSystemFtpSessionProvider;
import org.bluezoo.gumdrop.ftp.server.FtpServer;
import org.bluezoo.gumdrop.ftp.server.FtpServerSessionProviders;
import org.junit.Test;

import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FTP session-provider composition (server and client staged handler SPI).
 */
public class FtpSessionProviderTest {

    @Test
    public void testFileSystemSessionProvider() throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("ftp-test")
                .toRealPath();
        ClientConnected session = FtpServerSessionProviders.fileSystem()
                .rootDirectory(root)
                .openSession(new FtpListener());
        assertTrue(session instanceof DefaultFtpHandler);
    }

    @Test
    public void testLegacyConnectionHandlerProvider() {
        ClientConnected session = FtpServerSessionProviders.connectionHandler(
                () -> new org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler(
                        new BasicFTPFileSystem(
                                java.nio.file.Path.of("/tmp"), false)))
                .openSession(new FtpListener());
        assertTrue(session instanceof LegacyConnectionHandlerAdapter);
        assertNotNull(LegacyConnectionHandlerAdapter.unwrap(session));
    }

    @Test
    public void testListenerSessionProviderWiring() {
        FileSystemFtpSessionProvider provider =
                FtpServerSessionProviders.fileSystem();
        FtpListener listener = new FtpListener().sessionProvider(provider);
        assertSame(provider, listener.getSessionProvider());
    }

    @Test
    public void testEmptyComposedFtpServer() {
        FtpServer server = FtpServer.compose()
                .listener(new FtpListener())
                .server();
        assertNotNull(server);
        assertNull(server.openSession(new FtpListener()));
    }

    @Test
    public void testFtpClientSessionProviderPerSession() {
        FtpClientSessionProvider provider =
                FtpClientSessionProviders.perSession(this::noopRemoteGreeting);
        assertNotNull(provider.openSession());
        assertNotNull(provider.openSession());
    }

    @Test
    public void testFtpClientSessionProviderWiring() {
        FtpClientSessionProvider provider =
                FtpClientSessionProviders.perSession(this::noopRemoteGreeting);
        FtpClient client = new FtpClient("ftp.example.com", 21)
                .sessionProvider(provider);
        assertSame(provider, client.getSessionProvider());
    }

    @Test
    public void testFtpClientConnectRequiresSessionProvider() {
        FtpClient client = new FtpClient("ftp.example.com", 21);
        try {
            client.connect();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("sessionProvider"));
        }
    }

    private RemoteGreeting noopRemoteGreeting() {
        return new RemoteGreeting() {
            @Override
            public void handleGreeting(
                    org.bluezoo.gumdrop.ftp.client.handler.ClientLoginState login,
                    String message) {
            }

            @Override
            public void handleServiceUnavailable(String message) {
            }

            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(
                    org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
            }

            @Override
            public void onDisconnected() {
            }
        };
    }

}
