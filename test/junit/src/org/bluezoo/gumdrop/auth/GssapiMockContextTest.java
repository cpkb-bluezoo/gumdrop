/*
 * GssapiMockContextTest.java
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

package org.bluezoo.gumdrop.auth;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.security.auth.Subject;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the GSSAPI client mechanism and server exchange (RFC 4752)
 * driven by a hand-written mock {@link GSSContext}, so the context
 * establishment and security-layer negotiation logic is exercised without a
 * KDC, keytab or network.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GssapiMockContextTest {

    /** Scripted GSSContext: answers by method name and records calls. */
    private static final class MockContext implements InvocationHandler {
        byte[] initToken;
        byte[] acceptToken;
        byte[] unwrapResult = new byte[] {1, 0, 0, 0};
        byte[] wrapResult = new byte[] {9, 9};
        boolean established;
        Throwable initFailure;
        Throwable acceptFailure;
        Throwable unwrapFailure;
        Throwable wrapFailure;
        Throwable srcNameFailure;
        Throwable disposeFailure;
        String srcName = "client@EXAMPLE.COM";
        byte[] wrapped;
        byte[] unwrapped;
        boolean disposed;
        final List<String> calls = new ArrayList<String>();

        GSSContext proxy() {
            Object o = Proxy.newProxyInstance(MockContext.class.getClassLoader(),
                    new Class<?>[] {GSSContext.class}, this);
            return (GSSContext) o;
        }

        private byte[] slice(Object[] args) {
            byte[] in = (byte[]) args[0];
            int off = ((Integer) args[1]).intValue();
            int len = ((Integer) args[2]).intValue();
            byte[] out = new byte[len];
            System.arraycopy(in, off, out, 0, len);
            return out;
        }

        public Object invoke(Object p, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            calls.add(n);
            if (n.equals("initSecContext")) {
                if (initFailure != null) {
                    throw initFailure;
                }
                return initToken;
            }
            if (n.equals("acceptSecContext")) {
                if (acceptFailure != null) {
                    throw acceptFailure;
                }
                return acceptToken;
            }
            if (n.equals("isEstablished")) {
                return Boolean.valueOf(established);
            }
            if (n.equals("unwrap")) {
                if (unwrapFailure != null) {
                    throw unwrapFailure;
                }
                unwrapped = slice(args);
                return unwrapResult;
            }
            if (n.equals("wrap")) {
                if (wrapFailure != null) {
                    throw wrapFailure;
                }
                wrapped = slice(args);
                return wrapResult;
            }
            if (n.equals("getSrcName")) {
                if (srcNameFailure != null) {
                    throw srcNameFailure;
                }
                return nameProxy(srcName);
            }
            if (n.equals("dispose")) {
                disposed = true;
                if (disposeFailure != null) {
                    throw disposeFailure;
                }
                return null;
            }
            if (n.equals("hashCode")) {
                return Integer.valueOf(System.identityHashCode(p));
            }
            if (n.equals("equals")) {
                return Boolean.valueOf(p == args[0]);
            }
            if (n.equals("toString")) {
                return "mock-context";
            }
            return null;
        }
    }

    private static GSSName nameProxy(final String text) {
        Object o = Proxy.newProxyInstance(GssapiMockContextTest.class.getClassLoader(),
                new Class<?>[] {GSSName.class}, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] args) {
                        if (m.getName().equals("toString")) {
                            return text;
                        }
                        if (m.getName().equals("hashCode")) {
                            return Integer.valueOf(1);
                        }
                        if (m.getName().equals("equals")) {
                            return Boolean.valueOf(p == args[0]);
                        }
                        return null;
                    }
                });
        return (GSSName) o;
    }

    private MockContext mock;
    private Subject subject;

    @Before
    public void setUp() {
        mock = new MockContext();
        subject = new Subject();
    }

    // ===== client mechanism =====

    private GssapiClientMechanism client() {
        return new GssapiClientMechanism(subject, mock.proxy());
    }

    @Test
    public void clientIdentifiesItself() {
        GssapiClientMechanism c = client();
        assertEquals("GSSAPI", c.getMechanismName());
        assertTrue(c.hasInitialResponse());
        assertFalse(c.isComplete());
    }

    @Test
    public void clientReturnsContextTokenWhileNotEstablished() throws Exception {
        mock.initToken = new byte[] {1, 2, 3};
        GssapiClientMechanism c = client();
        byte[] out = c.evaluateChallenge(new byte[] {7});
        assertArrayEquals(new byte[] {1, 2, 3}, out);
        assertFalse(c.isComplete());
    }

    @Test
    public void clientReturnsEmptyTokenWhenContextEstablishesWithoutOutput() throws Exception {
        mock.initToken = null;
        mock.established = true;
        GssapiClientMechanism c = client();
        byte[] out = c.evaluateChallenge(new byte[0]);
        assertEquals(0, out.length);
        assertFalse(c.isComplete());
    }

    @Test
    public void clientNegotiatesNoSecurityLayerAfterEstablishment() throws Exception {
        mock.initToken = null;
        mock.established = true;
        GssapiClientMechanism c = client();
        c.evaluateChallenge(new byte[0]);
        mock.unwrapResult = new byte[] {7, 0, 0, 0, 'x'};
        byte[] wrapped = c.evaluateChallenge(new byte[] {4, 5});
        assertArrayEquals(new byte[] {9, 9}, wrapped);
        assertArrayEquals(new byte[] {4, 5}, mock.unwrapped);
        assertArrayEquals(new byte[] {1, 0, 0, 0}, mock.wrapped);
        assertTrue(c.isComplete());
    }

    @Test
    public void clientRejectsShortSecurityLayerOffer() throws Exception {
        mock.established = true;
        GssapiClientMechanism c = client();
        c.evaluateChallenge(new byte[0]);
        mock.unwrapResult = new byte[] {1, 0};
        try {
            c.evaluateChallenge(new byte[] {1});
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("invalid security layer offer"));
        }
        assertFalse(c.isComplete());
    }

    @Test
    public void clientWrapsContextFailure() throws Exception {
        mock.initFailure = new GSSException(GSSException.FAILURE);
        GssapiClientMechanism c = client();
        try {
            c.evaluateChallenge(new byte[0]);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI token exchange failed", e.getMessage());
            assertSame(mock.initFailure, e.getCause());
        }
    }

    @Test
    public void clientWrapsUnwrapFailure() throws Exception {
        mock.established = true;
        GssapiClientMechanism c = client();
        c.evaluateChallenge(new byte[0]);
        mock.unwrapFailure = new GSSException(GSSException.BAD_MIC);
        try {
            c.evaluateChallenge(new byte[] {1});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI security layer negotiation failed", e.getMessage());
            assertSame(mock.unwrapFailure, e.getCause());
        }
    }

    @Test
    public void clientWrapsWrapFailure() throws Exception {
        mock.established = true;
        GssapiClientMechanism c = client();
        c.evaluateChallenge(new byte[0]);
        mock.wrapFailure = new GSSException(GSSException.FAILURE);
        try {
            c.evaluateChallenge(new byte[] {1});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI security layer negotiation failed", e.getMessage());
        }
        assertFalse(c.isComplete());
    }

    // ===== server exchange =====

    private GssapiServer.GssapiExchange exchange() {
        GssapiServer server = new GssapiServer(subject, null, "imap/mail.example.com");
        assertEquals("imap/mail.example.com", server.getServicePrincipal());
        return server.new GssapiExchange(mock.proxy());
    }

    @Test
    public void serverAcceptsTokenAndReportsEstablishment() throws Exception {
        mock.acceptToken = new byte[] {5, 6};
        GssapiServer.GssapiExchange ex = exchange();
        assertFalse(ex.isContextEstablished());
        byte[] reply = ex.acceptToken(new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {5, 6}, reply);
        mock.established = true;
        assertTrue(ex.isContextEstablished());
    }

    @Test
    public void serverMapsGssFailureToTokenRejected() throws Exception {
        mock.acceptFailure = new GSSException(GSSException.DEFECTIVE_TOKEN);
        GssapiServer.GssapiExchange ex = exchange();
        try {
            ex.acceptToken(new byte[] {1});
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("GSSAPI token rejected: "));
            assertSame(mock.acceptFailure, e.getCause());
        }
    }

    @Test
    public void serverMapsOtherFailureToContextError() throws Exception {
        mock.acceptFailure = new IllegalStateException("odd");
        GssapiServer.GssapiExchange ex = exchange();
        try {
            ex.acceptToken(new byte[] {1});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI context error", e.getMessage());
            assertSame(mock.acceptFailure, e.getCause());
        }
    }

    @Test
    public void serverOffersOnlyNoSecurityLayer() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        byte[] challenge = ex.generateSecurityLayerChallenge();
        assertArrayEquals(new byte[] {9, 9}, challenge);
        assertArrayEquals(new byte[] {1, 0, 0, 0}, mock.wrapped);
    }

    @Test
    public void serverWrapFailureIsReported() throws Exception {
        mock.wrapFailure = new GSSException(GSSException.FAILURE);
        GssapiServer.GssapiExchange ex = exchange();
        try {
            ex.generateSecurityLayerChallenge();
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI wrap failed", e.getMessage());
        }
    }

    @Test
    public void serverRequiresChallengeBeforeResponse() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        try {
            ex.validateSecurityLayerResponse(new byte[] {1, 2, 3, 4});
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not yet sent"));
        }
    }

    @Test
    public void serverReturnsAuthenticatedPrincipal() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        ex.generateSecurityLayerChallenge();
        mock.unwrapResult = new byte[] {1, 0, 0, 0, 'a'};
        String who = ex.validateSecurityLayerResponse(new byte[] {8});
        assertEquals("client@EXAMPLE.COM", who);
        assertArrayEquals(new byte[] {8}, mock.unwrapped);
    }

    @Test
    public void serverRejectsShortResponse() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        ex.generateSecurityLayerChallenge();
        mock.unwrapResult = new byte[] {1, 0, 0};
        try {
            ex.validateSecurityLayerResponse(new byte[] {8});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Invalid GSSAPI security layer response", e.getMessage());
        }
    }

    @Test
    public void serverRejectsUnsupportedSecurityLayer() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        ex.generateSecurityLayerChallenge();
        mock.unwrapResult = new byte[] {2, 0, 0, 0};
        try {
            ex.validateSecurityLayerResponse(new byte[] {8});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Client requested unsupported GSSAPI security layer", e.getMessage());
        }
    }

    @Test
    public void serverReportsUnwrapFailure() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        ex.generateSecurityLayerChallenge();
        mock.unwrapFailure = new GSSException(GSSException.BAD_MIC);
        try {
            ex.validateSecurityLayerResponse(new byte[] {8});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI unwrap failed", e.getMessage());
            assertSame(mock.unwrapFailure, e.getCause());
        }
    }

    @Test
    public void serverReportsSourceNameFailure() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        ex.generateSecurityLayerChallenge();
        mock.srcNameFailure = new GSSException(GSSException.NO_CONTEXT);
        try {
            ex.validateSecurityLayerResponse(new byte[] {8});
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("GSSAPI context error", e.getMessage());
            assertSame(mock.srcNameFailure, e.getCause());
        }
    }

    @Test
    public void disposeReleasesContextAndToleratesFailure() throws Exception {
        GssapiServer.GssapiExchange ex = exchange();
        ex.dispose();
        assertTrue(mock.disposed);
        mock.disposeFailure = new GSSException(GSSException.FAILURE);
        mock.disposed = false;
        ex.dispose();
        assertTrue(mock.disposed);
    }
}
