/*
 * FtpClientTransferTest.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.AcceptLoopProbe;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Loopback tests of the FTP client's data connections in passive and active
 * mode (download, upload, listings), of PROT P failure handling, and of the
 * {@link FtpClient} facade connecting to a local greeting server. Control
 * replies are delivered on the worker loop thread, as the transport does.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpClientTransferTest {

    private static final long WAIT_SECONDS = 15;

    private Gumdrop gumdrop;
    private ClientEndpoint keeper;
    private SelectorLoop loop;
    private LoopEndpoint control;
    private FtpClientProtocolHandler handler;
    private final List<Exception> errors = Collections.synchronizedList(new ArrayList<Exception>());
    private final List<Thread> serverThreads = new ArrayList<Thread>();
    private final List<ServerSocket> serverSockets = new ArrayList<ServerSocket>();

    /** Control stub on a real loop that records commands for the test to await. */
    private final class LoopEndpoint extends FTPClientProtocolHandlerTest.StubEndpoint {
        final BlockingQueue<String> commands = new LinkedBlockingQueue<String>();

        LoopEndpoint() {
            super(new ArrayList<String>());
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String text = new String(bytes, StandardCharsets.US_ASCII);
            commands.add(text.replace("\r\n", ""));
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public void execute(Runnable task) {
            loop.invokeLater(task);
        }
    }

    private final class NullGreeting implements RemoteGreeting {
        @Override
        public void handleGreeting(ClientLoginState login, String message) {
        }

        @Override
        public void handleServiceUnavailable(String message) {
        }

        @Override
        public void onConnected(Endpoint ep) {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception e) {
            errors.add(e);
        }
    }

    @Before
    public void setUp() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        loop = gumdrop.nextWorkerLoop();
        keeper = new ClientEndpoint(new TcpTransportFactory(), loop, "localhost", 1);
        gumdrop.addClient(keeper);
        control = new LoopEndpoint();
        handler = new FtpClientProtocolHandler(new NullGreeting());
        handler.setGumdrop(gumdrop);
        handler.connected(control);
        onLoop("220 ready\r\n");
    }

    @After
    public void tearDown() throws Exception {
        handler.disconnected();
        handler.close();
        for (ServerSocket ss : serverSockets) {
            ss.close();
        }
        for (Thread t : serverThreads) {
            t.join(5000);
        }
        gumdrop.shutdown();
        gumdrop.join();
    }

    /** Delivers a control reply on the loop thread and waits until it was handled. */
    private void onLoop(final String reply) {
        final CountDownLatch done = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    handler.receive(ByteBuffer.wrap(reply.getBytes(StandardCharsets.US_ASCII)));
                } finally {
                    done.countDown();
                }
            }
        });
        await(done);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("timed out", latch.await(WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private String awaitCommand(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            long remaining = deadline - System.nanoTime();
            assertTrue("no command starting with " + prefix, remaining > 0);
            String cmd = control.commands.poll(remaining, TimeUnit.NANOSECONDS);
            if (cmd != null && cmd.startsWith(prefix)) {
                return cmd;
            }
        }
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByName("127.0.0.1");
    }

    /** A one-shot data server: accepts, optionally writes a payload, optionally reads to EOF. */
    private final class DataServer implements Runnable {
        final ServerSocket socket;
        final byte[] payload;
        final boolean readToEof;
        final CountDownLatch holdClose;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final CountDownLatch finished = new CountDownLatch(1);

        DataServer(byte[] payload, boolean readToEof, CountDownLatch holdClose) throws IOException {
            this.socket = new ServerSocket(0, 1, loopback());
            this.payload = payload;
            this.readToEof = readToEof;
            this.holdClose = holdClose;
            serverSockets.add(socket);
            Thread t = new Thread(this, "ftp-data-server");
            t.setDaemon(true);
            serverThreads.add(t);
            t.start();
        }

        InetSocketAddress address() throws IOException {
            return new InetSocketAddress(loopback(), socket.getLocalPort());
        }

        @Override
        public void run() {
            try {
                Socket s = socket.accept();
                try {
                    if (payload != null) {
                        OutputStream out = s.getOutputStream();
                        out.write(payload);
                        out.flush();
                    }
                    if (readToEof) {
                        InputStream in = s.getInputStream();
                        byte[] buf = new byte[512];
                        int n = in.read(buf);
                        while (n >= 0) {
                            received.write(buf, 0, n);
                            n = in.read(buf);
                        }
                    }
                    if (holdClose != null) {
                        holdClose.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    }
                } finally {
                    s.close();
                }
            } catch (IOException e) {
                // the test fails on its own assertions
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }
    }

    private final class Download implements RetrReplyHandler {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String failure;

        @Override
        public void handleServiceClosing(String message) {
            failure = "closing";
            done.countDown();
        }

        @Override
        public void handleContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            content.write(b, 0, b.length);
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState a) {
            done.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            failure = code + ":" + message;
            done.countDown();
        }
    }

    private final class Upload implements StorReplyHandler {
        final byte[] data;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String failure;

        Upload(byte[] data) {
            this.data = data;
        }

        @Override
        public void handleServiceClosing(String message) {
            failure = "closing";
            done.countDown();
        }

        @Override
        public void handleReadyToSend(ClientDataSink sink) {
            sink.write(ByteBuffer.wrap(data));
            sink.finish();
            sink.finish();
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState a) {
            done.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            failure = code + ":" + message;
            done.countDown();
        }
    }

    private final class Listing implements ListReplyHandler {
        final CountDownLatch done = new CountDownLatch(1);
        volatile List<FtpFileEntry> entries;
        volatile String failure;

        @Override
        public void handleServiceClosing(String message) {
            failure = "closing";
            done.countDown();
        }

        @Override
        public void handleEntries(List<FtpFileEntry> list, ClientAuthenticatedState a) {
            entries = list;
            done.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            failure = code + ":" + message;
            done.countDown();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Issues PORT, answers 200 and returns the announced listener port. */
    private int activePort() throws Exception {
        handler.port(new PortReplyHandler() {
            @Override
            public void handleServiceClosing(String message) {
            }

            @Override
            public void handleOk(ClientAuthenticatedState a) {
            }

            @Override
            public void handleError(ClientAuthenticatedState a, int code, String message) {
                errors.add(new IOException(code + ":" + message));
            }
        });
        String cmd = awaitCommand("PORT ");
        onLoop("200 ok\r\n");
        String[] f = cmd.substring(5).split(",");
        return (Integer.parseInt(f[4]) << 8) | Integer.parseInt(f[5]);
    }

    // ── passive mode ──

    @Test
    public void passiveDownloadControlReplyFirst() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        DataServer server = new DataServer(bytes("hello passive"), false, hold);
        Download dl = new Download();
        handler.retr("f.txt", server.address(), dl);
        awaitCommand("RETR f.txt");
        onLoop("150 opening\r\n");
        onLoop("226 done\r\n");
        hold.countDown();
        await(dl.done);
        assertEquals(null, dl.failure);
        assertEquals("hello passive", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(handler.isConnected());
    }

    @Test
    public void passiveDownloadDataEofFirst() throws Exception {
        DataServer server = new DataServer(bytes("eof first"), false, null);
        Download dl = new Download();
        handler.retr("f.txt", server.address(), dl);
        awaitCommand("RETR f.txt");
        await(server.finished);
        onLoop("226 done\r\n");
        await(dl.done);
        assertEquals(null, dl.failure);
        assertEquals("eof first", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void passiveDownloadRejectedByControlReply() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        DataServer server = new DataServer(null, false, hold);
        Download dl = new Download();
        handler.retr("nope", server.address(), dl);
        awaitCommand("RETR nope");
        onLoop("550 not found\r\n");
        await(dl.done);
        assertEquals("550:not found", dl.failure);
        hold.countDown();
    }

    @Test
    public void passiveUploadStorDeliversBytes() throws Exception {
        DataServer server = new DataServer(null, true, null);
        Upload up = new Upload(bytes("uploaded bytes"));
        handler.stor("u.txt", server.address(), up);
        awaitCommand("STOR u.txt");
        onLoop("226 stored\r\n");
        await(up.done);
        await(server.finished);
        assertEquals(null, up.failure);
        assertEquals("uploaded bytes", new String(server.received.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void passiveUploadAppeSendsAppe() throws Exception {
        DataServer server = new DataServer(null, true, null);
        Upload up = new Upload(bytes("more"));
        handler.appe("u.txt", server.address(), up);
        awaitCommand("APPE u.txt");
        onLoop("226 appended\r\n");
        await(up.done);
        await(server.finished);
        assertEquals("more", new String(server.received.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void passiveListParsesUnixLines() throws Exception {
        DataServer server = new DataServer(
                bytes("-rw-r--r-- 1 u g 12 Jan 01 00:00 a.txt\r\ndrwxr-xr-x 2 u g 0 Jan 01 00:00 sub\n\r\n"),
                false, null);
        Listing l = new Listing();
        handler.list("/", server.address(), l);
        assertEquals("LIST /", awaitCommand("LIST"));
        await(server.finished);
        onLoop("226 done\r\n");
        await(l.done);
        assertNotNull(l.entries);
        assertEquals(2, l.entries.size());
        assertEquals("a.txt", l.entries.get(0).getName());
        assertEquals(12, l.entries.get(0).getSize());
        assertTrue(l.entries.get(1).isDirectory());
    }

    @Test
    public void passiveNlstParsesBareNames() throws Exception {
        DataServer server = new DataServer(bytes("one\r\ntwo\r\n"), false, null);
        Listing l = new Listing();
        handler.nlst(null, server.address(), l);
        assertEquals("NLST", awaitCommand("NLST"));
        await(server.finished);
        onLoop("226 done\r\n");
        await(l.done);
        assertEquals(2, l.entries.size());
        assertEquals("two", l.entries.get(1).getName());
    }

    @Test
    public void passiveMlsdParsesFacts() throws Exception {
        DataServer server = new DataServer(
                bytes("type=file;size=5;modify=20260101000000; f.bin\r\ntype=dir; d\r\n"), false, null);
        Listing l = new Listing();
        handler.mlsd("", server.address(), l);
        assertEquals("MLSD", awaitCommand("MLSD"));
        await(server.finished);
        onLoop("226 done\r\n");
        await(l.done);
        assertEquals(2, l.entries.size());
        assertEquals("f.bin", l.entries.get(0).getName());
        assertEquals("5", l.entries.get(0).getFact("SIZE"));
        assertTrue(l.entries.get(1).isDirectory());
    }

    @Test
    public void passiveListRejectedByControlReply() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        DataServer server = new DataServer(null, false, hold);
        Listing l = new Listing();
        handler.list("x", server.address(), l);
        awaitCommand("LIST x");
        onLoop("550 denied\r\n");
        await(l.done);
        assertEquals("550:denied", l.failure);
        hold.countDown();
    }

    @Test
    public void protectedPassiveConnectionToPlainServerDoesNotSucceed() throws Exception {
        handler.prot("P", new SimpleReplyHandler() {
            @Override
            public void handleServiceClosing(String message) {
            }

            @Override
            public void handleOk(ClientAuthenticatedState a) {
            }

            @Override
            public void handleError(ClientAuthenticatedState a, int code, String message) {
                errors.add(new IOException(message));
            }
        });
        awaitCommand("PROT P");
        onLoop("200 ok\r\n");
        DataServer server = new DataServer(null, false, null);
        Download dl = new Download();
        handler.retr("f", server.address(), dl);
        await(server.finished);
        // Either the aborted handshake already failed the transfer (code 0)
        // or the control connection's refusal does.
        onLoop("550 refused\r\n");
        await(dl.done);
        assertNotNull(dl.failure);
        assertTrue(errors.isEmpty());
    }

    // ── active mode ──

    @Test
    public void activeDownloadWhenServerConnectsAfterRetr() throws Exception {
        int port = activePort();
        Download dl = new Download();
        handler.retr("a.bin", null, dl);
        awaitCommand("RETR a.bin");
        Socket s = new Socket(loopback(), port);
        try {
            OutputStream out = s.getOutputStream();
            out.write(bytes("active download"));
            out.flush();
        } finally {
            s.close();
        }
        onLoop("150 opening\r\n");
        onLoop("226 done\r\n");
        await(dl.done);
        assertEquals(null, dl.failure);
        assertEquals("active download", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void activeDownloadWhenServerConnectsBeforeRetr() throws Exception {
        int port = activePort();
        Socket s = new Socket(loopback(), port);
        try {
            OutputStream out = s.getOutputStream();
            out.write(bytes("early connection"));
            out.flush();
            Download dl = new Download();
            handler.retr("a.bin", null, dl);
            awaitCommand("RETR a.bin");
            s.close();
            onLoop("226 done\r\n");
            await(dl.done);
            assertEquals(null, dl.failure);
            assertEquals("early connection", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            s.close();
        }
    }

    @Test
    public void activeUploadDeliversBytes() throws Exception {
        int port = activePort();
        Upload up = new Upload(bytes("active upload"));
        handler.stor("u.bin", null, up);
        awaitCommand("STOR u.bin");
        Socket s = new Socket(loopback(), port);
        try {
            InputStream in = s.getInputStream();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[128];
            int n = in.read(buf);
            while (n >= 0) {
                got.write(buf, 0, n);
                n = in.read(buf);
            }
            assertEquals("active upload", new String(got.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            s.close();
        }
        onLoop("226 stored\r\n");
        await(up.done);
        assertEquals(null, up.failure);
    }

    @Test
    public void activeAppendUsesAppe() throws Exception {
        int port = activePort();
        Upload up = new Upload(bytes("x"));
        handler.appe("u.bin", null, up);
        awaitCommand("APPE u.bin");
        Socket s = new Socket(loopback(), port);
        try {
            InputStream in = s.getInputStream();
            while (in.read() >= 0) {
                // drain until the client closes
            }
        } finally {
            s.close();
        }
        onLoop("226 ok\r\n");
        await(up.done);
    }

    @Test
    public void activeListingParsesEntries() throws Exception {
        int port = activePort();
        Listing l = new Listing();
        handler.nlst("d", null, l);
        awaitCommand("NLST d");
        Socket s = new Socket(loopback(), port);
        try {
            OutputStream out = s.getOutputStream();
            out.write(bytes("alpha\r\nbeta\r\n"));
            out.flush();
        } finally {
            s.close();
        }
        onLoop("226 done\r\n");
        await(l.done);
        assertEquals(2, l.entries.size());
        assertEquals("alpha", l.entries.get(0).getName());
    }

    @Test
    public void activeListingWithNoLinesYieldsEmptyList() throws Exception {
        int port = activePort();
        Listing l = new Listing();
        handler.list(null, null, l);
        awaitCommand("LIST");
        Socket s = new Socket(loopback(), port);
        s.close();
        onLoop("226 done\r\n");
        await(l.done);
        assertEquals(0, l.entries.size());
    }

    @Test
    public void eprtAnnouncesListenerAndAcceptsAConnection() throws Exception {
        handler.eprt(new PortReplyHandler() {
            @Override
            public void handleServiceClosing(String message) {
            }

            @Override
            public void handleOk(ClientAuthenticatedState a) {
            }

            @Override
            public void handleError(ClientAuthenticatedState a, int code, String message) {
                errors.add(new IOException(message));
            }
        });
        String cmd = awaitCommand("EPRT |1|127.0.0.1|");
        onLoop("200 ok\r\n");
        String[] f = cmd.split("\\|");
        int port = Integer.parseInt(f[3]);
        Download dl = new Download();
        handler.retr("e.bin", null, dl);
        awaitCommand("RETR e.bin");
        Socket s = new Socket(loopback(), port);
        try {
            OutputStream out = s.getOutputStream();
            out.write(bytes("eprt data"));
            out.flush();
        } finally {
            s.close();
        }
        onLoop("226 done\r\n");
        await(dl.done);
        assertEquals("eprt data", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void secondPortReplacesTheFirstListener() throws Exception {
        int first = activePort();
        java.nio.channels.ServerSocketChannel oldListener = handler.activeListenerChannel();
        int second = activePort();
        assertTrue(first != second || first > 0);
        assertFalse("the replaced listener must be closed", oldListener.isOpen());
        assertFalse("and deregistered from the accept loop (closeRawAcceptor)",
                AcceptLoopProbe.isRegistered(gumdrop, oldListener));
        assertTrue(handler.activeListenerChannel().isOpen());
    }

    // ── FtpClient facade ──

    @Test
    public void facadeRequiresATarget() {
        FtpClient client = new FtpClient();
        try {
            client.connect(gumdrop, new NullGreeting());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("required"));
        }
        assertFalse(client.isOpen());
        client.close();
    }

    @Test
    public void facadeConnectsAndReceivesGreeting() throws Exception {
        final ServerSocket ss = new ServerSocket(0, 1, loopback());
        serverSockets.add(ss);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = ss.accept();
                    try {
                        OutputStream out = s.getOutputStream();
                        out.write(bytes("220 facade ready\r\n"));
                        out.flush();
                        InputStream in = s.getInputStream();
                        byte[] buf = new byte[64];
                        while (in.read(buf) >= 0) {
                            // wait for the client to close
                        }
                    } finally {
                        s.close();
                    }
                } catch (IOException e) {
                    // the client side assertions report failures
                }
            }
        }, "ftp-greeting-server");
        t.setDaemon(true);
        serverThreads.add(t);
        t.start();

        final CountDownLatch greeted = new CountDownLatch(1);
        final List<String> greetings = Collections.synchronizedList(new ArrayList<String>());
        FtpClient client = new FtpClient(loopback(), ss.getLocalPort())
                .secure(false).trustJvm().dnsResolver(null);
        client.connect(gumdrop, new RemoteGreeting() {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                greetings.add(message);
                greeted.countDown();
            }

            @Override
            public void handleServiceUnavailable(String message) {
                greeted.countDown();
            }

            @Override
            public void onConnected(Endpoint ep) {
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }

            @Override
            public void onError(Exception e) {
                errors.add(e);
                greeted.countDown();
            }
        });
        await(greeted);
        assertEquals("[facade ready]", greetings.toString());
        assertTrue(client.isOpen());
        client.close();
        assertFalse(client.isOpen());
        assertTrue(errors.isEmpty());
    }

    @Test
    public void facadeFluentAndPlainSettersAcceptValues() {
        FtpClient client = new FtpClient("ftp.example.com", 2121);
        client.setSecure(true);
        client.setClientCredentials(null);
        client.setTrustManager(null);
        client.setKeystoreFile(null);
        client.setKeystorePass("x");
        client.setKeystoreFormat(null);
        client.selectorLoop(loop).host("other.example.com").port(990).secure(false);
        client.clientCredentials(null).trustManager(null).keystoreFile(null)
                .keystorePass("y").keystoreFormat(null);
        client.host(InetAddress.getLoopbackAddress()).socketPath("/tmp/ftp.sock");
        new FtpClient(loop, "h.example", 21);
        new FtpClient(loop, InetAddress.getLoopbackAddress(), 21);
        new FtpClient("/tmp/ftp.sock");
        new FtpClient(loop, "/tmp/ftp.sock");
        assertFalse(client.isOpen());
    }
}
