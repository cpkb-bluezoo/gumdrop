/*
 * DnsZoneOperationsIxfrTcpTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsZoneOperationsIxfrTcpTest {

    private Gumdrop gumdrop;

    @Before
    public void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create()
                .workerThreads(1)
                .drainTimeoutMs(0));
    }

    @After
    public void shutdownGumdrop() {
        if (gumdrop != null) {
            gumdrop.shutdown();
        }
    }

    @Test
    public void testIxfrOverTcpMultiMessage() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        try {
            final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            final CountDownLatch ready = new CountDownLatch(1);
            Thread serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ready.countDown();
                        serveIxfr(serverSocket);
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));

            DnsResourceRecord clientSoa = DnsResourceRecord.soa("example.com.", 300,
                    "ns1.example.com.", "host.example.com.", 1, 7200, 3600,
                    1209600, 300);
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1",
                    serverSocket.getLocalPort());
            SelectorLoop loop = gumdrop.nextWorkerLoop();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<List<DnsResourceRecord>> records =
                    new AtomicReference<List<DnsResourceRecord>>();
            final AtomicReference<Exception> clientError =
                    new AtomicReference<Exception>();
            DnsZoneClient.ixfrOverTcp(loop, addr, "example.com.", clientSoa, 5000,
                    null, null, new DnsZoneClient.TransferCallback() {
                        @Override
                        public void onSuccess(List<DnsResourceRecord> result) {
                            records.set(result);
                            done.countDown();
                        }

                        @Override
                        public void onFailure(Exception error) {
                            clientError.set(error);
                            done.countDown();
                        }
                    });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            serverThread.join(5000);
            if (error.get() != null) {
                throw new Exception(error.get());
            }
            if (clientError.get() != null) {
                throw clientError.get();
            }

            List<DnsResourceRecord> got = records.get();
            assertNotNull(got);
            assertTrue(got.size() >= 2);
            assertEquals(DnsType.SOA, got.get(0).getType());
            assertEquals(DnsType.SOA, got.get(got.size() - 1).getType());
            boolean sawDelta = false;
            for (int i = 0; i < got.size(); i++) {
                if ("www.example.com".equals(got.get(i).getName())
                        && got.get(i).getType() == DnsType.A) {
                    sawDelta = true;
                }
            }
            assertTrue(sawDelta);
        } finally {
            serverSocket.close();
        }
    }

    private static void serveIxfr(ServerSocket serverSocket)
            throws IOException, DnsFormatException {
        Socket client = serverSocket.accept();
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] queryFrame = readFramed(in);
            assertNotNull(queryFrame);
            DnsMessage query = DnsMessage.parse(ByteBuffer.wrap(queryFrame));
            assertEquals(DnsType.IXFR, query.getQuestions().get(0).getType());
            assertFalse(query.getAuthorities().isEmpty());
            assertEquals(DnsType.SOA, query.getAuthorities().get(0).getType());

            DnsResourceRecord soaStart = DnsResourceRecord.soa("example.com.", 0,
                    "ns1.example.com.", "host.example.com.", 2, 7200, 3600,
                    1209600, 300);
            DnsResourceRecord a = DnsResourceRecord.a("www.example.com.", 300,
                    java.net.InetAddress.getByName("192.0.2.1"));
            DnsResourceRecord soaEnd = DnsResourceRecord.soa("example.com.", 300,
                    "ns1.example.com.", "host.example.com.", 2, 7200, 3600,
                    1209600, 300);

            writeFramed(out, response(query.getId(), Collections.singletonList(soaStart)));
            List<DnsResourceRecord> chunk = new ArrayList<DnsResourceRecord>();
            chunk.add(a);
            chunk.add(soaEnd);
            writeFramed(out, response(query.getId(), chunk));
        } finally {
            client.close();
        }
    }

    private static DnsMessage response(int id, List<DnsResourceRecord> answers) {
        DnsQuestion q = new DnsQuestion("example.com.", DnsType.IXFR, DnsClass.IN);
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_AA;
        return new DnsMessage(id, flags, Collections.singletonList(q), answers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    private static void writeFramed(OutputStream out, DnsMessage message)
            throws IOException {
        ByteBuffer wire = message.serialize();
        byte[] bytes = new byte[wire.remaining()];
        wire.get(bytes);
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
        out.flush();
    }

    private static byte[] readFramed(InputStream in) throws IOException {
        int hi = in.read();
        if (hi < 0) {
            return null;
        }
        int lo = in.read();
        int len = ((hi & 0xFF) << 8) | (lo & 0xFF);
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new IOException("truncated frame");
            }
            off += n;
        }
        return buf;
    }
}
