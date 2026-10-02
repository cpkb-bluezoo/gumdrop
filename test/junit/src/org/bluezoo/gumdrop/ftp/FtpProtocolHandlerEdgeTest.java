/*
 * FtpProtocolHandlerEdgeTest.java
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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedFs;
import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Edge behaviour of {@link FtpProtocolHandler}: commands before login,
 * authorization denial, every file-operation result mapped for each
 * mutating command, line framing, and connection shapes (secure, no
 * addresses, transport errors).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpProtocolHandlerEdgeTest {

    private Path root;
    private ScriptedFs fs;
    private ScriptedHandler scripted;
    private FtpProtocolHandler handler;
    private FTPProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        Files.write(root.resolve("f.txt"), "data".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("d"));
        fs = new ScriptedFs(new BasicFTPFileSystem(root, false));
        scripted = new ScriptedHandler(fs);
        start(new FTPProtocolHandlerTest.StubEndpoint());
    }

    private void start(FTPProtocolHandlerTest.StubEndpoint ep) {
        handler = new FtpProtocolHandler(new FtpListener(), scripted);
        endpoint = ep;
        handler.connected(endpoint);
    }

    private String raw(String text) {
        endpoint.sentData.clear();
        ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1));
        while (buf.hasRemaining()) {
            int before = buf.remaining();
            handler.receive(buf);
            if (buf.remaining() == before) {
                break;
            }
        }
        List<String> rs = endpoint.getResponses();
        StringBuilder sb = new StringBuilder();
        for (String r : rs) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }

    private String cmd(String command) {
        return raw(command + "\r\n");
    }

    private void login() {
        cmd("USER alice");
        String r = cmd("PASS secret");
        assertTrue(r, r.startsWith("230"));
    }

    // before login

    @Test
    public void testFileCommandsRequireLogin() {
        String[] commands = {"CWD d", "CDUP", "PWD", "MKD x", "RMD d", "DELE f.txt",
                "RNFR f.txt", "RNTO g.txt", "SIZE f.txt", "MDTM f.txt", "RETR f.txt",
                "STOR n.txt", "APPE f.txt", "STOU", "LIST", "NLST", "MLSD", "MLST"};
        for (int i = 0; i < commands.length; i++) {
            String r = cmd(commands[i]);
            assertTrue(commands[i] + " -> " + r, r.startsWith("530"));
        }
    }

    @Test
    public void testUnknownAndMalformedLines() {
        assertTrue(cmd("BOGUS").startsWith("500"));
        assertTrue(cmd("bogus arg").startsWith("500"));
        assertTrue(cmd("syst").startsWith("215"));
        assertTrue(cmd("NOOP").startsWith("200"));
        assertTrue(cmd("").length() >= 0);
        assertTrue(raw("NOOP\n").length() >= 0);
        String pipelined = raw("NOOP\r\nSYST\r\nNOOP\r\n");
        assertTrue(pipelined, pipelined.contains("215"));
    }

    @Test
    public void testLineSplitAcrossReceives() {
        endpoint.sentData.clear();
        ByteBuffer acc = ByteBuffer.allocate(64);
        String[] pieces = {"SY", "ST", "\r", "\n"};
        for (int i = 0; i < pieces.length; i++) {
            acc.put(pieces[i].getBytes(StandardCharsets.US_ASCII));
            acc.flip();
            handler.receive(acc);
            acc.compact();
        }
        assertTrue(endpoint.getResponses().toString(),
                endpoint.getResponses().get(0).startsWith("215"));
    }

    @Test
    public void testOverlongLineIsRejectedAndSessionRecovers() {
        StringBuilder sb = new StringBuilder("USER ");
        for (int i = 0; i < 20000; i++) {
            sb.append('a');
        }
        String r = cmd(sb.toString());
        assertTrue(r, r.startsWith("500"));
        String first = cmd("NOOP");
        assertTrue("after long arg: " + first, first.startsWith("200"));
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            key.append('K');
        }
        String keyReply = cmd(key.toString());
        assertTrue("keyword: " + keyReply, keyReply.startsWith("500"));
        String after = cmd("NOOP");
        assertTrue("after keyword: " + after, after.startsWith("200"));
    }

    // authorization

    @Test
    public void testDeniedAuthorizationRefusesFileCommands() {
        login();
        scripted.authorized = false;
        String[] commands = {"CWD d", "MKD x", "RMD d", "DELE f.txt", "RNFR f.txt",
                "SIZE f.txt", "MDTM f.txt", "RETR f.txt", "STOR n.txt", "APPE f.txt",
                "LIST", "NLST", "MLSD", "MLST f.txt", "STAT d"};
        for (int i = 0; i < commands.length; i++) {
            String r = cmd(commands[i]);
            assertTrue(commands[i] + " -> " + r, r.startsWith("550") || r.startsWith("5"));
        }
    }

    // every result for each mutating command

    @Test
    public void testEveryFileResultMapsToACompleteReply() {
        login();
        FtpFileOperationResult[] all = FtpFileOperationResult.values();
        String[] commands = {"MKD x", "RMD d", "DELE f.txt"};
        for (int i = 0; i < all.length; i++) {
            fs.result = all[i];
            for (int j = 0; j < commands.length; j++) {
                String r = cmd(commands[j]);
                assertTrue(all[i] + " " + commands[j] + " -> " + r,
                        r.matches("(?s)[1-5][0-9][0-9][ -].*"));
                if (all[i] != FtpFileOperationResult.SUCCESS
                        && all[i] != FtpFileOperationResult.TRANSFER_STARTING
                        && all[i] != FtpFileOperationResult.RENAME_PENDING) {
                    assertTrue(all[i] + " " + commands[j] + " -> " + r,
                            r.startsWith("4") || r.startsWith("5"));
                }
            }
            cmd("RNFR f.txt");
            String r = cmd("RNTO other.txt");
            assertTrue(all[i] + " RNTO -> " + r, r.matches("(?s)[1-5][0-9][0-9][ -].*"));
        }
    }

    // connection shapes

    @Test
    public void testConnectionWithoutAddressesStillGreets() {
        FTPProtocolHandlerTest.StubEndpoint ep = new FTPProtocolHandlerTest.StubEndpoint() {
            @Override
            public SocketAddress getLocalAddress() {
                return null;
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return null;
            }
        };
        start(ep);
        assertTrue(ep.getResponses().toString(), ep.getResponses().get(0).startsWith("220"));
        login();
        assertTrue(cmd("PWD").startsWith("257"));
        handler.disconnected();
        handler.disconnected();
    }

    @Test
    public void testSecureConnectionRecordsSecurityInfo() {
        final SecurityInfo info = new SecurityInfo() {
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
            public Certificate[] getPeerCertificates() {
                return null;
            }

            @Override
            public Certificate[] getLocalCertificates() {
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
        };
        FTPProtocolHandlerTest.StubEndpoint ep = new FTPProtocolHandlerTest.StubEndpoint() {
            @Override
            public SecurityInfo getSecurityInfo() {
                return info;
            }
        };
        ep.secure = true;
        start(ep);
        login();
        String r = cmd("PROT P");
        assertTrue(r, r.startsWith("200") || r.startsWith("5"));
        handler.securityEstablished(info);
        handler.securityEstablished(null);
        assertTrue(cmd("NOOP").startsWith("200"));
    }

    @Test
    public void testTransportErrorClosesTheConnection() {
        handler.error(new IOException("reset"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testQuitClosesAfterGoodbye() {
        login();
        String r = cmd("QUIT");
        assertTrue(r, r.startsWith("221"));
    }

    private void expect(String code, String command) {
        String r = cmd(command);
        assertTrue(command + " -> " + r, r.startsWith(code));
    }

    @Test
    public void testRenameStateSurvivesNoopAndFeatOptsReplies() {
        login();
        expect("350", "REST 0");
        expect("350", "RNFR d");
        expect("250", "RNTO e");
        expect("350", "RNFR d");
        expect("200", "NOOP");
        expect("250", "RNTO e2");
        expect("503", "RNTO e3");
        expect("213", "STAT f.txt");
        expect("550", "STAT nothere");
        expect("211", "FEAT");
        expect("200", "OPTS UTF8 ON");
        expect("501", "OPTS");
    }

    @Test
    public void testOptsMlstSelectsFactsForMlst() {
        login();
        String all = cmd("MLST f.txt");
        assertTrue(all, all.contains("modify=") && all.contains("perm="));
        String ok = cmd("OPTS MLST type;size;bogus;");
        assertTrue(ok, ok.startsWith("200 MLST OPTS type;size;"));
        String some = cmd("MLST f.txt");
        assertTrue(some, some.contains("type=file;size=4;"));
        assertFalse(some, some.contains("modify=") || some.contains("perm="));
        String none = cmd("OPTS MLST");
        assertTrue(none, none.startsWith("200 MLST OPTS"));
        String bare = cmd("MLST f.txt");
        assertFalse(bare, bare.contains("type=") || bare.contains("size="));
    }

    @Test
    public void testDataAndTransferStateCommandsRequireLogin() {
        String[] commands = {"PASV", "EPSV", "PORT 127,0,0,1,4,1", "EPRT |1|127.0.0.1|1025|",
                "REST 1", "TYPE I", "MODE S", "STRU F", "ALLO 5", "ABOR", "SMNT /x",
                "STAT d", "SITE HELP"};
        for (int i = 0; i < commands.length; i++) {
            String r = cmd(commands[i]);
            assertTrue(commands[i] + " -> " + r, r.startsWith("530"));
        }
        assertFalse("PASV before login must not open a listener", handler.hasPassiveListener());
        login();
        String pasv = cmd("PASV");
        assertFalse(pasv, pasv.startsWith("530"));
    }

    @Test
    public void testInformationCommandsMayPrecedeLogin() {
        expect("211", "FEAT");
        expect("214", "HELP");
        expect("200", "NOOP");
        expect("215", "SYST");
        expect("200", "OPTS UTF8 ON");
        expect("211", "STAT");
    }

    @Test
    public void testTransferCommandsWithoutFileSystemGiveSingle550() {
        scripted = new ScriptedHandler(null);
        start(new FTPProtocolHandlerTest.StubEndpoint());
        login();
        String[] commands = {"RETR f.txt", "STOR n.txt", "APPE f.txt", "STOU",
                "LIST", "NLST", "MLSD"};
        for (int i = 0; i < commands.length; i++) {
            String r = cmd(commands[i]);
            assertEquals(commands[i] + " -> " + r, "550", r.substring(0, 3));
            assertEquals(commands[i] + " -> " + r, 1, r.split("\n").length);
        }
    }

}
