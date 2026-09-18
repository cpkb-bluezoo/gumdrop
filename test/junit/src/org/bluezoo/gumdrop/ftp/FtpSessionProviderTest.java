/*
 * FtpSessionProviderTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.server.ClientConnected;
import org.bluezoo.gumdrop.ftp.server.DefaultFtpHandler;
import org.bluezoo.gumdrop.ftp.server.LegacyConnectionHandlerAdapter;
import org.bluezoo.gumdrop.ftp.server.FileSystemFtpSessionProvider;
import org.bluezoo.gumdrop.ftp.server.FtpServer;
import org.bluezoo.gumdrop.ftp.server.FtpServerSessionProviders;
import org.junit.Test;

import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * FTP server session-provider composition (staged handler SPI).
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

}
