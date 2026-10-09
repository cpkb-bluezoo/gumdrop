/*
 * MessageSizeLimitTest.java
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

package org.bluezoo.gumdrop.websocket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * {@link MessageSizeLimit} applies a maximum message size to a connection
 * when it opens and otherwise passes every event through.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageSizeLimitTest {

    /** A connection that is also its own session. */
    private static final class Connection extends WebSocketConnection implements WebSocketSession {
        @Override protected void opened() { }
        @Override protected void textMessageReceived(String message) { }
        @Override protected void binaryMessageReceived(ByteBuffer data) { }
        @Override protected void closed(int code, String reason) { }
        @Override protected void error(Throwable cause) { }
        @Override public java.security.Principal getPrincipal() { return null; }
    }

    private static final class Recorder extends DefaultWebSocketEventHandler {
        final List<String> calls = new ArrayList<String>();

        @Override public void opened(WebSocketSession session) { calls.add("opened"); }
        @Override public void textMessageReceived(WebSocketSession session, String m) { calls.add("text:" + m); }
        @Override public void binaryMessageReceived(WebSocketSession session, ByteBuffer d) { calls.add("binary"); }
        @Override public void closed(int code, String reason) { calls.add("closed:" + code); }
        @Override public void error(Throwable cause) { calls.add("error"); }
    }

    @Test
    public void connectionDefaultsToTheBuiltInLimit() {
        assertEquals(WebSocketConnection.DEFAULT_MAX_MESSAGE_SIZE,
                new Connection().getMaxMessageSize());
    }

    @Test
    public void openingAppliesTheLimitBeforeTheDelegateSeesTheSession() {
        Connection connection = new Connection();
        final long[] seen = new long[1];
        final Connection c = connection;
        WebSocketEventHandler delegate = new DefaultWebSocketEventHandler() {
            @Override public void opened(WebSocketSession session) {
                seen[0] = c.getMaxMessageSize();
            }
        };
        new MessageSizeLimit(delegate, 4096L).opened(connection);
        assertEquals(4096L, connection.getMaxMessageSize());
        assertEquals(4096L, seen[0]);
    }

    @Test
    public void zeroMeansUnlimited() {
        Connection connection = new Connection();
        new MessageSizeLimit(new Recorder(), 0L).opened(connection);
        assertEquals(0L, connection.getMaxMessageSize());
    }

    @Test
    public void everyEventPassesThrough() {
        Recorder recorder = new Recorder();
        MessageSizeLimit limit = new MessageSizeLimit(recorder, 10L);
        Connection connection = new Connection();
        limit.opened(connection);
        limit.textMessageReceived(connection, "hi");
        limit.binaryMessageReceived(connection, ByteBuffer.allocate(1));
        limit.error(new RuntimeException());
        limit.closed(1000, "bye");
        assertEquals("[opened, text:hi, binary, error, closed:1000]", recorder.calls.toString());
    }

    @Test
    public void aSessionThatIsNotAConnectionIsPassedOnUnchanged() {
        Recorder recorder = new Recorder();
        WebSocketSession notAConnection = (WebSocketSession) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {WebSocketSession.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        return null;
                    }
                });
        new MessageSizeLimit(recorder, 10L).opened(notAConnection);
        assertEquals("[opened]", recorder.calls.toString());
    }

    @Test
    public void constructorRejectsBadArguments() {
        try {
            new MessageSizeLimit(null, 1L);
            fail();
        } catch (NullPointerException expected) {
            assertSame(NullPointerException.class, expected.getClass());
        }
        try {
            new MessageSizeLimit(new Recorder(), -1L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertSame(IllegalArgumentException.class, expected.getClass());
        }
    }
}
