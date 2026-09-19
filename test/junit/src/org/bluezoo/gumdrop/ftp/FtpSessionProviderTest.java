/*
 * FtpSessionProviderTest.java
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
                new java.util.function.Supplier<org.bluezoo.gumdrop.ftp.FtpConnectionHandler>() {
                    @Override
                    public org.bluezoo.gumdrop.ftp.FtpConnectionHandler get() {
                        return new org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler(
                                new BasicFTPFileSystem(
                                        java.nio.file.Path.of("/tmp"), false));
                    }
                })
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
