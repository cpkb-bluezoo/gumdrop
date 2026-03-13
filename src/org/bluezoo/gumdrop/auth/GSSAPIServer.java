/*
 * GSSAPIServer.java
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
import java.nio.file.Path;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * Server-side GSSAPI context manager for SASL GSSAPI authentication
 * (RFC 4752).
 *
 * <p>Created at service startup and shared by all connections on a listener.
 * Loads the service keytab via JAAS {@link LoginContext} and provides
 * per-connection {@link GSSAPIExchange} objects that handle the GSS-API
 * token exchange and RFC 4752 security layer negotiation.
 *
 * <p>{@code acceptSecContext()} is CPU-bound (AES decryption of the service
 * ticket using the local keytab) and does not perform KDC network I/O,
 * making it safe to call from the NIO event loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4752">RFC 4752: GSSAPI SASL</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422: SASL Framework</a>
 */
public final class GSSAPIServer {

    private static final Logger logger =
            Logger.getLogger(GSSAPIServer.class.getName());

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");

    /** RFC 4752 §3.1 — security layer bitmask: no security layer */
    private static final byte SECURITY_LAYER_NONE = 0x01;

    private static final Oid KRB5_OID;

    static {
        try {
            KRB5_OID = new Oid("1.2.840.113554.1.2.2");
        } catch (GSSException e) {
            throw new RuntimeException("Kerberos OID unavailable", e);
        }
    }

    private final Subject serviceSubject;
    private final GSSCredential serverCredential;
    private final String servicePrincipal;

    /**
     * Creates a GSSAPIServer by loading the service keytab and acquiring
     * the server credential via JAAS.
     *
     * <p>This constructor performs local file I/O (keytab read) and should
     * be called during service startup, not on the NIO event loop.
     *
     * @param keytabPath the path to the Kerberos keytab file
     * @param servicePrincipal the service principal name
     *        (e.g. "imap/mail.example.com@EXAMPLE.COM")
     * @throws IOException if the keytab cannot be read or credentials
     *         cannot be acquired
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4752#section-3.2">
     *      RFC 4752 §3.2 — Service Name</a>
     */
    public GSSAPIServer(Path keytabPath, String servicePrincipal)
            throws IOException {
        this.servicePrincipal = servicePrincipal;
        try {
            Map<String, String> options = new HashMap<>();
            options.put("useKeyTab", "true");
            options.put("keyTab", keytabPath.toAbsolutePath().toString());
            options.put("principal", servicePrincipal);
            options.put("storeKey", "true");
            options.put("isInitiator", "false");

            KeytabLoginConfiguration config =
                    new KeytabLoginConfiguration(options);
            LoginContext loginContext =
                    new LoginContext("gumdrop", null, null, config);
            loginContext.login();
            this.serviceSubject = loginContext.getSubject();

            this.serverCredential = Subject.doAs(serviceSubject,
                    new PrivilegedExceptionAction<GSSCredential>() {
                        @Override
                        public GSSCredential run() throws GSSException {
                            GSSManager manager = GSSManager.getInstance();
                            GSSName serverName = manager.createName(
                                    servicePrincipal,
                                    GSSName.NT_HOSTBASED_SERVICE);
                            return manager.createCredential(serverName,
                                    GSSCredential.DEFAULT_LIFETIME,
                                    KRB5_OID,
                                    GSSCredential.ACCEPT_ONLY);
                        }
                    });
        } catch (LoginException e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.gssapi_keytab_load_failed"),
                    keytabPath);
            throw new IOException(msg, e);
        } catch (PrivilegedActionException e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.gssapi_credential_failed"),
                    servicePrincipal);
            throw new IOException(msg, e.getCause());
        }
    }

    /**
     * Returns the service principal name.
     *
     * @return the service principal name
     */
    public String getServicePrincipal() {
        return servicePrincipal;
    }

    /**
     * Creates a new per-connection GSSAPI exchange.
     *
     * <p>Each client authentication attempt requires its own exchange
     * instance. The exchange maintains a {@link GSSContext} that tracks
     * the state of the token exchange.
     *
     * @return a new exchange instance
     * @throws IOException if the GSS context cannot be created
     */
    public GSSAPIExchange createExchange() throws IOException {
        try {
            GSSContext context = Subject.doAs(serviceSubject,
                    new PrivilegedExceptionAction<GSSContext>() {
                        @Override
                        public GSSContext run() throws GSSException {
                            GSSManager manager = GSSManager.getInstance();
                            return manager.createContext(serverCredential);
                        }
                    });
            context.requestMutualAuth(true);
            return new GSSAPIExchange(context);
        } catch (PrivilegedActionException e) {
            String msg = L10N.getString("err.gssapi_context_create_failed");
            throw new IOException(msg, e.getCause());
        } catch (org.ietf.jgss.GSSException e) {
            String msg = L10N.getString("err.gssapi_context_create_failed");
            throw new IOException(msg, e);
        }
    }

    /**
     * Per-connection GSSAPI exchange that handles the token exchange
     * and RFC 4752 security layer negotiation.
     *
     * <p>All methods are CPU-bound and safe for the NIO event loop.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4752#section-3.1">
     *      RFC 4752 §3.1 — GSSAPI Exchange</a>
     */
    public final class GSSAPIExchange {

        private final GSSContext context;
        private boolean securityLayerSent;

        GSSAPIExchange(GSSContext context) {
            this.context = context;
        }

        /**
         * RFC 4752 §3.1 — processes a client GSS-API token and returns
         * the server response token.
         *
         * <p>This wraps {@code GSSContext.acceptSecContext()} within
         * {@code Subject.doAs()} for proper credential access.
         * The operation is CPU-bound (keytab decryption) with no KDC
         * network I/O.
         *
         * @param clientToken the client's GSS-API token
         * @return the server response token, or null if no token to send
         * @throws IOException if the token is invalid or context fails
         */
        public byte[] acceptToken(byte[] clientToken) throws IOException {
            try {
                byte[] responseToken = Subject.doAs(serviceSubject,
                        new PrivilegedExceptionAction<byte[]>() {
                            @Override
                            public byte[] run() throws GSSException {
                                return context.acceptSecContext(
                                        clientToken, 0, clientToken.length);
                            }
                        });
                return responseToken;
            } catch (PrivilegedActionException e) {
                Exception cause = e.getException();
                if (cause instanceof GSSException) {
                    String msg = MessageFormat.format(
                            L10N.getString("err.gssapi_token_rejected"),
                            cause.getMessage());
                    throw new IOException(msg, cause);
                }
                throw new IOException(
                        L10N.getString("err.gssapi_context_failed"), cause);
            }
        }

        /**
         * Returns whether the GSS-API context has been established.
         *
         * @return true if context establishment is complete
         */
        public boolean isContextEstablished() {
            return context.isEstablished();
        }

        /**
         * RFC 4752 §3.1 — generates the security layer challenge.
         *
         * <p>After context establishment, the server wraps a 4-byte
         * message offering the available security layers. Gumdrop only
         * offers "no security layer" (0x01) since TLS handles security.
         * The maximum buffer size is set to 0 (no security layer means
         * no buffer size constraint).
         *
         * @return the wrapped security layer challenge
         * @throws IOException if wrapping fails
         * @see <a href="https://www.rfc-editor.org/rfc/rfc4752#section-3.1">
         *      RFC 4752 §3.1 para 6</a>
         */
        public byte[] generateSecurityLayerChallenge() throws IOException {
            byte[] offer = new byte[4];
            offer[0] = SECURITY_LAYER_NONE;
            offer[1] = 0;
            offer[2] = 0;
            offer[3] = 0;
            try {
                byte[] wrapped = Subject.doAs(serviceSubject,
                        new PrivilegedExceptionAction<byte[]>() {
                            @Override
                            public byte[] run() throws GSSException {
                                return context.wrap(offer, 0, offer.length,
                                        new org.ietf.jgss.MessageProp(0,
                                                false));
                            }
                        });
                securityLayerSent = true;
                return wrapped;
            } catch (PrivilegedActionException e) {
                throw new IOException(
                        L10N.getString("err.gssapi_wrap_failed"),
                        e.getCause());
            }
        }

        /**
         * RFC 4752 §3.1 — validates the client's security layer response
         * and extracts the authorization identity.
         *
         * <p>The client's response is a wrapped 4-byte message: the first
         * byte is the chosen security layer (must be 0x01 for no layer),
         * bytes 2-4 are the client's max buffer size, followed by the
         * optional authorization identity (authzid).
         *
         * @param wrapped the client's wrapped security layer response
         * @return the authenticated principal name from the GSS context
         * @throws IOException if the response is invalid or the client
         *         chose an unsupported security layer
         * @see <a href="https://www.rfc-editor.org/rfc/rfc4752#section-3.1">
         *      RFC 4752 §3.1 para 7-8</a>
         */
        public String validateSecurityLayerResponse(byte[] wrapped)
                throws IOException {
            if (!securityLayerSent) {
                throw new IllegalStateException(
                        "Security layer challenge not yet sent");
            }
            try {
                org.ietf.jgss.MessageProp prop =
                        new org.ietf.jgss.MessageProp(0, false);
                byte[] unwrapped = Subject.doAs(serviceSubject,
                        new PrivilegedExceptionAction<byte[]>() {
                            @Override
                            public byte[] run() throws GSSException {
                                return context.unwrap(wrapped, 0,
                                        wrapped.length, prop);
                            }
                        });
                if (unwrapped.length < 4) {
                    throw new IOException(
                            L10N.getString("err.gssapi_invalid_layer"));
                }
                byte chosenLayer = unwrapped[0];
                if (chosenLayer != SECURITY_LAYER_NONE) {
                    throw new IOException(
                            L10N.getString("err.gssapi_unsupported_layer"));
                }

                GSSName srcName = context.getSrcName();
                return srcName.toString();
            } catch (PrivilegedActionException e) {
                throw new IOException(
                        L10N.getString("err.gssapi_unwrap_failed"),
                        e.getCause());
            } catch (GSSException e) {
                throw new IOException(
                        L10N.getString("err.gssapi_context_failed"), e);
            }
        }

        /**
         * Disposes the GSS context and releases resources.
         */
        public void dispose() {
            try {
                context.dispose();
            } catch (GSSException e) {
                logger.log(Level.FINE, "GSSContext dispose error", e);
            }
        }
    }

    /**
     * JAAS configuration for keytab-based Kerberos login.
     * Provides the Krb5LoginModule configuration programmatically
     * rather than requiring an external JAAS config file.
     */
    private static final class KeytabLoginConfiguration
            extends javax.security.auth.login.Configuration {

        private final javax.security.auth.login.AppConfigurationEntry[] entries;

        KeytabLoginConfiguration(Map<String, String> options) {
            javax.security.auth.login.AppConfigurationEntry entry =
                    new javax.security.auth.login.AppConfigurationEntry(
                            "com.sun.security.auth.module.Krb5LoginModule",
                            javax.security.auth.login.AppConfigurationEntry
                                    .LoginModuleControlFlag.REQUIRED,
                            options);
            this.entries =
                    new javax.security.auth.login.AppConfigurationEntry[]
                            { entry };
        }

        @Override
        public javax.security.auth.login.AppConfigurationEntry[]
                getAppConfigurationEntry(String name) {
            return entries;
        }
    }

}
