/*
 * DnsZoneClient.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.TsigKey;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Non-blocking zone maintenance and transfer (NOTIFY, UPDATE, AXFR, IXFR)
 * using {@link UdpDnsClientTransport} and {@link TcpDnsClientTransport}
 * on a {@link SelectorLoop}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsZoneClient {

    public static final int DEFAULT_TIMEOUT_MS = 5000;

    private DnsZoneClient() {
    }

    public interface MessageCallback {
        void onSuccess(DnsMessage response);

        void onFailure(Exception error);
    }

    public interface TransferCallback {
        void onSuccess(List<DnsResourceRecord> records);

        void onFailure(Exception error);
    }

    public static void sendNotify(SelectorLoop loop, InetSocketAddress server,
            String zoneName, MessageCallback callback) {
        sendNotify(loop, server, zoneName, DEFAULT_TIMEOUT_MS, callback);
    }

    public static void sendNotify(SelectorLoop loop, InetSocketAddress server,
            String zoneName, int timeoutMs, MessageCallback callback) {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage notify = DnsMessage.createNotify(id, zoneName);
        exchangeUdp(loop, server, notify, timeoutMs, null, callback);
    }

    public static void sendUpdate(SelectorLoop loop, InetSocketAddress server,
            DnsMessage update, TsigKey tsigKey, MessageCallback callback) {
        sendUpdate(loop, server, update, tsigKey, DEFAULT_TIMEOUT_MS, callback);
    }

    public static void sendUpdate(SelectorLoop loop, InetSocketAddress server,
            DnsMessage update, TsigKey tsigKey, int timeoutMs,
            MessageCallback callback) {
        DnsMessage out = update;
        if (tsigKey != null) {
            try {
                out = DnsTsig.sign(update, tsigKey);
            } catch (IOException e) {
                callback.onFailure(e);
                return;
            }
        }
        final DnsMessage signed = out;
        final TsigKey key = tsigKey;
        exchangeUdp(loop, server, signed, timeoutMs, key,
                new MessageCallback() {
                    @Override
                    public void onSuccess(DnsMessage response) {
                        if (key != null && signed.getTsigRecord() != null
                                && !DnsTsig.verifyResponse(response, key, signed)) {
                            callback.onFailure(new IOException(
                                    "TSIG verification failed on UPDATE response"));
                            return;
                        }
                        callback.onSuccess(response);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        callback.onFailure(error);
                    }
                });
    }

    public static void axfr(SelectorLoop loop, InetSocketAddress server,
            String zoneName, TransferCallback callback) {
        axfr(loop, server, zoneName, null, callback);
    }

    public static void axfr(SelectorLoop loop, InetSocketAddress server,
            String zoneName, TsigKey tsigKey, TransferCallback callback) {
        axfr(loop, server, zoneName, tsigKey, DEFAULT_TIMEOUT_MS, null, callback);
    }

    public static void axfr(SelectorLoop loop, InetSocketAddress server,
            String zoneName, TsigKey tsigKey, int timeoutMs,
            TcpDnsClientTransport transport, TransferCallback callback) {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = DnsZoneOperations.createAxfrQuery(id, zoneName);
        if (tsigKey != null) {
            try {
                query = DnsTsig.sign(query, tsigKey);
            } catch (IOException e) {
                callback.onFailure(e);
                return;
            }
        }
        final DnsMessage signedQuery = query;
        final TsigKey key = tsigKey;
        exchangeUdp(loop, server, signedQuery, timeoutMs, key,
                new MessageCallback() {
                    @Override
                    public void onSuccess(DnsMessage response) {
                        if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
                            callback.onFailure(new IOException(
                                    "AXFR failed: rcode=" + response.getRcode()));
                            return;
                        }
                        if (response.isTruncated()) {
                            axfrOverTcp(loop, server, zoneName, timeoutMs, key,
                                    transport, callback);
                            return;
                        }
                        if (key != null && signedQuery.getTsigRecord() != null
                                && !DnsTsig.verifyResponse(response, key,
                                        signedQuery)) {
                            callback.onFailure(new IOException(
                                    "TSIG verification failed on AXFR response"));
                            return;
                        }
                        callback.onSuccess(
                                new ArrayList<DnsResourceRecord>(response.getAnswers()));
                    }

                    @Override
                    public void onFailure(Exception error) {
                        callback.onFailure(error);
                    }
                });
    }

    public static void axfrOverTcp(SelectorLoop loop, InetSocketAddress server,
            String zoneName, TransferCallback callback) {
        axfrOverTcp(loop, server, zoneName, DEFAULT_TIMEOUT_MS, null, null,
                callback);
    }

    public static void axfrOverTcp(SelectorLoop loop, InetSocketAddress server,
            String zoneName, int timeoutMs, TransferCallback callback) {
        axfrOverTcp(loop, server, zoneName, timeoutMs, null, null, callback);
    }

    public static void axfrOverTcp(SelectorLoop loop, InetSocketAddress server,
            String zoneName, int timeoutMs, TsigKey tsigKey,
            TcpDnsClientTransport transport, TransferCallback callback) {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = DnsZoneOperations.createAxfrQuery(id, zoneName);
        runTcpTransfer(loop, server, query, timeoutMs, tsigKey, transport, callback);
    }

    public static void axfrOverTls(SelectorLoop loop, InetSocketAddress server,
            String zoneName, TcpDnsClientTransport transport,
            TransferCallback callback) {
        axfrOverTcp(loop, server, zoneName, DEFAULT_TIMEOUT_MS, null, transport,
                callback);
    }

    public static void ixfr(SelectorLoop loop, InetSocketAddress server,
            String zoneName, DnsResourceRecord clientSoa,
            TransferCallback callback) {
        ixfr(loop, server, zoneName, clientSoa, null, callback);
    }

    public static void ixfr(SelectorLoop loop, InetSocketAddress server,
            String zoneName, DnsResourceRecord clientSoa, TsigKey tsigKey,
            TransferCallback callback) {
        ixfr(loop, server, zoneName, clientSoa, tsigKey, DEFAULT_TIMEOUT_MS, null,
                callback);
    }

    public static void ixfr(SelectorLoop loop, InetSocketAddress server,
            String zoneName, DnsResourceRecord clientSoa, TsigKey tsigKey,
            int timeoutMs, TcpDnsClientTransport transport,
            TransferCallback callback) {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = DnsZoneOperations.createIxfrQuery(id, zoneName, clientSoa);
        if (tsigKey != null) {
            try {
                query = DnsTsig.sign(query, tsigKey);
            } catch (IOException e) {
                callback.onFailure(e);
                return;
            }
        }
        final DnsMessage signedQuery = query;
        final TsigKey key = tsigKey;
        exchangeUdp(loop, server, signedQuery, timeoutMs, key,
                new MessageCallback() {
                    @Override
                    public void onSuccess(DnsMessage response) {
                        if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
                            callback.onFailure(new IOException(
                                    "IXFR failed: rcode=" + response.getRcode()));
                            return;
                        }
                        if (response.isTruncated()) {
                            ixfrOverTcp(loop, server, zoneName, clientSoa, timeoutMs,
                                    key, transport, callback);
                            return;
                        }
                        if (key != null && signedQuery.getTsigRecord() != null
                                && !DnsTsig.verifyResponse(response, key,
                                        signedQuery)) {
                            callback.onFailure(new IOException(
                                    "TSIG verification failed on IXFR response"));
                            return;
                        }
                        callback.onSuccess(
                                new ArrayList<DnsResourceRecord>(response.getAnswers()));
                    }

                    @Override
                    public void onFailure(Exception error) {
                        callback.onFailure(error);
                    }
                });
    }

    public static void ixfrOverTcp(SelectorLoop loop, InetSocketAddress server,
            String zoneName, DnsResourceRecord clientSoa,
            TransferCallback callback) {
        ixfrOverTcp(loop, server, zoneName, clientSoa, DEFAULT_TIMEOUT_MS, null,
                null, callback);
    }

    public static void ixfrOverTcp(SelectorLoop loop, InetSocketAddress server,
            String zoneName, DnsResourceRecord clientSoa, int timeoutMs,
            TsigKey tsigKey, TcpDnsClientTransport transport,
            TransferCallback callback) {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        DnsMessage query = DnsZoneOperations.createIxfrQuery(id, zoneName, clientSoa);
        runTcpTransfer(loop, server, query, timeoutMs, tsigKey, transport, callback);
    }

    private static void exchangeUdp(SelectorLoop loop, InetSocketAddress server,
            DnsMessage request, int timeoutMs, TsigKey tsigKey,
            MessageCallback callback) {
        if (loop == null || server == null || callback == null) {
            if (callback != null) {
                callback.onFailure(new IllegalArgumentException(
                        "loop, server, and callback are required"));
            }
            return;
        }
        final UdpDnsClientTransport transport = new UdpDnsClientTransport();
        final AtomicCompletion completion = new AtomicCompletion();
        final TimerHandle[] timeout = new TimerHandle[1];
        final ByteBuffer wire = request.serialize();
        try {
            transport.open(server.getAddress(), server.getPort(), loop,
                    new DnsClientTransportHandler() {
                        @Override
                        public void onReceive(ByteBuffer data) {
                            if (!completion.markDone()) {
                                return;
                            }
                            cancelTimeout(timeout);
                            transport.close();
                            try {
                                DnsMessage response = DnsMessage.parse(data);
                                if (tsigKey != null && request.getTsigRecord() != null
                                        && !DnsTsig.verifyResponse(response, tsigKey,
                                                request)) {
                                    callback.onFailure(new IOException(
                                            "TSIG verification failed"));
                                    return;
                                }
                                callback.onSuccess(response);
                            } catch (DnsFormatException e) {
                                callback.onFailure(new IOException(
                                        "invalid DNS response", e));
                            }
                        }

                        @Override
                        public void onError(Exception cause) {
                            if (!completion.markDone()) {
                                return;
                            }
                            cancelTimeout(timeout);
                            transport.close();
                            callback.onFailure(cause);
                        }
                    });
            transport.send(wire);
            timeout[0] = transport.scheduleTimer(timeoutMs, new Runnable() {
                @Override
                public void run() {
                    if (!completion.markDone()) {
                        return;
                    }
                    transport.close();
                    callback.onFailure(new IOException("DNS timeout"));
                }
            });
        } catch (IOException e) {
            transport.close();
            callback.onFailure(e);
        }
    }

    private static void runTcpTransfer(SelectorLoop loop, InetSocketAddress server,
            DnsMessage query, int timeoutMs, TsigKey tsigKey,
            TcpDnsClientTransport transportTemplate, TransferCallback callback) {
        if (loop == null || server == null || callback == null) {
            if (callback != null) {
                callback.onFailure(new IllegalArgumentException(
                        "loop, server, and callback are required"));
            }
            return;
        }
        DnsMessage request = query;
        if (tsigKey != null && query.getTsigRecord() == null) {
            try {
                request = DnsTsig.sign(query, tsigKey);
            } catch (IOException e) {
                callback.onFailure(e);
                return;
            }
        }
        final DnsMessage signedRequest = request;
        final TsigKey key = tsigKey;
        final TcpDnsClientTransport transport = copyTcpTransport(transportTemplate);
        final AtomicCompletion completion = new AtomicCompletion();
        final TimerHandle[] timeout = new TimerHandle[1];
        final List<DnsMessage> messages = new ArrayList<DnsMessage>();
        final ByteBuffer wire = signedRequest.serialize();
        int port = server.getPort() > 0 ? server.getPort() : 0;
        try {
            transport.openTransfer(server.getAddress(), port, loop,
                    new DnsClientTransportHandler() {
                        @Override
                        public void onReceive(ByteBuffer data) {
                            try {
                                messages.add(DnsMessage.parse(data));
                            } catch (DnsFormatException e) {
                                fail(new IOException("invalid DNS response", e));
                            }
                        }

                        @Override
                        public void onClosed() {
                            finishTransfer();
                        }

                        @Override
                        public void onError(Exception cause) {
                            fail(cause);
                        }

                        private void finishTransfer() {
                            if (!completion.markDone()) {
                                return;
                            }
                            cancelTimeout(timeout);
                            transport.close();
                            try {
                                verifyTsig(messages, key, signedRequest);
                                List<DnsResourceRecord> records =
                                        DnsZoneOperations.mergeTransferAnswers(messages);
                                callback.onSuccess(records);
                            } catch (IOException e) {
                                callback.onFailure(e);
                            }
                        }

                        private void fail(Exception cause) {
                            if (!completion.markDone()) {
                                return;
                            }
                            cancelTimeout(timeout);
                            transport.close();
                            callback.onFailure(cause);
                        }
                    }, wire);
            timeout[0] = transport.scheduleTimer(timeoutMs, new Runnable() {
                @Override
                public void run() {
                    if (!completion.markDone()) {
                        return;
                    }
                    transport.close();
                    callback.onFailure(new IOException("DNS timeout"));
                }
            });
        } catch (IOException e) {
            transport.close();
            callback.onFailure(e);
        }
    }

    private static void verifyTsig(List<DnsMessage> messages, TsigKey key,
            DnsMessage request) throws IOException {
        if (key == null || request.getTsigRecord() == null) {
            return;
        }
        if (messages.size() == 1) {
            if (!DnsTsig.verifyResponse(messages.get(0), key, request)) {
                throw new IOException("TSIG verification failed on zone transfer");
            }
        } else if (!DnsTsig.verifyResponseSequence(messages, key, request)) {
            throw new IOException("TSIG verification failed on zone transfer");
        }
    }

    private static TcpDnsClientTransport copyTcpTransport(
            TcpDnsClientTransport template) {
        if (template == null) {
            return new TcpDnsClientTransport();
        }
        return template.duplicate();
    }

    private static void cancelTimeout(TimerHandle[] timeout) {
        if (timeout[0] != null) {
            timeout[0].cancel();
            timeout[0] = null;
        }
    }

    private static final class AtomicCompletion {
        private boolean done;

        synchronized boolean markDone() {
            if (done) {
                return false;
            }
            done = true;
            return true;
        }
    }
}
