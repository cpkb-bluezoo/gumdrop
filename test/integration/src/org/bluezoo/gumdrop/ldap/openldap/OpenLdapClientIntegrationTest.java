/*
 * OpenLdapClientIntegrationTest.java
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

package org.bluezoo.gumdrop.ldap.openldap;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ldap.client.AddResultHandler;
import org.bluezoo.gumdrop.ldap.client.BindResultHandler;
import org.bluezoo.gumdrop.ldap.client.DeleteResultHandler;
import org.bluezoo.gumdrop.ldap.client.LDAPClient;
import org.bluezoo.gumdrop.ldap.client.LDAPConnected;
import org.bluezoo.gumdrop.ldap.client.LDAPConnectionReady;
import org.bluezoo.gumdrop.ldap.client.LDAPPostTLS;
import org.bluezoo.gumdrop.ldap.client.LDAPResult;
import org.bluezoo.gumdrop.ldap.client.LDAPResultCode;
import org.bluezoo.gumdrop.ldap.client.LDAPSession;
import org.bluezoo.gumdrop.ldap.client.Modification;
import org.bluezoo.gumdrop.ldap.client.ModifyResultHandler;
import org.bluezoo.gumdrop.ldap.client.SearchRequest;
import org.bluezoo.gumdrop.ldap.client.SearchResultEntry;
import org.bluezoo.gumdrop.ldap.client.SearchResultHandler;
import org.bluezoo.gumdrop.ldap.client.StartTLSResultHandler;

import org.junit.Before;
import org.junit.Test;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * End-to-end tests of gumdrop's LDAP client against a real, locally-running
 * OpenLDAP ({@code slapd}) instance -- not run in CI, see {@link
 * OpenLdapTestSupport}.
 *
 * <p>Same rationale as the Postfix/vsftpd/Dante tests: an independent
 * implementation on the other end of the wire catches bugs a same-lineage
 * fake server can't.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OpenLdapClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Before
    public void checkReachable() {
        assumeTrue(OpenLdapTestSupport.NOT_REACHABLE_MESSAGE, OpenLdapTestSupport.isReachable());
    }

    private LDAPClient newClient() {
        return new LDAPClient(OpenLdapTestSupport.HOST, OpenLdapTestSupport.PORT);
    }

    // ── Plaintext bind + search ──

    @Test
    public void testAdminBindAndSearchFindsTestUser() throws Exception {
        LDAPClient client = newClient();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<SearchResultEntry> foundEntry = new AtomicReference<>();

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(LDAPConnected connection) {
                connection.bind(OpenLdapTestSupport.ADMIN_DN, OpenLdapTestSupport.ADMIN_PASSWORD,
                        new TestBindHandler(error, doneLatch) {
                            @Override
                            public void handleBindSuccess(LDAPSession session) {
                                SearchRequest request = new SearchRequest();
                                request.setBaseDN(OpenLdapTestSupport.BASE_DN);
                                request.setFilter("(uid=jdoe)");
                                session.search(request, new SearchResultHandler() {
                                    @Override
                                    public void handleEntry(SearchResultEntry entry) {
                                        foundEntry.set(entry);
                                    }

                                    @Override
                                    public void handleReference(String[] referralUrls) {
                                    }

                                    @Override
                                    public void handleDone(LDAPResult result, LDAPSession s) {
                                        if (!result.isSuccess()) {
                                            fail(error, doneLatch, "search failed: " + result);
                                            return;
                                        }
                                        doneLatch.countDown();
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
        SearchResultEntry entry = foundEntry.get();
        assertEquals(OpenLdapTestSupport.TEST_USER_DN, entry.getDN());
        assertEquals("John Doe", entry.getAttributeStringValue("cn"));
        assertEquals("jdoe@test.gumdrop.local", entry.getAttributeStringValue("mail"));
    }

    // ── Add / modify / delete round trip ──

    @Test
    public void testAddModifyDeleteRoundTrip() throws Exception {
        LDAPClient client = newClient();
        String dn = "uid=temp-" + System.nanoTime() + ",ou=people," + OpenLdapTestSupport.BASE_DN;

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(LDAPConnected connection) {
                connection.bind(OpenLdapTestSupport.ADMIN_DN, OpenLdapTestSupport.ADMIN_PASSWORD,
                        new TestBindHandler(error, doneLatch) {
                            @Override
                            public void handleBindSuccess(LDAPSession session) {
                                Map<String, List<byte[]>> attrs = new HashMap<>();
                                attrs.put("objectClass", List.of(
                                        "inetOrgPerson".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                                attrs.put("cn", List.of("Temp User".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                                attrs.put("sn", List.of("User".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                                session.add(dn, attrs, new AddResultHandler() {
                                    @Override
                                    public void handleAddResult(LDAPResult result, LDAPSession s) {
                                        if (!result.isSuccess()) {
                                            fail(error, doneLatch, "add failed: " + result);
                                            return;
                                        }
                                        List<Modification> mods = List.of(new Modification(
                                                Modification.Operation.REPLACE, "sn",
                                                List.of("Modified".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                                        s.modify(dn, mods, new ModifyResultHandler() {
                                            @Override
                                            public void handleModifyResult(LDAPResult result2, LDAPSession s2) {
                                                if (!result2.isSuccess()) {
                                                    fail(error, doneLatch, "modify failed: " + result2);
                                                    return;
                                                }
                                                s2.delete(dn, new DeleteResultHandler() {
                                                    @Override
                                                    public void handleDeleteResult(LDAPResult result3, LDAPSession s3) {
                                                        if (!result3.isSuccess()) {
                                                            fail(error, doneLatch, "delete failed: " + result3);
                                                            return;
                                                        }
                                                        doneLatch.countDown();
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

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
    }

    // ── StartTLS upgrade, then bind as the test user over the encrypted connection ──

    @Test
    public void testStartTlsThenBindAsTestUser() throws Exception {
        X509Certificate serverCert = OpenLdapTestSupport.loadServerCertificate();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[] { pinningTrustManager(serverCert) }, null);

        LDAPClient client = newClient();
        client.setSSLContext(sslContext);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Boolean> tlsEstablished = new AtomicReference<>(false);

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(LDAPConnected connection) {
                connection.startTLS(new StartTLSResultHandler() {
                    @Override
                    public void handleTLSEstablished(LDAPPostTLS postTLS) {
                        tlsEstablished.set(true);
                        postTLS.bind(OpenLdapTestSupport.TEST_USER_DN, OpenLdapTestSupport.TEST_USER_PASSWORD,
                                new TestBindHandler(error, doneLatch) {
                                    @Override
                                    public void handleBindSuccess(LDAPSession session) {
                                        doneLatch.countDown();
                                    }
                                });
                    }

                    @Override
                    public void handleStartTLSFailure(LDAPResult result, LDAPConnected connection2) {
                        fail(error, doneLatch, "StartTLS failed: " + result);
                    }
                });
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("TLS was never established", tlsEstablished.get());
    }

    // ── Negative: wrong password ──

    @Test
    public void testInvalidCredentialsRejected() throws Exception {
        LDAPClient client = newClient();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<LDAPResultCode> failureCode = new AtomicReference<>();

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(LDAPConnected connection) {
                connection.bind(OpenLdapTestSupport.TEST_USER_DN, "wrong-password",
                        new BindResultHandler() {
                            @Override
                            public void handleBindSuccess(LDAPSession session) {
                                fail(error, doneLatch, "bind should not have succeeded with wrong password");
                            }

                            @Override
                            public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
                                failureCode.set(result.getResultCode());
                                conn.unbind();
                                doneLatch.countDown();
                            }
                        });
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals(LDAPResultCode.INVALID_CREDENTIALS, failureCode.get());
    }

    // ── Shared plumbing ──

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, String message) {
        error.set(new RuntimeException(message));
        doneLatch.countDown();
    }

    private X509TrustManager pinningTrustManager(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ldap-test", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced for the pinned ldap-test cert");
    }

    // ── Test handler base classes (default: fail on unexpected callback) ──

    private abstract class TestConnectionReady implements LDAPConnectionReady {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestConnectionReady(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onError(Exception cause) {
            error.set(cause);
            latch.countDown();
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    private abstract class TestBindHandler implements BindResultHandler {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestBindHandler(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void handleBindFailure(LDAPResult result, LDAPConnected connection) {
            fail(error, latch, "bind failed: " + result);
        }
    }
}
