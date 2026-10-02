/*
 * FtpDataTransferMockTest.java
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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Drives {@link FtpProtocolHandler} and {@link FtpDataConnectionCoordinator}
 * through complete passive and active mode transfers (RETR, LIST, NLST,
 * MLSD, STOR, APPE, STOU) with the data connection, the listener and the
 * file channels all supplied by a {@link MockFtpDataTransport}: no socket,
 * loop, thread, timer or real file is involved. The test hands data
 * connections to the code under test and feeds and drains each one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpDataTransferMockTest {

    private TestGumdrop.QueuedExecutor queue;
    private Gumdrop gumdrop;
    private Path root;
    private FtpListener listener;
    private FtpProtocolHandler handler;
    private RecordingStubEndpoint control;
    private MockFtpDataTransport transport;

    @Before
    public void setUp() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        gumdrop = TestGumdrop.create();
        startSession(gumdrop);
    }

    private void startSession(Gumdrop g) throws Exception {
        listener = new FtpListener();
        listener.start(g);
        BasicFTPFileSystem fs = new BasicFTPFileSystem(root, false);
        handler = new FtpProtocolHandler(listener, new SimpleFTPHandler(fs));
        control = new RecordingStubEndpoint(21);
        control.setSelectorLoop(g.nextWorkerLoop());
        transport = new MockFtpDataTransport(g.nextWorkerLoop());
        handler.setDataTransport(transport);
        handler.connected(control);
        expect("220");
        cmd("USER alice");
        cmd("PASS secret");
        runStorage();
        assertNotNull(control.findLineStartingWith("230"));
    }

    private void runStorage() {
        if (queue != null) {
            queue.runAll();
        }
    }

    /** PBSZ/PROT P on a control connection the test marks as TLS-secured. */
    private void protPrivate() {
        control.setSecure(true);
        expectCmd("200", "PBSZ 0");
        expectCmd("200", "PROT P");
    }

    /** Restarts the session with storage work held until the test runs it. */
    private void useQueuedStorage() throws Exception {
        queue = new TestGumdrop.QueuedExecutor();
        gumdrop = TestGumdrop.create(queue);
        startSession(gumdrop);
    }

    private String cmd(String command) {
        control.clearResponses();
        byte[] bytes = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(bytes));
        return replies();
    }

    private String replies() {
        List<String> lines = control.getResponses();
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void expect(String code) {
        String all = replies();
        assertTrue("expected " + code + " in: " + all, all.contains(code));
    }

    private void expectCmd(String code, String command) {
        String r = cmd(command);
        assertTrue(command + " -> " + r, r.startsWith(code));
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return b;
    }

    private StubSocketChannel dataChannel(String remoteHost) {
        return new StubSocketChannel(new InetSocketAddress("127.0.0.1", 40000),
                new InetSocketAddress(remoteHost, 50000));
    }

    /** PASV, then the client connects: returns the data channel. */
    private StubSocketChannel passive() throws Exception {
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        StubSocketChannel ch = dataChannel("127.0.0.1");
        transport.lastListener().accept(ch);
        return ch;
    }

    private void pump() {
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        int guard = 0;
        boolean ran = ep.fireWriteReady();
        while (ran) {
            guard++;
            assertTrue("write pacing did not finish", guard < 10000);
            ran = ep.fireWriteReady();
        }
    }

    private String sentText() {
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        byte[] b = ep.sentBytes();
        return new String(b, StandardCharsets.UTF_8);
    }

    private void feed(byte[] data) {
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        ep.handler.receive(ByteBuffer.wrap(data));
    }

    private void clientDone() {
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        ep.handler.disconnected();
    }

    private int fileCount() throws IOException {
        int n = 0;
        DirectoryStream<Path> ds = Files.newDirectoryStream(root);
        try {
            for (Path p : ds) {
                n++;
            }
        } finally {
            ds.close();
        }
        return n;
    }

    // ── Passive downloads ──

    @Test
    public void passiveRetrStreamsLargeFile() throws Exception {
        byte[] content = pattern(700 * 1024);
        Files.write(root.resolve("big.bin"), content);
        expectCmd("200", "TYPE I");
        passive();
        String r = cmd("RETR big.bin");
        assertTrue(r, r.startsWith("150"));
        pump();
        assertArrayEquals(content, transport.lastEndpoint().sentBytes());
        assertFalse(transport.lastEndpoint().isOpen());
        assertNotNull(control.findLineStartingWith("226"));
        assertTrue("listener released on accept", transport.lastListener().closed);
    }

    @Test
    public void passiveRetrHonoursRestartOffset() throws Exception {
        byte[] content = pattern(5000);
        Files.write(root.resolve("f.bin"), content);
        expectCmd("200", "TYPE I");
        passive();
        expectCmd("350", "REST 1234");
        cmd("RETR f.bin");
        pump();
        byte[] expected = new byte[5000 - 1234];
        System.arraycopy(content, 1234, expected, 0, expected.length);
        assertArrayEquals(expected, transport.lastEndpoint().sentBytes());
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void passiveRetrAsciiConvertsLineEndings() throws Exception {
        Files.write(root.resolve("t.txt"),
                "one\ntwo\nthree\n".getBytes(StandardCharsets.US_ASCII));
        expectCmd("200", "TYPE A");
        passive();
        cmd("RETR t.txt");
        pump();
        assertEquals("one\r\ntwo\r\nthree\r\n", sentText());
    }

    @Test
    public void passiveRetrOfMissingFileFails() throws Exception {
        passive();
        String r = cmd("RETR nothing.bin");
        assertTrue(r, r.contains("150"));
        assertTrue(r, r.contains("450"));
        assertNull(control.findLineStartingWith("226"));
    }

    @Test
    public void passiveRetrWhenDataConnectionComesLater() throws Exception {
        byte[] content = pattern(3000);
        Files.write(root.resolve("late.bin"), content);
        expectCmd("200", "TYPE I");
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        cmd("RETR late.bin");
        assertEquals("parked until the client connects", 0,
                transport.endpoints.size());
        transport.lastListener().accept(dataChannel("127.0.0.1"));
        pump();
        assertArrayEquals(content, transport.lastEndpoint().sentBytes());
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void passiveTimeoutFailsTransfer() throws Exception {
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        Files.write(root.resolve("x"), new byte[1]);
        cmd("RETR x");
        int fired = control.fireTimers();
        assertEquals(1, fired);
        assertNotNull(control.findLineStartingWith("450"));
        assertEquals(0, transport.endpoints.size());
    }

    @Test
    public void connectionArrivingAfterTimeoutIsQueuedNotDelivered() throws Exception {
        cmd("PASV");
        Files.write(root.resolve("x"), new byte[1]);
        cmd("RETR x");
        control.fireTimers();
        StubSocketChannel ch = dataChannel("127.0.0.1");
        transport.lastListener().accept(ch);
        assertEquals(0, transport.endpoints.size());
    }

    @Test
    public void passiveDataFromForeignAddressIsRefused() throws Exception {
        cmd("PASV");
        StubSocketChannel foreign = dataChannel("10.9.8.7");
        transport.lastListener().accept(foreign);
        assertTrue("foreign data connection closed", foreign.getCloseCount() > 0);
        Files.write(root.resolve("x"), new byte[1]);
        cmd("RETR x");
        assertEquals("foreign connection must not be used", 0,
                transport.endpoints.size());
    }

    @Test
    public void passiveListenerFailureReplies425() throws Exception {
        transport.failListen(new IOException("no ports"));
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("425"));
        assertFalse(handler.hasPassiveListener());
    }

    @Test
    public void secondPasvReleasesTheFirstListener() throws Exception {
        cmd("PASV");
        MockFtpDataTransport.MockListener first = transport.lastListener();
        cmd("PASV");
        assertTrue(first.closed);
        assertEquals(2, transport.listeners.size());
    }

    @Test
    public void epsvOpensPassiveListener() throws Exception {
        String r = cmd("EPSV");
        assertTrue(r, r.startsWith("229"));
        assertEquals(1, transport.listeners.size());
    }

    // ── Listings ──

    private void prepareListing() throws IOException {
        Files.write(root.resolve("a.txt"), "hello".getBytes(StandardCharsets.US_ASCII));
        Files.write(root.resolve("b.bin"), new byte[2048]);
        Files.createDirectories(root.resolve("sub"));
    }

    @Test
    public void passiveListNlstAndMlsd() throws Exception {
        prepareListing();
        passive();
        cmd("LIST");
        pump();
        String list = sentText();
        assertTrue(list, list.contains("a.txt"));
        assertTrue(list, list.contains("b.bin"));
        assertTrue(list, list.contains("sub"));
        assertNotNull(control.findLineStartingWith("226"));

        passive();
        cmd("NLST");
        pump();
        String nlst = sentText();
        assertTrue(nlst, nlst.contains("a.txt\r\n"));
        assertFalse(nlst, nlst.contains("-rw"));

        passive();
        cmd("MLSD");
        pump();
        String mlsd = sentText();
        assertTrue(mlsd, mlsd.contains("type=file;size=5;"));
        assertTrue(mlsd, mlsd.contains("type=dir;"));
    }

    @Test
    public void mlsdHonoursSelectedFacts() throws Exception {
        prepareListing();
        expectCmd("200", "OPTS MLST type;size");
        passive();
        cmd("MLSD");
        pump();
        String mlsd = sentText();
        assertTrue(mlsd, mlsd.contains("type=file;size=5; a.txt"));
        assertFalse(mlsd, mlsd.contains("modify="));
        assertFalse(mlsd, mlsd.contains("perm="));
    }

    @Test
    public void largeListingCarriesLinesAcrossBuffers() throws Exception {
        StringBuilder name = new StringBuilder("file-with-a-rather-long-name-");
        for (int i = 0; i < 20; i++) {
            name.append("0123456789");
        }
        for (int i = 0; i < 400; i++) {
            Files.write(root.resolve(name.toString() + i), new byte[1]);
        }
        passive();
        cmd("NLST");
        pump();
        String nlst = sentText();
        int lines = 0;
        int at = nlst.indexOf("\r\n");
        while (at >= 0) {
            lines++;
            at = nlst.indexOf("\r\n", at + 2);
        }
        assertEquals(400, lines);
        assertTrue(nlst.length() > 32 * 1024);
    }

    @Test
    public void passiveListOfMissingDirectoryFails() throws Exception {
        passive();
        String r = cmd("LIST nodir");
        assertTrue(r, r.contains("450") || r.contains("550"));
        assertNull(control.findLineStartingWith("226"));
    }

    // ── Passive uploads ──

    private void upload(String command, byte[] body) throws Exception {
        passive();
        String r = cmd(command);
        assertTrue(r, r.startsWith("150"));
        feed(body);
        clientDone();
    }

    @Test
    public void passiveStorWritesFile() throws Exception {
        byte[] content = pattern(100 * 1024);
        expectCmd("200", "TYPE I");
        upload("STOR up.bin", content);
        assertArrayEquals(content, Files.readAllBytes(root.resolve("up.bin")));
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void passiveStorAsciiConvertsLineEndings() throws Exception {
        expectCmd("200", "TYPE A");
        upload("STOR t.txt", "a\r\nb\r\n".getBytes(StandardCharsets.US_ASCII));
        byte[] written = Files.readAllBytes(root.resolve("t.txt"));
        assertEquals("a\nb\n", new String(written, StandardCharsets.US_ASCII));
    }

    @Test
    public void passiveStorAsciiChunkOfOnlyCarriageReturn() throws Exception {
        expectCmd("200", "TYPE A");
        passive();
        cmd("STOR cr.txt");
        feed(new byte[] {'\r'});
        feed("x\n".getBytes(StandardCharsets.US_ASCII));
        clientDone();
        byte[] written = Files.readAllBytes(root.resolve("cr.txt"));
        assertEquals("x\n", new String(written, StandardCharsets.US_ASCII));
    }

    @Test
    public void passiveAppeAppendsToFile() throws Exception {
        Files.write(root.resolve("log.txt"), "first\n".getBytes(StandardCharsets.US_ASCII));
        expectCmd("200", "TYPE I");
        upload("APPE log.txt", "second\n".getBytes(StandardCharsets.US_ASCII));
        byte[] written = Files.readAllBytes(root.resolve("log.txt"));
        assertEquals("first\nsecond\n", new String(written, StandardCharsets.US_ASCII));
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void passiveStouCreatesUniqueFile() throws Exception {
        expectCmd("200", "TYPE I");
        upload("STOU", "unique".getBytes(StandardCharsets.US_ASCII));
        assertEquals(1, fileCount());
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void passiveStorIntoMissingDirectoryFails() throws Exception {
        passive();
        String r = cmd("STOR nodir/f.bin");
        assertTrue(r, r.contains("450") || r.contains("550"));
        assertNull(control.findLineStartingWith("226"));
    }

    @Test
    public void uploadDataQueuedUntilOpenCompletes() throws Exception {
        useQueuedStorage();
        expectCmd("200", "TYPE I");
        passive();
        cmd("STOR early.bin");
        // The storage open is queued: data and the disconnect arrive first.
        feed("abc".getBytes(StandardCharsets.US_ASCII));
        feed(new byte[0]);
        feed("def".getBytes(StandardCharsets.US_ASCII));
        clientDone();
        assertNull(control.findLineStartingWith("226"));
        queue.runAll();
        byte[] written = Files.readAllBytes(root.resolve("early.bin"));
        assertEquals("abcdef", new String(written, StandardCharsets.US_ASCII));
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void uploadWriteFailureFailsTransfer() throws Exception {
        transport.failWrites(true);
        expectCmd("200", "TYPE I");
        passive();
        cmd("STOR w.bin");
        feed("abc".getBytes(StandardCharsets.US_ASCII));
        String r = replies();
        assertTrue(r, r.contains("450"));
        assertFalse(transport.lastEndpoint().isOpen());
    }

    @Test
    public void uploadDataErrorFailsTransfer() throws Exception {
        passive();
        cmd("STOR e.bin");
        transport.lastEndpoint().handler.error(new IOException("reset"));
        String r = replies();
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void uploadDataErrorBeforeOpenCompletesFailsTransfer() throws Exception {
        useQueuedStorage();
        passive();
        cmd("STOR e.bin");
        transport.lastEndpoint().handler.error(new IOException("reset"));
        String r = replies();
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void uploadOpenFailureFailsTransfer() throws Exception {
        transport.failOpen(new IOException("disk full"));
        passive();
        String r = cmd("STOR f.bin");
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void downloadOpenFailureFailsTransfer() throws Exception {
        Files.write(root.resolve("f.bin"), new byte[10]);
        transport.failOpen(new IOException("gone"));
        passive();
        String r = cmd("RETR f.bin");
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void downloadReadFailureFailsTransfer() throws Exception {
        Files.write(root.resolve("f.bin"), new byte[10]);
        transport.failReads(true);
        passive();
        String r = cmd("RETR f.bin");
        assertTrue(r, r.contains("450"));
        assertFalse(transport.lastEndpoint().isOpen());
    }

    @Test
    public void downloadDataErrorFailsTransfer() throws Exception {
        Files.write(root.resolve("f.bin"), new byte[10]);
        passive();
        cmd("RETR f.bin");
        transport.lastEndpoint().handler.error(new IOException("reset"));
        String r = replies();
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void listingDataErrorFailsTransfer() throws Exception {
        prepareListing();
        passive();
        cmd("LIST");
        transport.lastEndpoint().handler.error(new IOException("reset"));
        String r = replies();
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void downloadAndListingIgnoreInboundData() throws Exception {
        Files.write(root.resolve("f.bin"), new byte[10]);
        passive();
        cmd("RETR f.bin");
        feed(new byte[3]);
        transport.lastEndpoint().handler.disconnected();
        passive();
        cmd("LIST");
        feed(new byte[3]);
        transport.lastEndpoint().handler.disconnected();
        assertTrue(transport.endpoints.size() >= 2);
    }

    @Test
    public void abortDuringTransferReports426() throws Exception {
        Files.write(root.resolve("f.bin"), pattern(100 * 1024));
        passive();
        cmd("RETR f.bin");
        String r = cmd("ABOR");
        assertTrue(r, r.contains("426"));
        assertTrue(r, r.contains("226"));
    }

    // ── Active mode ──

    private void activeCommand() {
        String r = cmd("PORT 127,0,0,1,195,80");
        assertTrue(r, r.startsWith("200"));
    }

    @Test
    public void activeRetrConnectsBackToClient() throws Exception {
        byte[] content = pattern(40 * 1024);
        Files.write(root.resolve("a.bin"), content);
        expectCmd("200", "TYPE I");
        activeCommand();
        cmd("RETR a.bin");
        MockFtpDataTransport.MockConnect c = transport.lastConnect();
        assertEquals(50000, c.port);
        assertEquals("127.0.0.1", c.address.getHostAddress());
        c.callback.connected(dataChannel("127.0.0.1"));
        pump();
        assertArrayEquals(content, transport.lastEndpoint().sentBytes());
        assertNotNull(control.findLineStartingWith("226"));
    }

    @Test
    public void activeEprtListAndStor() throws Exception {
        prepareListing();
        expectCmd("200", "EPRT |1|127.0.0.1|50000|");
        cmd("LIST");
        transport.lastConnect().callback.connected(dataChannel("127.0.0.1"));
        pump();
        assertTrue(sentText().contains("a.txt"));

        expectCmd("200", "TYPE I");
        activeCommand();
        cmd("STOR from-active.bin");
        transport.lastConnect().callback.connected(dataChannel("127.0.0.1"));
        feed("active".getBytes(StandardCharsets.US_ASCII));
        clientDone();
        byte[] written = Files.readAllBytes(root.resolve("from-active.bin"));
        assertEquals("active", new String(written, StandardCharsets.US_ASCII));
    }

    @Test
    public void activeConnectFailureFailsTransfer() throws Exception {
        Files.write(root.resolve("a.bin"), new byte[4]);
        activeCommand();
        cmd("RETR a.bin");
        transport.lastConnect().callback.failed(new IOException("refused"));
        String r = replies();
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void activeConnectFailureWithOtherExceptionIsWrapped() throws Exception {
        Files.write(root.resolve("a.bin"), new byte[4]);
        activeCommand();
        cmd("RETR a.bin");
        transport.lastConnect().callback.failed(new IllegalStateException("odd"));
        String r = replies();
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void activeConnectCannotStartFailsTransfer() throws Exception {
        Files.write(root.resolve("a.bin"), new byte[4]);
        transport.failConnect(new IOException("no route"));
        activeCommand();
        String r = cmd("RETR a.bin");
        assertTrue(r, r.contains("450"));
    }

    @Test
    public void activeTimeoutFailsTransferAndAbandonsConnect() throws Exception {
        Files.write(root.resolve("a.bin"), new byte[4]);
        activeCommand();
        cmd("RETR a.bin");
        MockFtpDataTransport.MockConnect c = transport.lastConnect();
        control.fireTimers();
        assertTrue(c.closed);
        assertNotNull(control.findLineStartingWith("450"));
        // a late connect or failure after the timeout is ignored
        c.callback.connected(dataChannel("127.0.0.1"));
        c.callback.failed(new IOException("late"));
        assertEquals(0, transport.endpoints.size());
    }

    @Test
    public void portToForeignAddressIsRefused() throws Exception {
        String r = cmd("PORT 10,1,2,3,195,80");
        assertTrue(r, r.startsWith("501"));
    }

    // ── PROT P ──

    @Test
    public void protPrivateRequestsSecureEndpointAndWaitsForHandshake() throws Exception {
        protPrivate();
        transport.setSecureEndpoints(true);
        Files.write(root.resolve("s.bin"), new byte[100]);
        passive();
        cmd("RETR s.bin");
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        assertEquals(Boolean.TRUE, transport.secureRequests.get(0));
        assertEquals("nothing is sent before the TLS handshake", 0,
                ep.sentBytes().length);
        ep.handler.securityEstablished(null);
        pump();
        assertEquals(100, ep.sentBytes().length);
    }

    @Test
    public void protPrivateListingWaitsForHandshake() throws Exception {
        protPrivate();
        transport.setSecureEndpoints(true);
        prepareListing();
        passive();
        cmd("LIST");
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        assertEquals(0, ep.sentBytes().length);
        ep.handler.securityEstablished(null);
        pump();
        assertTrue(sentText().contains("a.txt"));
    }

    @Test
    public void protPrivateUploadSecurityBeforeAndAfterOpen() throws Exception {
        useQueuedStorage();
        protPrivate();
        transport.setSecureEndpoints(true);
        passive();
        cmd("STOR s.bin");
        MockFtpDataTransport.MockDataEndpoint ep = transport.lastEndpoint();
        SecurityInfoStub info = new SecurityInfoStub();
        ep.handler.securityEstablished(info);
        queue.runAll();
        ep.handler.securityEstablished(info);
        feed("x".getBytes(StandardCharsets.US_ASCII));
        clientDone();
        assertEquals(1, Files.readAllBytes(root.resolve("s.bin")).length);
    }

    @Test
    public void protPrivateWithoutTlsFactoryFailsTransfer() throws Exception {
        // a listener that was never started has no transport factory
        listener = new FtpListener();
        BasicFTPFileSystem fs = new BasicFTPFileSystem(root, false);
        handler = new FtpProtocolHandler(listener, new SimpleFTPHandler(fs));
        control = new RecordingStubEndpoint(21);
        control.setSelectorLoop(gumdrop.nextWorkerLoop());
        handler.setDataTransport(transport);
        handler.connected(control);
        cmd("USER alice");
        cmd("PASS secret");
        protPrivate();
        Files.write(root.resolve("s.bin"), new byte[3]);
        passive();
        String r = cmd("RETR s.bin");
        assertTrue(r, r.contains("450"));
    }

    /** A bare {@link org.bluezoo.gumdrop.SecurityInfo} for handshake callbacks. */
    private static final class SecurityInfoStub
            implements org.bluezoo.gumdrop.SecurityInfo {
        @Override
        public String getProtocol() {
            return "TLSv1.3";
        }

        @Override
        public String getCipherSuite() {
            return "TLS_AES_128_GCM_SHA256";
        }

        @Override
        public int getKeySize() {
            return 128;
        }

        @Override
        public java.security.cert.Certificate[] getPeerCertificates() {
            return null;
        }

        @Override
        public java.security.cert.Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public String getApplicationProtocol() {
            return null;
        }

        @Override
        public long getHandshakeDurationMs() {
            return 0L;
        }

        @Override
        public boolean isSessionResumed() {
            return false;
        }
    }

    // ── Session end ──

    @Test
    public void disconnectReleasesListener() throws Exception {
        cmd("PASV");
        MockFtpDataTransport.MockListener l = transport.lastListener();
        handler.disconnected();
        assertTrue(l.closed);
    }
}
