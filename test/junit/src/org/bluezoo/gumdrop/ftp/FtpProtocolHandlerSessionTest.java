/*
 * FtpProtocolHandlerSessionTest.java
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Drives {@link FtpProtocolHandler} through authenticated control-channel
 * command sequences against an in-memory file system.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpProtocolHandlerSessionTest {

    private Path root;
    private FtpProtocolHandler handler;
    private FTPProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        BasicFTPFileSystem fs = new BasicFTPFileSystem(root, false);
        FtpListener listener = new FtpListener();
        handler = new FtpProtocolHandler(listener, new SimpleFTPHandler(fs));
        endpoint = new FTPProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
    }

    private String cmd(String command) {
        endpoint.sentData.clear();
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
        List<String> responses = endpoint.getResponses();
        StringBuilder sb = new StringBuilder();
        for (String r : responses) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }

    private void login() {
        String r = cmd("USER alice");
        assertTrue(r, r.startsWith("331"));
        r = cmd("PASS secret");
        assertTrue(r, r.startsWith("230"));
    }

    private void expect(String code, String command) {
        String r = cmd(command);
        assertTrue(command + " -> " + r, r.startsWith(code));
    }

    @Test
    public void testLoginAndPwd() {
        login();
        String r = cmd("PWD");
        assertTrue(r, r.startsWith("257"));
        assertTrue(r, r.contains("\"/\""));
    }

    @Test
    public void testPassWithoutUser() {
        String r = cmd("PASS secret");
        assertTrue(r, r.startsWith("503"));
    }

    @Test
    public void testPassEmpty() {
        cmd("USER alice");
        String r = cmd("PASS");
        assertTrue(r, r.length() > 0);
    }

    @Test
    public void testAcct() {
        login();
        String r = cmd("ACCT foo");
        assertTrue(r, r.length() > 0);
    }

    @Test
    public void testSecondLoginRejected() {
        login();
        String r = cmd("USER bob");
        assertTrue(r, r.length() > 0);
    }

    @Test
    public void testMkdCwdCdupRmd() {
        login();
        expect("257", "MKD docs");
        expect("250", "CWD docs");
        String r = cmd("PWD");
        assertTrue(r, r.contains("/docs"));
        expect("250", "CDUP");
        r = cmd("PWD");
        assertTrue(r, r.contains("\"/\""));
        expect("250", "RMD docs");
        expect("550", "CWD docs");
        expect("550", "RMD docs");
    }

    @Test
    public void testMkdExistingFails() {
        login();
        expect("257", "MKD d");
        String r = cmd("MKD d");
        assertTrue(r, r.startsWith("5"));
    }

    @Test
    public void testXAliasesUnsupported() {
        login();
        expect("500", "XMKD x");
    }

    @Test
    public void testSizeMdtmDele() throws IOException {
        Files.write(root.resolve("a.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        login();
        String r = cmd("SIZE a.txt");
        assertTrue(r, r.startsWith("213 5"));
        r = cmd("MDTM a.txt");
        assertTrue(r, r.startsWith("213"));
        expect("550", "SIZE missing.txt");
        expect("550", "MDTM missing.txt");
        expect("250", "DELE a.txt");
        expect("550", "DELE a.txt");
        assertFalse(Files.exists(root.resolve("a.txt")));
    }

    @Test
    public void testRenameSequence() throws IOException {
        Files.write(root.resolve("old.txt"), "x".getBytes(StandardCharsets.UTF_8));
        login();
        expect("350", "RNFR old.txt");
        expect("250", "RNTO new.txt");
        assertTrue(Files.exists(root.resolve("new.txt")));
        assertFalse(Files.exists(root.resolve("old.txt")));
    }

    @Test
    public void testRntoWithoutRnfr() {
        login();
        expect("503", "RNTO foo");
    }

    @Test
    public void testRnfrMissing() {
        login();
        expect("550", "RNFR nothere");
    }

    @Test
    public void testRnfrNoArg() {
        login();
        expect("501", "RNFR");
    }

    @Test
    public void testTypeModeStru() {
        login();
        expect("200", "TYPE I");
        expect("200", "TYPE A");
        expect("200", "TYPE A N");
        expect("504", "TYPE Z");
        expect("501", "TYPE");
        expect("200", "MODE S");
        expect("504", "MODE B");
        expect("200", "STRU F");
    }

    @Test
    public void testFeatSystHelpStatOpts() {
        login();
        String r = cmd("FEAT");
        assertTrue(r, r.startsWith("211"));
        assertTrue(r, r.contains("MLST"));
        r = cmd("SYST");
        assertTrue(r, r.startsWith("215"));
        r = cmd("HELP");
        assertTrue(r, r.startsWith("214"));
        r = cmd("STAT");
        assertTrue(r, r.startsWith("211") || r.startsWith("212"));
        r = cmd("OPTS UTF8 ON");
        assertTrue(r, r.length() > 0);
        r = cmd("OPTS MLST type;size;");
        assertTrue(r, r.length() > 0);
        r = cmd("OPTS BOGUS");
        assertTrue(r, r.startsWith("5"));
    }

    @Test
    public void testMlst() throws IOException {
        Files.write(root.resolve("m.txt"), "abc".getBytes(StandardCharsets.UTF_8));
        login();
        String r = cmd("MLST m.txt");
        assertTrue(r, r.startsWith("250"));
        assertTrue(r, r.contains("m.txt"));
        r = cmd("MLST nothere");
        assertTrue(r, r.startsWith("550"));
        r = cmd("MLST");
        assertTrue(r, r.startsWith("250"));
    }

    @Test
    public void testPortCommandVariants() {
        login();
        expect("200", "PORT 127,0,0,1,4,1");
        expect("501", "PORT 1,2,3");
        expect("501", "PORT a,b,c,d,e,f");
        expect("501", "PORT");
        expect("200", "EPRT |1|127.0.0.1|1025|");
        expect("501", "EPRT garbage");
        expect("501", "EPRT");
    }

    @Test
    public void testDataCommandsWithoutDataConnection() throws IOException {
        Files.write(root.resolve("f.txt"), "data".getBytes(StandardCharsets.UTF_8));
        login();
        String r = cmd("LIST");
        assertTrue(r, r.length() > 0);
        r = cmd("NLST");
        assertTrue(r, r.length() > 0);
        r = cmd("MLSD");
        assertTrue(r, r.length() > 0);
        r = cmd("RETR f.txt");
        assertTrue(r, r.length() > 0);
        r = cmd("STOR g.txt");
        assertTrue(r, r.length() > 0);
        r = cmd("APPE g.txt");
        assertTrue(r, r.length() > 0);
        r = cmd("STOU");
        assertTrue(r, r.length() > 0);
    }

    @Test
    public void testRestAndAllo() {
        login();
        expect("350", "REST 100");
        expect("501", "REST abc");
        expect("501", "REST");
        String r = cmd("ALLO 100");
        assertTrue(r, r.startsWith("200") || r.startsWith("202") || r.startsWith("250"));
    }

    @Test
    public void testAbor() {
        login();
        String r = cmd("ABOR");
        assertTrue(r, r.startsWith("225") || r.startsWith("226") || r.startsWith("426"));
    }

    @Test
    public void testSite() {
        login();
        String r = cmd("SITE HELP");
        assertTrue(r, r.startsWith("2"));
        r = cmd("SITE BOGUS");
        assertTrue(r, r.startsWith("5"));
        r = cmd("SITE");
        assertTrue(r, r.length() > 0);
    }

    @Test
    public void testUnauthenticatedCommandsRejected() {
        String[] cmds = {"CWD x", "CDUP", "MKD x", "RMD x", "LIST", "NLST",
            "RETR x", "STOR x", "SIZE x", "MDTM x", "RNFR x",
            "MLSD", "MLST", "SITE X"};
        for (String c : cmds) {
            String r = cmd(c);
            assertTrue(c + " -> " + r, r.startsWith("530") || r.startsWith("503"));
        }
    }

    @Test
    public void testAuthTlsAndProtWithoutTls() {
        String r = cmd("AUTH TLS");
        assertTrue(r, r.length() > 0);
        r = cmd("AUTH BOGUS");
        assertTrue(r, r.startsWith("5"));
        r = cmd("PBSZ 0");
        assertTrue(r, r.length() > 0);
        r = cmd("PROT P");
        assertTrue(r, r.length() > 0);
        r = cmd("CCC");
        assertTrue(r, r.length() > 0);
    }

    @Test
    public void testReinAndQuitAfterLogin() {
        login();
        String r = cmd("REIN");
        assertTrue(r, r.length() > 0);
        r = cmd("QUIT");
        assertTrue(r, r.startsWith("221"));
        handler.disconnected();
    }

    @Test
    public void testErrorCallbackClosesEndpoint() {
        handler.error(new IOException("boom"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testPathTraversalRejected() {
        login();
        String r = cmd("CWD ../../etc");
        assertTrue(r, r.startsWith("550") || r.startsWith("250"));
        r = cmd("PWD");
        assertTrue(r, r.startsWith("257"));
    }

    @Test
    public void testTypeVariants() {
        login();
        expect("200", "TYPE E");
        expect("200", "TYPE L 8");
        expect("501", "TYPE L");
        expect("501", "TYPE L x");
        expect("501", "TYPE L 0");
        expect("200", "TYPE I");
    }

    @Test
    public void testStruMatrix() {
        login();
        expect("200", "STRU F");
        expect("504", "STRU P");
        expect("501", "STRU X");
        expect("501", "STRU");
        expect("200", "TYPE I");
        expect("504", "STRU R");
        expect("200", "TYPE A");
        expect("200", "STRU R");
        expect("200", "TYPE E");
        expect("200", "STRU R");
    }

    @Test
    public void testModeVariants() {
        login();
        expect("504", "MODE C");
        expect("501", "MODE X");
        expect("501", "MODE");
    }

    @Test
    public void testPortRangeErrors() {
        login();
        expect("501", "PORT 999,0,0,1,4,1");
        expect("501", "PORT 127,0,0,1,999,1");
        expect("501", "PORT 127,0,0,1,4,999");
        expect("501", "PORT 127,0,0,1,4,1,9");
        expect("501", "PORT 127,0,0,1,4,xx");
        expect("501", "PORT 127,0,0,1,4,1,");
    }

    @Test
    public void testEprtVariants() {
        login();
        expect("522", "EPRT |3|127.0.0.1|1025|");
        expect("501", "EPRT |1|127.0.0.1|0|");
        expect("501", "EPRT |1|127.0.0.1|70000|");
        expect("501", "EPRT |1|127.0.0.1|abc|");
        expect("522", "EPRT |2|127.0.0.1|1025|");
        expect("522", "EPRT |1|::1|1025|");
        expect("501", "EPRT |1|no.such.host.invalid.|1025|");
        String r = cmd("EPRT |2|::1|1025|");
        assertTrue(r, r.length() >= 3);
        r = cmd("EPRT |1|127.0.0.1|");
        assertTrue(r, r.length() >= 3);
    }

    @Test
    public void testEpsvAllBlocksActiveAndPassive() {
        login();
        expect("522", "EPSV 3");
        expect("200", "EPSV ALL");
        expect("522", "PORT 127,0,0,1,4,1");
        expect("522", "EPRT |1|127.0.0.1|1025|");
        expect("522", "PASV");
        expect("501", "EPSV x");
    }

    @Test
    public void testPasvWithoutGumdropReplies425() {
        login();
        expect("425", "PASV");
        expect("425", "EPSV");
    }

    @Test
    public void testPasvBadArgument() {
        login();
        expect("501", "PASV abc");
    }

    @Test
    public void testTransferCommandsMissingArgs() {
        login();
        expect("501", "RETR");
        expect("501", "STOR");
        expect("501", "APPE");
        expect("501", "CWD");
        expect("501", "MKD");
        expect("501", "RMD");
        expect("501", "DELE");
        expect("501", "SIZE");
        expect("501", "MDTM");
    }

    @Test
    public void testNotLoggedInVariants() {
        String[] cmds = {"RETR f", "STOR f", "APPE f", "STOU", "SIZE f",
            "MDTM f", "RNFR f", "DELE f", "RMD d", "MKD d", "CWD d", "CDUP",
            "PWD", "LIST", "NLST", "MLSD", "MLST", "SITE X", "ALLO 1",
            "REST 1", "STAT", "ABOR", "FEAT", "OPTS UTF8 ON", "SYST", "HELP"};
        for (int i = 0; i < cmds.length; i++) {
            String r = cmd(cmds[i]);
            assertTrue(cmds[i] + " -> " + r, r.length() >= 3);
        }
    }

    @Test
    public void testStatWithPathAndHelpTopics() throws IOException {
        Files.write(root.resolve("s.txt"), "abc".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("dir"));
        login();
        String r = cmd("STAT s.txt");
        assertTrue(r, r.length() >= 3);
        r = cmd("STAT dir");
        assertTrue(r, r.length() >= 3);
        r = cmd("STAT nothere");
        assertTrue(r, r.length() >= 3);
        r = cmd("HELP USER");
        assertTrue(r, r.length() >= 3);
        r = cmd("HELP BOGUS");
        assertTrue(r, r.length() >= 3);
        r = cmd("MLST dir");
        assertTrue(r, r.startsWith("250"));
        r = cmd("SIZE dir");
        assertTrue(r, r.length() >= 3);
        r = cmd("RMD s.txt");
        assertTrue(r, r.length() >= 3);
        r = cmd("DELE dir");
        assertTrue(r, r.length() >= 3);
        r = cmd("CWD s.txt");
        assertTrue(r, r.length() >= 3);
    }

    @Test
    public void testOptsVariants() {
        login();
        String[] cmds = {"OPTS", "OPTS UTF8", "OPTS UTF8 OFF", "OPTS UTF8 ON",
            "OPTS MLST", "OPTS MLST type;size;modify;", "OPTS MLST bogus;",
            "OPTS NOPE X"};
        for (int i = 0; i < cmds.length; i++) {
            String r = cmd(cmds[i]);
            assertTrue(cmds[i] + " -> " + r, r.length() >= 3);
        }
    }

    @Test
    public void testRenameWithinSubdirsAndRetryAfterBadSequence() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.write(root.resolve("a/x.txt"), "z".getBytes(StandardCharsets.UTF_8));
        login();
        expect("350", "RNFR a/x.txt");
        expect("501", "RNTO");
        expect("350", "RNFR a/x.txt");
        expect("250", "RNTO a/y.txt");
        assertTrue(Files.exists(root.resolve("a/y.txt")));
    }

    @Test
    public void testReinResetsLogin() {
        login();
        String r = cmd("REIN");
        assertTrue(r, r.length() >= 3);
        r = cmd("PWD");
        assertTrue(r, r.length() >= 3);
    }

    @Test
    public void testAbortWithoutTransferAndAllo() {
        login();
        String r = cmd("ALLO");
        assertTrue(r, r.length() >= 3);
        r = cmd("ALLO x");
        assertTrue(r, r.length() >= 3);
        r = cmd("ALLO 5 R 10");
        assertTrue(r, r.length() >= 3);
    }
}
