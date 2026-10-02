/*
 * DoHResponseHandlerTest.java
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


package org.bluezoo.gumdrop.http.doh;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.dns.client.DnsClientTransportHandler;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HttpResponse;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.http.client.PushPromise;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the private response handler inside {@link DoHClientTransport}
 * and its argument validation. RFC 8484 section 4.2.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoHResponseHandlerTest {

    private static class Rec implements DnsClientTransportHandler {
        ByteBuffer received;
        int receiveCount;
        Exception error;

        @Override
        public void onReceive(ByteBuffer response) {
            received = response;
            receiveCount++;
        }

        @Override
        public void onError(Exception cause) {
            error = cause;
        }
    }

    private static class Promise implements PushPromise {
        boolean rejected;

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public String getPath() {
            return "/";
        }

        @Override
        public String getAuthority() {
            return "h";
        }

        @Override
        public String getScheme() {
            return "https";
        }

        @Override
        public Headers getHeaders() {
            return null;
        }

        @Override
        public void accept(HttpResponseHandler handler) {
        }

        @Override
        public void reject() {
            rejected = true;
        }
    }

    private HttpResponseHandler make(Rec rec) throws Exception {
        Class<?> c = Class.forName(
                "org.bluezoo.gumdrop.http.doh.DoHClientTransport$DoHResponseHandler");
        Constructor<?> k = c.getDeclaredConstructor(DnsClientTransportHandler.class);
        k.setAccessible(true);
        return (HttpResponseHandler) k.newInstance(rec);
    }

    @Test
    public void testSuccessfulBodyDeliveredOnce() throws Exception {
        Rec rec = new Rec();
        HttpResponseHandler h = make(rec);
        h.ok(new HttpResponse(HttpStatus.OK));
        h.header("content-type", "application/dns-message");
        h.startResponseBody();
        h.responseBodyContent(ByteBuffer.wrap(new byte[] {1, 2}));
        h.responseBodyContent(ByteBuffer.wrap(new byte[] {3}));
        assertEquals(0, rec.receiveCount);
        h.endResponseBody();
        h.close();
        assertEquals(1, rec.receiveCount);
        assertEquals(3, rec.received.remaining());
        assertEquals(1, rec.received.get(0));
        assertEquals(3, rec.received.get(2));
    }

    @Test
    public void testEmptyBodyNotDelivered() throws Exception {
        Rec rec = new Rec();
        HttpResponseHandler h = make(rec);
        h.ok(new HttpResponse(HttpStatus.OK));
        h.endResponseBody();
        assertEquals(0, rec.receiveCount);
    }

    @Test
    public void testBodyIgnoredWithoutOk() throws Exception {
        Rec rec = new Rec();
        HttpResponseHandler h = make(rec);
        h.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
        h.endResponseBody();
        assertEquals(0, rec.receiveCount);
    }

    @Test
    public void testErrorReported() throws Exception {
        Rec rec = new Rec();
        HttpResponseHandler h = make(rec);
        h.error(new HttpResponse(HttpStatus.NOT_FOUND));
        assertTrue(rec.error instanceof IOException);
    }

    @Test
    public void testFailedReported() throws Exception {
        Rec rec = new Rec();
        HttpResponseHandler h = make(rec);
        Exception ex = new IOException("x");
        h.failed(ex);
        assertSame(ex, rec.error);
    }

    @Test
    public void testPushPromiseRejected() throws Exception {
        Rec rec = new Rec();
        HttpResponseHandler h = make(rec);
        Promise p = new Promise();
        h.pushPromise(p);
        assertTrue(p.rejected);
    }

    @Test
    public void testOpenRequiresLoopWithGumdrop() throws Exception {
        DoHClientTransport t = new DoHClientTransport();
        InetAddress a = InetAddress.getLoopbackAddress();
        try {
            t.open(a, 443, null, new Rec());
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testSettersAccepted() {
        DoHClientTransport t = new DoHClientTransport();
        t.setClientCredentials(null);
        t.setTrustManager(null);
        t.close();
    }
}
