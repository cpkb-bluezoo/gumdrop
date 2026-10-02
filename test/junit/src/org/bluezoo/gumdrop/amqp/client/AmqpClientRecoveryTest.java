/*
 * AmqpClientRecoveryTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.testsupport.RefusingTransportFactory;
import org.bluezoo.gumdrop.amqp.AmqpFrame;
import org.bluezoo.gumdrop.amqp.AmqpMethod;
import org.bluezoo.gumdrop.amqp.FieldTable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link AmqpClientRecovery} against a minimal in-test broker on a
 * loopback socket: mechanism selection, the automatic handshake, loss and
 * recovery notifications, retry exhaustion and the configuration surface.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AmqpClientRecoveryTest {

    private static final int TIMEOUT_MS = 15000;

    private Gumdrop gumdrop;
    private ClientEndpoint keeper;
    private ServerSocket broker;
    private AmqpClientRecovery client;

    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private final List<Exception> causes = Collections.synchronizedList(new ArrayList<Exception>());

    private final class Listener implements RecoveryListener {
        final CountDownLatch lost = new CountDownLatch(1);
        final CountDownLatch recovered = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);

        @Override
        public void onConnectionLost(Exception cause) {
            causes.add(cause);
            events.add("lost");
            lost.countDown();
        }

        @Override
        public void onReconnecting(int attempt, long delayMs) {
            events.add("reconnecting:" + attempt);
        }

        @Override
        public void onRecovered() {
            events.add("recovered");
            recovered.countDown();
        }

        @Override
        public void onRecoveryFailed(Exception cause) {
            causes.add(cause);
            events.add("failed");
            failed.countDown();
        }
    }

    private final class First implements RecoveryHandler {
        final CountDownLatch connected = new CountDownLatch(1);
        volatile ClientConnection connection;

        @Override
        public void onFirstConnect(ClientConnection c) {
            connection = c;
            events.add("first");
            connected.countDown();
        }
    }

    @Before
    public void setUp() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        keeper = new ClientEndpoint(new TcpTransportFactory(), gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        broker = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        broker.setSoTimeout(TIMEOUT_MS);
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        broker.close();
        gumdrop.shutdown();
        gumdrop.join();
    }

    private RecoveryPolicy fastPolicy(int maxAttempts) {
        return new RecoveryPolicy().withInitialDelayMs(1).withMaxDelayMs(5).withMultiplier(1.0)
                .withMaxAttempts(maxAttempts);
    }

    // ── minimal broker ──

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }

    /** Reads one frame; returns its payload (class id, method id, arguments). */
    private static ByteBuffer readFrame(InputStream in) throws IOException {
        byte[] header = new byte[AmqpFrame.HEADER_SIZE];
        readFully(in, header);
        int size = ByteBuffer.wrap(header, 3, 4).getInt();
        byte[] payload = new byte[size];
        readFully(in, payload);
        byte[] end = new byte[1];
        readFully(in, end);
        return ByteBuffer.wrap(payload);
    }

    private static void write(OutputStream out, ByteBuffer frame) throws IOException {
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        out.write(bytes);
        out.flush();
    }

    private static ByteBuffer startFrame(String mechanisms) {
        FieldTable props = new FieldTable().put("product", "TestBroker");
        ByteBuffer encodedProps = props.encode();
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + 4 + encodedProps.remaining() + 4 + 64 + 4 + 5);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_START);
        args.put((byte) 0);
        args.put((byte) 9);
        args.putInt(encodedProps.remaining());
        args.put(encodedProps);
        FieldTable.putLongString(args, mechanisms);
        FieldTable.putLongString(args, "en_US");
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer tuneFrame() {
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + 4 + 2);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_TUNE);
        args.putShort((short) 0);
        args.putInt(131072);
        args.putShort((short) 0);
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer openOkFrame() {
        ByteBuffer args = ByteBuffer.allocate(4 + 1);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_OPEN_OK);
        FieldTable.putShortString(args, "");
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer closeFrame(int code, String text) {
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + FieldTable.shortStringEncodedSize(text) + 4);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_CLOSE);
        args.putShort((short) code);
        FieldTable.putShortString(args, text);
        args.putShort((short) 0);
        args.putShort((short) 0);
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    /** Extracts the mechanism name from a connection.start-ok payload. */
    private static String startOkMechanism(ByteBuffer payload) {
        payload.getShort();
        payload.getShort();
        int tableLen = payload.getInt();
        payload.position(payload.position() + tableLen);
        int len = payload.get() & 0xFF;
        byte[] name = new byte[len];
        payload.get(name);
        return new String(name, StandardCharsets.US_ASCII);
    }

    /** Accepts one connection and drives it through open-ok; returns the socket. */
    private Socket handshake(String offered, String[] chosenMechanism) throws IOException {
        Socket s = broker.accept();
        s.setSoTimeout(TIMEOUT_MS);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        byte[] header = new byte[8];
        readFully(in, header);
        assertEquals("AMQP", new String(header, 0, 4, StandardCharsets.US_ASCII));
        write(out, startFrame(offered));
        ByteBuffer startOk = readFrame(in);
        if (chosenMechanism != null) {
            chosenMechanism[0] = startOkMechanism(startOk);
        }
        write(out, tuneFrame());
        ByteBuffer tuneOk = readFrame(in);
        tuneOk.getShort();
        assertEquals(AmqpMethod.CONNECTION_TUNE_OK, tuneOk.getShort() & 0xFFFF);
        ByteBuffer open = readFrame(in);
        open.getShort();
        assertEquals(AmqpMethod.CONNECTION_OPEN, open.getShort() & 0xFFFF);
        write(out, openOkFrame());
        return s;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("timed out", latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private AmqpClientRecovery newClient() {
        client = new AmqpClientRecovery(InetAddress.getLoopbackAddress(), broker.getLocalPort());
        return client;
    }

    // ── tests ──

    @Test
    public void plainHandshakeThenLossAndRecovery() throws Exception {
        Listener listener = new Listener();
        First first = new First();
        newClient().credentials("u", "p").virtualHost("/v").recoveryPolicy(fastPolicy(0))
                .recoveryListener(listener).connect(gumdrop, first);
        String[] chosen = new String[1];
        Socket s1 = handshake("PLAIN", chosen);
        await(first.connected);
        assertEquals("PLAIN", chosen[0]);
        assertNotNull(first.connection);

        s1.close();
        await(listener.lost);
        Socket s2 = handshake("PLAIN", null);
        await(listener.recovered);
        assertTrue(events.toString(), events.indexOf("lost") < events.indexOf("recovered"));
        assertTrue(events.toString(), events.contains("reconnecting:1"));
        assertEquals("first runs once", 1, Collections.frequency(events, "first"));
        s2.close();
    }

    @Test
    public void amqplainMechanismIsUsedWhenRequested() throws Exception {
        First first = new First();
        newClient().mechanism("AMQPLAIN").recoveryPolicy(fastPolicy(0)).connect(gumdrop, first);
        String[] chosen = new String[1];
        Socket s = handshake("PLAIN AMQPLAIN", chosen);
        await(first.connected);
        assertEquals("AMQPLAIN", chosen[0]);
        s.close();
    }

    @Test
    public void externalMechanismIsUsedWhenOffered() throws Exception {
        First first = new First();
        newClient().mechanism("external").recoveryPolicy(fastPolicy(0)).connect(gumdrop, first);
        String[] chosen = new String[1];
        Socket s = handshake("PLAIN EXTERNAL", chosen);
        await(first.connected);
        assertEquals("EXTERNAL", chosen[0]);
        s.close();
    }

    @Test
    public void unofferedMechanismExhaustsRetriesWithPermanentFailure() throws Exception {
        Listener listener = new Listener();
        First first = new First();
        newClient().mechanism("EXTERNAL").recoveryPolicy(fastPolicy(1)).recoveryListener(listener)
                .connect(gumdrop, first);
        Socket s = broker.accept();
        s.setSoTimeout(TIMEOUT_MS);
        byte[] header = new byte[8];
        readFully(s.getInputStream(), header);
        write(s.getOutputStream(), startFrame("PLAIN"));
        await(listener.lost);
        s.close();
        Socket s2 = broker.accept();
        s2.setSoTimeout(TIMEOUT_MS);
        readFully(s2.getInputStream(), header);
        write(s2.getOutputStream(), startFrame("PLAIN"));
        await(listener.failed);
        s2.close();
        Exception last = causes.get(causes.size() - 1);
        assertTrue(last.getMessage(), last.getMessage().startsWith("Broker does not offer the requested SASL mechanism"));
        assertFalse(events.contains("first"));
    }

    @Test
    public void gssapiWithoutCredentialsFailsTheAttempt() throws Exception {
        Listener listener = new Listener();
        newClient().mechanism("GSSAPI").recoveryPolicy(fastPolicy(1)).recoveryListener(listener)
                .connect(gumdrop, new First());
        Socket s = broker.accept();
        s.setSoTimeout(TIMEOUT_MS);
        byte[] header = new byte[8];
        readFully(s.getInputStream(), header);
        write(s.getOutputStream(), startFrame("PLAIN GSSAPI"));
        await(listener.lost);
        assertTrue(causes.get(0).getMessage(), causes.get(0).getMessage().contains("gssapiCredentials"));
        s.close();
    }

    @Test
    public void brokerInitiatedCloseTriggersReconnect() throws Exception {
        Listener listener = new Listener();
        First first = new First();
        newClient().recoveryPolicy(fastPolicy(0)).recoveryListener(listener).connect(gumdrop, first);
        Socket s1 = handshake("PLAIN", null);
        await(first.connected);
        write(s1.getOutputStream(), closeFrame(320, "CONNECTION_FORCED"));
        await(listener.lost);
        Exception cause = causes.get(0);
        assertTrue(cause.getMessage(), cause.getMessage().contains("320"));
        s1.close();
        Socket s2 = handshake("PLAIN", null);
        await(listener.recovered);
        s2.close();
    }

    @Test
    public void refusedConnectionsAreRetriedThenAbandoned() throws Exception {
        final RefusingTransportFactory refusing = new RefusingTransportFactory();
        Listener listener = new Listener();
        client = new AmqpClientRecovery(InetAddress.getLoopbackAddress(), broker.getLocalPort()) {
            @Override
            TcpTransportFactory newTransportFactory() {
                return refusing;
            }
        };
        client.recoveryPolicy(fastPolicy(2)).recoveryListener(listener).connect(gumdrop, new First());
        await(listener.failed);
        assertTrue(events.toString(), events.contains("reconnecting:1"));
        assertTrue(events.toString(), events.contains("reconnecting:2"));
        assertFalse(events.toString(), events.contains("reconnecting:3"));
        assertEquals("initial attempt plus two retries", 3, refusing.attempts());
    }

    @Test
    public void closeStopsFurtherReconnects() throws Exception {
        Listener listener = new Listener();
        First first = new First();
        newClient().recoveryPolicy(new RecoveryPolicy().withInitialDelayMs(60000).withMaxDelayMs(60000))
                .recoveryListener(listener).connect(gumdrop, first);
        Socket s = handshake("PLAIN", null);
        await(first.connected);
        s.close();
        await(listener.lost);
        client.close();
        broker.setSoTimeout(300);
        try {
            broker.accept();
            fail("no reconnect expected after close()");
        } catch (java.net.SocketTimeoutException expected) {
            assertFalse(events.contains("recovered"));
        }
    }

    @Test
    public void connectAfterCloseDoesNothing() throws Exception {
        newClient();
        client.close();
        First first = new First();
        client.connect(gumdrop, first);
        broker.setSoTimeout(300);
        try {
            broker.accept();
            fail("a closed client must not connect");
        } catch (java.net.SocketTimeoutException expected) {
            assertEquals(1, first.connected.getCount());
        }
    }

    @Test
    public void hostNameAndUnixSocketConstructorsAndSetters() {
        AmqpClientRecovery byName = new AmqpClientRecovery("localhost", 5672)
                .setSecure(false).setClientCredentials(null).setTrustManager(null)
                .setKeystoreFile(null).setKeystorePass("x").setKeystoreFormat(null)
                .gssapiCredentials(null, "amqp@localhost", null);
        assertNotNull(byName);
        AmqpClientRecovery path = new AmqpClientRecovery("/tmp/amqp.sock");
        assertNotNull(path);
        try {
            new AmqpClientRecovery(null, (String) null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("socketPath", expected.getMessage());
        }
        // closing a never-connected client is harmless
        byName.close();
        path.close();
    }

    @Test
    public void unixSocketClientToMissingPathReportsLossAndGivesUp() throws Exception {
        Listener listener = new Listener();
        client = new AmqpClientRecovery("/nonexistent-dir-for-test/amqp.sock");
        client.recoveryPolicy(fastPolicy(1)).recoveryListener(listener).connect(gumdrop, new First());
        await(listener.failed);
        assertTrue(events.toString(), events.contains("lost"));
    }
}
