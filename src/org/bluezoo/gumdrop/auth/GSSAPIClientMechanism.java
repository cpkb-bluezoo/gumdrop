/*
 * GSSAPIClientMechanism.java
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;

/**
 * Client-side GSSAPI SASL mechanism (RFC 4752) for driving Kerberos
 * authentication exchanges with remote servers.
 *
 * <p>Implements the {@link SASLClientMechanism} interface using
 * {@code org.ietf.jgss} directly. The mechanism has two phases:
 * <ol>
 *   <li>Context establishment — exchange of GSS-API tokens via
 *       {@link GSSContext#initSecContext}</li>
 *   <li>Security layer negotiation — RFC 4752 §3.1 4-byte wrapped
 *       message exchange</li>
 * </ol>
 *
 * <p><strong>Blocking warning:</strong> The first call to
 * {@link #evaluateChallenge} may contact the KDC to obtain a service
 * ticket. Callers on the NIO event loop must offload this call to a
 * worker thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SASLUtils#createClient
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4752">RFC 4752: GSSAPI SASL</a>
 */
public final class GSSAPIClientMechanism implements SASLClientMechanism {

    /** RFC 4752 §3.1 — security layer: no security layer */
    private static final byte SECURITY_LAYER_NONE = 0x01;

    private static final Oid KRB5_OID;

    static {
        try {
            KRB5_OID = new Oid("1.2.840.113554.1.2.2");
        } catch (GSSException e) {
            throw new RuntimeException("Kerberos OID unavailable", e);
        }
    }

    private final Subject subject;
    private final GSSContext context;
    private boolean complete;
    private boolean contextEstablished;

    /**
     * Creates a GSSAPI client mechanism.
     *
     * @param servicePrincipal the target service principal name
     *        (e.g. "ldap@ldap.example.com")
     * @param subject the JAAS Subject with Kerberos credentials
     *        (from keytab login or kinit)
     * @throws IOException if the GSS context cannot be initialized
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4752#section-3.2">
     *      RFC 4752 §3.2 — Service Name</a>
     */
    public GSSAPIClientMechanism(String servicePrincipal, Subject subject)
            throws IOException {
        this.subject = subject;
        try {
            this.context = Subject.doAs(subject,
                    new PrivilegedExceptionAction<GSSContext>() {
                        @Override
                        public GSSContext run() throws GSSException {
                            GSSManager manager = GSSManager.getInstance();
                            GSSName targetName = manager.createName(
                                    servicePrincipal,
                                    GSSName.NT_HOSTBASED_SERVICE);
                            GSSContext ctx = manager.createContext(
                                    targetName, KRB5_OID,
                                    (GSSCredential) null,
                                    GSSContext.DEFAULT_LIFETIME);
                            ctx.requestMutualAuth(true);
                            ctx.requestInteg(true);
                            return ctx;
                        }
                    });
        } catch (PrivilegedActionException e) {
            throw new IOException("GSSAPI context init failed",
                    e.getCause());
        }
    }

    @Override
    public String getMechanismName() {
        return "GSSAPI";
    }

    /**
     * RFC 4752 §3.1 — GSSAPI produces an initial token before the
     * server sends a challenge.
     *
     * @return true
     */
    @Override
    public boolean hasInitialResponse() {
        return true;
    }

    /**
     * RFC 4752 §3.1 — evaluates a server challenge and produces the
     * next client response.
     *
     * <p>During context establishment, this wraps
     * {@code GSSContext.initSecContext()}. After the context is
     * established, it handles the security layer negotiation per
     * RFC 4752 §3.1 paragraphs 7-8.
     *
     * <p><strong>Blocking warning:</strong> The first call may contact
     * the KDC for a service ticket via blocking socket I/O. Callers
     * on the NIO event loop must offload to a worker thread.
     *
     * @param challenge the server's challenge (empty for initial)
     * @return the client response token
     * @throws IOException if the challenge is invalid or context fails
     */
    @Override
    public byte[] evaluateChallenge(byte[] challenge) throws IOException {
        if (!contextEstablished) {
            return processContextToken(challenge);
        }
        return processSecurityLayerChallenge(challenge);
    }

    /**
     * RFC 4752 §3.1 — context establishment phase.
     * Wraps {@code initSecContext()} within {@code Subject.doAs()}.
     */
    private byte[] processContextToken(byte[] challenge) throws IOException {
        try {
            byte[] token = Subject.doAs(subject,
                    new PrivilegedExceptionAction<byte[]>() {
                        @Override
                        public byte[] run() throws GSSException {
                            return context.initSecContext(
                                    challenge, 0, challenge.length);
                        }
                    });
            if (context.isEstablished()) {
                contextEstablished = true;
            }
            if (token == null) {
                return new byte[0];
            }
            return token;
        } catch (PrivilegedActionException e) {
            Exception cause = e.getException();
            throw new IOException("GSSAPI token exchange failed", cause);
        }
    }

    /**
     * RFC 4752 §3.1 para 7-8 — security layer negotiation.
     *
     * <p>Unwraps the server's 4-byte security layer offer, then wraps
     * a response choosing "no security layer" (0x01) with max buffer
     * size 0 and no authorization identity.
     */
    private byte[] processSecurityLayerChallenge(byte[] challenge)
            throws IOException {
        try {
            MessageProp prop = new MessageProp(0, false);
            byte[] serverOffer = Subject.doAs(subject,
                    new PrivilegedExceptionAction<byte[]>() {
                        @Override
                        public byte[] run() throws GSSException {
                            return context.unwrap(challenge, 0,
                                    challenge.length, prop);
                        }
                    });
            if (serverOffer.length < 4) {
                throw new IOException(
                        "GSSAPI: invalid security layer offer");
            }

            byte[] response = new byte[4];
            response[0] = SECURITY_LAYER_NONE;
            response[1] = 0;
            response[2] = 0;
            response[3] = 0;

            byte[] wrapped = Subject.doAs(subject,
                    new PrivilegedExceptionAction<byte[]>() {
                        @Override
                        public byte[] run() throws GSSException {
                            return context.wrap(response, 0, response.length,
                                    new MessageProp(0, false));
                        }
                    });
            complete = true;
            return wrapped;
        } catch (PrivilegedActionException e) {
            throw new IOException(
                    "GSSAPI security layer negotiation failed",
                    e.getCause());
        }
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

}
