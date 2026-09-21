/*
 * FTPClientIntegrationTest.java
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

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.ftp.FtpListener;
import org.bluezoo.gumdrop.ftp.client.*;
import org.bluezoo.gumdrop.ftp.server.FtpServer;
import org.bluezoo.gumdrop.ftp.server.FtpServerSessionProviders;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.*;

/**
 * Integration tests for Gumdrop's FTP client implementation (issue #104).
 *
 * <p>Drives a real {@code FileSystemFtpSessionProvider}-backed FTP server over an
 * actual socket, replacing what would otherwise be a raw-socket test
 * helper — see the companion "no FTP integration coverage" issue referenced
 * from #104. Mirrors the structure of {@code SMTPClientIntegrationTest} and
 * {@code POP3ServerIntegrationTest}.
 *
 * <p>Covers: USER/PASS login, PWD/CWD/MKD/TYPE, and PASV-mode STOR/RETR/
 * LIST round trips. AUTH TLS/PROT P and PORT/EPRT (active mode) are
 * exercised at the unit level ({@code FTPClientProtocolHandlerTest}) but
 * not here, since this server configuration is plaintext-only.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPClientIntegrationTest extends AbstractServerIntegrationTest {

    private static final int FTP_PORT = 18021;
    private static final String TEST_HOST = "::1";
    private static final int ASYNC_TIMEOUT_SECONDS = 5;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(ASYNC_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        FtpServer server = FtpServer.compose()
                .listener(new FtpListener()
                        .port(FTP_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST)))
                .sessionProvider(FtpServerSessionProviders.fileSystem()
                        .rootDirectory(Path.of("test/integration/ftp-data"))
                        .readOnly(false))
                .server();
        return Collections.singletonList(server);
    }

    @BeforeClass
    public static void setupDataDirectory() {
        File dataDir = new File("test/integration/ftp-data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────

    private static class FTPTestClient {
        private final Gumdrop gumdrop;
        private final ClientEndpoint client;

        FTPTestClient(Gumdrop gumdrop, int port) throws Exception {
            TcpTransportFactory factory = new TcpTransportFactory();
            factory.start();
            this.gumdrop = gumdrop;
            SelectorLoop selectorLoop = gumdrop.nextWorkerLoop();
            this.client = new ClientEndpoint(factory, selectorLoop, TEST_HOST, port);
        }

        void connect(RemoteGreeting handler) throws Exception {
            FtpClientProtocolHandler protocol = new FtpClientProtocolHandler(handler);
            protocol.setGumdrop(gumdrop);
            client.connect(gumdrop, protocol);
        }
    }

    private FTPTestClient createClient(int port) throws Exception {
        return new FTPTestClient(gumdrop, port);
    }

    private static String decode(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Login / basic commands
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testLoginAndPwd() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> pwd = new AtomicReference<>();

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user("testuser", new TestUserHandler(latch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState pass) {
                        pass.pass("testpass", new TestPassHandler(latch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                auth.pwd(new TestPwdHandler(latch, error) {
                                    @Override
                                    public void handlePathname(String pathname,
                                            ClientAuthenticatedState a) {
                                        pwd.set(pathname);
                                        a.quit();
                                        latch.countDown();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertNotNull("PWD should return a path", pwd.get());
    }

    @Test
    public void testMkdCwdPwd() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> madePath = new AtomicReference<>();
        AtomicReference<String> cwdPath = new AtomicReference<>();
        String dirName = "testdir-" + System.nanoTime();

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user("testuser", new TestUserHandler(latch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState pass) {
                        pass.pass("testpass", new TestPassHandler(latch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                auth.mkd(dirName, new TestMkdHandler(latch, error) {
                                    @Override
                                    public void handlePathname(String pathname,
                                            ClientAuthenticatedState a) {
                                        madePath.set(pathname);
                                        a.cwd(dirName, new TestCwdHandler(latch, error) {
                                            @Override
                                            public void handleOk(ClientAuthenticatedState a2) {
                                                a2.pwd(new TestPwdHandler(latch, error) {
                                                    @Override
                                                    public void handlePathname(String p,
                                                            ClientAuthenticatedState a3) {
                                                        cwdPath.set(p);
                                                        a3.quit();
                                                        latch.countDown();
                                                    }
                                                });
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("MKD path should contain the directory name",
                madePath.get().contains(dirName));
        assertTrue("CWD then PWD should reflect the new directory",
                cwdPath.get().contains(dirName));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Data connection round trips (PASV)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testStorAndRetrRoundTrip() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> downloaded = new AtomicReference<>();
        String fileName = "roundtrip-" + System.nanoTime() + ".txt";
        String content = "Hello, FTP client integration test!";

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user("testuser", new TestUserHandler(latch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState pass) {
                        pass.pass("testpass", new TestPassHandler(latch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                auth.type("I", new TestSimpleHandler(latch, error) {
                                    @Override
                                    public void handleOk(ClientAuthenticatedState a) {
                                        upload(a);
                                    }
                                });
                            }
                        });
                    }

                    private void upload(ClientAuthenticatedState auth) {
                        auth.pasv(new TestPasvHandler(latch, error) {
                            @Override
                            public void handlePassive(InetSocketAddress addr,
                                    ClientAuthenticatedState a) {
                                a.stor(fileName, addr, new TestStorHandler(latch, error) {
                                    @Override
                                    public void handleReadyToSend(ClientDataSink sink) {
                                        sink.write(ByteBuffer.wrap(
                                                content.getBytes(StandardCharsets.UTF_8)));
                                        sink.finish();
                                    }

                                    @Override
                                    public void handleTransferComplete(
                                            ClientAuthenticatedState a2) {
                                        download(a2);
                                    }
                                });
                            }
                        });
                    }

                    private void download(ClientAuthenticatedState auth) {
                        auth.pasv(new TestPasvHandler(latch, error) {
                            @Override
                            public void handlePassive(InetSocketAddress addr,
                                    ClientAuthenticatedState a) {
                                StringBuilder received = new StringBuilder();
                                a.retr(fileName, addr, new TestRetrHandler(latch, error) {
                                    @Override
                                    public void handleContent(ByteBuffer data) {
                                        received.append(decode(data));
                                    }

                                    @Override
                                    public void handleTransferComplete(
                                            ClientAuthenticatedState a2) {
                                        downloaded.set(received.toString());
                                        a2.quit();
                                        latch.countDown();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals("Downloaded content should match uploaded content",
                content, downloaded.get());
    }

    @Test
    public void testListShowsUploadedFile() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<List<FtpFileEntry>> entriesRef = new AtomicReference<>();
        String fileName = "listed-" + System.nanoTime() + ".txt";

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user("testuser", new TestUserHandler(latch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState pass) {
                        pass.pass("testpass", new TestPassHandler(latch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                upload(auth);
                            }
                        });
                    }

                    private void upload(ClientAuthenticatedState auth) {
                        auth.pasv(new TestPasvHandler(latch, error) {
                            @Override
                            public void handlePassive(InetSocketAddress addr,
                                    ClientAuthenticatedState a) {
                                a.stor(fileName, addr, new TestStorHandler(latch, error) {
                                    @Override
                                    public void handleReadyToSend(ClientDataSink sink) {
                                        sink.write(ByteBuffer.wrap(
                                                "listing test".getBytes(StandardCharsets.UTF_8)));
                                        sink.finish();
                                    }

                                    @Override
                                    public void handleTransferComplete(
                                            ClientAuthenticatedState a2) {
                                        list(a2);
                                    }
                                });
                            }
                        });
                    }

                    private void list(ClientAuthenticatedState auth) {
                        auth.pasv(new TestPasvHandler(latch, error) {
                            @Override
                            public void handlePassive(InetSocketAddress addr,
                                    ClientAuthenticatedState a) {
                                a.nlst(null, addr, new TestListHandler(latch, error) {
                                    @Override
                                    public void handleEntries(List<FtpFileEntry> entries,
                                            ClientAuthenticatedState a2) {
                                        entriesRef.set(entries);
                                        a2.quit();
                                        latch.countDown();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        List<FtpFileEntry> entries = entriesRef.get();
        assertNotNull(entries);
        boolean found = false;
        for (FtpFileEntry entry : entries) {
            if (entry.getName().contains(fileName)) {
                found = true;
                break;
            }
        }
        assertTrue("NLST should list the uploaded file", found);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test handler base classes (default: fail the test on any unexpected
    // callback, mirroring SMTPClientIntegrationTest's pattern)
    // ─────────────────────────────────────────────────────────────────────

    private abstract static class TestGreeting implements RemoteGreeting {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestGreeting(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            error.set(new FtpException("Service unavailable: " + message));
            latch.countDown();
        }

        @Override
        public void onConnected(Endpoint endpoint) { }

        @Override
        public void onDisconnected() { }

        @Override
        public void onError(Exception e) {
            error.set(e);
            latch.countDown();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) { }
    }

    private abstract static class TestUserHandler implements UserReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestUserHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleUserAccepted(ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected immediate login"));
            latch.countDown();
        }

        @Override
        public void handlePasswordRequired(ClientPasswordState pass) {
            error.set(new FtpException("Unexpected password-required path"));
            latch.countDown();
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            error.set(new FtpException("Unexpected account-required path"));
            latch.countDown();
        }

        @Override
        public void handleRejected(ClientLoginState login, String message) {
            error.set(new FtpException("USER rejected: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestPassHandler implements PassReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPassHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleAuthenticated(ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected authenticated path"));
            latch.countDown();
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            error.set(new FtpException("Unexpected account-required path"));
            latch.countDown();
        }

        @Override
        public void handleAuthFailed(ClientLoginState login, String message) {
            error.set(new FtpException("PASS rejected: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestPwdHandler implements PwdReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPwdHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePathname(String pathname, ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected pathname path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FtpException("PWD error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestMkdHandler implements MkdReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestMkdHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePathname(String pathname, ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected pathname path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FtpException("MKD error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestCwdHandler implements CwdReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestCwdHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleOk(ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected OK path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FtpException("CWD error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestSimpleHandler implements SimpleReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestSimpleHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleOk(ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected OK path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FtpException("Command error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestPasvHandler implements PasvReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPasvHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePassive(InetSocketAddress dataAddress,
                ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected passive path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FtpException("PASV error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestStorHandler implements StorReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestStorHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleReadyToSend(ClientDataSink sink) {
            error.set(new FtpException("Unexpected ready-to-send path"));
            latch.countDown();
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected transfer-complete path"));
            latch.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated,
                int code, String message) {
            error.set(new FtpException("STOR failed " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestRetrHandler implements RetrReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestRetrHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleContent(ByteBuffer data) {
            error.set(new FtpException("Unexpected content path"));
            latch.countDown();
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected transfer-complete path"));
            latch.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated,
                int code, String message) {
            error.set(new FtpException("RETR failed " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestListHandler implements ListReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestListHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleEntries(List<FtpFileEntry> entries, ClientAuthenticatedState authenticated) {
            error.set(new FtpException("Unexpected entries path"));
            latch.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated,
                int code, String message) {
            error.set(new FtpException("Listing failed " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FtpException("Service closing: " + message));
            latch.countDown();
        }
    }
}
