/*
 * FtpLiveTransferTest.java
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;

import static org.junit.Assert.*;

/**
 * Drives {@link FtpProtocolHandler} and {@link FtpDataConnectionCoordinator}
 * through complete passive and active mode transfers (RETR, LIST, NLST,
 * MLSD, STOR, APPE, STOU) on a live worker loop, with the control channel an
 * in-memory endpoint and the data channel a loopback socket owned by the
 * test. Synchronisation is on a reply queue with a hang-guard timeout; the
 * data-connection timeout paths fire their timers inline.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpLiveTransferTest {

    private static final int GUARD_MS = 15000;

    private static Gumdrop gumdrop;
    private static ClientEndpoint keeper;

    /** Control endpoint on a real loop whose replies are queued. */
    private static final class Control
            extends FTPProtocolHandlerTest.StubEndpoint {
        final BlockingQueue<String> replies =
                new LinkedBlockingQueue<String>();
        final List<Runnable> timers =
                Collections.synchronizedList(new ArrayList<Runnable>());
        volatile boolean fireTimersInline;
        volatile InetSocketAddress remote =
                new InetSocketAddress("127.0.0.1", 54321);
        private final SelectorLoop loop;

        Control(SelectorLoop loop) {
            this.loop = loop;
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String s = new String(bytes, StandardCharsets.US_ASCII);
            String[] lines = s.split("\r\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    replies.add(lines[i]);
                }
            }
        }

        @Override
        public java.net.SocketAddress getRemoteAddress() {
            return remote;
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public void execute(Runnable task) {
            loop.invokeLater(task);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            if (fireTimersInline) {
                cb.run();
            } else {
                timers.add(cb);
            }
            return super.scheduleTimer(delayMs, cb);
        }
    }

    private Path root;
    private FtpListener listener;
    private FtpProtocolHandler handler;
    private Control control;

    @BeforeClass
    public static void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1)
                .drainTimeoutMs(0));
        keeper = new ClientEndpoint(new TcpTransportFactory(),
                gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        gumdrop.ensureAcceptLoop();
    }

    @AfterClass
    public static void stopGumdrop() throws Exception {
        gumdrop.shutdown();
        gumdrop.join();
    }

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("ftplive");
        listener = new FtpListener();
        listener.start(gumdrop);
        startSession(new InetSocketAddress("127.0.0.1", 54321));
    }

    private void startSession(InetSocketAddress remote) throws Exception {
        BasicFTPFileSystem fs = new BasicFTPFileSystem(root, false);
        handler = new FtpProtocolHandler(listener, new SimpleFTPHandler(fs));
        control = new Control(gumdrop.nextWorkerLoop());
        control.remote = remote;
        feedConnected();
        expect("220");
        send("USER alice");
        expect("331");
        send("PASS secret");
        expect("230");
    }

    @After
    public void tearDown() throws Exception {
        handler.disconnected();
        deleteTree(root);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            java.nio.file.DirectoryStream<Path> ds =
                    Files.newDirectoryStream(dir);
            try {
                for (Path p : ds) {
                    deleteTree(p);
                }
            } finally {
                ds.close();
            }
        }
        Files.deleteIfExists(dir);
    }

    private void onLoop(final Runnable task) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        control.getSelectorLoop().invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(GUARD_MS, TimeUnit.MILLISECONDS));
    }

    private void feedConnected() throws InterruptedException {
        onLoop(new Runnable() {
            @Override
            public void run() {
                handler.connected(control);
            }
        });
    }

    private void send(String command) throws InterruptedException {
        final ByteBuffer data = ByteBuffer.wrap(
                (command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        onLoop(new Runnable() {
            @Override
            public void run() {
                handler.receive(data);
            }
        });
    }

    private String reply() throws InterruptedException {
        String r = control.replies.poll(GUARD_MS, TimeUnit.MILLISECONDS);
        assertNotNull("no control reply within the hang guard", r);
        return r;
    }

    private String expect(String code) throws InterruptedException {
        String r = reply();
        assertTrue("expected " + code + " got " + r, r.startsWith(code));
        return r;
    }

    private static byte[] readAll(Socket s) throws IOException {
        s.setSoTimeout(GUARD_MS);
        InputStream in = s.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n = in.read(buf);
        while (n >= 0) {
            out.write(buf, 0, n);
            n = in.read(buf);
        }
        return out.toByteArray();
    }

    private Socket passive() throws Exception {
        send("PASV");
        String r = expect("227");
        int open = r.indexOf('(');
        int close = r.indexOf(')');
        String[] parts = r.substring(open + 1, close).split(",");
        int port = Integer.parseInt(parts[4].trim()) * 256
                + Integer.parseInt(parts[5].trim());
        return new Socket(InetAddress.getLoopbackAddress(), port);
    }

    private ServerSocket activeServer() throws Exception {
        ServerSocket ss = new ServerSocket(0, 1,
                InetAddress.getLoopbackAddress());
        ss.setSoTimeout(GUARD_MS);
        int p = ss.getLocalPort();
        send("PORT 127,0,0,1," + (p / 256) + "," + (p % 256));
        expect("200");
        return ss;
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return b;
    }

    // ── Passive downloads ──

    @Test
    public void passiveRetrStreamsLargeFile() throws Exception {
        byte[] content = pattern(700 * 1024);
        Files.write(root.resolve("big.bin"), content);
        send("TYPE I");
        expect("200");
        Socket data = passive();
        try {
            send("RETR big.bin");
            expect("150");
            byte[] got = readAll(data);
            assertArrayEquals(content, got);
            expect("226");
        } finally {
            data.close();
        }
    }

    @Test
    public void passiveRetrHonoursRestartOffset() throws Exception {
        Files.write(root.resolve("r.txt"), "0123456789".getBytes(
                StandardCharsets.US_ASCII));
        send("TYPE I");
        expect("200");
        send("REST 4");
        expect("350");
        Socket data = passive();
        try {
            send("RETR r.txt");
            expect("150");
            assertEquals("456789", new String(readAll(data),
                    StandardCharsets.US_ASCII));
            expect("226");
        } finally {
            data.close();
        }
    }

    @Test
    public void passiveRetrAsciiConvertsLineEndings() throws Exception {
        Files.write(root.resolve("t.txt"), "a\nb\n".getBytes(
                StandardCharsets.US_ASCII));
        send("TYPE A");
        expect("200");
        Socket data = passive();
        try {
            send("RETR t.txt");
            expect("150");
            assertEquals("a\r\nb\r\n", new String(readAll(data),
                    StandardCharsets.US_ASCII));
            expect("226");
        } finally {
            data.close();
        }
    }

    @Test
    public void passiveRetrOfMissingFileFails() throws Exception {
        Socket data = passive();
        try {
            send("RETR nothere.txt");
            String r = reply();
            if (r.startsWith("150")) {
                r = reply();
            }
            assertTrue(r, r.startsWith("5") || r.startsWith("4"));
        } finally {
            data.close();
        }
    }

    @Test
    public void passiveRetrWhenDataConnectionComesLater() throws Exception {
        Files.write(root.resolve("l.txt"), "late".getBytes(
                StandardCharsets.US_ASCII));
        send("TYPE I");
        expect("200");
        send("PASV");
        String r = expect("227");
        int open = r.indexOf('(');
        int close = r.indexOf(')');
        String[] parts = r.substring(open + 1, close).split(",");
        int port = Integer.parseInt(parts[4].trim()) * 256
                + Integer.parseInt(parts[5].trim());
        send("RETR l.txt");
        Socket data = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            expect("150");
            assertEquals("late", new String(readAll(data),
                    StandardCharsets.US_ASCII));
            expect("226");
        } finally {
            data.close();
        }
    }

    // ── Passive listings ──

    private void prepareListing() throws IOException {
        Files.write(root.resolve("one.txt"), "1".getBytes(
                StandardCharsets.US_ASCII));
        Files.write(root.resolve("two.txt"), "22".getBytes(
                StandardCharsets.US_ASCII));
        Files.createDirectory(root.resolve("sub"));
    }

    private String listing(String command) throws Exception {
        Socket data = passive();
        try {
            send(command);
            expect("150");
            String text = new String(readAll(data), StandardCharsets.UTF_8);
            expect("226");
            return text;
        } finally {
            data.close();
        }
    }

    @Test
    public void passiveListNlstAndMlsd() throws Exception {
        prepareListing();
        String list = listing("LIST");
        assertTrue(list, list.contains("one.txt"));
        assertTrue(list, list.contains("sub"));
        String nlst = listing("NLST");
        assertTrue(nlst, nlst.contains("two.txt"));
        String mlsd = listing("MLSD");
        assertTrue(mlsd, mlsd.contains("type=dir"));
        assertTrue(mlsd, mlsd.contains("one.txt"));
    }

    @Test
    public void passiveListOfMissingDirectoryFails() throws Exception {
        Socket data = passive();
        try {
            send("LIST nodir");
            String r = reply();
            if (r.startsWith("150")) {
                r = reply();
            }
            assertTrue(r, r.startsWith("5") || r.startsWith("4"));
        } finally {
            data.close();
        }
    }

    // ── Passive uploads ──

    private void upload(String command, byte[] body) throws Exception {
        Socket data = passive();
        try {
            send(command);
            expect("150");
            OutputStream out = data.getOutputStream();
            out.write(body);
            out.flush();
        } finally {
            data.close();
        }
        expect("226");
    }

    @Test
    public void passiveStorWritesFile() throws Exception {
        byte[] body = pattern(300 * 1024);
        send("TYPE I");
        expect("200");
        upload("STOR up.bin", body);
        assertArrayEquals(body, Files.readAllBytes(root.resolve("up.bin")));
    }

    @Test
    public void passiveStorAsciiConvertsLineEndings() throws Exception {
        send("TYPE A");
        expect("200");
        upload("STOR a.txt", "x\r\ny\r\n".getBytes(StandardCharsets.US_ASCII));
        String text = new String(Files.readAllBytes(root.resolve("a.txt")),
                StandardCharsets.US_ASCII);
        assertEquals(-1, text.indexOf('\r'));
    }

    @Test
    public void passiveAppeAppendsToFile() throws Exception {
        Files.write(root.resolve("log.txt"), "head-".getBytes(
                StandardCharsets.US_ASCII));
        send("TYPE I");
        expect("200");
        upload("APPE log.txt", "tail".getBytes(StandardCharsets.US_ASCII));
        assertEquals("head-tail", new String(
                Files.readAllBytes(root.resolve("log.txt")),
                StandardCharsets.US_ASCII));
    }

    @Test
    public void passiveStouCreatesUniqueFile() throws Exception {
        send("TYPE I");
        expect("200");
        Socket data = passive();
        try {
            send("STOU");
            String r = expect("150");
            assertNotNull(r);
            OutputStream out = data.getOutputStream();
            out.write("uniq".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } finally {
            data.close();
        }
        expect("226");
        assertEquals(1, root.toFile().list().length);
    }

    @Test
    public void passiveStorIntoMissingDirectoryFails() throws Exception {
        Socket data = passive();
        try {
            send("STOR nodir/x.bin");
            String r = reply();
            if (r.startsWith("150")) {
                r = reply();
            }
            assertTrue(r, r.startsWith("5") || r.startsWith("4"));
        } finally {
            data.close();
        }
    }

    // ── Active mode ──

    @Test
    public void activeRetrConnectsBackToClient() throws Exception {
        byte[] content = pattern(50000);
        Files.write(root.resolve("a.bin"), content);
        send("TYPE I");
        expect("200");
        ServerSocket ss = activeServer();
        try {
            send("RETR a.bin");
            Socket data = ss.accept();
            try {
                expect("150");
                assertArrayEquals(content, readAll(data));
                expect("226");
            } finally {
                data.close();
            }
        } finally {
            ss.close();
        }
    }

    @Test
    public void activeListAndStor() throws Exception {
        prepareListing();
        send("TYPE I");
        expect("200");
        ServerSocket ss = activeServer();
        try {
            send("NLST");
            Socket data = ss.accept();
            try {
                expect("150");
                String text = new String(readAll(data),
                        StandardCharsets.UTF_8);
                assertTrue(text, text.contains("one.txt"));
                expect("226");
            } finally {
                data.close();
            }
        } finally {
            ss.close();
        }
        ServerSocket up = activeServer();
        try {
            send("STOR in.bin");
            Socket data = up.accept();
            try {
                expect("150");
                OutputStream out = data.getOutputStream();
                out.write(pattern(1000));
                out.flush();
            } finally {
                data.close();
            }
            expect("226");
            assertEquals(1000, Files.size(root.resolve("in.bin")));
        } finally {
            up.close();
        }
    }

    @Test
    public void activeStorOnly() throws Exception {
        send("TYPE I");
        expect("200");
        ServerSocket up = activeServer();
        try {
            send("STOR in.bin");
            Socket data = up.accept();
            try {
                expect("150");
                OutputStream out = data.getOutputStream();
                out.write(pattern(1000));
                out.flush();
            } finally {
                data.close();
            }
            expect("226");
            assertEquals(1000, Files.size(root.resolve("in.bin")));
        } finally {
            up.close();
        }
    }

    @Test
    public void twoPassiveUploadsInOneSession() throws Exception {
        send("TYPE I");
        expect("200");
        upload("STOR one.bin", pattern(100));
        upload("STOR two.bin", pattern(200));
        assertEquals(200, Files.size(root.resolve("two.bin")));
    }

    @Test
    public void activeEprtRetr() throws Exception {
        Files.write(root.resolve("e.txt"), "eprt".getBytes(
                StandardCharsets.US_ASCII));
        ServerSocket ss = new ServerSocket(0, 1,
                InetAddress.getLoopbackAddress());
        ss.setSoTimeout(GUARD_MS);
        try {
            send("EPRT |1|127.0.0.1|" + ss.getLocalPort() + "|");
            expect("200");
            send("RETR e.txt");
            Socket data = ss.accept();
            try {
                expect("150");
                assertEquals("eprt", new String(readAll(data),
                        StandardCharsets.US_ASCII));
                expect("226");
            } finally {
                data.close();
            }
        } finally {
            ss.close();
        }
    }

    // ── Failure paths ──

    @Test
    public void passiveTimeoutFailsTransfer() throws Exception {
        Files.write(root.resolve("t.bin"), pattern(10));
        send("PASV");
        expect("227");
        control.fireTimersInline = true;
        send("RETR t.bin");
        String r = reply();
        if (r.startsWith("150")) {
            r = reply();
        }
        assertTrue(r, r.startsWith("4") || r.startsWith("5"));
    }

    @Test
    public void activeTimeoutFailsTransfer() throws Exception {
        Files.write(root.resolve("t.bin"), pattern(10));
        ServerSocket ss = activeServer();
        try {
            control.fireTimersInline = true;
            send("RETR t.bin");
            String r = reply();
            if (r.startsWith("150")) {
                r = reply();
            }
            assertTrue(r, r.startsWith("4") || r.startsWith("5"));
        } finally {
            ss.close();
        }
    }

    @Test
    public void passiveDataFromForeignAddressIsRefused() throws Exception {
        handler.disconnected();
        startSession(new InetSocketAddress("192.0.2.77", 4000));
        send("PASV");
        String r = expect("227");
        int open = r.indexOf('(');
        int close = r.indexOf(')');
        String[] parts = r.substring(open + 1, close).split(",");
        int port = Integer.parseInt(parts[4].trim()) * 256
                + Integer.parseInt(parts[5].trim());
        Socket data = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            byte[] got = readAll(data);
            assertEquals(0, got.length);
        } finally {
            data.close();
        }
    }
}
