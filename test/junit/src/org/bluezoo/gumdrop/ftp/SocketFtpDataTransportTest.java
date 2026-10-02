/*
 * SocketFtpDataTransportTest.java
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.util.AsyncFile;

import static org.junit.Assert.*;

/**
 * Tests the parts of the production {@link SocketFtpDataTransport} that need
 * no real socket: the missing accept loop, wrapping a data socket and opening
 * a file on a file system without asynchronous channels. Listening, outbound
 * connects and loop registration are covered by the integration tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocketFtpDataTransportTest {

    private static final class Probe implements ProtocolHandler {
        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
        }
    }

    @Test
    public void listenWithoutAnAcceptLoopFails() {
        SocketFtpDataTransport transport = new SocketFtpDataTransport();
        FtpListener listener = new FtpListener();
        Gumdrop gumdrop = TestGumdrop.create();
        listener.start(gumdrop);
        try {
            transport.listenPassive(listener, 0, null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("accept loop"));
        }
    }

    @Test
    public void listenWithoutAListenerFails() {
        SocketFtpDataTransport transport = new SocketFtpDataTransport();
        try {
            transport.listenPassive(null, 0, null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("accept loop"));
        }
    }

    @Test
    public void plaintextDataSocketIsWrappedInATcpEndpoint() throws Exception {
        SocketFtpDataTransport transport = new SocketFtpDataTransport();
        StubSocketChannel channel = new StubSocketChannel(
                new InetSocketAddress("127.0.0.1", 40000),
                new InetSocketAddress("127.0.0.1", 50000));
        Endpoint endpoint = transport.createDataEndpoint(channel, new Probe(), null);
        assertTrue(endpoint instanceof TcpEndpoint);
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void filesOnAnyFileSystemAreOpenedAsAsyncFiles() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path file = mem.getPath("/f.bin");
        Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));
        SocketFtpDataTransport transport = new SocketFtpDataTransport();
        AsyncFile open = transport.openFile(null, file, StandardOpenOption.READ);
        assertEquals(3L, open.size());
        open.close();
    }
}
