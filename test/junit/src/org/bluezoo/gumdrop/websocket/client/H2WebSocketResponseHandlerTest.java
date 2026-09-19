/*
 * H2WebSocketResponseHandlerTest.java
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

package org.bluezoo.gumdrop.websocket.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponse;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketFrame;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the RFC 8441 (WebSocket over HTTP/2) response bridge with a stub
 * {@link HttpRequest} standing in for the H2 stream.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2WebSocketResponseHandlerTest {

    private static final class StubRequest implements HttpRequest {
        final List<byte[]> body = new ArrayList<byte[]>();
        boolean ended;

        @Override public void header(String name, String value) { }
        @Override public void priority(int weight) { }
        @Override public void dependency(HttpRequest parent) { }
        @Override public void exclusive(boolean exclusive) { }
        @Override public void send(HttpResponseHandler handler) { }
        @Override public void startRequestBody(HttpResponseHandler handler) { }
        @Override public int requestBodyContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            body.add(b);
            return b.length;
        }
        @Override public void endRequestBody() { ended = true; }
        @Override public void cancel() { }
    }

    private StubRequest request;
    private RecordingWebSocketEventHandler ws;

    @Before
    public void setUp() {
        request = new StubRequest();
        ws = new RecordingWebSocketEventHandler();
    }

    private H2WebSocketResponseHandler handler(
            List<WebSocketExtension> extensions) {
        return new H2WebSocketResponseHandler(request, extensions, ws);
    }

    private static List<WebSocketExtension> noExtensions() {
        return new ArrayList<WebSocketExtension>();
    }

    @Test
    public void startResponseBodyOpensWebSocket() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.ok(new HttpResponse(HttpStatus.OK));
        h.startResponseBody();
        assertEquals(1, ws.openedCount);
        assertTrue(ws.session.isOpen());
    }

    @Test
    public void incomingFramesDelivered() throws IOException {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        h.responseBodyContent(WebSocketFrame.createTextFrame("hi", false)
                .encode());
        assertEquals(Arrays.asList("hi"), ws.texts);
    }

    @Test
    public void outboundFramesGoToRequestBodyMasked() throws IOException {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        ws.session.sendText("out");
        assertEquals(1, request.body.size());
        WebSocketFrame f = WebSocketFrame.parse(
                ByteBuffer.wrap(request.body.get(0)));
        assertTrue(f.isMasked());
        assertEquals("out", f.getTextPayload());
    }

    @Test
    public void closeEndsRequestBody() throws IOException {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        h.responseBodyContent(WebSocketFrame.createCloseFrame(1000, "", false)
                .encode());
        assertEquals(Arrays.asList(1000), ws.closeCodes);
        assertTrue(request.ended);
    }

    @Test
    public void extensionsFromHeaderAreReconciled() {
        List<WebSocketExtension> requested = noExtensions();
        requested.add(new PerMessageDeflateExtension());
        H2WebSocketResponseHandler h = handler(requested);
        h.header("Content-Type", "ignored");
        h.header("Sec-WebSocket-Extensions", "permessage-deflate");
        h.startResponseBody();
        assertEquals(1, ws.openedCount);
    }

    @Test
    public void errorResponseReportsAndSuppressesOpen() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.error(new HttpResponse(HttpStatus.BAD_REQUEST));
        assertEquals(1, ws.errors.size());
        h.startResponseBody();
        assertEquals(0, ws.openedCount);
        h.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
        h.endResponseBody();
        h.failed(new IOException("late"));
        assertEquals(1, ws.errors.size());
    }

    @Test
    public void bodyBeforeStartIsIgnored() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
        h.endResponseBody();
        assertEquals(0, ws.openedCount);
        assertTrue(ws.closeCodes.isEmpty());
    }

    @Test
    public void streamEndReports1001() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        h.endResponseBody();
        assertEquals(Arrays.asList(1001), ws.closeCodes);
    }

    @Test
    public void streamEndAfterCloseNotReportedTwice() throws IOException {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        h.responseBodyContent(WebSocketFrame.createCloseFrame(1000, "", false)
                .encode());
        h.endResponseBody();
        assertEquals(1, ws.closeCodes.size());
    }

    @Test
    public void failureBeforeOpenGoesToHandler() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.failed(new IOException("boom"));
        assertEquals(1, ws.errors.size());
    }

    @Test
    public void failureAfterOpenGoesToHandler() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        h.failed(new IOException("boom"));
        assertEquals(1, ws.errors.size());
        assertNotNull(ws.errors.get(0));
    }

    @Test
    public void malformedFrameReportedAsError() {
        H2WebSocketResponseHandler h = handler(noExtensions());
        h.startResponseBody();
        // reserved opcode 0x3
        h.responseBodyContent(ByteBuffer.wrap(new byte[] {(byte) 0x83, 0}));
        // protocol error: the connection answers with a 1002 close frame
        assertFalse(request.body.isEmpty());
    }
}
