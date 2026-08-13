/*
 * RedisClientIntegrationTest.java
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

package org.bluezoo.gumdrop.redis.redis;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.client.BulkResultHandler;
import org.bluezoo.gumdrop.redis.client.IntegerResultHandler;
import org.bluezoo.gumdrop.redis.client.MessageHandler;
import org.bluezoo.gumdrop.redis.client.RedisClient;
import org.bluezoo.gumdrop.redis.client.RedisConnectionReady;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.StringResultHandler;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
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
 * End-to-end tests of gumdrop's Redis client against a real, locally-running
 * Redis instance -- not run in CI, see {@link RedisTestSupport}.
 *
 * <p>Same rationale as the Postfix/vsftpd/Dante/OpenLDAP tests: an
 * independent implementation on the other end of the wire catches bugs a
 * same-lineage fake server can't.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Before
    public void checkReachable() {
        assumeTrue(RedisTestSupport.NOT_REACHABLE_MESSAGE, RedisTestSupport.isReachable());
    }

    private RedisClient newClient() {
        return new RedisClient(RedisTestSupport.HOST, RedisTestSupport.PORT);
    }

    // ── AUTH + string/increment round trip ──

    @Test
    public void testAuthSetGetIncrRoundTrip() throws Exception {
        RedisClient client = newClient();
        String key = "gumdrop-test-" + System.nanoTime();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> gotValue = new AtomicReference<>();
        AtomicReference<Long> counter = new AtomicReference<>();

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(RedisSession session) {
                session.auth(RedisTestSupport.PASSWORD, new TestStringHandler(error, doneLatch) {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        s.set(key, "hello redis", new TestStringHandler(error, doneLatch) {
                            @Override
                            public void handleResult(String result2, RedisSession s2) {
                                s2.get(key, new TestBulkHandler(error, doneLatch) {
                                    @Override
                                    public void handleResult(byte[] value, RedisSession s3) {
                                        gotValue.set(new String(value, StandardCharsets.UTF_8));
                                        String counterKey = key + "-counter";
                                        s3.incr(counterKey, new TestIntegerHandler(error, doneLatch) {
                                            @Override
                                            public void handleResult(long value2, RedisSession s4) {
                                                counter.set(value2);
                                                s4.incr(counterKey, new TestIntegerHandler(error, doneLatch) {
                                                    @Override
                                                    public void handleResult(long value3, RedisSession s5) {
                                                        counter.set(value3);
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
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals("hello redis", gotValue.get());
        assertEquals(Long.valueOf(2L), counter.get());
    }

    // ── Pub/Sub: subscribe on one connection, publish from another ──

    @Test
    public void testPubSubRoundTrip() throws Exception {
        String channel = "gumdrop-test-channel-" + System.nanoTime();
        String payload = "hello subscribers";

        CountDownLatch subscribedLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();

        RedisClient subscriber = newClient();
        subscriber.connect(new TestConnectionReady(error, subscribedLatch) {
            @Override
            public void handleReady(RedisSession session) {
                session.auth(RedisTestSupport.PASSWORD, new TestStringHandler(error, subscribedLatch) {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        s.subscribe(new MessageHandler() {
                            @Override
                            public void handleMessage(String ch, byte[] message) {
                                received.set(new String(message, StandardCharsets.UTF_8));
                                messageLatch.countDown();
                            }

                            @Override
                            public void handlePatternMessage(String pattern, String ch, byte[] message) {
                            }

                            @Override
                            public void handleSubscribed(String ch, int subscriptionCount) {
                                subscribedLatch.countDown();
                            }

                            @Override
                            public void handlePatternSubscribed(String pattern, int subscriptionCount) {
                            }

                            @Override
                            public void handleUnsubscribed(String ch, int subscriptionCount) {
                            }

                            @Override
                            public void handlePatternUnsubscribed(String pattern, int subscriptionCount) {
                            }

                            @Override
                            public void handleError(String errorMessage) {
                                error.set(new RuntimeException("pub/sub error: " + errorMessage));
                                subscribedLatch.countDown();
                                messageLatch.countDown();
                            }
                        }, channel);
                    }
                });
            }
        });

        assertTrue("subscribe did not complete within timeout",
                subscribedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }

        RedisClient publisher = newClient();
        CountDownLatch publishDoneLatch = new CountDownLatch(1);
        publisher.connect(new TestConnectionReady(error, publishDoneLatch) {
            @Override
            public void handleReady(RedisSession session) {
                session.auth(RedisTestSupport.PASSWORD, new TestStringHandler(error, publishDoneLatch) {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        s.publish(channel, payload, new TestIntegerHandler(error, publishDoneLatch) {
                            @Override
                            public void handleResult(long subscriberCount, RedisSession s2) {
                                publishDoneLatch.countDown();
                            }
                        });
                    }
                });
            }
        });

        assertTrue("publish did not complete within timeout",
                publishDoneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("message not received within timeout",
                messageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals(payload, received.get());
    }

    // ── TLS: AUTH + round trip over the encrypted port ──

    @Test
    public void testTlsAuthSetGetRoundTrip() throws Exception {
        X509Certificate serverCert = RedisTestSupport.loadServerCertificate();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[] { pinningTrustManager(serverCert) }, null);

        RedisClient client = new RedisClient(RedisTestSupport.HOST, RedisTestSupport.TLS_PORT);
        client.setSecure(true);
        client.setSSLContext(sslContext);

        String key = "gumdrop-tls-test-" + System.nanoTime();
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> gotValue = new AtomicReference<>();

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(RedisSession session) {
                session.auth(RedisTestSupport.PASSWORD, new TestStringHandler(error, doneLatch) {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        s.set(key, "hello over tls", new TestStringHandler(error, doneLatch) {
                            @Override
                            public void handleResult(String result2, RedisSession s2) {
                                s2.get(key, new TestBulkHandler(error, doneLatch) {
                                    @Override
                                    public void handleResult(byte[] value, RedisSession s3) {
                                        gotValue.set(new String(value, StandardCharsets.UTF_8));
                                        doneLatch.countDown();
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
        assertEquals("hello over tls", gotValue.get());
    }

    // ── Negative: wrong password ──

    @Test
    public void testWrongPasswordRejected() throws Exception {
        RedisClient client = newClient();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> authError = new AtomicReference<>();

        client.connect(new TestConnectionReady(error, doneLatch) {
            @Override
            public void handleReady(RedisSession session) {
                session.auth("wrong-password", new StringResultHandler() {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        fail(error, doneLatch, "AUTH should not have succeeded with the wrong password");
                    }

                    @Override
                    public void handleError(String errorMessage, RedisSession s) {
                        authError.set(errorMessage);
                        doneLatch.countDown();
                    }
                });
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("expected a WRONGPASS-style error, got: " + authError.get(),
                authError.get() != null && authError.get().toUpperCase().contains("WRONGPASS")
                        || authError.get().toLowerCase().contains("invalid password"));
    }

    // ── Shared plumbing ──

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, String message) {
        error.set(new RuntimeException(message));
        doneLatch.countDown();
    }

    private X509TrustManager pinningTrustManager(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("redis-test", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced for the pinned redis-test cert");
    }

    // ── Test handler base classes (default: fail on unexpected callback) ──

    private abstract class TestConnectionReady implements RedisConnectionReady {
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

    private abstract class TestStringHandler implements StringResultHandler {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestStringHandler(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void handleError(String errorMessage, RedisSession session) {
            fail(error, latch, "Redis error: " + errorMessage);
        }
    }

    private abstract class TestBulkHandler implements BulkResultHandler {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestBulkHandler(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void handleNull(RedisSession session) {
            fail(error, latch, "unexpected null bulk result");
        }

        @Override
        public void handleError(String errorMessage, RedisSession session) {
            fail(error, latch, "Redis error: " + errorMessage);
        }
    }

    private abstract class TestIntegerHandler implements IntegerResultHandler {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestIntegerHandler(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void handleError(String errorMessage, RedisSession session) {
            fail(error, latch, "Redis error: " + errorMessage);
        }
    }
}
