/*
 * FtpDataTransferFailureMockTest.java
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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Failure paths of {@link FtpDataConnectionCoordinator} that the complete
 * transfer scenarios do not reach: a listener that was never started with a
 * runtime, a handler whose file system disappears between the transfer
 * command and the arrival of the data connection, and active-mode peers that
 * are not the control client, plus malformed transfer-parameter, TLS
 * negotiation, SITE and ALLO arguments. Everything runs over a {@link
 * MockFtpDataTransport}, so no socket, loop or thread is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpDataTransferFailureMockTest {

    /** Handler whose file system can be withdrawn after login. */
    private static final class WithdrawableHandler extends ScriptedHandler {
        boolean withdrawn;

        WithdrawableHandler(FtpFileSystem fs) {
            super(fs);
        }

        @Override
        public FtpFileSystem getFileSystem(FtpConnectionMetadata m) {
            if (withdrawn) {
                return null;
            }
            return super.getFileSystem(m);
        }
    }

    private Path root;
    private Gumdrop gumdrop;
    private FtpListener listener;
    private WithdrawableHandler scripted;
    private FtpProtocolHandler handler;
    private RecordingStubEndpoint control;
    private MockFtpDataTransport transport;

    @Before
    public void setUp() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        Files.write(root.resolve("f.txt"), "data".getBytes(StandardCharsets.US_ASCII));
        gumdrop = TestGumdrop.create();
        listener = new FtpListener();
        scripted = new WithdrawableHandler(new BasicFTPFileSystem(root, false));
        handler = new FtpProtocolHandler(listener, scripted);
        control = new RecordingStubEndpoint(21);
        control.setSelectorLoop(gumdrop.nextWorkerLoop());
        transport = new MockFtpDataTransport(gumdrop.nextWorkerLoop());
        handler.setDataTransport(transport);
        handler.connected(control);
        cmd("USER alice");
        cmd("PASS secret");
        assertNotNull(control.findLineStartingWith("230"));
    }

    private String cmd(String command) {
        control.clearResponses();
        byte[] bytes = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(bytes));
        List<String> lines = control.getResponses();
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private StubSocketChannel dataChannel() {
        return new StubSocketChannel(new InetSocketAddress("127.0.0.1", 40000),
                new InetSocketAddress("127.0.0.1", 50000));
    }

    private void passiveConnection() throws Exception {
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        transport.lastListener().accept(dataChannel());
    }

    private void assertTransferFailed(String reply) {
        assertTrue(reply, reply.contains("4") || reply.contains("5"));
        assertNull(control.findLineStartingWith("226"));
    }

    @Test
    public void downloadWithoutRuntimeFails() throws Exception {
        passiveConnection();
        assertTransferFailed(cmd("RETR f.txt"));
    }

    @Test
    public void listingWithoutRuntimeFails() throws Exception {
        passiveConnection();
        assertTransferFailed(cmd("LIST"));
    }

    @Test
    public void uploadWithoutRuntimeFails() throws Exception {
        passiveConnection();
        assertTransferFailed(cmd("STOR up.bin"));
    }

    @Test
    public void downloadFailsWhenFileSystemIsWithdrawnBeforeTheDataConnection() throws Exception {
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        cmd("RETR f.txt");
        scripted.withdrawn = true;
        transport.lastListener().accept(dataChannel());
        assertNull(control.findLineStartingWith("226"));
        assertNotNull(control.findLineStartingWith("4"));
    }

    @Test
    public void listingFailsWhenFileSystemIsWithdrawnBeforeTheDataConnection() throws Exception {
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        cmd("LIST");
        scripted.withdrawn = true;
        transport.lastListener().accept(dataChannel());
        assertNull(control.findLineStartingWith("226"));
    }

    @Test
    public void uploadFailsWhenFileSystemIsWithdrawnBeforeTheDataConnection() throws Exception {
        String r = cmd("PASV");
        assertTrue(r, r.startsWith("227"));
        cmd("STOR up.bin");
        scripted.withdrawn = true;
        transport.lastListener().accept(dataChannel());
        assertNull(control.findLineStartingWith("226"));
    }

    @Test
    public void activeModeWithoutRuntimeFailsTheTransfer() throws Exception {
        String r = cmd("PORT 127,0,0,1,195,80");
        assertTrue(r, r.startsWith("200"));
        assertTransferFailed(cmd("RETR f.txt"));
        assertEquals(0, transport.connects.size());
    }

    @Test
    public void activeModeWithoutLoopFailsTheTransfer() throws Exception {
        listener.start(gumdrop);
        control.setSelectorLoop(null);
        String r = cmd("PORT 127,0,0,1,195,80");
        assertTrue(r, r.startsWith("200"));
        assertTransferFailed(cmd("RETR f.txt"));
        assertEquals(0, transport.connects.size());
        assertNull(control.findLineStartingWith("226"));
    }

    @Test
    public void transferWithoutAnyDataConnectionModeIsRefused() throws Exception {
        String r = cmd("RETR f.txt");
        assertTrue(r, r.contains("550"));
        assertNull(control.findLineStartingWith("226"));
        assertEquals(0, transport.endpoints.size());
    }

    @Test
    public void listenerStartedWithTelemetryCreatesMetrics() {
        FtpListener metered = new FtpListener();
        gumdrop.telemetryConfig(new org.bluezoo.gumdrop.telemetry.TelemetryConfig());
        metered.start(gumdrop);
        assertNotNull(metered.getMetrics());
        FtpListener plain = new FtpListener();
        plain.start();
        assertNull(plain.getMetrics());
    }

    private void expect(String code, String command) {
        String r = cmd(command);
        assertTrue(command + " -> " + r, r.startsWith(code));
    }

    @Test
    public void malformedPortArgumentsAreRejected() {
        expect("501", "PORT 1,2,3");
        expect("501", "PORT 256,0,0,1,195,80");
        expect("501", "PORT 127,0,0,1,256,80");
        expect("501", "PORT 127,0,0,1,195,300");
        expect("501", "PORT 127,0,0,1,195,80,9");
        expect("501", "PORT a,b,c,d,e,f,g");
        expect("501", "PORT 127,0,0,x,195,80");
        expect("200", "PORT 127,0,0,1,195,80");
    }

    @Test
    public void pasvArgumentsAreValidatedAndForwarded() {
        expect("501", "PASV abc");
        expect("227", "PASV 5000");
        assertEquals(5000, transport.lastListener().requestedPort);
        expect("227", "PASV");
    }

    @Test
    public void epsvArgumentsAreValidated() {
        expect("522", "EPSV 3");
        expect("501", "EPSV x");
        expect("229", "EPSV 1");
        expect("229", "EPSV 2");
        expect("229", "EPSV");
    }

    @Test
    public void epsvAllRefusesPortAndPasvForTheRestOfTheSession() {
        expect("200", "EPSV ALL");
        expect("522", "PASV");
        expect("522", "PORT 127,0,0,1,195,80");
        expect("229", "EPSV");
    }

    @Test
    public void authArgumentsAreValidated() {
        expect("504", "AUTH");
        expect("504", "AUTH KERBEROS");
        expect("534", "AUTH TLS");
        control.setSecure(true);
        expect("503", "AUTH TLS");
    }

    @Test
    public void pbszAndProtNeedASecureControlConnection() {
        expect("503", "PBSZ 0");
        expect("503", "PROT P");
        control.setSecure(true);
        expect("503", "PROT P");
        expect("501", "PBSZ");
        expect("501", "PBSZ abc");
        expect("200", "PBSZ 5");
        expect("200", "PBSZ 0");
        expect("501", "PROT");
        expect("200", "PROT P");
        expect("200", "PROT C");
        expect("536", "PROT S");
        expect("536", "PROT E");
        expect("504", "PROT X");
    }

    @Test
    public void siteReplyWithCrLfLinesIsFormattedAsAMultiLineReply() {
        scripted.siteResult = FtpFileOperationResult.SUCCESS;
        scripted.siteResponse = "first\r\nsecond\r\nthird";
        String r = cmd("SITE HELLO");
        assertEquals("211-first\n211-second\n211 third\n", r);
    }

    @Test
    public void siteReplyWithBareLinefeedsIsFormattedAsAMultiLineReply() {
        scripted.siteResult = FtpFileOperationResult.SUCCESS;
        scripted.siteResponse = "one\ntwo";
        String r = cmd("SITE HELLO");
        assertEquals("211-one\n211 two\n", r);
    }

    @Test
    public void siteReplyWithoutNewlinesIsASingleLine() {
        scripted.siteResult = FtpFileOperationResult.SUCCESS;
        scripted.siteResponse = "only";
        assertEquals("211 only\n", cmd("SITE HELLO"));
    }

    @Test
    public void siteResultWithoutResponseUsesTheResultReply() {
        scripted.siteResult = FtpFileOperationResult.NOT_SUPPORTED;
        expect("502", "SITE HELLO");
        scripted.siteResult = FtpFileOperationResult.SUCCESS;
        scripted.siteResponse = null;
        expect("250", "SITE HELLO");
    }

    @Test
    public void siteQuotaWithoutAQuotaManagerIsNotConfigured() {
        expect("502", "SITE QUOTA");
        expect("502", "SITE quota someone");
    }

    @Test
    public void alloValidatesItsArgument() {
        expect("501", "ALLO");
        expect("501", "ALLO abc");
        expect("200", "ALLO 100");
    }
}
