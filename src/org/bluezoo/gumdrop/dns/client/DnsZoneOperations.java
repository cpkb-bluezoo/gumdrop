/*
 * DnsZoneOperations.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client operations for zone transfer and maintenance (NOTIFY, UPDATE, AXFR).
 */
public final class DnsZoneOperations {

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private DnsZoneOperations() {
    }

    /**
     * Sends RFC 1996 NOTIFY for {@code zoneName} to {@code server}.
     */
    public static DnsMessage sendNotify(InetSocketAddress server, String zoneName)
            throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage notify = DnsMessage.createNotify(id, zoneName);
        return exchange(server, notify, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Sends a dynamic update and optionally signs it with TSIG.
     */
    public static DnsMessage sendUpdate(InetSocketAddress server, DnsMessage update,
                                        TsigKey tsigKey) throws IOException {
        DnsMessage out = update;
        if (tsigKey != null) {
            out = DnsTsig.sign(update, tsigKey);
        }
        return exchange(server, out, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Performs AXFR (RFC 5936) over UDP; suitable for small zones in tests.
     */
    public static List<DnsResourceRecord> axfr(InetSocketAddress server, String zoneName)
            throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsQuestion q = new DnsQuestion(zoneName, DnsType.AXFR, DnsClass.IN);
        DnsMessage query = new DnsMessage(id, DnsMessage.FLAG_RD,
                Collections.singletonList(q),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        DnsMessage response = exchange(server, query, DEFAULT_TIMEOUT_MS);
        if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
            throw new IOException("AXFR failed: rcode=" + response.getRcode());
        }
        return new ArrayList<DnsResourceRecord>(response.getAnswers());
    }

    /**
     * Synchronous UDP request/response exchange.
     */
    public static DnsMessage exchange(InetSocketAddress server, DnsMessage request,
                                      int timeoutMs) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(timeoutMs);
            ByteBuffer wire = request.serialize();
            byte[] out = new byte[wire.remaining()];
            wire.get(out);
            DatagramPacket packet = new DatagramPacket(out, out.length,
                    server.getAddress(), server.getPort());
            socket.send(packet);
            byte[] buf = new byte[65535];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            try {
                return DnsMessage.parse(ByteBuffer.wrap(buf, 0, in.getLength()));
            } catch (DnsFormatException e) {
                throw new IOException("invalid DNS response", e);
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("DNS timeout", e);
        } finally {
            socket.close();
        }
    }
}
