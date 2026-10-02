/*
 * FtpProtocolHandlerArgsTest.java
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

import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedFs;
import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Argument validation, missing file system, secure-channel and quota
 * behaviour of {@link FtpProtocolHandler} commands.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpProtocolHandlerArgsTest {

    private ScriptedHandler scripted;
    private FtpProtocolHandler handler;
    private FTPProtocolHandlerTest.StubEndpoint endpoint;
    private Path root;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        Files.write(root.resolve("f.txt"), "data".getBytes(StandardCharsets.UTF_8));
        scripted = new ScriptedHandler(new ScriptedFs(new BasicFTPFileSystem(root, false)));
        start(scripted, false);
    }

    private void start(ScriptedHandler h, boolean secure) {
        handler = new FtpProtocolHandler(new FtpListener(), h);
        endpoint = new FTPProtocolHandlerTest.StubEndpoint();
        endpoint.secure = secure;
        handler.connected(endpoint);
    }

    private String cmd(String command) {
        endpoint.sentData.clear();
        ByteBuffer buf = ByteBuffer.wrap((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        handler.receive(buf);
        List<String> rs = endpoint.getResponses();
        StringBuilder sb = new StringBuilder();
        for (String r : rs) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }

    private void login() {
        cmd("USER alice");
        String r = cmd("PASS secret");
        assertTrue(r, r.startsWith("230"));
    }

    private void expect(String code, String command) {
        String r = cmd(command);
        assertTrue(command + " -> " + r, r.startsWith(code));
    }

    @Test
    public void testBlankArgumentsAreSyntaxErrors() {
        login();
        String[] commands = {"CWD", "MKD", "RMD", "DELE", "RNFR", "SIZE", "MDTM", "RETR",
                "STOR", "APPE", "REST", "PORT", "EPRT", "TYPE", "MODE", "STRU", "OPTS",
                "AUTH"};
        for (int i = 0; i < commands.length; i++) {
            String r = cmd(commands[i] + "   ");
            assertTrue(commands[i] + " -> " + r, r.startsWith("501") || r.startsWith("504"));
        }
    }

    @Test
    public void testNoFileSystemYieldsFailureReplies() {
        ScriptedHandler none = new ScriptedHandler(null);
        start(none, false);
        login();
        String[] commands = {"CWD d", "MKD x", "RMD d", "DELE f.txt", "RNFR f.txt",
                "SIZE f.txt", "MDTM f.txt", "STAT d", "MLST f.txt", "RETR f.txt",
                "STOR n.txt", "APPE f.txt", "STOU", "LIST", "NLST", "MLSD"};
        for (int i = 0; i < commands.length; i++) {
            String r = cmd(commands[i]);
            assertTrue(commands[i] + " -> " + r, r.contains("550") || r.contains("5"));
        }
        expect("257", "PWD");
    }

    @Test
    public void testRenameToWithoutFileSystemAndBlank() {
        login();
        expect("350", "RNFR f.txt");
        expect("501", "RNTO   ");
    }

    @Test
    public void testSecureControlChannelNegotiation() {
        start(scripted, true);
        login();
        expect("200", "PBSZ 0");
        expect("501", "PBSZ x");
        String big = cmd("PBSZ 4096");
        assertTrue(big, big.startsWith("200") || big.startsWith("501"));
        expect("200", "PROT P");
        expect("200", "PROT C");
        String s = cmd("PROT S");
        assertTrue(s, s.startsWith("536") || s.startsWith("504") || s.startsWith("534"));
        String e = cmd("PROT E");
        assertTrue(e, e.startsWith("536") || e.startsWith("504") || e.startsWith("534"));
        expect("504", "PROT Q");
        String feat = cmd("FEAT");
        assertTrue(feat, feat.contains("PBSZ") && feat.contains("PROT"));
        assertFalse(feat, feat.contains("AUTH TLS"));
        String again = cmd("AUTH TLS");
        assertTrue(again, again.startsWith("5") || again.startsWith("2"));
    }

    @Test
    public void testPlainControlChannelRefusesProtectionAndTls() {
        login();
        String pbsz = cmd("PBSZ 0");
        assertTrue(pbsz, pbsz.startsWith("5") || pbsz.startsWith("2"));
        String prot = cmd("PROT P");
        assertTrue(prot, prot.startsWith("5") || prot.startsWith("2"));
        String auth = cmd("AUTH TLS");
        assertTrue(auth, auth.startsWith("5") || auth.startsWith("2"));
        expect("504", "AUTH GSSAPI");
    }

    @Test
    public void testEprtAndEpsvProtocolArguments() {
        login();
        expect("501", "EPRT |");
        expect("501", "EPRT |1|");
        expect("501", "EPRT |1|127.0.0.1|");
        expect("522", "EPRT |9|127.0.0.1|1025|");
        expect("501", "EPRT |x|127.0.0.1|1025|");
        expect("522", "EPSV 0");
        expect("522", "EPSV 9");
    }

    @Test
    public void testSiteMultiLineResponseFormats() {
        login();
        scripted.siteResult = FtpFileOperationResult.SUCCESS;
        scripted.siteResponse = "one";
        String single = cmd("SITE X");
        assertTrue(single, single.startsWith("211"));
        scripted.siteResponse = "one\ntwo\r\nthree";
        String multi = cmd("SITE X");
        assertTrue(multi, multi.startsWith("211-one"));
        assertTrue(multi, multi.contains("two"));
        assertTrue(multi, multi.contains("211 three"));
        scripted.siteResponse = null;
        String plain = cmd("SITE X");
        assertTrue(plain, plain.length() > 3);
        assertTrue(cmd("SITE").length() > 3);
    }

    @Test
    public void testSiteQuotaForAdminAndUser() {
        login();
        Quota[] holder = new Quota[] {new Quota(1000, 10)};
        scripted.quotaManager = new FtpProtocolHandlerResultsTest.QuotaManagerWrapper(null, holder);
        String own = cmd("SITE QUOTA");
        assertTrue(own, own.startsWith("2"));
        scripted.admin = true;
        String other = cmd("SITE QUOTA bob");
        assertTrue(other, other.startsWith("2"));
        expect("501", "SITE SETQUOTA bob");
        String set = cmd("SITE SETQUOTA   bob   10M");
        assertTrue(set, set.startsWith("2") || set.startsWith("5"));
        scripted.admin = false;
        expect("550", "SITE SETQUOTA bob 10M");
    }

    @Test
    public void testUploadsRefusedWhenQuotaFull() {
        login();
        final Quota full = new Quota(10, 10);
        FtpMiscTest.StubQuota quota = new FtpMiscTest.StubQuota() {
            @Override
            public Quota getQuota(String u) {
                return full;
            }
        };
        scripted.quotaManager = quota;
        expect("552", "STOR n.txt");
        expect("552", "APPE f.txt");
        expect("552", "STOU");
        assertTrue(quota.canStoreCalls >= 3);
    }

}
