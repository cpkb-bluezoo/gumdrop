/*
 * LDAPClientProtocolHandler.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.auth.GSSAPIClientMechanism;
import org.bluezoo.gumdrop.auth.SASLClientMechanism;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Element;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Exception;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Type;
import org.bluezoo.gumdrop.ldap.asn1.BERDecoder;
import org.bluezoo.gumdrop.ldap.asn1.BEREncoder;

/**
 * LDAPv3 client protocol handler (RFC 4511).
 *
 * <p>Implements the LDAP client interfaces ({@link LDAPConnected},
 * {@link LDAPPostTLS}, {@link LDAPSession}) and delegates all transport
 * operations to a transport-agnostic {@link Endpoint}.
 *
 * <p>Messages are encoded/decoded using BER (ITU-T X.690) via the
 * {@link BEREncoder} and {@link BERDecoder} classes.
 *
 * <p>The handler implements stateful interfaces that guide the caller through
 * the LDAP protocol:
 * <ul>
 * <li>{@link LDAPConnected} — initial state, allows bind and STARTTLS</li>
 * <li>{@link LDAPPostTLS} — after STARTTLS, must bind</li>
 * <li>{@link LDAPSession} — after bind, full operations available</li>
 * </ul>
 *
 * <p>Supported operations (RFC 4511):
 * <ul>
 *   <li>BindRequest (simple and SASL auth) — section 4.2, RFC 4513 §5.1–5.2</li>
 *   <li>UnbindRequest — section 4.3</li>
 *   <li>Unsolicited Notification — section 4.4 (Notice of Disconnection)</li>
 *   <li>SearchRequest — section 4.5</li>
 *   <li>ModifyRequest — section 4.6</li>
 *   <li>AddRequest — section 4.7</li>
 *   <li>DelRequest — section 4.8</li>
 *   <li>ModifyDNRequest — section 4.9</li>
 *   <li>CompareRequest — section 4.10</li>
 *   <li>AbandonRequest — section 4.11</li>
 *   <li>ExtendedRequest — section 4.12</li>
 *   <li>IntermediateResponse — section 4.13</li>
 *   <li>STARTTLS — section 4.14 / RFC 4513 section 3</li>
 *   <li>Controls — section 4.1.11 (request and response)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511 — LDAPv3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4513">RFC 4513 — LDAP Authentication</a>
 */
public class LDAPClientProtocolHandler
        implements ProtocolHandler, LDAPConnected, LDAPPostTLS, LDAPSession {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.ldap.client.L10N");
    private static final Logger logger =
            Logger.getLogger(LDAPClientProtocolHandler.class.getName());

    // Message ID generator
    private final AtomicInteger nextMessageId = new AtomicInteger(1);

    // Protocol state
    private final LDAPConnectionReady handler;
    private final boolean secure;
    private final BERDecoder decoder;
    private Endpoint endpoint;
    private boolean closed = false;
    private boolean tlsEstablished = false;

    // Pending operation callbacks (keyed by message ID)
    private final Map<Integer, Object> pendingCallbacks = new HashMap<Integer, Object>();

    // Pending STARTTLS state
    private int startTLSMessageId = -1;
    private StartTLSResultHandler startTLSCallback;

    // Active SASL negotiation (RFC 4513 §5.2)
    private SASLClientMechanism activeSaslClient;
    // RFC 4752 — worker executor for offloading blocking GSSAPI calls
    private ExecutorService gssapiExecutor;

    // RFC 4511 section 4.1.11 — per-request controls (consumed after use)
    private List<Control> requestControls;
    // Last parsed response controls
    private List<Control> lastResponseControls;

    /**
     * Creates an LDAP client endpoint handler.
     *
     * @param handler the client handler for callbacks
     * @param secure whether this is an initially secure connection (LDAPS)
     */
    public LDAPClientProtocolHandler(LDAPConnectionReady handler, boolean secure) {
        this.handler = handler;
        this.secure = secure;
        this.decoder = new BERDecoder();
        this.tlsEstablished = secure;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        logger.fine("LDAP connection established");
        handler.onConnected(ep);
        handler.handleReady(this);
    }

    // RFC 4511 section 5.1 — BER-decoded messages from the wire
    @Override
    public void receive(ByteBuffer buf) {
        try {
            decoder.receive(buf);

            ASN1Element message;
            while ((message = decoder.next()) != null) {
                processMessage(message);
            }
        } catch (ASN1Exception e) {
            logger.log(Level.WARNING, "LDAP protocol error", e);
            handler.onError(e);
            close();
        }
    }

    @Override
    public void disconnected() {
        closed = true;
        handler.onDisconnected();
    }

    // RFC 4513 section 3 — TLS establishment (LDAPS or post-STARTTLS)
    @Override
    public void securityEstablished(SecurityInfo info) {
        logger.fine("TLS handshake complete: " + info.getCipherSuite());
        tlsEstablished = true;
        handler.onSecurityEstablished(info);

        // If we have a pending STARTTLS callback, invoke it
        if (startTLSCallback != null) {
            StartTLSResultHandler callback = startTLSCallback;
            startTLSCallback = null;
            callback.handleTLSEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        handler.onError(cause);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection state
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns whether the connection is open.
     *
     * @return true if connected
     */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        logger.fine("Closing LDAP connection");
        clearSaslClient();
        if (endpoint != null) {
            endpoint.close();
        }
        decoder.reset();
        pendingCallbacks.clear();
    }

    private void send(ByteBuffer buf) {
        if (endpoint != null && endpoint.isOpen()) {
            endpoint.send(buf);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LDAPConnected implementation (RFC 4511 section 4.2–4.3, 4.14)
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 4511 section 4.2 — BindRequest (simple authentication)
    // RFC 4513 section 5.1 — simple bind with DN and password
    @Override
    public void bind(String dn, String password, BindResultHandler callback) {
        if (dn == null) {
            dn = "";
        }
        if (password == null) {
            password = "";
        }

        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(0, true);  // BindRequest
        encoder.writeInteger(LDAPConstants.LDAP_VERSION_3);
        encoder.writeOctetString(dn);
        encoder.writeContext(0, password.getBytes(StandardCharsets.UTF_8));  // Simple auth
        encoder.endApplication();
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent BindRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    // RFC 4511 section 4.2.1 — anonymous bind (empty DN and password)
    @Override
    public void bindAnonymous(BindResultHandler callback) {
        bind("", "", callback);
    }

    // RFC 4511 section 4.2 + RFC 4513 section 5.2 — SASL bind.
    // The SASLClientMechanism drives the multi-step challenge-response
    // exchange; intermediate SASL_BIND_IN_PROGRESS responses are handled
    // internally.
    @Override
    public void bindSASL(SASLClientMechanism saslClient, BindResultHandler callback) {
        bindSASL(saslClient, callback, null);
    }

    /**
     * RFC 4511 §4.2 + RFC 4752 — SASL bind with optional worker executor.
     *
     * <p>For GSSAPI, the first {@code evaluateChallenge()} call may block
     * to contact the KDC. The provided {@code executor} is used to offload
     * these potentially blocking calls; results are dispatched back to the
     * NIO event loop via {@link Endpoint#execute(Runnable)}.
     *
     * <p>For non-GSSAPI mechanisms, the executor is ignored and the bind
     * proceeds synchronously on the event loop as before.
     *
     * @param saslClient the pre-created SASL client mechanism
     * @param callback receives the bind result
     * @param executor worker executor for blocking calls (required for
     *        GSSAPI, may be null for other mechanisms)
     */
    public void bindSASL(SASLClientMechanism saslClient,
                         BindResultHandler callback,
                         ExecutorService executor) {
        activeSaslClient = saslClient;
        if (saslClient instanceof GSSAPIClientMechanism && executor != null) {
            this.gssapiExecutor = executor;
            evaluateChallengeAsync(new byte[0], callback);
        } else {
            try {
                byte[] initialResponse = null;
                if (saslClient.hasInitialResponse()) {
                    initialResponse = saslClient.evaluateChallenge(new byte[0]);
                }
                sendSASLBindRequest(saslClient.getMechanismName(),
                        initialResponse, callback);
            } catch (IOException e) {
                clearSaslClient();
                callback.handleBindFailure(
                        new LDAPResult(LDAPResultCode.OTHER, "",
                                e.getMessage(), null),
                        this);
            }
        }
    }

    // RFC 4752 — offloads evaluateChallenge() to the worker executor
    // and dispatches the result back to the NIO event loop.
    private void evaluateChallengeAsync(byte[] challenge,
                                        BindResultHandler callback) {
        SASLClientMechanism client = activeSaslClient;
        gssapiExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] response = client.evaluateChallenge(challenge);
                    endpoint.execute(new Runnable() {
                        @Override
                        public void run() {
                            sendSASLBindRequest(
                                    client.getMechanismName(),
                                    response, callback);
                        }
                    });
                } catch (IOException e) {
                    endpoint.execute(new Runnable() {
                        @Override
                        public void run() {
                            clearSaslClient();
                            callback.handleBindFailure(
                                    new LDAPResult(LDAPResultCode.OTHER, "",
                                            e.getMessage(), null),
                                    LDAPClientProtocolHandler.this);
                        }
                    });
                }
            }
        });
    }

    private void sendSASLBindRequest(String mechanism, byte[] credentials,
                                     BindResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(0, true);  // BindRequest
        encoder.writeInteger(LDAPConstants.LDAP_VERSION_3);
        encoder.writeOctetString("");       // name (empty for SASL)
        encoder.beginContext(3, true);      // SaslCredentials [3]
        encoder.writeOctetString(mechanism);
        if (credentials != null) {
            encoder.writeOctetString(credentials);
        }
        encoder.endContext();
        encoder.endApplication();
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent SASL BindRequest (messageId=" + messageId
                + ", mechanism=" + mechanism + ")");
    }

    // RFC 4511 section 4.14 — STARTTLS via ExtendedRequest
    // RFC 4513 section 3 — TLS establishment
    @Override
    public void startTLS(StartTLSResultHandler callback) {
        this.startTLSMessageId = nextMessageId.getAndIncrement();
        this.startTLSCallback = callback;

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(startTLSMessageId);
        encoder.beginApplication(23, true);  // ExtendedRequest
        encoder.writeContext(0, LDAPConstants.OID_STARTTLS.getBytes(StandardCharsets.UTF_8));
        encoder.endApplication();
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent STARTTLS ExtendedRequest");
    }

    // RFC 4511 section 4.3 — UnbindRequest (no response expected)
    @Override
    public void unbind() {
        int messageId = nextMessageId.getAndIncrement();

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.writeContext(2, new byte[0]);  // UnbindRequest (application 2, primitive)
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent UnbindRequest");
        close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LDAPSession implementation (RFC 4511 sections 4.5–4.12)
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 4511 section 4.11 — AbandonRequest
    // AbandonRequest ::= [APPLICATION 16] MessageID  (primitive)
    // No response is returned by the server.
    @Override
    public void abandon(int targetMessageId) {
        pendingCallbacks.remove(targetMessageId);

        int messageId = nextMessageId.getAndIncrement();
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.writeApplication(16, encodeIntegerValue(targetMessageId));
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent AbandonRequest (messageId=" + messageId
                + ", target=" + targetMessageId + ")");
    }

    private byte[] encodeIntegerValue(int value) {
        if (value < 0x80) {
            return new byte[]{(byte) value};
        } else if (value < 0x8000) {
            return new byte[]{(byte) (value >> 8), (byte) value};
        } else if (value < 0x800000) {
            return new byte[]{(byte) (value >> 16), (byte) (value >> 8), (byte) value};
        } else {
            return new byte[]{(byte) (value >> 24), (byte) (value >> 16),
                    (byte) (value >> 8), (byte) value};
        }
    }

    // RFC 4511 section 4.5 — SearchRequest
    @Override
    public void search(SearchRequest request, SearchResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(3, true);  // SearchRequest

        encoder.writeOctetString(request.getBaseDN());
        encoder.writeEnumerated(request.getScope().getValue());
        encoder.writeEnumerated(request.getDerefAliases().getValue());
        encoder.writeInteger(request.getSizeLimit());
        encoder.writeInteger(request.getTimeLimit());
        encoder.writeBoolean(request.isTypesOnly());

        // Encode filter
        encodeFilter(encoder, request.getFilter());

        // Encode attribute list
        encoder.beginSequence();
        for (String attr : request.getAttributes()) {
            encoder.writeOctetString(attr);
        }
        encoder.endSequence();

        encoder.endApplication();
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent SearchRequest (messageId=" + messageId + ")");
    }

    // RFC 4511 section 4.6 — ModifyRequest
    @Override
    public void modify(String dn, List<Modification> modifications, ModifyResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(6, true);  // ModifyRequest
        encoder.writeOctetString(dn);

        // Encode modifications sequence
        encoder.beginSequence();
        for (Modification mod : modifications) {
            encoder.beginSequence();
            encoder.writeEnumerated(mod.getOperation().getValue());
            encoder.beginSequence();
            encoder.writeOctetString(mod.getAttributeName());
            encoder.beginSet();
            for (byte[] value : mod.getValues()) {
                encoder.writeOctetString(value);
            }
            encoder.endSet();
            encoder.endSequence();
            encoder.endSequence();
        }
        encoder.endSequence();

        encoder.endApplication();
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent ModifyRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    // RFC 4511 section 4.7 — AddRequest
    @Override
    public void add(String dn, Map<String, List<byte[]>> attributes, AddResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(8, true);  // AddRequest
        encoder.writeOctetString(dn);

        // Encode attributes sequence
        encoder.beginSequence();
        for (Map.Entry<String, List<byte[]>> entry : attributes.entrySet()) {
            encoder.beginSequence();
            encoder.writeOctetString(entry.getKey());
            encoder.beginSet();
            for (byte[] value : entry.getValue()) {
                encoder.writeOctetString(value);
            }
            encoder.endSet();
            encoder.endSequence();
        }
        encoder.endSequence();

        encoder.endApplication();
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent AddRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    // RFC 4511 section 4.8 — DelRequest
    @Override
    public void delete(String dn, DeleteResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.writeApplication(10, dn.getBytes(StandardCharsets.UTF_8));  // DelRequest
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent DeleteRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    // RFC 4511 section 4.10 — CompareRequest
    @Override
    public void compare(String dn, String attribute, byte[] value, CompareResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(14, true);  // CompareRequest
        encoder.writeOctetString(dn);
        encoder.beginSequence();  // AttributeValueAssertion
        encoder.writeOctetString(attribute);
        encoder.writeOctetString(value);
        encoder.endSequence();
        encoder.endApplication();
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent CompareRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    // RFC 4511 section 4.9 — ModifyDNRequest (without newSuperior)
    @Override
    public void modifyDN(String dn, String newRDN, boolean deleteOldRDN,
                         ModifyDNResultHandler callback) {
        modifyDN(dn, newRDN, deleteOldRDN, null, callback);
    }

    // RFC 4511 section 4.9 — ModifyDNRequest (with optional newSuperior)
    @Override
    public void modifyDN(String dn, String newRDN, boolean deleteOldRDN, String newSuperior,
                         ModifyDNResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(12, true);  // ModifyDNRequest
        encoder.writeOctetString(dn);
        encoder.writeOctetString(newRDN);
        encoder.writeBoolean(deleteOldRDN);
        if (newSuperior != null) {
            encoder.writeContext(0, newSuperior.getBytes(StandardCharsets.UTF_8));
        }
        encoder.endApplication();
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent ModifyDNRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    // RFC 4511 section 4.12 — ExtendedRequest
    @Override
    public void extended(String oid, byte[] value, ExtendedResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(23, true);  // ExtendedRequest
        encoder.writeContext(0, oid.getBytes(StandardCharsets.UTF_8));
        if (value != null) {
            encoder.writeContext(1, value);
        }
        encoder.endApplication();
        encodeRequestControls(encoder);
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent ExtendedRequest (messageId=" + messageId + ", oid=" + oid + ")");
    }

    // RFC 4511 section 4.2 — rebind delegates to bind()
    @Override
    public void rebind(String dn, String password, BindResultHandler callback) {
        bind(dn, password, callback);
    }

    @Override
    public void rebindSASL(SASLClientMechanism saslClient, BindResultHandler callback) {
        bindSASL(saslClient, callback);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter encoding (RFC 4515 — String Representation of Search Filters)
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 4515 — search filter string-to-BER encoding.
    // Supports: AND, OR, NOT, presence, equality, substring,
    // greater-or-equal, less-or-equal, approximate match (RFC 4515 §4 / context tag 8),
    // and extensible match (RFC 4515 §4 / context tag 9).
    private void encodeFilter(BEREncoder encoder, String filter) {
        filter = filter.trim();

        if (filter.startsWith("(") && filter.endsWith(")")) {
            filter = filter.substring(1, filter.length() - 1);
        }

        if (filter.startsWith("&")) {
            // AND filter
            encoder.beginContext(0, true);
            encodeFilterList(encoder, filter.substring(1));
            encoder.endContext();
        } else if (filter.startsWith("|")) {
            // OR filter
            encoder.beginContext(1, true);
            encodeFilterList(encoder, filter.substring(1));
            encoder.endContext();
        } else if (filter.startsWith("!")) {
            // NOT filter
            encoder.beginContext(2, true);
            encodeFilter(encoder, filter.substring(1).trim());
            encoder.endContext();
        } else if (filter.contains("=*") && filter.endsWith("=*") && !filter.contains("*=")) {
            // Presence filter: (attr=*)
            String attr = filter.substring(0, filter.length() - 2);
            encoder.writeContext(7, attr.getBytes(StandardCharsets.UTF_8));
        } else if (filter.contains("~=")) {
            // RFC 4515 section 4 — approximate match (context tag 8)
            int idx = filter.indexOf("~=");
            encoder.beginContext(8, true);
            encoder.writeOctetString(filter.substring(0, idx));
            encoder.writeOctetString(filter.substring(idx + 2));
            encoder.endContext();
        } else if (filter.contains(":=")) {
            // RFC 4515 section 4 — extensible match (context tag 9)
            encodeExtensibleMatchFilter(encoder, filter);
        } else if (filter.contains(">=")) {
            // Greater or equal
            int idx = filter.indexOf(">=");
            encoder.beginContext(5, true);
            encoder.writeOctetString(filter.substring(0, idx));
            encoder.writeOctetString(filter.substring(idx + 2));
            encoder.endContext();
        } else if (filter.contains("<=")) {
            // Less or equal
            int idx = filter.indexOf("<=");
            encoder.beginContext(6, true);
            encoder.writeOctetString(filter.substring(0, idx));
            encoder.writeOctetString(filter.substring(idx + 2));
            encoder.endContext();
        } else if (filter.contains("=") && filter.contains("*")) {
            // Substring filter
            int idx = filter.indexOf("=");
            String attr = filter.substring(0, idx);
            String value = filter.substring(idx + 1);
            encodeSubstringFilter(encoder, attr, value);
        } else if (filter.contains("=")) {
            // Equality filter
            int idx = filter.indexOf("=");
            encoder.beginContext(3, true);
            encoder.writeOctetString(filter.substring(0, idx));
            encoder.writeOctetString(filter.substring(idx + 1));
            encoder.endContext();
        } else {
            // Default: treat as presence of objectClass
            encoder.writeContext(7, "objectClass".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void encodeFilterList(BEREncoder encoder, String filterList) {
        // Parse multiple filters like (filter1)(filter2)
        int depth = 0;
        int start = 0;

        for (int i = 0; i < filterList.length(); i++) {
            char c = filterList.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    encodeFilter(encoder, filterList.substring(start, i + 1));
                }
            }
        }
    }

    private void encodeSubstringFilter(BEREncoder encoder, String attr, String value) {
        encoder.beginContext(4, true);
        encoder.writeOctetString(attr);
        encoder.beginSequence();

        // Count the number of parts to determine positions
        int partCount = 1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '*') {
                partCount++;
            }
        }

        int start = 0;
        int length = value.length();
        int partIndex = 0;

        while (start <= length) {
            int end = value.indexOf('*', start);
            if (end < 0) {
                end = length;
            }
            String part = value.substring(start, end);

            if (!part.isEmpty()) {
                if (partIndex == 0) {
                    // Initial
                    encoder.writeContext(0, part.getBytes(StandardCharsets.UTF_8));
                } else if (partIndex == partCount - 1) {
                    // Final
                    encoder.writeContext(2, part.getBytes(StandardCharsets.UTF_8));
                } else {
                    // Any
                    encoder.writeContext(1, part.getBytes(StandardCharsets.UTF_8));
                }
            }

            partIndex++;
            start = end + 1;
        }

        encoder.endSequence();
        encoder.endContext();
    }

    // RFC 4515 section 4 / RFC 4511 section 4.5.1.7 — extensible match filter.
    // Syntax:  [attr][:dn][:matchingRule]:=value
    // BER:     MatchingRuleAssertion (context tag 9, constructed)
    //          matchingRule [1], type [2], matchValue [3], dnAttributes [4]
    private void encodeExtensibleMatchFilter(BEREncoder encoder, String filter) {
        int extIdx = filter.indexOf(":=");
        String lhs = filter.substring(0, extIdx);
        String matchValue = filter.substring(extIdx + 2);

        String attr = null;
        String matchingRule = null;
        boolean dnAttributes = false;

        // Parse LHS: attr:dn:matchingRule, attr:matchingRule, :dn:matchingRule, etc.
        String[] parts = lhs.split(":");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (i == 0 && !part.isEmpty()) {
                attr = part;
            } else if ("dn".equalsIgnoreCase(part)) {
                dnAttributes = true;
            } else if (!part.isEmpty()) {
                matchingRule = part;
            }
        }

        encoder.beginContext(9, true);
        if (matchingRule != null) {
            encoder.writeContext(1, matchingRule.getBytes(StandardCharsets.UTF_8));
        }
        if (attr != null) {
            encoder.writeContext(2, attr.getBytes(StandardCharsets.UTF_8));
        }
        encoder.writeContext(3, matchValue.getBytes(StandardCharsets.UTF_8));
        if (dnAttributes) {
            encoder.writeContext(4, new byte[]{(byte) 0xFF});
        }
        encoder.endContext();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message processing (RFC 4511 section 4.2 — LDAPMessage envelope)
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 4511 section 4.2 — decode LDAPMessage envelope (messageID + protocolOp)
    // and dispatch to operation-specific response handlers.
    private void processMessage(ASN1Element message) throws ASN1Exception {
        if (message.getTag() != ASN1Type.SEQUENCE) {
            throw new ASN1Exception("Expected SEQUENCE, got "
                    + ASN1Type.getTagName(message.getTag()));
        }

        List<ASN1Element> children = message.getChildren();
        if (children.size() < 2) {
            throw new ASN1Exception("Invalid LDAP message structure");
        }

        int messageId = children.get(0).asInt();
        ASN1Element protocolOp = children.get(1);
        int tag = protocolOp.getTag();

        // RFC 4511 section 4.1.11 — parse response controls if present
        List<Control> responseControls = null;
        if (children.size() >= 3
                && children.get(2).getTag() == LDAPConstants.TAG_CONTROLS) {
            responseControls = parseControls(children.get(2));
        }
        this.lastResponseControls = responseControls;

        logger.fine("Received LDAP response (messageId=" + messageId + ", tag=0x"
                + Integer.toHexString(tag) + ")");

        // RFC 4511 section 4.4 — Unsolicited Notification (messageID 0)
        if (messageId == 0) {
            handleUnsolicitedNotification(protocolOp);
            return;
        }

        switch (tag) {
            case LDAPConstants.TAG_BIND_RESPONSE:          // application 1
                handleBindResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_SEARCH_RESULT_ENTRY:    // application 4
                handleSearchResultEntry(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_SEARCH_RESULT_DONE:     // application 5
                handleSearchResultDone(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_SEARCH_RESULT_REFERENCE: // application 19
                handleSearchResultReference(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_MODIFY_RESPONSE:        // application 7
                handleModifyResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_ADD_RESPONSE:           // application 9
                handleAddResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_DEL_RESPONSE:           // application 11
                handleDeleteResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_MODIFY_DN_RESPONSE:     // application 13
                handleModifyDNResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_COMPARE_RESPONSE:       // application 15
                handleCompareResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_EXTENDED_RESPONSE:      // application 24
                handleExtendedResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_INTERMEDIATE_RESPONSE:  // application 25
                handleIntermediateResponse(messageId, protocolOp);
                break;
            default:
                logger.warning(MessageFormat.format(
                        L10N.getString("warn.unknown_ldap_response_tag"),
                        Integer.toHexString(tag)));
        }
    }

    // RFC 4511 section 4.1.9 — LDAPResult (resultCode, matchedDN, diagnosticMessage, referral)
    private LDAPResult parseResult(ASN1Element element) throws ASN1Exception {
        List<ASN1Element> children = element.getChildren();
        if (children.size() < 3) {
            throw new ASN1Exception("Invalid LDAPResult structure");
        }

        int code = children.get(0).asInt();
        String matchedDN = children.get(1).asString();
        String diagnosticMessage = children.get(2).asString();

        List<String> referrals = null;
        if (children.size() > 3 && children.get(3).getTag() == LDAPConstants.TAG_REFERRAL) {
            referrals = new ArrayList<String>();
            for (ASN1Element ref : children.get(3).getChildren()) {
                referrals.add(ref.asString());
            }
        }

        LDAPResult result = new LDAPResult(LDAPResultCode.fromCode(code), matchedDN,
                diagnosticMessage, referrals);
        // RFC 4511 section 4.1.11 — attach response controls if present
        if (lastResponseControls != null) {
            result.setControls(lastResponseControls);
        }
        return result;
    }

    // RFC 4511 section 4.1.11 — parse Controls SEQUENCE from message envelope
    private List<Control> parseControls(ASN1Element controlsElement) throws ASN1Exception {
        List<Control> controls = new ArrayList<Control>();
        for (ASN1Element controlSeq : controlsElement.getChildren()) {
            List<ASN1Element> parts = controlSeq.getChildren();
            if (parts.isEmpty()) {
                continue;
            }
            String oid = parts.get(0).asString();
            boolean critical = false;
            byte[] value = null;
            for (int i = 1; i < parts.size(); i++) {
                ASN1Element part = parts.get(i);
                if (part.getTag() == ASN1Type.BOOLEAN) {
                    critical = part.asBoolean();
                } else if (part.getTag() == ASN1Type.OCTET_STRING) {
                    value = part.asOctetString();
                }
            }
            controls.add(new Control(oid, critical, value));
        }
        return controls;
    }

    // RFC 4511 section 4.2.2 — BindResponse (handles both simple and SASL)
    private void handleBindResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (!(callback instanceof BindResultHandler)) {
            return;
        }
        BindResultHandler bindCallback = (BindResultHandler) callback;

        if (activeSaslClient != null) {
            byte[] serverSaslCreds = extractServerSaslCreds(element);
            byte[] challenge =
                    serverSaslCreds != null ? serverSaslCreds : new byte[0];

            if (result.getResultCode() == LDAPResultCode.SASL_BIND_IN_PROGRESS) {
                // RFC 4752 — offload GSSAPI evaluateChallenge to worker
                if (gssapiExecutor != null
                        && activeSaslClient instanceof GSSAPIClientMechanism) {
                    evaluateChallengeAsync(challenge, bindCallback);
                } else {
                    try {
                        byte[] response =
                                activeSaslClient.evaluateChallenge(challenge);
                        sendSASLBindRequest(
                                activeSaslClient.getMechanismName(),
                                response, bindCallback);
                    } catch (IOException e) {
                        clearSaslClient();
                        bindCallback.handleBindFailure(
                                new LDAPResult(LDAPResultCode.OTHER, "",
                                        e.getMessage(), null),
                                this);
                    }
                }
                return;
            }

            if (result.isSuccess()) {
                try {
                    if (serverSaslCreds != null) {
                        activeSaslClient.evaluateChallenge(serverSaslCreds);
                    }
                    if (!activeSaslClient.isComplete()) {
                        clearSaslClient();
                        bindCallback.handleBindFailure(
                                new LDAPResult(LDAPResultCode.OTHER, "",
                                        "SASL negotiation incomplete after "
                                                + "server success", null),
                                this);
                        return;
                    }
                } catch (IOException e) {
                    clearSaslClient();
                    bindCallback.handleBindFailure(
                            new LDAPResult(LDAPResultCode.OTHER, "",
                                    e.getMessage(), null),
                            this);
                    return;
                }
            }
            clearSaslClient();
        }

        if (result.isSuccess()) {
            bindCallback.handleBindSuccess(this);
        } else {
            bindCallback.handleBindFailure(result, this);
        }
    }

    // RFC 4511 section 4.2.2 — extract optional serverSaslCreds [7] from BindResponse
    private byte[] extractServerSaslCreds(ASN1Element element) {
        List<ASN1Element> children = element.getChildren();
        for (int i = 3; i < children.size(); i++) {
            if (children.get(i).getTag() == LDAPConstants.TAG_SERVER_SASL_CREDS) {
                return children.get(i).asOctetString();
            }
        }
        return null;
    }

    private void clearSaslClient() {
        activeSaslClient = null;
        gssapiExecutor = null;
    }

    // RFC 4511 section 4.1.11 — request/response controls

    @Override
    public void setRequestControls(List<Control> controls) {
        this.requestControls = controls;
    }

    @Override
    public List<Control> getResponseControls() {
        return lastResponseControls != null ?
                java.util.Collections.unmodifiableList(lastResponseControls) :
                java.util.Collections.<Control>emptyList();
    }

    /**
     * Encodes controls into the current message SEQUENCE (RFC 4511 §4.1.11).
     * Consumes and clears the requestControls.
     */
    private void encodeRequestControls(BEREncoder encoder) {
        List<Control> controls = this.requestControls;
        this.requestControls = null;
        if (controls == null || controls.isEmpty()) {
            return;
        }
        encoder.beginContext(0, true);  // Controls [0]
        for (Control ctrl : controls) {
            encoder.beginSequence();
            encoder.writeOctetString(ctrl.getOID());
            if (ctrl.isCritical()) {
                encoder.writeBoolean(true);
            }
            if (ctrl.hasValue()) {
                encoder.writeOctetString(ctrl.getValue());
            }
            encoder.endSequence();
        }
        encoder.endContext();
    }

    // RFC 4511 section 4.5.2 — SearchResultEntry
    private void handleSearchResultEntry(int messageId, ASN1Element element) throws ASN1Exception {
        List<ASN1Element> children = element.getChildren();
        if (children.size() < 2) {
            throw new ASN1Exception("Invalid SearchResultEntry structure");
        }

        String dn = children.get(0).asString();
        Map<String, List<byte[]>> attributes = new HashMap<String, List<byte[]>>();

        ASN1Element attrList = children.get(1);
        for (ASN1Element attr : attrList.getChildren()) {
            List<ASN1Element> attrChildren = attr.getChildren();
            String name = attrChildren.get(0).asString();
            List<byte[]> values = new ArrayList<byte[]>();

            if (attrChildren.size() > 1) {
                for (ASN1Element val : attrChildren.get(1).getChildren()) {
                    values.add(val.asOctetString());
                }
            }
            attributes.put(name, values);
        }

        SearchResultEntry entry = new SearchResultEntry(dn, attributes);
        Object callback = pendingCallbacks.get(messageId);

        if (callback instanceof SearchResultHandler) {
            ((SearchResultHandler) callback).handleEntry(entry);
        }
    }

    // RFC 4511 section 4.5.2 — SearchResultDone
    private void handleSearchResultDone(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof SearchResultHandler) {
            ((SearchResultHandler) callback).handleDone(result, this);
        }
    }

    // RFC 4511 section 4.5.3 — SearchResultReference (continuation references)
    private void handleSearchResultReference(int messageId, ASN1Element element)
            throws ASN1Exception {
        List<ASN1Element> children = element.getChildren();
        String[] urls = new String[children.size()];
        for (int i = 0; i < children.size(); i++) {
            urls[i] = children.get(i).asString();
        }

        Object callback = pendingCallbacks.get(messageId);

        if (callback instanceof SearchResultHandler) {
            ((SearchResultHandler) callback).handleReference(urls);
        }
    }

    // RFC 4511 section 4.6 — ModifyResponse
    private void handleModifyResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof ModifyResultHandler) {
            ((ModifyResultHandler) callback).handleModifyResult(result, this);
        }
    }

    // RFC 4511 section 4.7 — AddResponse
    private void handleAddResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof AddResultHandler) {
            ((AddResultHandler) callback).handleAddResult(result, this);
        }
    }

    // RFC 4511 section 4.8 — DelResponse
    private void handleDeleteResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof DeleteResultHandler) {
            ((DeleteResultHandler) callback).handleDeleteResult(result, this);
        }
    }

    // RFC 4511 section 4.9 — ModifyDNResponse
    private void handleModifyDNResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof ModifyDNResultHandler) {
            ((ModifyDNResultHandler) callback).handleModifyDNResult(result, this);
        }
    }

    // RFC 4511 section 4.10 — CompareResponse (COMPARE_TRUE / COMPARE_FALSE)
    private void handleCompareResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof CompareResultHandler) {
            CompareResultHandler compareCallback = (CompareResultHandler) callback;
            if (result.getResultCode() == LDAPResultCode.COMPARE_TRUE) {
                compareCallback.handleCompareTrue(this);
            } else if (result.getResultCode() == LDAPResultCode.COMPARE_FALSE) {
                compareCallback.handleCompareFalse(this);
            } else {
                compareCallback.handleCompareFailure(result, this);
            }
        }
    }

    // RFC 4511 section 4.12 — ExtendedResponse (with optional responseName/responseValue)
    private void handleExtendedResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);

        String responseName = null;
        byte[] responseValue = null;

        List<ASN1Element> children = element.getChildren();
        for (int i = 3; i < children.size(); i++) {
            ASN1Element child = children.get(i);
            int tagNum = ASN1Type.getTagNumber(child.getTag());
            if (tagNum == 10) {  // responseName
                responseName = child.asString();
            } else if (tagNum == 11) {  // responseValue
                responseValue = child.asOctetString();
            }
        }

        // Check if this is a STARTTLS response
        if (messageId == startTLSMessageId) {
            startTLSMessageId = -1;

            if (result.isSuccess()) {
                // Upgrade to TLS - securityEstablished will call the callback
                try {
                    if (endpoint != null) {
                        endpoint.startTLS();
                    }
                    logger.fine("TLS upgrade initiated after STARTTLS response");
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.starttls_failed"),
                            e.getMessage()));
                    if (startTLSCallback != null) {
                        StartTLSResultHandler callback = startTLSCallback;
                        startTLSCallback = null;
                        callback.handleStartTLSFailure(
                                new LDAPResult(LDAPResultCode.OTHER, "", e.getMessage(), null),
                                this);
                    } else {
                        handler.onError(e);
                    }
                }
            } else {
                // STARTTLS failed
                if (startTLSCallback != null) {
                    StartTLSResultHandler callback = startTLSCallback;
                    startTLSCallback = null;
                    callback.handleStartTLSFailure(result, this);
                }
            }
            return;
        }

        // Handle other extended responses
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof ExtendedResultHandler) {
            ((ExtendedResultHandler) callback).handleExtendedResult(result, responseName,
                    responseValue, this);
        }
    }

    // RFC 4511 section 4.4 — Unsolicited Notification (messageID 0)
    // The only defined unsolicited notification is Notice of Disconnection
    // (OID 1.3.6.1.4.1.1466.20036, RFC 4511 section 4.4.1).
    private void handleUnsolicitedNotification(ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);

        String responseName = null;
        List<ASN1Element> children = element.getChildren();
        for (int i = 3; i < children.size(); i++) {
            int tagNum = ASN1Type.getTagNumber(children.get(i).getTag());
            if (tagNum == 10) {
                responseName = children.get(i).asString();
                break;
            }
        }

        if (LDAPConstants.OID_NOTICE_OF_DISCONNECTION.equals(responseName)) {
            logger.warning("Notice of Disconnection from server: "
                    + result.getDiagnosticMessage()
                    + " (code=" + result.getResultCode() + ")");
            handler.onError(new IOException("Server sent Notice of Disconnection: "
                    + result.getDiagnosticMessage()));
            close();
        } else {
            logger.info("Unsolicited notification: oid="
                    + responseName + ", " + result);
        }
    }

    // RFC 4511 section 4.13 — IntermediateResponse
    // Dispatched to the pending callback if it implements IntermediateResponseHandler.
    private void handleIntermediateResponse(int messageId, ASN1Element element)
            throws ASN1Exception {
        String responseName = null;
        byte[] responseValue = null;

        List<ASN1Element> children = element.getChildren();
        for (ASN1Element child : children) {
            int tagNum = ASN1Type.getTagNumber(child.getTag());
            if (tagNum == 0) {
                responseName = child.asString();
            } else if (tagNum == 1) {
                responseValue = child.asOctetString();
            }
        }

        Object callback = pendingCallbacks.get(messageId);
        if (callback instanceof IntermediateResponseHandler) {
            ((IntermediateResponseHandler) callback)
                    .handleIntermediateResponse(responseName, responseValue);
        } else {
            logger.fine("IntermediateResponse (messageId=" + messageId
                    + ", oid=" + responseName + ") — no handler registered");
        }
    }
}
