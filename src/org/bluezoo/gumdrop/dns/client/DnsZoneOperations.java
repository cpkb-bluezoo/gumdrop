/*
 * DnsZoneOperations.java
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

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTcpFraming;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client operations for zone transfer and maintenance (NOTIFY, UPDATE, AXFR, IXFR).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
        DnsMessage response = exchange(server, out, DEFAULT_TIMEOUT_MS);
        if (tsigKey != null && out.getTsigRecord() != null
                && !DnsTsig.verifyResponse(response, tsigKey, out)) {
            throw new IOException("TSIG verification failed on UPDATE response");
        }
        return response;
    }

    /**
     * Performs AXFR (RFC 5936). Tries UDP first; on truncation retries over TCP.
     */
    public static List<DnsResourceRecord> axfr(InetSocketAddress server, String zoneName)
            throws IOException {
        return axfr(server, zoneName, null);
    }

    /**
     * AXFR with optional TSIG signing and response verification.
     */
    public static List<DnsResourceRecord> axfr(InetSocketAddress server, String zoneName,
                                               TsigKey tsigKey) throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = createAxfrQuery(id, zoneName);
        if (tsigKey != null) {
            query = DnsTsig.sign(query, tsigKey);
        }
        DnsMessage response = exchange(server, query, DEFAULT_TIMEOUT_MS);
        if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
            throw new IOException("AXFR failed: rcode=" + response.getRcode());
        }
        if (response.isTruncated()) {
            return axfrOverTcp(server, zoneName, DEFAULT_TIMEOUT_MS, tsigKey, null);
        }
        if (tsigKey != null && query.getTsigRecord() != null
                && !DnsTsig.verifyResponse(response, tsigKey, query)) {
            throw new IOException("TSIG verification failed on AXFR response");
        }
        return new ArrayList<DnsResourceRecord>(response.getAnswers());
    }

    /**
     * Performs AXFR (RFC 5936) over TCP with length-prefixed framing (RFC 1035
     * section 4.2.2). Reads sequential response messages until the server closes
     * the connection.
     */
    public static List<DnsResourceRecord> axfrOverTcp(InetSocketAddress server,
                                                      String zoneName)
            throws IOException {
        return axfrOverTcp(server, zoneName, DEFAULT_TIMEOUT_MS);
    }

    /**
     * AXFR over TCP with a custom read timeout.
     */
    public static List<DnsResourceRecord> axfrOverTcp(InetSocketAddress server,
                                                      String zoneName,
                                                      int timeoutMs)
            throws IOException {
        return axfrOverTcp(server, zoneName, timeoutMs, null, null);
    }

    /**
     * AXFR over TCP or DoT ({@code transport} from {@link TcpDnsClientTransport},
     * e.g. {@link TcpDnsClientTransport#createDoT()}), optionally TSIG-signed.
     */
    public static List<DnsResourceRecord> axfrOverTcp(InetSocketAddress server,
                                                      String zoneName,
                                                      int timeoutMs,
                                                      TsigKey tsigKey,
                                                      TcpDnsClientTransport transport)
            throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = createAxfrQuery(id, zoneName);
        List<DnsMessage> messages = exchangeTransfer(server, query, timeoutMs,
                true, tsigKey, transport);
        return mergeTransferAnswers(messages);
    }

    /**
     * AXFR over DNS-over-TLS using the same TLS/SPKI settings as
     * {@link TcpDnsClientTransport}.
     */
    public static List<DnsResourceRecord> axfrOverTls(InetSocketAddress server,
                                                      String zoneName,
                                                      TcpDnsClientTransport transport)
            throws IOException {
        return axfrOverTcp(server, zoneName, DEFAULT_TIMEOUT_MS, null, transport);
    }

    /**
     * Performs IXFR (RFC 1995). Tries UDP first; on truncation retries over TCP.
     *
     * @param clientSoa the slave's current SOA for the zone (authority section)
     */
    public static List<DnsResourceRecord> ixfr(InetSocketAddress server, String zoneName,
                                               DnsResourceRecord clientSoa)
            throws IOException {
        return ixfr(server, zoneName, clientSoa, null);
    }

    /**
     * IXFR with optional TSIG.
     */
    public static List<DnsResourceRecord> ixfr(InetSocketAddress server, String zoneName,
                                               DnsResourceRecord clientSoa,
                                               TsigKey tsigKey) throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = createIxfrQuery(id, zoneName, clientSoa);
        if (tsigKey != null) {
            query = DnsTsig.sign(query, tsigKey);
        }
        DnsMessage response = exchange(server, query, DEFAULT_TIMEOUT_MS);
        if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
            throw new IOException("IXFR failed: rcode=" + response.getRcode());
        }
        if (response.isTruncated()) {
            return ixfrOverTcp(server, zoneName, clientSoa, DEFAULT_TIMEOUT_MS,
                    tsigKey, null);
        }
        if (tsigKey != null && query.getTsigRecord() != null
                && !DnsTsig.verifyResponse(response, tsigKey, query)) {
            throw new IOException("TSIG verification failed on IXFR response");
        }
        return new ArrayList<DnsResourceRecord>(response.getAnswers());
    }

    /**
     * IXFR over TCP until the server closes the connection.
     */
    public static List<DnsResourceRecord> ixfrOverTcp(InetSocketAddress server,
                                                      String zoneName,
                                                      DnsResourceRecord clientSoa)
            throws IOException {
        return ixfrOverTcp(server, zoneName, clientSoa, DEFAULT_TIMEOUT_MS);
    }

    public static List<DnsResourceRecord> ixfrOverTcp(InetSocketAddress server,
                                                      String zoneName,
                                                      DnsResourceRecord clientSoa,
                                                      int timeoutMs)
            throws IOException {
        return ixfrOverTcp(server, zoneName, clientSoa, timeoutMs, null, null);
    }

    public static List<DnsResourceRecord> ixfrOverTcp(InetSocketAddress server,
                                                      String zoneName,
                                                      DnsResourceRecord clientSoa,
                                                      int timeoutMs,
                                                      TsigKey tsigKey,
                                                      TcpDnsClientTransport transport)
            throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = createIxfrQuery(id, zoneName, clientSoa);
        List<DnsMessage> messages = exchangeTransfer(server, query, timeoutMs,
                true, tsigKey, transport);
        return mergeTransferAnswers(messages);
    }

    private static List<DnsResourceRecord> mergeTransferAnswers(
            List<DnsMessage> messages) throws IOException {
        List<DnsResourceRecord> records = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < messages.size(); i++) {
            DnsMessage msg = messages.get(i);
            if (msg.getRcode() != DnsMessage.RCODE_NOERROR) {
                throw new IOException("zone transfer failed: rcode=" + msg.getRcode());
            }
            records.addAll(msg.getAnswers());
        }
        if (records.isEmpty()) {
            throw new IOException("zone transfer returned no records");
        }
        return records;
    }

    private static List<DnsMessage> exchangeTransfer(InetSocketAddress server,
                                                     DnsMessage query,
                                                     int timeoutMs,
                                                     boolean readUntilClose,
                                                     TsigKey tsigKey,
                                                     TcpDnsClientTransport transport)
            throws IOException {
        DnsMessage request = query;
        if (tsigKey != null && query.getTsigRecord() == null) {
            request = DnsTsig.sign(query, tsigKey);
        }
        List<DnsMessage> messages;
        if (transport != null) {
            messages = transport.exchangeBlocking(server, request, timeoutMs,
                    readUntilClose);
        } else {
            messages = exchangeTcp(server, request, timeoutMs, readUntilClose);
        }
        if (tsigKey != null && request.getTsigRecord() != null) {
            if (messages.size() == 1) {
                if (!DnsTsig.verifyResponse(messages.get(0), tsigKey, request)) {
                    throw new IOException("TSIG verification failed on zone transfer");
                }
            } else if (!DnsTsig.verifyResponseSequence(messages, tsigKey, request)) {
                throw new IOException("TSIG verification failed on zone transfer");
            }
        }
        return messages;
    }

    private static DnsMessage createAxfrQuery(int id, String zoneName) {
        DnsQuestion q = new DnsQuestion(zoneName, DnsType.AXFR, DnsClass.IN);
        return new DnsMessage(id, DnsMessage.FLAG_RD,
                Collections.singletonList(q),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    private static DnsMessage createIxfrQuery(int id, String zoneName,
                                              DnsResourceRecord clientSoa) {
        DnsQuestion q = new DnsQuestion(zoneName, DnsType.IXFR, DnsClass.IN);
        return new DnsMessage(id, DnsMessage.FLAG_RD,
                Collections.singletonList(q),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(clientSoa),
                Collections.<DnsResourceRecord>emptyList());
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

    /**
     * Sends one DNS message over TCP and reads one or more framed replies.
     *
     * @param readUntilClose when {@code true}, reads messages until EOF after
     *                       the first reply (RFC 5936 AXFR)
     */
    static List<DnsMessage> exchangeTcp(InetSocketAddress server, DnsMessage request,
                                        int timeoutMs, boolean readUntilClose)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(server, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            return readWriteFramed(socket.getInputStream(), socket.getOutputStream(),
                    request, readUntilClose);
        } catch (SocketTimeoutException e) {
            throw new IOException("DNS TCP timeout", e);
        } finally {
            socket.close();
        }
    }

    static List<DnsMessage> readWriteFramed(InputStream in, OutputStream out,
                                            DnsMessage request,
                                            boolean readUntilClose)
            throws IOException {
        ByteBuffer wire = request.serialize();
        byte[] requestBytes = new byte[wire.remaining()];
        wire.get(requestBytes);
        DnsTcpFraming.writeFramed(out, requestBytes);

        List<DnsMessage> messages = new ArrayList<DnsMessage>();
        while (true) {
            byte[] frame = DnsTcpFraming.readFramed(in);
            if (frame == null) {
                break;
            }
            try {
                messages.add(DnsMessage.parse(ByteBuffer.wrap(frame)));
            } catch (DnsFormatException e) {
                throw new IOException("invalid DNS response", e);
            }
            if (!readUntilClose) {
                break;
            }
        }
        if (messages.isEmpty()) {
            throw new IOException("no DNS response on TCP");
        }
        return messages;
    }

}
