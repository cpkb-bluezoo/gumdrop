/*
 * FtpClientDataTransferMockTest.java
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the FTP client's data connections (passive and active mode,
 * download, upload, listings, PROT P handling) with the data endpoints
 * supplied by a {@link MockDataConnector} and the active-mode listener by a
 * {@link MockActiveListenerOpener}: no socket, loop or thread is involved.
 * Control replies are scripted by the test on a stub control endpoint.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpClientDataTransferMockTest {

    private Gumdrop gumdrop;
    private FtpClientProtocolHandler handler;
    private FtpClientHandlerBranchTest.TestEndpoint control;
    private final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
    private final List<Exception> errors = new ArrayList<Exception>();
    private final MockDataConnector connector = new MockDataConnector();
    private final MockActiveListenerOpener listeners = new MockActiveListenerOpener();
    private final InetSocketAddress dataAddress = new InetSocketAddress("127.0.0.1", 40123);

    private final class Greeting implements RemoteGreeting {
        @Override
        public void handleGreeting(ClientLoginState login, String message) {
        }

        @Override
        public void handleServiceUnavailable(String message) {
        }

        @Override
        public void onConnected(org.bluezoo.gumdrop.Endpoint ep) {
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

    /** Records a download. */
    private static final class Download implements RetrReplyHandler {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        boolean complete;
        String failure;

        @Override
        public void handleServiceClosing(String message) {
            failure = "closing";
        }

        @Override
        public void handleContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            content.write(b, 0, b.length);
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState a) {
            complete = true;
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            failure = code + ":" + message;
        }
    }

    /** Records an upload, writing {@code data} and finishing when the sink is ready. */
    private static final class Upload implements StorReplyHandler {
        final byte[] data;
        int readyCount;
        boolean complete;
        String failure;

        Upload(byte[] data) {
            this.data = data;
        }

        @Override
        public void handleServiceClosing(String message) {
            failure = "closing";
        }

        @Override
        public void handleReadyToSend(ClientDataSink sink) {
            readyCount++;
            sink.write(ByteBuffer.wrap(data));
            sink.finish();
            sink.finish();
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState a) {
            complete = true;
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            failure = code + ":" + message;
        }
    }

    /** Records a listing. */
    private static final class Listing implements ListReplyHandler {
        List<FtpFileEntry> entries;
        String failure;

        @Override
        public void handleServiceClosing(String message) {
            failure = "closing";
        }

        @Override
        public void handleEntries(List<FtpFileEntry> list, ClientAuthenticatedState a) {
            entries = list;
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            failure = code + ":" + message;
        }
    }

    /** Records the outcome of a simple command. */
    private static final class Simple implements PortReplyHandler, SimpleReplyHandler {
        String result;

        @Override
        public void handleServiceClosing(String message) {
            result = "closing";
        }

        @Override
        public void handleOk(ClientAuthenticatedState a) {
            result = "ok";
        }

        @Override
        public void handleError(ClientAuthenticatedState a, int code, String message) {
            result = "error:" + code + ":" + message;
        }
    }

    @Before
    public void setUp() {
        gumdrop = TestGumdrop.create();
        control = new FtpClientHandlerBranchTest.TestEndpoint(sent);
        handler = new FtpClientProtocolHandler(new Greeting());
        handler.setGumdrop(gumdrop);
        handler.activeListenerOpener = listeners;
        handler.dataConnector = connector;
        handler.connected(control);
        reply("220 ready\r\n");
    }

    private void reply(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private String lastSent() {
        if (sent.isEmpty()) {
            return "";
        }
        return sent.get(sent.size() - 1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void deliver(org.bluezoo.gumdrop.ProtocolHandler h, String s) {
        h.receive(ByteBuffer.wrap(bytes(s)));
    }

    private StubSocketChannel serverChannel() {
        return new StubSocketChannel(new InetSocketAddress("127.0.0.1", 40000),
                new InetSocketAddress("127.0.0.1", 20));
    }

    /** PORT, answered 200; leaves the listener open. */
    private void port() {
        Simple s = new Simple();
        handler.port(s);
        assertTrue(lastSent(), lastSent().startsWith("PORT 127,0,0,1,"));
        reply("200 ok\r\n");
        assertEquals("ok", s.result);
    }

    // ── passive mode ──

    @Test
    public void passiveDownloadControlReplyFirst() {
        Download dl = new Download();
        handler.retr("f.txt", dataAddress, dl);
        MockDataConnector.Connect c = connector.lastConnect();
        assertEquals(dataAddress, c.address);
        MockDataConnector.MockDataEndpoint ep = new MockDataConnector.MockDataEndpoint();
        c.handler.connected(ep);
        assertEquals("RETR f.txt", lastSent());
        reply("150 opening\r\n");
        deliver(c.handler, "hello ");
        deliver(c.handler, "passive");
        reply("226 done\r\n");
        assertFalse(dl.complete);
        c.handler.disconnected();
        assertTrue(dl.complete);
        assertNull(dl.failure);
        assertEquals("hello passive", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void passiveDownloadDataEofFirst() {
        Download dl = new Download();
        handler.retr("f.txt", dataAddress, dl);
        MockDataConnector.Connect c = connector.lastConnect();
        c.handler.connected(new MockDataConnector.MockDataEndpoint());
        deliver(c.handler, "eof first");
        c.handler.disconnected();
        assertFalse(dl.complete);
        reply("226 done\r\n");
        assertTrue(dl.complete);
        assertEquals("eof first", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void passiveDownloadRejectedByControlReply() {
        Download dl = new Download();
        handler.retr("nope", dataAddress, dl);
        MockDataConnector.Connect c = connector.lastConnect();
        c.handler.connected(new MockDataConnector.MockDataEndpoint());
        reply("550 not found\r\n");
        assertEquals("550:not found", dl.failure);
        assertFalse(dl.complete);
    }

    @Test
    public void passiveDownloadDataConnectionErrorFailsTransfer() {
        Download dl = new Download();
        handler.retr("f", dataAddress, dl);
        connector.lastConnect().handler.error(new IOException("reset"));
        assertEquals("0:reset", dl.failure);
    }

    @Test
    public void passiveConnectThatCannotStartFailsTransfer() {
        connector.failConnect(new IOException("no route"));
        Download dl = new Download();
        handler.retr("f", dataAddress, dl);
        assertEquals("0:no route", dl.failure);
        assertTrue(connector.connects.isEmpty());
    }

    @Test
    public void passiveUploadStorDeliversBytes() {
        Upload up = new Upload(bytes("uploaded"));
        handler.stor("u.bin", dataAddress, up);
        MockDataConnector.Connect c = connector.lastConnect();
        MockDataConnector.MockDataEndpoint ep = new MockDataConnector.MockDataEndpoint();
        c.handler.connected(ep);
        assertEquals("STOR u.bin", sent.get(sent.size() - 1));
        assertEquals(1, up.readyCount);
        assertEquals("uploaded", new String(ep.sentBytes(), StandardCharsets.UTF_8));
        assertFalse("finish closes a plaintext data connection", ep.isOpen());
        assertFalse(up.complete);
        reply("226 stored\r\n");
        assertTrue(up.complete);
        assertNull(up.failure);
        c.handler.receive(ByteBuffer.wrap(new byte[0]));
        c.handler.disconnected();
    }

    @Test
    public void passiveUploadAppeSendsAppe() {
        Upload up = new Upload(bytes("more"));
        handler.appe("u.bin", dataAddress, up);
        connector.lastConnect().handler.connected(new MockDataConnector.MockDataEndpoint());
        assertEquals("APPE u.bin", lastSent());
        reply("226 ok\r\n");
        assertTrue(up.complete);
    }

    @Test
    public void securedUploadWaitsForHandshakeAndClosesWhenIdle() {
        Upload up = new Upload(bytes("secret"));
        handler.stor("s.bin", dataAddress, up);
        MockDataConnector.Connect c = connector.lastConnect();
        MockDataConnector.MockDataEndpoint ep = new MockDataConnector.MockDataEndpoint();
        ep.secure = true;
        c.handler.connected(ep);
        assertEquals("not ready before the TLS handshake", 0, up.readyCount);
        c.handler.securityEstablished(null);
        assertEquals(1, up.readyCount);
        assertEquals("secret", new String(ep.sentBytes(), StandardCharsets.UTF_8));
        assertEquals(1, ep.idleCloseRequests);
        assertTrue("a secured upload is closed by the transport when idle", ep.isOpen());
        c.handler.disconnected();
        reply("226 stored\r\n");
        assertTrue(up.complete);
    }

    @Test
    public void uploadDataConnectionErrorFailsTransfer() {
        Upload up = new Upload(bytes("x"));
        handler.stor("u.bin", dataAddress, up);
        connector.lastConnect().handler.error(new IOException("reset"));
        assertEquals("0:reset", up.failure);
    }

    @Test
    public void passiveListParsesUnixLines() {
        Listing l = new Listing();
        handler.list("/pub", dataAddress, l);
        MockDataConnector.Connect c = connector.lastConnect();
        c.handler.connected(new MockDataConnector.MockDataEndpoint());
        assertEquals("LIST /pub", lastSent());
        deliver(c.handler, "-rw-r--r-- 1 ftp ftp 1234 Jan 15 10:30 a.txt\r\n");
        deliver(c.handler, "drwxr-xr-x 2 ftp ftp 4096 Jan 15 10:30 sub\r\n");
        c.handler.disconnected();
        reply("226 done\r\n");
        assertEquals(2, l.entries.size());
        assertEquals("a.txt", l.entries.get(0).getName());
        assertEquals(1234L, l.entries.get(0).getSize());
        assertTrue(l.entries.get(1).isDirectory());
    }

    @Test
    public void passiveNlstParsesBareNames() {
        Listing l = new Listing();
        handler.nlst(null, dataAddress, l);
        MockDataConnector.Connect c = connector.lastConnect();
        c.handler.connected(new MockDataConnector.MockDataEndpoint());
        assertEquals("NLST", lastSent());
        deliver(c.handler, "alpha\r\nbeta\r\n");
        c.handler.disconnected();
        reply("226 done\r\n");
        assertEquals(2, l.entries.size());
        assertEquals("beta", l.entries.get(1).getName());
    }

    @Test
    public void passiveNlstHandlesMixedLineEndingsAndBlankLines() {
        Listing l = new Listing();
        handler.nlst(null, dataAddress, l);
        MockDataConnector.Connect c = connector.lastConnect();
        c.handler.connected(new MockDataConnector.MockDataEndpoint());
        deliver(c.handler, "alpha\nbeta\r\n\r\n\ngamma");
        c.handler.disconnected();
        reply("226 done\r\n");
        assertEquals(3, l.entries.size());
        assertEquals("alpha", l.entries.get(0).getName());
        assertEquals("beta", l.entries.get(1).getName());
        assertEquals("gamma", l.entries.get(2).getName());
    }

    @Test
    public void passiveMlsdParsesFacts() {
        Listing l = new Listing();
        handler.mlsd("", dataAddress, l);
        MockDataConnector.Connect c = connector.lastConnect();
        c.handler.connected(new MockDataConnector.MockDataEndpoint());
        assertEquals("MLSD", lastSent());
        deliver(c.handler, "type=file;size=5;modify=20250115103000;perm=r; a.txt\r\n");
        deliver(c.handler, "type=dir;modify=20250115103000;perm=el; sub\r\n");
        c.handler.disconnected();
        reply("226 done\r\n");
        assertEquals(2, l.entries.size());
        assertEquals("a.txt", l.entries.get(0).getName());
        assertEquals(5L, l.entries.get(0).getSize());
        assertTrue(l.entries.get(1).isDirectory());
    }

    @Test
    public void passiveListRejectedByControlReply() {
        Listing l = new Listing();
        handler.list("x", dataAddress, l);
        connector.lastConnect().handler.connected(new MockDataConnector.MockDataEndpoint());
        reply("550 denied\r\n");
        assertEquals("550:denied", l.failure);
        assertNull(l.entries);
    }

    @Test
    public void listingDataErrorFailsTransfer() {
        Listing l = new Listing();
        handler.list("x", dataAddress, l);
        connector.lastConnect().handler.error(new IOException("reset"));
        assertEquals("0:reset", l.failure);
    }

    // ── PROT P ──

    @Test
    public void protPrivateSelectsSecureFactoryForPassiveConnect() {
        Simple p = new Simple();
        handler.prot("P", p);
        reply("200 PROT P ok\r\n");
        assertEquals("ok", p.result);
        Download dl = new Download();
        handler.retr("f", dataAddress, dl);
        assertNotNull(connector.lastConnect().factory);
        assertTrue(connector.lastConnect().factory.isSecure());
    }

    @Test
    public void plainPassiveConnectUsesPlainFactory() {
        Download dl = new Download();
        handler.retr("f", dataAddress, dl);
        assertNotNull(connector.lastConnect().factory);
        assertFalse(connector.lastConnect().factory.isSecure());
    }

    @Test
    public void protPrivateIsRefusedForActiveMode() {
        port();
        Simple p = new Simple();
        handler.prot("P", p);
        reply("200 PROT P ok\r\n");
        Download dl = new Download();
        handler.retr("f", null, dl);
        assertNotNull(dl.failure);
        assertTrue(dl.failure, dl.failure.contains("PROT P is not supported"));
    }

    // ── active mode ──

    @Test
    public void activeDownloadWhenServerConnectsAfterRetr() throws Exception {
        port();
        Download dl = new Download();
        handler.retr("a.bin", null, dl);
        assertEquals("RETR a.bin", lastSent());
        MockActiveListenerOpener.Listener l = listeners.last;
        l.accept(serverChannel());
        assertTrue("listener released on accept", l.closed);
        MockDataConnector.MockDataEndpoint ep = connector.lastAdopted();
        assertNotNull(ep);
        // the adopted connection's handler is the download handler
        reply("150 ok\r\n");
        reply("226 done\r\n");
        assertFalse(dl.complete);
    }

    @Test
    public void activeDownloadDeliversContentAndCompletes() throws Exception {
        port();
        Download dl = new Download();
        handler.retr("a.bin", null, dl);
        listeners.last.accept(serverChannel());
        org.bluezoo.gumdrop.ProtocolHandler data = connector.lastAdoptedHandler();
        assertNotNull(data);
        deliver(data, "active download");
        data.disconnected();
        reply("226 done\r\n");
        assertTrue(dl.complete);
        assertEquals("active download", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void activeDownloadWhenServerConnectsBeforeRetr() throws Exception {
        port();
        listeners.last.accept(serverChannel());
        assertTrue("queued until a transfer asks for it", connector.adopted.isEmpty());
        Download dl = new Download();
        handler.retr("a.bin", null, dl);
        org.bluezoo.gumdrop.ProtocolHandler data = connector.lastAdoptedHandler();
        assertNotNull(data);
        deliver(data, "early connection");
        data.disconnected();
        reply("226 done\r\n");
        assertTrue(dl.complete);
        assertEquals("early connection", new String(dl.content.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void activeUploadDeliversBytes() throws Exception {
        port();
        Upload up = new Upload(bytes("active upload"));
        handler.stor("u.bin", null, up);
        assertEquals("STOR u.bin", lastSent());
        listeners.last.accept(serverChannel());
        MockDataConnector.MockDataEndpoint ep = connector.lastAdopted();
        assertEquals("active upload", new String(ep.sentBytes(), StandardCharsets.UTF_8));
        reply("226 stored\r\n");
        assertTrue(up.complete);
    }

    @Test
    public void activeAppendUsesAppe() throws Exception {
        port();
        Upload up = new Upload(bytes("x"));
        handler.appe("u.bin", null, up);
        assertEquals("APPE u.bin", lastSent());
        listeners.last.accept(serverChannel());
        reply("226 ok\r\n");
        assertTrue(up.complete);
    }

    @Test
    public void activeListingCommandsAreSentBeforeTheConnection() throws Exception {
        port();
        Listing l = new Listing();
        handler.list(null, null, l);
        assertEquals("LIST", lastSent());
        handler.nlst("d", null, new Listing());
        assertEquals("NLST d", lastSent());
        handler.mlsd("d", null, new Listing());
        assertEquals("MLSD d", lastSent());
    }

    @Test
    public void activeListingWithNoLinesYieldsEmptyList() throws Exception {
        port();
        Listing l = new Listing();
        handler.list(null, null, l);
        listeners.last.accept(serverChannel());
        connector.lastAdoptedHandler().disconnected();
        reply("226 done\r\n");
        assertNotNull(l.entries);
        assertEquals(0, l.entries.size());
    }

    @Test
    public void eprtAnnouncesListener() throws Exception {
        Simple s = new Simple();
        handler.eprt(s);
        assertTrue(lastSent(), lastSent().startsWith("EPRT |1|127.0.0.1|"));
        reply("200 ok\r\n");
        assertEquals("ok", s.result);
        assertNotNull(handler.activeListener());
    }

    @Test
    public void secondPortReplacesTheFirstListener() {
        port();
        MockActiveListenerOpener.Listener first = listeners.last;
        FtpClientDataConnectionCoordinator.ActiveListener oldListener = handler.activeListener();
        port();
        assertTrue(first.closed);
        FtpClientDataConnectionCoordinator.ActiveListener newListener = handler.activeListener();
        assertNotSame(oldListener, newListener);
    }

    @Test
    public void activeAdoptFailureFailsTransfer() throws Exception {
        port();
        connector.failAdopt(new IOException("channel lost"));
        Download dl = new Download();
        handler.retr("a.bin", null, dl);
        listeners.last.accept(serverChannel());
        assertEquals("0:channel lost", dl.failure);
    }

    @Test
    public void portListenerFailureIsReported() {
        MockActiveListenerOpener failing = new MockActiveListenerOpener();
        failing.failWith(new IOException("cannot bind"));
        // use a fresh session whose coordinator gets the failing opener
        FtpClientProtocolHandler h2 = new FtpClientProtocolHandler(new Greeting());
        h2.setGumdrop(gumdrop);
        h2.activeListenerOpener = failing;
        h2.dataConnector = connector;
        h2.connected(new FtpClientHandlerBranchTest.TestEndpoint(new ArrayList<String>()));
        h2.receive(ByteBuffer.wrap(bytes("220 ready\r\n")));
        Simple s = new Simple();
        h2.port(s);
        assertEquals("error:0:cannot bind", s.result);
        Simple e = new Simple();
        h2.eprt(e);
        assertEquals("error:0:cannot bind", e.result);
    }

    @Test
    public void controlDisconnectReleasesTheListener() {
        port();
        MockActiveListenerOpener.Listener l = listeners.last;
        handler.disconnected();
        assertTrue(l.closed);
    }
}
