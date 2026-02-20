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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Element;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Exception;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Type;
import org.bluezoo.gumdrop.ldap.asn1.BERDecoder;
import org.bluezoo.gumdrop.ldap.asn1.BEREncoder;

/**
 * LDAP client protocol handler implementing {@link ProtocolHandler}.
 *
 * <p>Implements the LDAP client interfaces ({@link LDAPConnected},
 * {@link LDAPPostTLS}, {@link LDAPSession}) and delegates all transport
 * operations to a transport-agnostic {@link Endpoint}.
 *
 * <p>The handler implements stateful interfaces that guide the caller through
 * the LDAP protocol:
 * <ul>
 * <li>{@link LDAPConnected} - initial state, allows bind and STARTTLS</li>
 * <li>{@link LDAPPostTLS} - after STARTTLS, must bind</li>
 * <li>{@link LDAPSession} - after bind, full operations available</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
    // LDAPConnected implementation
    // ─────────────────────────────────────────────────────────────────────────

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

    @Override
    public void bindAnonymous(BindResultHandler callback) {
        bind("", "", callback);
    }

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
    // LDAPSession implementation
    // ─────────────────────────────────────────────────────────────────────────

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
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent SearchRequest (messageId=" + messageId + ")");
    }

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
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent ModifyRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

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
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent AddRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    @Override
    public void delete(String dn, DeleteResultHandler callback) {
        int messageId = nextMessageId.getAndIncrement();
        pendingCallbacks.put(messageId, callback);

        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.writeApplication(10, dn.getBytes(StandardCharsets.UTF_8));  // DelRequest
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent DeleteRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

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
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent CompareRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

    @Override
    public void modifyDN(String dn, String newRDN, boolean deleteOldRDN,
                         ModifyDNResultHandler callback) {
        modifyDN(dn, newRDN, deleteOldRDN, null, callback);
    }

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
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent ModifyDNRequest (messageId=" + messageId + ", dn=" + dn + ")");
    }

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
        encoder.endSequence();

        send(encoder.toByteBuffer());
        logger.fine("Sent ExtendedRequest (messageId=" + messageId + ", oid=" + oid + ")");
    }

    @Override
    public void rebind(String dn, String password, BindResultHandler callback) {
        bind(dn, password, callback);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter encoding
    // ─────────────────────────────────────────────────────────────────────────

    private void encodeFilter(BEREncoder encoder, String filter) {
        // Parse and encode LDAP filter
        // This is a simplified implementation - a full parser would handle all filter types
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

    // ─────────────────────────────────────────────────────────────────────────
    // Message processing
    // ─────────────────────────────────────────────────────────────────────────

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

        logger.fine("Received LDAP response (messageId=" + messageId + ", tag=0x"
                + Integer.toHexString(tag) + ")");

        switch (tag) {
            case LDAPConstants.TAG_BIND_RESPONSE:
                handleBindResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_SEARCH_RESULT_ENTRY:
                handleSearchResultEntry(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_SEARCH_RESULT_DONE:
                handleSearchResultDone(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_SEARCH_RESULT_REFERENCE:
                handleSearchResultReference(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_MODIFY_RESPONSE:
                handleModifyResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_ADD_RESPONSE:
                handleAddResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_DEL_RESPONSE:
                handleDeleteResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_MODIFY_DN_RESPONSE:
                handleModifyDNResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_COMPARE_RESPONSE:
                handleCompareResponse(messageId, protocolOp);
                break;
            case LDAPConstants.TAG_EXTENDED_RESPONSE:
                handleExtendedResponse(messageId, protocolOp);
                break;
            default:
                logger.warning(MessageFormat.format(
                        L10N.getString("warn.unknown_ldap_response_tag"),
                        Integer.toHexString(tag)));
        }
    }

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

        return new LDAPResult(LDAPResultCode.fromCode(code), matchedDN,
                diagnosticMessage, referrals);
    }

    private void handleBindResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof BindResultHandler) {
            BindResultHandler bindCallback = (BindResultHandler) callback;
            if (result.isSuccess()) {
                bindCallback.handleBindSuccess(this);
            } else {
                bindCallback.handleBindFailure(result, this);
            }
        }
    }

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

    private void handleSearchResultDone(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof SearchResultHandler) {
            ((SearchResultHandler) callback).handleDone(result, this);
        }
    }

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

    private void handleModifyResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof ModifyResultHandler) {
            ((ModifyResultHandler) callback).handleModifyResult(result, this);
        }
    }

    private void handleAddResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof AddResultHandler) {
            ((AddResultHandler) callback).handleAddResult(result, this);
        }
    }

    private void handleDeleteResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof DeleteResultHandler) {
            ((DeleteResultHandler) callback).handleDeleteResult(result, this);
        }
    }

    private void handleModifyDNResponse(int messageId, ASN1Element element) throws ASN1Exception {
        LDAPResult result = parseResult(element);
        Object callback = pendingCallbacks.remove(messageId);

        if (callback instanceof ModifyDNResultHandler) {
            ((ModifyDNResultHandler) callback).handleModifyDNResult(result, this);
        }
    }

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
}
