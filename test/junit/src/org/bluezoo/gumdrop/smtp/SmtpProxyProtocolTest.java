/*
 * SmtpProxyProtocolTest.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ProxyEndpointProbe;
import org.bluezoo.gumdrop.TcpEndpoint;

import static org.junit.Assert.*;

/**
 * SMTP behind a PROXY protocol listener: the 220 greeting follows the
 * header, the client and server addresses are the ones it asserts, XCLIENT
 * still overrides them when authorised, and the authorisation is judged on
 * the relay, not on the address the header asserts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpProxyProtocolTest {

    private static final InetSocketAddress RELAY = new InetSocketAddress("10.0.0.5", 40000);
    private static final InetSocketAddress LISTENER = new InetSocketAddress("10.0.1.1", 2525);

    private static final byte[] HEADER =
            "PROXY TCP4 192.0.2.7 198.51.100.9 51000 25\r\n".getBytes(StandardCharsets.US_ASCII);

    /** Authorises XCLIENT for the relay only, recording what it was asked about. */
    private static final class RelayOnlyListener extends SmtpListener {
        InetAddress asked;

        @Override
        protected boolean isXclientAuthorized(InetAddress clientAddress) {
            asked = clientAddress;
            return RELAY.getAddress().equals(clientAddress);
        }
    }

    private RelayOnlyListener listener;
    private SmtpProtocolHandler handler;
    private TcpEndpoint endpoint;

    @Before
    public void setUp() throws Exception {
        listener = new RelayOnlyListener();
        handler = new SmtpProtocolHandler(listener, null);
        endpoint = ProxyEndpointProbe.accept(handler, listener, LISTENER, RELAY);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private String sent() {
        return ProxyEndpointProbe.queued(endpoint);
    }

    @Test
    public void nothingIsSentBeforeTheHeader() {
        assertEquals("", sent());
        ProxyEndpointProbe.push(endpoint, ascii("PROXY TCP4 192.0.2.7 "));
        assertEquals("a partial header draws no greeting", "", sent());
        assertNull("the handler is not connected yet", handler.getClientAddress());
    }

    @Test
    public void greetingFollowsTheHeaderAndAddressesComeFromIt() throws Exception {
        ProxyEndpointProbe.push(endpoint, HEADER);
        assertTrue("greeting: " + sent(), sent().startsWith("220"));
        assertEquals(new InetSocketAddress(InetAddress.getByName("192.0.2.7"), 51000),
                handler.getClientAddress());
        assertEquals(new InetSocketAddress(InetAddress.getByName("198.51.100.9"), 25),
                handler.getServerAddress());
    }

    @Test
    public void addressLiteralGreetingUsesTheAssertedDestination() throws Exception {
        ProxyEndpointProbe.push(endpoint, HEADER);
        // the address literal in the greeting must not be the relay-facing one
        assertFalse(sent().contains("10.0.1.1"));
    }

    @Test
    public void xclientAuthorisationIsJudgedOnTheRelay() throws Exception {
        ProxyEndpointProbe.push(endpoint, HEADER);
        ProxyEndpointProbe.push(endpoint, ascii("EHLO c.example.org\r\n"));
        ProxyEndpointProbe.push(endpoint, ascii(
                "XCLIENT ADDR=203.0.113.4 PORT=1234 DESTADDR=203.0.113.5 DESTPORT=587\r\n"));
        assertEquals("authorisation looked at the real peer",
                RELAY.getAddress(), listener.asked);
        assertEquals("and XCLIENT overrides the header's client",
                new InetSocketAddress(InetAddress.getByName("203.0.113.4"), 1234),
                handler.getClientAddress());
        assertEquals(new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 587),
                handler.getServerAddress());
    }

    @Test
    public void aClaimedRelayAddressInTheHeaderDoesNotGrantXclient() throws Exception {
        endpoint.close();
        listener = new RelayOnlyListener();
        handler = new SmtpProtocolHandler(listener, null);
        // the real peer is NOT the relay; the header claims to be it
        endpoint = ProxyEndpointProbe.accept(handler, listener, LISTENER,
                new InetSocketAddress("198.18.0.9", 40001));
        ProxyEndpointProbe.push(endpoint, ascii(
                "PROXY TCP4 10.0.0.5 198.51.100.9 51000 25\r\n"));
        ProxyEndpointProbe.push(endpoint, ascii("EHLO c.example.org\r\n"));
        ProxyEndpointProbe.push(endpoint, ascii("XCLIENT ADDR=203.0.113.4\r\n"));
        assertEquals(InetAddress.getByName("198.18.0.9"), listener.asked);
        assertEquals("override refused",
                InetAddress.getByName("10.0.0.5"), handler.getClientAddress().getAddress());
    }
}
