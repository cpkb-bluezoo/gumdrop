/*
 * FtpProtocolHandlerResultsTest.java
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
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaSource;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Exercises {@link FtpProtocolHandler} reply mapping for scripted
 * authentication and file-operation results, authorization denial, SITE
 * handling, quotas, TLS-state checks and telemetry spans.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpProtocolHandlerResultsTest {

    private Path root;
    private BasicFTPFileSystem real;
    private ScriptedFs fs;
    private ScriptedHandler scripted;
    private FtpProtocolHandler handler;
    private FTPProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        real = new BasicFTPFileSystem(root, false);
        fs = new ScriptedFs(real);
        scripted = new ScriptedHandler(fs);
        start(scripted);
    }

    private void start(FtpConnectionHandler h) {
        handler = new FtpProtocolHandler(new FtpListener(), h);
        endpoint = new FTPProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
    }

    private String cmd(String command) {
        endpoint.sentData.clear();
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
        StringBuilder sb = new StringBuilder();
        List<String> rs = endpoint.getResponses();
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

    // Authentication result mapping

    @Test
    public void testUserResultMapping() {
        FtpAuthenticationResult[] all = FtpAuthenticationResult.values();
        for (int i = 0; i < all.length; i++) {
            scripted.authResult = all[i];
            start(scripted);
            String r = cmd("USER alice");
            assertTrue(all[i] + " -> " + r, r.length() >= 3);
        }
    }

    @Test
    public void testPassResultMapping() {
        FtpAuthenticationResult[] all = FtpAuthenticationResult.values();
        for (int i = 0; i < all.length; i++) {
            scripted.authResult = FtpAuthenticationResult.NEED_PASSWORD;
            start(scripted);
            cmd("USER alice");
            scripted.authResult = all[i];
            String r = cmd("PASS secret");
            assertTrue(all[i] + " -> " + r, r.length() >= 3);
        }
    }

    @Test
    public void testRepeatedBadPasswordsLockTheClientOut() {
        FtpListener listener = new FtpListener();
        listener.maxAuthFailures(2);
        scripted.authResult = FtpAuthenticationResult.NEED_PASSWORD;
        handler = new FtpProtocolHandler(listener, scripted);
        endpoint = new FTPProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        for (int i = 0; i < 2; i++) {
            scripted.authResult = FtpAuthenticationResult.NEED_PASSWORD;
            cmd("USER alice");
            scripted.authResult = FtpAuthenticationResult.INVALID_PASSWORD;
            assertTrue(cmd("PASS bad").startsWith("530"));
        }
        scripted.authResult = FtpAuthenticationResult.NEED_PASSWORD;
        cmd("USER alice");
        scripted.authResult = FtpAuthenticationResult.SUCCESS;
        String r = cmd("PASS good");
        assertTrue("locked out even with the right password: " + r, r.startsWith("421"));
    }

    @Test
    public void testAccountFlow() {
        scripted.authResult = FtpAuthenticationResult.NEED_ACCOUNT;
        start(scripted);
        String r = cmd("USER alice");
        assertTrue(r, r.startsWith("331") || r.startsWith("332"));
        r = cmd("PASS secret");
        assertTrue(r, r.startsWith("332"));
        scripted.authResult = FtpAuthenticationResult.SUCCESS;
        r = cmd("ACCT billing");
        assertTrue(r, r.startsWith("230"));
        r = cmd("PWD");
        assertTrue(r, r.startsWith("257"));
    }

    @Test
    public void testAccountRejected() {
        scripted.authResult = FtpAuthenticationResult.NEED_ACCOUNT;
        start(scripted);
        cmd("USER alice");
        cmd("PASS secret");
        scripted.authResult = FtpAuthenticationResult.INVALID_ACCOUNT;
        String r = cmd("ACCT billing");
        assertTrue(r, r.startsWith("530"));
    }

    // File operation result mapping

    @Test
    public void testFileOperationResultMapping() {
        login();
        FtpFileOperationResult[] all = FtpFileOperationResult.values();
        for (int i = 0; i < all.length; i++) {
            fs.result = all[i];
            String r = cmd("MKD d" + i);
            assertTrue(all[i] + " MKD -> " + r, r.length() >= 3);
            r = cmd("RMD d" + i);
            assertTrue(all[i] + " RMD -> " + r, r.length() >= 3);
            r = cmd("DELE f" + i);
            assertTrue(all[i] + " DELE -> " + r, r.length() >= 3);
            cmd("RNFR f" + i);
            r = cmd("RNTO g" + i);
            assertTrue(all[i] + " RNTO -> " + r, r.length() >= 3);
            r = cmd("ALLO 10");
            assertTrue(all[i] + " ALLO -> " + r, r.length() >= 3);
        }
    }

    @Test
    public void testSpecificFileResultCodes() {
        login();
        fs.result = FtpFileOperationResult.NOT_FOUND;
        assertTrue(cmd("DELE x").startsWith("550"));
        fs.result = FtpFileOperationResult.INSUFFICIENT_SPACE;
        assertTrue(cmd("DELE x").startsWith("552"));
        fs.result = FtpFileOperationResult.INVALID_NAME;
        assertTrue(cmd("DELE x").startsWith("553"));
        fs.result = FtpFileOperationResult.NOT_SUPPORTED;
        assertTrue(cmd("DELE x").startsWith("502"));
        fs.result = FtpFileOperationResult.QUOTA_EXCEEDED;
        assertTrue(cmd("DELE x").startsWith("552"));
        fs.result = FtpFileOperationResult.SUCCESS;
        assertTrue(cmd("DELE x").startsWith("250"));
    }

    // Authorization denial

    @Test
    public void testAuthorizationDenied() throws IOException {
        Files.write(root.resolve("f.txt"), "x".getBytes(StandardCharsets.UTF_8));
        login();
        scripted.authorized = false;
        String[] cmds = {"CWD x", "CDUP", "MKD x", "RMD x", "DELE f.txt",
            "RNFR f.txt", "SIZE f.txt", "MDTM f.txt", "MLST f.txt", "LIST",
            "NLST", "MLSD", "RETR f.txt", "STOR g", "APPE g", "STOU",
            "SITE HELP", "ALLO 10"};
        for (int i = 0; i < cmds.length; i++) {
            String r = cmd(cmds[i]);
            assertTrue(cmds[i] + " -> " + r, r.length() >= 3);
        }
        String r = cmd("MKD x");
        assertTrue(r, r.startsWith("550"));
    }

    // SITE

    @Test
    public void testSiteResultVariants() {
        login();
        scripted.siteResult = FtpFileOperationResult.SUCCESS;
        scripted.siteResponse = null;
        String r = cmd("SITE FOO");
        assertTrue(r, r.startsWith("250"));
        scripted.siteResult = FtpFileOperationResult.ACCESS_DENIED;
        r = cmd("SITE FOO");
        assertTrue(r, r.startsWith("550"));
        scripted.siteResult = FtpFileOperationResult.NOT_SUPPORTED;
        r = cmd("SITE FOO");
        assertTrue(r, r.startsWith("502"));
    }

    @Test
    public void testSiteQuotaNotConfigured() {
        login();
        String r = cmd("SITE QUOTA");
        assertTrue(r, r.startsWith("502"));
        r = cmd("SITE SETQUOTA bob 10M");
        assertTrue(r.length() >= 3);
    }

    @Test
    public void testSiteQuotaConfigured() {
        final FtpMiscTest.StubQuota q = new FtpMiscTest.StubQuota();
        final Quota[] holder = new Quota[1];
        QuotaSource[] sources = QuotaSource.values();
        for (int i = 0; i < sources.length; i++) {
            Quota quota = new Quota(1048576L, -1);
            quota.setSource(sources[i], "detail");
            quota.setStorageUsed(1024);
            holder[0] = quota;
            scripted.quotaManager = new QuotaManagerWrapper(q, holder);
            start(scripted);
            login();
            String r = cmd("SITE QUOTA");
            assertTrue(sources[i] + " -> " + r, r.startsWith("2"));
            r = cmd("SITE QUOTA bob");
            assertTrue(r.length() >= 3);
            scripted.admin = true;
            r = cmd("SITE QUOTA bob");
            assertTrue(r.length() >= 3);
            r = cmd("SITE SETQUOTA bob 10M");
            assertTrue(r.length() >= 3);
            r = cmd("SITE SETQUOTA bob");
            assertTrue(r.length() >= 3);
            r = cmd("SITE SETQUOTA bob bad");
            assertTrue(r.length() >= 3);
            scripted.admin = false;
        }
        holder[0] = Quota.unlimited();
        scripted.quotaManager = new QuotaManagerWrapper(q, holder);
        start(scripted);
        login();
        String r = cmd("SITE QUOTA");
        assertTrue(r, r.startsWith("2"));
    }

    // TLS state checks

    @Test
    public void testSecureEndpointTlsCommands() {
        endpoint.secure = true;
        String r = cmd("AUTH TLS");
        assertTrue(r, r.startsWith("503"));
        r = cmd("AUTH");
        assertTrue(r, r.startsWith("504"));
        r = cmd("AUTH KERBEROS");
        assertTrue(r, r.startsWith("504"));
        r = cmd("PBSZ 0");
        assertTrue(r, r.length() >= 3);
        r = cmd("PBSZ x");
        assertTrue(r, r.length() >= 3);
        r = cmd("PROT P");
        assertTrue(r, r.length() >= 3);
        r = cmd("PROT C");
        assertTrue(r, r.length() >= 3);
        r = cmd("PROT X");
        assertTrue(r, r.length() >= 3);
        r = cmd("PROT");
        assertTrue(r, r.length() >= 3);
        r = cmd("CCC");
        assertTrue(r, r.length() >= 3);
    }

    @Test
    public void testAuthTlsUnavailable() {
        String r = cmd("AUTH TLS");
        assertTrue(r, r.startsWith("534"));
    }

    // Telemetry

    @Test
    public void testTelemetryTraceLifecycle() {
        TelemetryConfig config = new TelemetryConfig();
        FTPProtocolHandlerTest.StubEndpoint ep =
                new FTPProtocolHandlerTest.StubEndpoint() {
            @Override public TelemetryConfig getTelemetryConfig() {
                return config;
            }
        };
        FtpProtocolHandler h = new FtpProtocolHandler(new FtpListener(), scripted);
        h.connected(ep);
        h.receive(ByteBuffer.wrap("USER a\r\n".getBytes(StandardCharsets.US_ASCII)));
        h.receive(ByteBuffer.wrap("PASS b\r\n".getBytes(StandardCharsets.US_ASCII)));
        h.receive(ByteBuffer.wrap("QUIT\r\n".getBytes(StandardCharsets.US_ASCII)));
        h.disconnected();
    }

    @Test
    public void testTelemetryFailureAndError() {
        final TelemetryConfig config = new TelemetryConfig();
        FTPProtocolHandlerTest.StubEndpoint ep =
                new FTPProtocolHandlerTest.StubEndpoint() {
            @Override public TelemetryConfig getTelemetryConfig() {
                return config;
            }
        };
        scripted.authResult = FtpAuthenticationResult.INVALID_PASSWORD;
        FtpProtocolHandler h = new FtpProtocolHandler(new FtpListener(), scripted);
        h.connected(ep);
        h.receive(ByteBuffer.wrap("USER a\r\n".getBytes(StandardCharsets.US_ASCII)));
        h.receive(ByteBuffer.wrap("PASS b\r\n".getBytes(StandardCharsets.US_ASCII)));
        h.error(new IOException("x"));
        h.disconnected();
    }

    @Test
    public void testDisconnectWithoutConnect() {
        FtpProtocolHandler h = new FtpProtocolHandler(new FtpListener(), scripted);
        h.disconnected();
    }

    @Test
    public void testSecurityEstablishedWithoutRealm() {
        handler.securityEstablished(null);
        login();
        handler.securityEstablished(null);
    }

    // Helpers

    static class ScriptedHandler extends FtpMiscTest.StubHandler {
        final FtpFileSystem fs;
        FtpAuthenticationResult authResult = FtpAuthenticationResult.SUCCESS;
        boolean authorized = true;
        boolean admin;
        FtpFileOperationResult siteResult = FtpFileOperationResult.NOT_SUPPORTED;
        String siteResponse;
        QuotaManager quotaManager;

        ScriptedHandler(FtpFileSystem fs) {
            this.fs = fs;
        }

        @Override
        public void authenticate(String u, String p,
                String a, FtpConnectionMetadata m,
                org.bluezoo.gumdrop.auth.RealmCallback<FtpAuthenticationResult> cb) {
            if (p == null && authResult == FtpAuthenticationResult.SUCCESS) {
                cb.completed(FtpAuthenticationResult.NEED_PASSWORD);
                return;
            }
            cb.completed(authResult);
        }

        @Override
        public FtpFileSystem getFileSystem(FtpConnectionMetadata m) {
            return fs;
        }

        @Override
        public boolean isAuthorized(FtpOperation op, String path,
                FtpConnectionMetadata m) {
            if (op == FtpOperation.ADMIN) {
                return admin;
            }
            return authorized;
        }

        @Override
        public FtpFileOperationResult handleSiteCommand(String c,
                FtpConnectionMetadata m) {
            if (siteResponse != null) {
                m.setSiteCommandResponse(siteResponse);
            }
            return siteResult;
        }

        @Override
        public QuotaManager getQuotaManager() {
            return quotaManager;
        }
    }

    static class QuotaManagerWrapper extends FtpMiscTest.StubQuota {
        final Quota[] holder;

        QuotaManagerWrapper(FtpMiscTest.StubQuota unused, Quota[] holder) {
            this.holder = holder;
        }

        @Override
        public Quota getQuota(String u) {
            return holder[0];
        }

        @Override
        public boolean canStore(String u, long b) {
            return true;
        }
    }

    static class ScriptedFs implements FtpFileSystem {
        final FtpFileSystem real;
        FtpFileOperationResult result = FtpFileOperationResult.SUCCESS;

        ScriptedFs(FtpFileSystem real) {
            this.real = real;
        }

        @Override
        public List<FtpFileInfo> listDirectory(String p, FtpConnectionMetadata m) {
            return real.listDirectory(p, m);
        }

        @Override
        public DirectoryChangeResult changeDirectory(String p, String c,
                FtpConnectionMetadata m) {
            return real.changeDirectory(p, c, m);
        }

        @Override
        public FtpFileInfo getFileInfo(String p, FtpConnectionMetadata m) {
            return real.getFileInfo(p, m);
        }

        @Override
        public FtpFileOperationResult createDirectory(String p,
                FtpConnectionMetadata m) {
            return result;
        }

        @Override
        public FtpFileOperationResult removeDirectory(String p,
                FtpConnectionMetadata m) {
            return result;
        }

        @Override
        public FtpFileOperationResult deleteFile(String p,
                FtpConnectionMetadata m) {
            return result;
        }

        @Override
        public FtpFileOperationResult rename(String a, String b,
                FtpConnectionMetadata m) {
            return result;
        }

        @Override
        public ReadableByteChannel openForReading(String p, long o,
                FtpConnectionMetadata m) {
            return real.openForReading(p, o, m);
        }

        @Override
        public WritableByteChannel openForWriting(String p, boolean a,
                FtpConnectionMetadata m) {
            return real.openForWriting(p, a, m);
        }

        @Override
        public UniqueNameResult generateUniqueName(String b, String s,
                FtpConnectionMetadata m) {
            return real.generateUniqueName(b, s, m);
        }

        @Override
        public FtpFileOperationResult allocateSpace(String p, long s,
                FtpConnectionMetadata m) {
            return result;
        }
    }
}
