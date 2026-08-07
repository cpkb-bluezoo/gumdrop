/*
 * FTPServerIntegrationTest.java
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

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.ftp.client.FTPClientProtocolHandler;
import org.bluezoo.gumdrop.ftp.client.FTPException;
import org.bluezoo.gumdrop.ftp.client.FTPFileEntry;
import org.bluezoo.gumdrop.ftp.client.handler.*;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.*;

/**
 * Integration tests for Gumdrop's FTP server ({@code FTPProtocolHandler})
 * over a real socket (issue #105).
 *
 * <p>Every other protocol server (HTTP, SMTP, POP3, IMAP, servlet, TLS)
 * has a real integration suite; this closes that gap for FTP. Driven by
 * the FTP client added for issue #104 ({@code FTPClientProtocolHandler})
 * rather than a hand-rolled raw-socket helper, the same way POP3's
 * integration tests use {@code POP3ClientHelper} — except here a full
 * async client implementation already exists, so there is no need for a
 * blocking raw-socket helper at all.
 *
 * <p>Covers: greeting, USER/PASS auth flow (success and rejection),
 * PWD/CWD/MKD/RMD, PASV + LIST/RETR/STOR over a real data connection,
 * AUTH TLS upgrade, and QUIT — verifying both the client-visible replies
 * and, where relevant, the server's actual on-disk file system state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPServerIntegrationTest extends AbstractServerIntegrationTest {

    private static final int FTP_PORT = 18022;
    private static final String TEST_HOST = "127.0.0.1";
    private static final int ASYNC_TIMEOUT_SECONDS = 5;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(ASYNC_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    private static TestCertificateManager certManager;
    private static File dataDir;

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/ftp-server-test.xml");
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    @BeforeClass
    public static void setup() throws Exception {
        dataDir = new File("test/integration/ftp-server-data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }
        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }

        certManager = new TestCertificateManager(certsDir);
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);
        certManager.saveServerKeystore(new File(certsDir, "ftp-test-keystore.p12"), "testpass");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────

    private static class FTPTestClient {
        private final TCPTransportFactory factory;
        private final ClientEndpoint client;

        FTPTestClient(int port) throws Exception {
            this.factory = new TCPTransportFactory();
            factory.start();
            Gumdrop gumdrop = Gumdrop.getInstance();
            SelectorLoop selectorLoop = gumdrop.nextWorkerLoop();
            this.client = new ClientEndpoint(factory, selectorLoop, TEST_HOST, port);
        }

        void setSSLContext(javax.net.ssl.SSLContext sslContext) {
            factory.setSSLContext(sslContext);
        }

        void connect(ServerGreeting handler) throws Exception {
            FTPClientProtocolHandler endpointHandler = new FTPClientProtocolHandler(handler);
            endpointHandler.setSSLContext(factory.getSSLContext());
            client.connect(endpointHandler);
        }
    }

    private FTPTestClient createClient(int port) throws Exception {
        return new FTPTestClient(port);
    }

    private static String decode(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Connects and logs in with USER/PASS, invoking {@code onAuth} with
     * the resulting {@link ClientAuthenticatedState} once authenticated.
     * Any unexpected reply fails the test via {@code error}/{@code latch}.
     */
    private void loginThen(FTPTestClient client, String user, String pass,
            CountDownLatch latch, AtomicReference<Exception> error,
            AuthContinuation onAuth) throws Exception {
        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user(user, new TestUserHandler(latch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState passState) {
                        passState.pass(pass, new TestPassHandler(latch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                onAuth.ready(auth);
                            }
                        });
                    }
                });
            }
        });
    }

    private interface AuthContinuation {
        void ready(ClientAuthenticatedState authenticated);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Greeting / login
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testGreetingAndLogin() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> pwd = new AtomicReference<>();

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.pwd(new TestPwdHandler(latch, error) {
                @Override
                public void handlePathname(String pathname, ClientAuthenticatedState a) {
                    pwd.set(pathname);
                    a.quit();
                    latch.countDown();
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertNotNull("Server should report a current directory", pwd.get());
    }

    @Test
    public void testInvalidUserRejected() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> rejectMessage = new AtomicReference<>();

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                // Server rejects an empty username with 501 (RFC 959 §4.1.1).
                login.user("", new TestUserHandler(latch, error) {
                    @Override
                    public void handleRejected(ClientLoginState login2, String msg) {
                        rejectMessage.set(msg);
                        login2.quit();
                        latch.countDown();
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertNotNull("Server should have rejected the empty username", rejectMessage.get());
    }

    // ─────────────────────────────────────────────────────────────────────
    // PWD / CWD / MKD / RMD
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testMkdCwdPwd() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> cwdPath = new AtomicReference<>();
        String dirName = "sub-" + System.nanoTime();

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.mkd(dirName, new TestMkdHandler(latch, error) {
                @Override
                public void handlePathname(String pathname, ClientAuthenticatedState a) {
                    assertTrue("On-disk directory should now exist",
                            new File(dataDir, dirName).isDirectory());
                    a.cwd(dirName, new TestCwdHandler(latch, error) {
                        @Override
                        public void handleOk(ClientAuthenticatedState a2) {
                            a2.pwd(new TestPwdHandler(latch, error) {
                                @Override
                                public void handlePathname(String p, ClientAuthenticatedState a3) {
                                    cwdPath.set(p);
                                    a3.quit();
                                    latch.countDown();
                                }
                            });
                        }
                    });
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("CWD then PWD should reflect the new directory",
                cwdPath.get().contains(dirName));
    }

    @Test
    public void testRmdRemovesDirectory() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean removed = new AtomicBoolean();
        String dirName = "toremove-" + System.nanoTime();
        File dir = new File(dataDir, dirName);
        assertTrue("Setup: directory should be created directly", dir.mkdir());

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.rmd(dirName, new TestSimpleHandler(latch, error) {
                @Override
                public void handleOk(ClientAuthenticatedState a) {
                    removed.set(true);
                    a.quit();
                    latch.countDown();
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("RMD should have succeeded", removed.get());
        assertFalse("Directory should no longer exist on disk", dir.exists());
    }

    // ─────────────────────────────────────────────────────────────────────
    // PASV + STOR/RETR/LIST — real data connection, verified on disk
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testStorWritesFileToDisk() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        String fileName = "stor-" + System.nanoTime() + ".txt";
        String content = "Server-side STOR verification.";

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.type("I", new TestSimpleHandler(latch, error) {
                @Override
                public void handleOk(ClientAuthenticatedState a) {
                    a.pasv(new TestPasvHandler(latch, error) {
                        @Override
                        public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a2) {
                            a2.stor(fileName, addr, new TestStorHandler(latch, error) {
                                @Override
                                public void handleReadyToSend(ClientDataSink sink) {
                                    sink.write(ByteBuffer.wrap(
                                            content.getBytes(StandardCharsets.UTF_8)));
                                    sink.finish();
                                }

                                @Override
                                public void handleTransferComplete(ClientAuthenticatedState a3) {
                                    a3.quit();
                                    latch.countDown();
                                }
                            });
                        }
                    });
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        File written = new File(dataDir, fileName);
        assertTrue("File should exist on disk after STOR", written.isFile());
        assertEquals("On-disk content should match what was uploaded",
                content, new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void testRetrReadsFileFromDisk() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> downloaded = new AtomicReference<>();
        String fileName = "retr-" + System.nanoTime() + ".txt";
        String content = "Server-side RETR verification.";
        Files.write(new File(dataDir, fileName).toPath(),
                content.getBytes(StandardCharsets.UTF_8));

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.type("I", new TestSimpleHandler(latch, error) {
                @Override
                public void handleOk(ClientAuthenticatedState a) {
                    a.pasv(new TestPasvHandler(latch, error) {
                        @Override
                        public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a2) {
                            StringBuilder received = new StringBuilder();
                            a2.retr(fileName, addr, new TestRetrHandler(latch, error) {
                                @Override
                                public void handleContent(ByteBuffer data) {
                                    received.append(decode(data));
                                }

                                @Override
                                public void handleTransferComplete(ClientAuthenticatedState a3) {
                                    downloaded.set(received.toString());
                                    a3.quit();
                                    latch.countDown();
                                }
                            });
                        }
                    });
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals("Downloaded content should match the on-disk file",
                content, downloaded.get());
    }

    @Test
    public void testListShowsFilesOnDisk() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<List<FTPFileEntry>> entriesRef = new AtomicReference<>();
        String fileName = "listme-" + System.nanoTime() + ".txt";
        Files.write(new File(dataDir, fileName).toPath(),
                "for listing".getBytes(StandardCharsets.UTF_8));

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.pasv(new TestPasvHandler(latch, error) {
                @Override
                public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a) {
                    a.list(null, addr, new TestListHandler(latch, error) {
                        @Override
                        public void handleEntries(List<FTPFileEntry> entries,
                                ClientAuthenticatedState a2) {
                            entriesRef.set(entries);
                            a2.quit();
                            latch.countDown();
                        }
                    });
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        List<FTPFileEntry> entries = entriesRef.get();
        assertNotNull(entries);
        boolean found = false;
        for (FTPFileEntry entry : entries) {
            if (entry.getName().contains(fileName)) {
                found = true;
                break;
            }
        }
        assertTrue("LIST should include the file written directly to disk", found);
    }

    /**
     * Regression test for the scalability fix streaming LIST output through
     * a bounded pooled buffer (issue #131) instead of building the whole
     * formatted listing into one buffer up front. Enough files are created
     * that the formatted listing exceeds one 32KB chunk, forcing at least
     * one entry line to be split across a chunk boundary and reassembled
     * via the handler's carry-over logic; every entry must still arrive
     * intact and none may be duplicated or dropped.
     */
    @Test
    public void testListSpanningMultipleChunksDeliversEveryEntryIntact()
            throws Exception {
        String subdirName = "bigdir-" + System.nanoTime();
        File subdir = new File(dataDir, subdirName);
        assertTrue(subdir.mkdir());

        int fileCount = 2000;
        java.util.Set<String> expectedNames = new java.util.HashSet<>();
        for (int i = 0; i < fileCount; i++) {
            String name = String.format("entry-%04d.txt", i);
            Files.write(new File(subdir, name).toPath(),
                    "x".getBytes(StandardCharsets.UTF_8));
            expectedNames.add(name);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<List<FTPFileEntry>> entriesRef = new AtomicReference<>();

        loginThen(createClient(FTP_PORT), "testuser", "testpass", latch, error, auth -> {
            auth.pasv(new TestPasvHandler(latch, error) {
                @Override
                public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a) {
                    a.list(subdirName, addr, new TestListHandler(latch, error) {
                        @Override
                        public void handleEntries(List<FTPFileEntry> entries,
                                ClientAuthenticatedState a2) {
                            entriesRef.set(entries);
                            a2.quit();
                            latch.countDown();
                        }
                    });
                }
            });
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        List<FTPFileEntry> entries = entriesRef.get();
        assertNotNull(entries);

        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (FTPFileEntry entry : entries) {
            seenNames.add(entry.getName());
        }
        assertEquals("every listed file must appear exactly once, no "
                        + "duplicates or drops across a chunk boundary",
                expectedNames, seenNames);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AUTH TLS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testAuthTlsUpgrade() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        client.setSSLContext(certManager.createClientSSLContext());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean tlsEstablished = new AtomicBoolean();
        AtomicReference<String> pwdAfterTls = new AtomicReference<>();

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.authTls(new TestAuthTlsHandler(latch, error) {
                    @Override
                    public void handleTlsEstablished(ClientLoginState login2) {
                        tlsEstablished.set(true);
                        login2.user("testuser", new TestUserHandler(latch, error) {
                            @Override
                            public void handlePasswordRequired(ClientPasswordState pass) {
                                pass.pass("testpass", new TestPassHandler(latch, error) {
                                    @Override
                                    public void handleAuthenticated(ClientAuthenticatedState auth) {
                                        auth.pwd(new TestPwdHandler(latch, error) {
                                            @Override
                                            public void handlePathname(String pathname,
                                                    ClientAuthenticatedState a) {
                                                pwdAfterTls.set(pathname);
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
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                assertNotNull("Security info should be present once TLS is established", info);
            }
        });

        assertTrue("Should complete within timeout",
                latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("AUTH TLS should have completed the handshake", tlsEstablished.get());
        assertNotNull("Commands should still work after the TLS upgrade", pwdAfterTls.get());
    }

    /**
     * Regression test for the bug found while adding this suite: {@code
     * FTPDataConnectionCoordinator} accepted PROT P (setting the {@code
     * dataProtection} flag and replying 200) but never actually
     * TLS-wrapped the data connection's {@code TCPEndpoint} — a silent
     * downgrade where the client believes its transfer is encrypted but
     * it is not.
     *
     * <p>This is deliberately an end-to-end content check, not just a
     * check that PROT P replies 200: the FTP client (issue #104) always
     * TLS-wraps its own end of a PASV data connection once PROT P is
     * active (see {@code FTPClientDataConnectionCoordinator}). If the
     * server's data connection were still plaintext, the client's TLS
     * ClientHello would arrive at the server as literal (mis-parsed) file
     * content instead of a handshake, and the round-tripped content would
     * not match what was uploaded — so a content mismatch here is exactly
     * what the unfixed bug would have produced, without needing to
     * inspect TLS handshake bytes directly.
     */
    @Test
    public void testProtPStorRetrRoundTrip() throws Exception {
        FTPTestClient client = createClient(FTP_PORT);
        client.setSSLContext(certManager.createClientSSLContext());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> downloaded = new AtomicReference<>();
        String fileName = "protp-" + System.nanoTime() + ".txt";
        String content = "PROT P data connection encryption round trip.";

        client.connect(new TestGreeting(latch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.authTls(new TestAuthTlsHandler(latch, error) {
                    @Override
                    public void handleTlsEstablished(ClientLoginState login2) {
                        login2.user("testuser", new TestUserHandler(latch, error) {
                            @Override
                            public void handlePasswordRequired(ClientPasswordState pass) {
                                pass.pass("testpass", new TestPassHandler(latch, error) {
                                    @Override
                                    public void handleAuthenticated(ClientAuthenticatedState auth) {
                                        auth.pbsz(0, new TestSimpleHandler(latch, error) {
                                            @Override
                                            public void handleOk(ClientAuthenticatedState a) {
                                                a.prot("P", new TestSimpleHandler(latch, error) {
                                                    @Override
                                                    public void handleOk(ClientAuthenticatedState a2) {
                                                        upload(a2);
                                                    }
                                                });
                                            }
                                        });
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
        assertEquals("Content should round-trip correctly over a PROT P data "
                        + "connection (a mismatch here means the data connection "
                        + "was not actually TLS-protected)",
                content, downloaded.get());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test handler base classes (default: fail the test on any unexpected
    // callback)
    // ─────────────────────────────────────────────────────────────────────

    private abstract static class TestGreeting implements ServerGreeting {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestGreeting(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            error.set(new FTPException("Service unavailable: " + message));
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

    private abstract static class TestUserHandler implements ServerUserReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestUserHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleUserAccepted(ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected immediate login"));
            latch.countDown();
        }

        @Override
        public void handlePasswordRequired(ClientPasswordState pass) {
            error.set(new FTPException("Unexpected password-required path"));
            latch.countDown();
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            error.set(new FTPException("Unexpected account-required path"));
            latch.countDown();
        }

        @Override
        public void handleRejected(ClientLoginState login, String message) {
            error.set(new FTPException("USER rejected: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestPassHandler implements ServerPassReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPassHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleAuthenticated(ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected authenticated path"));
            latch.countDown();
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            error.set(new FTPException("Unexpected account-required path"));
            latch.countDown();
        }

        @Override
        public void handleAuthFailed(ClientLoginState login, String message) {
            error.set(new FTPException("PASS rejected: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestPwdHandler implements ServerPwdReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPwdHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePathname(String pathname, ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected pathname path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("PWD error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestMkdHandler implements ServerMkdReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestMkdHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePathname(String pathname, ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected pathname path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("MKD error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestCwdHandler implements ServerCwdReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestCwdHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleOk(ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected OK path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("CWD error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestSimpleHandler implements ServerSimpleReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestSimpleHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleOk(ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected OK path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("Command error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestPasvHandler implements ServerPasvReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPasvHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePassive(InetSocketAddress dataAddress, ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected passive path"));
            latch.countDown();
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("PASV error " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestStorHandler implements ServerStorReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestStorHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleReadyToSend(ClientDataSink sink) {
            error.set(new FTPException("Unexpected ready-to-send path"));
            latch.countDown();
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected transfer-complete path"));
            latch.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("STOR failed " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestRetrHandler implements ServerRetrReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestRetrHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleContent(ByteBuffer data) {
            error.set(new FTPException("Unexpected content path"));
            latch.countDown();
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected transfer-complete path"));
            latch.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("RETR failed " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestListHandler implements ServerListReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestListHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleEntries(List<FTPFileEntry> entries, ClientAuthenticatedState authenticated) {
            error.set(new FTPException("Unexpected entries path"));
            latch.countDown();
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message) {
            error.set(new FTPException("Listing failed " + code + ": " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestAuthTlsHandler implements ServerAuthTlsReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestAuthTlsHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleTlsEstablished(ClientLoginState login) {
            error.set(new FTPException("Unexpected TLS-established path"));
            latch.countDown();
        }

        @Override
        public void handleTlsUnavailable(ClientLoginState login) {
            error.set(new FTPException("AUTH TLS unavailable"));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new FTPException("Service closing: " + message));
            latch.countDown();
        }
    }
}
