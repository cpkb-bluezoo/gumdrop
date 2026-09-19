/*
 * DnsZoneOperationsAxfrTcpTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
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

public class DnsZoneOperationsAxfrTcpTest {

    @Test
    public void testAxfrOverTcpMultiMessage() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        try {
            final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            final CountDownLatch ready = new CountDownLatch(1);
            Thread serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ready.countDown();
                        serveAxfr(serverSocket);
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));

            InetSocketAddress addr = new InetSocketAddress("127.0.0.1",
                    serverSocket.getLocalPort());
            List<DnsResourceRecord> records = DnsZoneOperations.axfrOverTcp(addr,
                    "example.com.", 5000);
            serverThread.join(5000);
            if (error.get() != null) {
                throw new Exception(error.get());
            }

            assertTrue(records.size() >= 3);
            assertEquals(DnsType.SOA, records.get(0).getType());
            assertEquals(DnsType.SOA, records.get(records.size() - 1).getType());
            boolean sawWww = false;
            for (int i = 0; i < records.size(); i++) {
                if ("www.example.com".equals(records.get(i).getName())
                        && records.get(i).getType() == DnsType.A) {
                    sawWww = true;
                }
            }
            assertTrue(sawWww);
        } finally {
            serverSocket.close();
        }
    }

    private static void serveAxfr(ServerSocket serverSocket)
            throws IOException, DnsFormatException {
        Socket client = serverSocket.accept();
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] queryFrame = readFramed(in);
            assertNotNull(queryFrame);
            DnsMessage query = DnsMessage.parse(ByteBuffer.wrap(queryFrame));
            assertEquals(DnsType.AXFR, query.getQuestions().get(0).getType());

            DnsResourceRecord soa = DnsResourceRecord.soa("example.com.", 300,
                    "ns1.example.com.", "host.example.com.", 1, 7200, 3600,
                    1209600, 300);
            DnsResourceRecord ns = DnsResourceRecord.ns("example.com.", 300,
                    "ns1.example.com.");
            DnsResourceRecord a = DnsResourceRecord.a("www.example.com.", 300,
                    java.net.InetAddress.getByName("192.0.2.1"));

            writeFramed(out, response(query.getId(), Collections.singletonList(soa)));
            List<DnsResourceRecord> chunk = new ArrayList<DnsResourceRecord>();
            chunk.add(ns);
            chunk.add(a);
            chunk.add(soa);
            writeFramed(out, response(query.getId(), chunk));
        } finally {
            client.close();
        }
    }

    private static DnsMessage response(int id, List<DnsResourceRecord> answers) {
        DnsQuestion q = new DnsQuestion("example.com.", DnsType.AXFR, DnsClass.IN);
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
