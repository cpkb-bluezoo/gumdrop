/*
 * SMTPScramCredentialsOffloadTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Regression coverage for issue #301: {@code getScramCredentials} was
 * called directly, synchronously, from the {@code SelectorLoop} thread at
 * both SCRAM round trips (client-first and client-final). See {@code
 * org.bluezoo.gumdrop.imap.IMAPScramCredentialsOffloadTest} for the full
 * background -- this is the SMTP counterpart, exercising {@code
 * SMTPProtocolHandler}'s own SCRAM call sites.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPScramCredentialsOffloadTest {

    private static final String USERNAME = "scramuser";
    private static final String PASSWORD = "correct horse battery staple";
    // Real PBKDF2-HMAC-SHA256 work, just fewer iterations than production's
    // 210,000 so the test suite doesn't pay that cost many times over --
    // the offload behaviour being tested does not depend on the count.
    private static final int TEST_ITERATIONS = 20_000;

    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        StorageExecutor.workThreadObserver = null;
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull("StorageExecutor must exist after Gumdrop.start()",
                gumdrop.getStorageExecutor());
    }

    @After
    public void tearDown() {
        StorageExecutor.workThreadObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    @Test(timeout = 20000)
    public void scramCredentialDerivationRunsOffSelectorLoopThread() throws Exception {
        SMTPListener listener = new SMTPListener();
        listener.setRealm(new Pbkdf2ScramRealm(USERNAME, PASSWORD));

        SMTPProtocolHandler handler = new SMTPProtocolHandler(listener, null);
        RecordingStubEndpoint endpoint = new RecordingStubEndpoint(25);
        endpoint.setSecure(true);
        handler.connected(endpoint);

        endpoint.clearResponses();
        sendLine(handler, "EHLO client.example.com");
        endpoint.awaitLineStartingWith("250 ");

        final List<String> observedThreads = Collections.synchronizedList(new ArrayList<String>());
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                observedThreads.add(worker.getName());
            }
        };

        String clientNonce = "test-client-nonce";
        String clientFirstBare = "n=" + USERNAME + ",r=" + clientNonce;
        String clientFirst = "n,," + clientFirstBare;

        endpoint.clearResponses();
        sendLine(handler, "AUTH SCRAM-SHA-256 "
                + Base64.getEncoder().encodeToString(
                        clientFirst.getBytes(StandardCharsets.UTF_8)));
        endpoint.awaitLineStartingWith("334 ");

        assertFalse("client-first credential derivation must run through "
                + "StorageExecutor -- the work-thread observer was never "
                + "invoked, meaning it ran inline on the calling thread",
                observedThreads.isEmpty());
        for (String name : observedThreads) {
            assertTrue("credential derivation ran on unexpected thread: " + name,
                    name.startsWith("gumdrop-storage-"));
        }

        String serverFirstLine = endpoint.findLineStartingWith("334 ");
        String serverFirst = new String(
                Base64.getDecoder().decode(serverFirstLine.substring(4)),
                StandardCharsets.UTF_8);
        String serverNonce = null;
        String saltBase64 = null;
        int iterations = -1;
        for (String part : serverFirst.split(",")) {
            if (part.startsWith("r=")) {
                serverNonce = part.substring(2);
            } else if (part.startsWith("s=")) {
                saltBase64 = part.substring(2);
            } else if (part.startsWith("i=")) {
                iterations = Integer.parseInt(part.substring(2));
            }
        }
        assertNotNull("server-first must include r=", serverNonce);
        assertNotNull("server-first must include s=", saltBase64);
        assertEquals("server-first must echo the configured iteration count",
                TEST_ITERATIONS, iterations);

        String authMessageWithoutProof = clientFirstBare + "," + serverFirst
                + ",c=biws,r=" + serverNonce;
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        byte[] clientProof = computeClientProof(PASSWORD, salt, iterations, authMessageWithoutProof);
        String clientFinal = "c=biws,r=" + serverNonce + ",p="
                + Base64.getEncoder().encodeToString(clientProof);

        observedThreads.clear();
        endpoint.clearResponses();
        sendLine(handler, Base64.getEncoder().encodeToString(
                clientFinal.getBytes(StandardCharsets.UTF_8)));
        endpoint.awaitLineStartingWith("235 ");

        assertFalse("client-final credential derivation must also run "
                + "through StorageExecutor",
                observedThreads.isEmpty());
        for (String name : observedThreads) {
            assertTrue("credential derivation ran on unexpected thread: " + name,
                    name.startsWith("gumdrop-storage-"));
        }
    }

    // ── RFC 5802 §3 client-side proof computation ──

    private static byte[] computeClientProof(String password, byte[] salt, int iterations,
            String authMessage) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        byte[] saltedPassword = factory.generateSecret(spec).getEncoded();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(saltedPassword, "HmacSHA256"));
        byte[] clientKey = mac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));

        byte[] storedKey = java.security.MessageDigest.getInstance("SHA-256").digest(clientKey);

        byte[] clientSignature = SASLUtils.hmacSHA256(storedKey,
                authMessage.getBytes(StandardCharsets.UTF_8));
        byte[] proof = new byte[clientSignature.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ clientSignature[i]);
        }
        return proof;
    }

    // ── helpers ──

    private static void sendLine(org.bluezoo.gumdrop.ProtocolHandler handler,
            String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    /**
     * Minimal realm supporting only SCRAM-SHA-256, deriving credentials
     * with a real PBKDF2-HMAC-SHA256 pass (at a reduced iteration count,
     * for test speed) on every call -- i.e. with no cache, so this test
     * doesn't depend on {@code BasicRealm}'s internal caching to exercise
     * the offload at both the client-first and client-final call sites.
     */
    private static final class Pbkdf2ScramRealm implements Realm {
        private final String user;
        private final String password;
        private static final Set<SASLMechanism> SUPPORTED =
                Collections.unmodifiableSet(EnumSet.of(SASLMechanism.SCRAM_SHA_256));

        Pbkdf2ScramRealm(String user, String password) {
            this.user = user;
            this.password = password;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SASLMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return false;
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        @Override
        public ScramCredentials getScramCredentials(String username) {
            if (!user.equals(username)) {
                return null;
            }
            byte[] salt = new byte[16];
            for (int i = 0; i < salt.length; i++) {
                salt[i] = (byte) i;
            }
            return ScramCredentials.derive(password, salt, TEST_ITERATIONS, "SHA-256");
        }
    }
}
