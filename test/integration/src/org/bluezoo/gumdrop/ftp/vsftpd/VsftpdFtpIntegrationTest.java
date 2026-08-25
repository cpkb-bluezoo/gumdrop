/*
 * VsftpdFtpIntegrationTest.java
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

package org.bluezoo.gumdrop.ftp.vsftpd;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ftp.client.FTPClient;
import org.bluezoo.gumdrop.ftp.client.FTPException;
import org.bluezoo.gumdrop.ftp.client.FTPFileEntry;
import org.bluezoo.gumdrop.ftp.client.handler.ClientAccountState;
import org.bluezoo.gumdrop.ftp.client.handler.ClientAuthenticatedState;
import org.bluezoo.gumdrop.ftp.client.handler.ClientDataSink;
import org.bluezoo.gumdrop.ftp.client.handler.ClientLoginState;
import org.bluezoo.gumdrop.ftp.client.handler.ClientPasswordState;
import org.bluezoo.gumdrop.ftp.client.handler.ServerAuthTlsReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.ftp.client.handler.ServerListReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerPassReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerPasvReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerRetrReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerSimpleReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerStorReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerUserReplyHandler;

import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end tests of gumdrop's FTP client against a real, locally-running
 * vsftpd instance -- not run in CI, see {@link VsftpdTestSupport}.
 *
 * <p>Same rationale as {@code PostfixSmtpIntegrationTest}: {@code
 * FTPClientIntegrationTest} already covers the client against gumdrop's
 * own server, but that server config is plaintext-only, so AUTH TLS/PROT
 * P is only exercised at the unit level there (mocked responses, no real
 * TLS handshake or encrypted data channel). This is where that gap gets
 * closed, against an independent implementation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class VsftpdFtpIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Before
    public void checkReachableAndClearHome() throws Exception {
        assumeTrue(VsftpdTestSupport.NOT_REACHABLE_MESSAGE, VsftpdTestSupport.isReachable());
        VsftpdTestSupport.clearHome();
    }

    // ── Plaintext PASV STOR/RETR/NLST round trip ──

    @Test
    public void testPlaintextStorRetrRoundTrip() throws Exception {
        FTPClient client = new FTPClient(VsftpdTestSupport.HOST, VsftpdTestSupport.PORT);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> downloaded = new AtomicReference<>();
        String fileName = "roundtrip-" + System.nanoTime() + ".txt";
        String content = "hello vsftpd, over plain PASV";

        client.connect(new TestGreeting(doneLatch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user(VsftpdTestSupport.USERNAME, new TestUserHandler(doneLatch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState pass) {
                        pass.pass(VsftpdTestSupport.PASSWORD, new TestPassHandler(doneLatch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                upload(auth, fileName, content, doneLatch, error, new AuthCallback() {
                                    @Override
                                    public void accept(ClientAuthenticatedState a2) {
                                        download(a2, fileName, doneLatch, error, downloaded);
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals(content, downloaded.get());
    }

    @Test
    public void testNlstShowsUploadedFile() throws Exception {
        FTPClient client = new FTPClient(VsftpdTestSupport.HOST, VsftpdTestSupport.PORT);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<List<FTPFileEntry>> entriesRef = new AtomicReference<>();
        String fileName = "listed-" + System.nanoTime() + ".txt";

        client.connect(new TestGreeting(doneLatch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.user(VsftpdTestSupport.USERNAME, new TestUserHandler(doneLatch, error) {
                    @Override
                    public void handlePasswordRequired(ClientPasswordState pass) {
                        pass.pass(VsftpdTestSupport.PASSWORD, new TestPassHandler(doneLatch, error) {
                            @Override
                            public void handleAuthenticated(ClientAuthenticatedState auth) {
                                upload(auth, fileName, "listing test", doneLatch, error, new AuthCallback() {
                                    @Override
                                    public void accept(ClientAuthenticatedState a2) {
                                        list(a2, fileName, doneLatch, error, entriesRef);
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        boolean found = false;
        for (FTPFileEntry entry : entriesRef.get()) {
            if (entry.getName().contains(fileName)) {
                found = true;
                break;
            }
        }
        assertTrue("NLST should list the uploaded file", found);
    }

    // ── AUTH TLS + PBSZ/PROT P, then STOR/RETR over the encrypted data channel ──

    @Test
    public void testAuthTlsProtPThenStorRetr() throws Exception {
        X509Certificate serverCert = VsftpdTestSupport.loadServerCertificate();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[] { pinningTrustManager(serverCert) }, null);

        FTPClient client = new FTPClient(VsftpdTestSupport.HOST, VsftpdTestSupport.PORT);
        client.setSSLContext(sslContext);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> downloaded = new AtomicReference<>();
        AtomicReference<Boolean> tlsEstablished = new AtomicReference<>(false);
        String fileName = "tls-roundtrip-" + System.nanoTime() + ".txt";
        String content = "hello vsftpd, over AUTH TLS + PROT P";

        client.connect(new TestGreeting(doneLatch, error) {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
                login.authTls(new ServerAuthTlsReplyHandler() {
                    @Override
                    public void handleTlsEstablished(ClientLoginState login2) {
                        tlsEstablished.set(true);
                        login2.user(VsftpdTestSupport.USERNAME, new TestUserHandler(doneLatch, error) {
                            @Override
                            public void handlePasswordRequired(ClientPasswordState pass) {
                                pass.pass(VsftpdTestSupport.PASSWORD, new TestPassHandler(doneLatch, error) {
                                    @Override
                                    public void handleAuthenticated(ClientAuthenticatedState auth) {
                                        auth.pbsz(0, new TestSimpleHandler(doneLatch, error) {
                                            @Override
                                            public void handleOk(ClientAuthenticatedState a) {
                                                a.prot("P", new TestSimpleHandler(doneLatch, error) {
                                                    @Override
                                                    public void handleOk(ClientAuthenticatedState a2) {
                                                        upload(a2, fileName, content, doneLatch, error, new AuthCallback() {
                                                            @Override
                                                            public void accept(ClientAuthenticatedState a3) {
                                                                download(a3, fileName, doneLatch, error, downloaded);
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

                    @Override
                    public void handleTlsUnavailable(ClientLoginState login2) {
                        fail(error, doneLatch, "AUTH TLS unavailable");
                    }

                    @Override
                    public void handleServiceClosing(String msg) {
                        fail(error, doneLatch, "service closing: " + msg);
                    }
                });
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("TLS was never established", tlsEstablished.get());
        assertEquals(content, downloaded.get());
    }

    // ── Shared PASV STOR/RETR/list plumbing (used by all three tests above) ──

    @FunctionalInterface
    private interface AuthCallback {
        void accept(ClientAuthenticatedState auth);
    }

    private void upload(ClientAuthenticatedState auth, String fileName, String content,
            CountDownLatch doneLatch, AtomicReference<Exception> error, AuthCallback onComplete) {
        auth.pasv(new TestPasvHandler(doneLatch, error) {
            @Override
            public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a) {
                a.stor(fileName, addr, new TestStorHandler(doneLatch, error) {
                    @Override
                    public void handleReadyToSend(ClientDataSink sink) {
                        sink.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                        sink.finish();
                    }

                    @Override
                    public void handleTransferComplete(ClientAuthenticatedState a2) {
                        onComplete.accept(a2);
                    }
                });
            }
        });
    }

    private void download(ClientAuthenticatedState auth, String fileName, CountDownLatch doneLatch,
            AtomicReference<Exception> error, AtomicReference<String> downloaded) {
        auth.pasv(new TestPasvHandler(doneLatch, error) {
            @Override
            public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a) {
                StringBuilder received = new StringBuilder();
                a.retr(fileName, addr, new TestRetrHandler(doneLatch, error) {
                    @Override
                    public void handleContent(ByteBuffer data) {
                        byte[] b = new byte[data.remaining()];
                        data.get(b);
                        received.append(new String(b, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void handleTransferComplete(ClientAuthenticatedState a2) {
                        downloaded.set(received.toString());
                        a2.quit();
                        doneLatch.countDown();
                    }
                });
            }
        });
    }

    private void list(ClientAuthenticatedState auth, String fileName, CountDownLatch doneLatch,
            AtomicReference<Exception> error, AtomicReference<List<FTPFileEntry>> entriesRef) {
        auth.pasv(new TestPasvHandler(doneLatch, error) {
            @Override
            public void handlePassive(InetSocketAddress addr, ClientAuthenticatedState a) {
                a.nlst(null, addr, new TestListHandler(doneLatch, error) {
                    @Override
                    public void handleEntries(List<FTPFileEntry> entries, ClientAuthenticatedState a2) {
                        entriesRef.set(entries);
                        a2.quit();
                        doneLatch.countDown();
                    }
                });
            }
        });
    }

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, String message) {
        error.set(new FTPException(message));
        doneLatch.countDown();
    }

    private X509TrustManager pinningTrustManager(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("vsftpd-test", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced for the pinned vsftpd cert");
    }

    // ── Test handler base classes (default: fail the test on any
    // unexpected callback), adapted from FTPClientIntegrationTest's
    // identical pattern ──

    private abstract class TestGreeting implements ServerGreeting {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestGreeting(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            fail(error, latch, "service unavailable: " + message);
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

    private abstract class TestUserHandler implements ServerUserReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestUserHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleUserAccepted(ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected immediate login");
        }

        @Override
        public void handlePasswordRequired(ClientPasswordState pass) {
            fail(error, latch, "unexpected password-required path");
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            fail(error, latch, "unexpected account-required path");
        }

        @Override
        public void handleRejected(ClientLoginState login, String message) {
            fail(error, latch, "USER rejected: " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }

    private abstract class TestPassHandler implements ServerPassReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPassHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleAuthenticated(ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected authenticated path");
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            fail(error, latch, "unexpected account-required path");
        }

        @Override
        public void handleAuthFailed(ClientLoginState login, String message) {
            fail(error, latch, "PASS rejected: " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }

    private abstract class TestSimpleHandler implements ServerSimpleReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestSimpleHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleOk(ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected OK path");
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            fail(error, latch, "command error " + code + ": " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }

    private abstract class TestPasvHandler implements ServerPasvReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestPasvHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handlePassive(InetSocketAddress dataAddress, ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected passive path");
        }

        @Override
        public void handleError(ClientAuthenticatedState authenticated, int code, String message) {
            fail(error, latch, "PASV error " + code + ": " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }

    private abstract class TestStorHandler implements ServerStorReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestStorHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleReadyToSend(ClientDataSink sink) {
            fail(error, latch, "unexpected ready-to-send path");
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected transfer-complete path");
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message) {
            fail(error, latch, "STOR failed " + code + ": " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }

    private abstract class TestRetrHandler implements ServerRetrReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestRetrHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleContent(ByteBuffer data) {
            fail(error, latch, "unexpected content path");
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected transfer-complete path");
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message) {
            fail(error, latch, "RETR failed " + code + ": " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }

    private abstract class TestListHandler implements ServerListReplyHandler {
        final CountDownLatch latch;
        final AtomicReference<Exception> error;

        TestListHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleEntries(List<FTPFileEntry> entries, ClientAuthenticatedState authenticated) {
            fail(error, latch, "unexpected entries path");
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message) {
            fail(error, latch, "listing failed " + code + ": " + message);
        }

        @Override
        public void handleServiceClosing(String message) {
            fail(error, latch, "service closing: " + message);
        }
    }
}
