/*
 * POP3ClientProtocolHandler.java
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

package org.bluezoo.gumdrop.pop3.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.pop3.client.handler.ClientAuthExchange;
import org.bluezoo.gumdrop.pop3.client.handler.ClientAuthorizationState;
import org.bluezoo.gumdrop.pop3.client.handler.ClientPasswordState;
import org.bluezoo.gumdrop.pop3.client.handler.ClientPostStls;
import org.bluezoo.gumdrop.pop3.client.handler.ClientTransactionState;
import org.bluezoo.gumdrop.pop3.client.handler.ServerApopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerAuthAbortHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerAuthReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerCapaReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerDeleReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.pop3.client.handler.ServerListReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerNoopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerPassReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerRetrReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerRsetReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerStatReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerStlsReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerTopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerUidlReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerUserReplyHandler;

/**
 * POP3 client protocol handler implementing {@link ProtocolHandler}.
 *
 * <p>Implements a type-safe POP3 client state machine
 * ({@code ClientAuthorizationState}, {@code ClientTransactionState}, etc.)
 * and delegates all transport operations to a transport-agnostic
 * {@link Endpoint}.
 *
 * <p>Line parsing is handled by the composable {@link LineParser} utility.
 * Multi-line content (RETR, TOP) is dot-unstuffed transparently via
 * {@link DotUnstuffer} and delivered as ByteBuffer chunks.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * ClientEndpoint client = new ClientEndpoint(factory, "pop.example.com", 110);
 * client.connect(new POP3ClientProtocolHandler(new ServerGreeting() {
 *     public void handleGreeting(ClientAuthorizationState auth,
 *                                String msg, String apopTimestamp) {
 *         auth.capa(capaHandler);
 *     }
 *     public void handleServiceUnavailable(String msg) { ... }
 * }));
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see ServerGreeting
 */
public class POP3ClientProtocolHandler
        implements ProtocolHandler, LineParser.Callback,
        DotUnstuffer.Callback,
        ClientAuthorizationState, ClientPasswordState,
        ClientPostStls, ClientTransactionState,
        ClientAuthExchange {

    private static final Logger LOGGER =
            Logger.getLogger(POP3ClientProtocolHandler.class.getName());

    private static final String CRLF = "\r\n";

    private final ServerGreeting handler;

    private Endpoint endpoint;
    private POP3State state = POP3State.DISCONNECTED;
    private boolean secure;

    private Object currentCallback;

    // Multi-line response accumulation for CAPA
    private List<String> capaLines;

    // Multi-line LIST/UIDL tracking
    private boolean multiLineList;

    // Content streaming for RETR/TOP
    private DotUnstuffer dotUnstuffer;
    private boolean dotUnstufferActive;

    // CAPA parsed capabilities
    private boolean capaStls;
    private List<String> capaSaslMechanisms;
    private boolean capaTop;
    private boolean capaUidl;
    private boolean capaUser;
    private boolean capaPipelining;
    private String capaImplementation;

    /**
     * Creates a POP3 client protocol handler.
     *
     * @param handler the server greeting handler
     */
    public POP3ClientProtocolHandler(ServerGreeting handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.capaLines = new ArrayList<String>();
        this.capaSaslMechanisms = new ArrayList<String>();
        this.dotUnstuffer = new DotUnstuffer(this);
    }

    /**
     * Sets whether this connection started in secure mode.
     *
     * @param secure true for implicit TLS (POP3S port 995)
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        state = POP3State.CONNECTING;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("POP3 client connected to "
                    + ep.getRemoteAddress());
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        if (dotUnstufferActive) {
            boolean more = dotUnstuffer.process(data);
            if (!more) {
                dotUnstufferActive = false;
            }
            if (data.hasRemaining()) {
                LineParser.parse(data, this);
            }
        } else {
            LineParser.parse(data, this);
        }
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("POP3 client disconnected");
        }
        state = POP3State.CLOSED;
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS established: " + info.getCipherSuite());
        }

        handler.onSecurityEstablished(info);

        if (currentCallback instanceof ServerStlsReplyHandler) {
            ServerStlsReplyHandler callback =
                    (ServerStlsReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.AUTHORIZATION;
            callback.handleTlsEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "POP3 transport error", cause);
        }
        state = POP3State.ERROR;
        handler.onError(cause);
    }

    // ── LineParser.Callback ──

    @Override
    public void lineReceived(ByteBuffer line) {
        int len = line.remaining();
        if (len < 2) {
            return;
        }
        byte[] bytes = new byte[len - 2];
        line.get(bytes);
        line.get(); // CR
        line.get(); // LF
        String text = new String(bytes, StandardCharsets.US_ASCII);
        handleResponseLine(text);
    }

    @Override
    public boolean continueLineProcessing() {
        return !dotUnstufferActive;
    }

    // ── DotUnstuffer.Callback ──

    @Override
    public void content(ByteBuffer data) {
        if (state == POP3State.RETR_DATA) {
            ServerRetrReplyHandler callback =
                    (ServerRetrReplyHandler) currentCallback;
            callback.handleMessageContent(data);
        } else if (state == POP3State.TOP_DATA) {
            ServerTopReplyHandler callback =
                    (ServerTopReplyHandler) currentCallback;
            callback.handleTopContent(data);
        }
    }

    @Override
    public void complete() {
        dotUnstufferActive = false;

        if (state == POP3State.RETR_DATA) {
            ServerRetrReplyHandler callback =
                    (ServerRetrReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.TRANSACTION;
            callback.handleMessageComplete(this);
        } else if (state == POP3State.TOP_DATA) {
            ServerTopReplyHandler callback =
                    (ServerTopReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.TRANSACTION;
            callback.handleTopComplete(this);
        }
    }

    // ── Connection state ──

    /**
     * Returns whether the connection is open.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        return endpoint != null && endpoint.isOpen()
                && state != POP3State.DISCONNECTED
                && state != POP3State.CLOSED
                && state != POP3State.ERROR;
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (state == POP3State.CLOSED) {
            return;
        }
        state = POP3State.CLOSED;
        dotUnstuffer.reset();
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ClientAuthorizationState ──

    @Override
    public void capa(ServerCapaReplyHandler callback) {
        this.currentCallback = callback;
        capaLines.clear();
        sendCommand("CAPA", POP3State.CAPA_SENT);
    }

    @Override
    public void user(String username, ServerUserReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("USER " + username, POP3State.USER_SENT);
    }

    @Override
    public void apop(String username, String digest,
                     ServerApopReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("APOP " + username + " " + digest,
                POP3State.APOP_SENT);
    }

    @Override
    public void auth(String mechanism, byte[] initialResponse,
                     ServerAuthReplyHandler callback) {
        this.currentCallback = callback;

        StringBuilder cmd = new StringBuilder("AUTH ");
        cmd.append(mechanism);
        if (initialResponse != null) {
            cmd.append(" ");
            cmd.append(Base64.getEncoder()
                    .encodeToString(initialResponse));
        }
        sendCommand(cmd.toString(), POP3State.AUTH_SENT);
    }

    @Override
    public void stls(ServerStlsReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("STLS", POP3State.STLS_SENT);
    }

    // ── ClientPasswordState ──

    @Override
    public void pass(String password, ServerPassReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("PASS " + password, POP3State.PASS_SENT);
    }

    // ── ClientTransactionState ──

    @Override
    public void stat(ServerStatReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("STAT", POP3State.STAT_SENT);
    }

    @Override
    public void list(ServerListReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = true;
        sendCommand("LIST", POP3State.LIST_SENT);
    }

    @Override
    public void list(int messageNumber,
                     ServerListReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = false;
        sendCommand("LIST " + messageNumber, POP3State.LIST_SENT);
    }

    @Override
    public void retr(int messageNumber,
                     ServerRetrReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RETR " + messageNumber, POP3State.RETR_SENT);
    }

    @Override
    public void dele(int messageNumber,
                     ServerDeleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("DELE " + messageNumber, POP3State.DELE_SENT);
    }

    @Override
    public void rset(ServerRsetReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RSET", POP3State.RSET_SENT);
    }

    @Override
    public void top(int messageNumber, int lines,
                    ServerTopReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("TOP " + messageNumber + " " + lines,
                POP3State.TOP_SENT);
    }

    @Override
    public void uidl(ServerUidlReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = true;
        sendCommand("UIDL", POP3State.UIDL_SENT);
    }

    @Override
    public void uidl(int messageNumber,
                     ServerUidlReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = false;
        sendCommand("UIDL " + messageNumber, POP3State.UIDL_SENT);
    }

    @Override
    public void noop(ServerNoopReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("NOOP", POP3State.NOOP_SENT);
    }

    @Override
    public void quit() {
        sendCommand("QUIT", POP3State.QUIT_SENT);
    }

    // ── ClientAuthExchange ──

    @Override
    public void respond(byte[] response,
                        ServerAuthReplyHandler callback) {
        this.currentCallback = callback;
        String encoded = Base64.getEncoder().encodeToString(response);
        sendRawLine(encoded, POP3State.AUTH_SENT);
    }

    @Override
    public void abort(ServerAuthAbortHandler callback) {
        this.currentCallback = callback;
        sendRawLine("*", POP3State.AUTH_ABORT_SENT);
    }

    // ── Command sending ──

    private void sendCommand(String command, POP3State newState) {
        if (!isOpen()) {
            handler.onError(new IOException("Not connected"));
            return;
        }

        this.state = newState;

        byte[] data = (command + CRLF).getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(data));

        if (LOGGER.isLoggable(Level.FINE)) {
            if (command.startsWith("PASS ")
                    || command.startsWith("AUTH ")) {
                LOGGER.fine("Sent POP3 command: "
                        + command.substring(0,
                                command.indexOf(' ') + 1)
                        + "***");
            } else {
                LOGGER.fine("Sent POP3 command: " + command);
            }
        }
    }

    private void sendRawLine(String line, POP3State newState) {
        if (!isOpen()) {
            handler.onError(new IOException("Not connected"));
            return;
        }

        this.state = newState;

        byte[] data = (line + CRLF).getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(data));

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Sent POP3 line: ***");
        }
    }

    // ── Response handling ──

    private void handleResponseLine(String line) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Received POP3 response: " + line);
        }

        try {
            switch (state) {
                case CAPA_DATA:
                    handleCapaDataLine(line);
                    return;
                case LIST_DATA:
                    handleListDataLine(line);
                    return;
                case UIDL_DATA:
                    handleUidlDataLine(line);
                    return;
                default:
                    break;
            }

            POP3Response response = POP3Response.parse(line);
            if (response == null) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(
                            "Unparseable POP3 response: " + line);
                }
                return;
            }

            dispatchResponse(response);
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING,
                        "Error handling POP3 response: " + line, e);
            }
            handler.onError(e);
        }
    }

    private void dispatchResponse(POP3Response response) {
        if (state == POP3State.CLOSED
                || state == POP3State.DISCONNECTED) {
            return;
        }

        switch (state) {
            case CONNECTING:
                dispatchGreeting(response);
                break;
            case CAPA_SENT:
                dispatchCapaReply(response);
                break;
            case USER_SENT:
                dispatchUserReply(response);
                break;
            case PASS_SENT:
                dispatchPassReply(response);
                break;
            case APOP_SENT:
                dispatchApopReply(response);
                break;
            case STLS_SENT:
                dispatchStlsReply(response);
                break;
            case AUTH_SENT:
                dispatchAuthReply(response);
                break;
            case AUTH_ABORT_SENT:
                dispatchAuthAbortReply(response);
                break;
            case STAT_SENT:
                dispatchStatReply(response);
                break;
            case LIST_SENT:
                dispatchListReply(response);
                break;
            case UIDL_SENT:
                dispatchUidlReply(response);
                break;
            case RETR_SENT:
                dispatchRetrReply(response);
                break;
            case TOP_SENT:
                dispatchTopReply(response);
                break;
            case DELE_SENT:
                dispatchDeleReply(response);
                break;
            case RSET_SENT:
                dispatchRsetReply(response);
                break;
            case NOOP_SENT:
                dispatchNoopReply(response);
                break;
            case QUIT_SENT:
                state = POP3State.CLOSED;
                close();
                break;
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unexpected response in state "
                            + state + ": " + response);
                }
        }
    }

    // ── Greeting ──

    private void dispatchGreeting(POP3Response response) {
        if (response.isOk()) {
            state = POP3State.AUTHORIZATION;
            String message = response.getMessage();
            String apopTimestamp = parseApopTimestamp(message);
            handler.onConnected(endpoint);
            handler.handleGreeting(this, message, apopTimestamp);
        } else {
            state = POP3State.ERROR;
            handler.handleServiceUnavailable(response.getMessage());
            close();
        }
    }

    private String parseApopTimestamp(String greeting) {
        int start = greeting.indexOf('<');
        if (start < 0) {
            return null;
        }
        int end = greeting.indexOf('>', start);
        if (end < 0) {
            return null;
        }
        return greeting.substring(start, end + 1);
    }

    // ── CAPA ──

    private void dispatchCapaReply(POP3Response response) {
        if (response.isOk()) {
            state = POP3State.CAPA_DATA;
            capaLines.clear();
            resetCapabilities();
        } else {
            ServerCapaReplyHandler callback =
                    (ServerCapaReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.AUTHORIZATION;
            callback.handleError(this, response.getMessage());
        }
    }

    private void handleCapaDataLine(String line) {
        if (".".equals(line)) {
            parseCapabilities(capaLines);

            ServerCapaReplyHandler callback =
                    (ServerCapaReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.AUTHORIZATION;
            callback.handleCapabilities(this, capaStls,
                    new ArrayList<String>(capaSaslMechanisms),
                    capaTop, capaUidl, capaUser, capaPipelining,
                    capaImplementation);
        } else {
            capaLines.add(line);
        }
    }

    private void resetCapabilities() {
        capaStls = false;
        capaSaslMechanisms.clear();
        capaTop = false;
        capaUidl = false;
        capaUser = false;
        capaPipelining = false;
        capaImplementation = null;
    }

    private void parseCapabilities(List<String> lines) {
        for (String line : lines) {
            String upper = line.toUpperCase();
            if ("STLS".equals(upper)) {
                capaStls = true;
            } else if ("TOP".equals(upper)) {
                capaTop = true;
            } else if ("UIDL".equals(upper)) {
                capaUidl = true;
            } else if ("USER".equals(upper)) {
                capaUser = true;
            } else if ("PIPELINING".equals(upper)) {
                capaPipelining = true;
            } else if (upper.startsWith("SASL")) {
                String mechs = line.substring(4).trim();
                if (!mechs.isEmpty()) {
                    for (String m : mechs.split("\\s+")) {
                        capaSaslMechanisms.add(m);
                    }
                }
            } else if (upper.startsWith("IMPLEMENTATION")) {
                capaImplementation = line.substring(14).trim();
            }
        }
    }

    // ── USER ──

    private void dispatchUserReply(POP3Response response) {
        ServerUserReplyHandler callback =
                (ServerUserReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = POP3State.AUTHORIZATION;
            callback.handleUserAccepted(this);
        } else {
            state = POP3State.AUTHORIZATION;
            callback.handleRejected(this, response.getMessage());
        }
    }

    // ── PASS ──

    private void dispatchPassReply(POP3Response response) {
        ServerPassReplyHandler callback =
                (ServerPassReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = POP3State.TRANSACTION;
            callback.handleAuthenticated(this);
        } else {
            state = POP3State.AUTHORIZATION;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    // ── APOP ──

    private void dispatchApopReply(POP3Response response) {
        ServerApopReplyHandler callback =
                (ServerApopReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = POP3State.TRANSACTION;
            callback.handleAuthenticated(this);
        } else {
            state = POP3State.AUTHORIZATION;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    // ── STLS ──

    private void dispatchStlsReply(POP3Response response) {
        if (response.isOk()) {
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                ServerStlsReplyHandler callback =
                        (ServerStlsReplyHandler) currentCallback;
                currentCallback = null;
                state = POP3State.AUTHORIZATION;
                callback.handleTlsUnavailable(this);
            }
        } else {
            ServerStlsReplyHandler callback =
                    (ServerStlsReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.AUTHORIZATION;
            callback.handleTlsUnavailable(this);
        }
    }

    // ── AUTH ──

    private void dispatchAuthReply(POP3Response response) {
        ServerAuthReplyHandler callback =
                (ServerAuthReplyHandler) currentCallback;

        if (response.isOk()) {
            currentCallback = null;
            state = POP3State.TRANSACTION;
            callback.handleAuthSuccess(this);
        } else if (response.isContinuation()) {
            String challengeData = response.getMessage();
            byte[] challenge;
            if (challengeData.isEmpty()) {
                challenge = new byte[0];
            } else {
                challenge = Base64.getDecoder().decode(challengeData);
            }
            callback.handleChallenge(challenge, this);
        } else {
            currentCallback = null;
            state = POP3State.AUTHORIZATION;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    // ── AUTH abort ──

    private void dispatchAuthAbortReply(POP3Response response) {
        ServerAuthAbortHandler callback =
                (ServerAuthAbortHandler) currentCallback;
        currentCallback = null;
        state = POP3State.AUTHORIZATION;
        callback.handleAborted(this);
    }

    // ── STAT ──

    private void dispatchStatReply(POP3Response response) {
        ServerStatReplyHandler callback =
                (ServerStatReplyHandler) currentCallback;
        currentCallback = null;
        state = POP3State.TRANSACTION;

        if (response.isOk()) {
            String msg = response.getMessage();
            int messageCount = 0;
            long totalSize = 0;
            try {
                int spaceIdx = msg.indexOf(' ');
                if (spaceIdx > 0) {
                    messageCount =
                            Integer.parseInt(msg.substring(0, spaceIdx));
                    totalSize = Long.parseLong(
                            msg.substring(spaceIdx + 1).trim());
                }
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to parse STAT response: " + msg, e);
            }
            callback.handleStat(this, messageCount, totalSize);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    // ── LIST ──

    private void dispatchListReply(POP3Response response) {
        ServerListReplyHandler callback =
                (ServerListReplyHandler) currentCallback;

        if (response.isOk()) {
            if (multiLineList) {
                state = POP3State.LIST_DATA;
            } else {
                currentCallback = null;
                state = POP3State.TRANSACTION;
                parseListEntry(response.getMessage(), callback);
            }
        } else {
            currentCallback = null;
            state = POP3State.TRANSACTION;
            String msg = response.getMessage().toLowerCase();
            if (msg.contains("no such message")
                    || msg.contains("not exist")) {
                callback.handleNoSuchMessage(this,
                        response.getMessage());
            } else {
                callback.handleError(this, response.getMessage());
            }
        }
    }

    private void handleListDataLine(String line) {
        if (".".equals(line)) {
            ServerListReplyHandler callback =
                    (ServerListReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.TRANSACTION;
            callback.handleListComplete(this);
        } else {
            ServerListReplyHandler callback =
                    (ServerListReplyHandler) currentCallback;
            parseListEntryMulti(line, callback);
        }
    }

    private void parseListEntry(String msg,
                                ServerListReplyHandler callback) {
        try {
            int spaceIdx = msg.indexOf(' ');
            if (spaceIdx > 0) {
                int num = Integer.parseInt(
                        msg.substring(0, spaceIdx));
                long size = Long.parseLong(
                        msg.substring(spaceIdx + 1).trim());
                callback.handleListing(this, num, size);
                return;
            }
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to parse LIST response: " + msg, e);
        }
        callback.handleError(this, msg);
    }

    private void parseListEntryMulti(String line,
                                     ServerListReplyHandler callback) {
        try {
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx > 0) {
                int num = Integer.parseInt(
                        line.substring(0, spaceIdx));
                long size = Long.parseLong(
                        line.substring(spaceIdx + 1).trim());
                callback.handleListEntry(num, size);
            }
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to parse LIST entry: " + line, e);
        }
    }

    // ── UIDL ──

    private void dispatchUidlReply(POP3Response response) {
        ServerUidlReplyHandler callback =
                (ServerUidlReplyHandler) currentCallback;

        if (response.isOk()) {
            if (multiLineList) {
                state = POP3State.UIDL_DATA;
            } else {
                currentCallback = null;
                state = POP3State.TRANSACTION;
                parseUidlEntry(response.getMessage(), callback);
            }
        } else {
            currentCallback = null;
            state = POP3State.TRANSACTION;
            String msg = response.getMessage().toLowerCase();
            if (msg.contains("no such message")
                    || msg.contains("not exist")) {
                callback.handleNoSuchMessage(this,
                        response.getMessage());
            } else {
                callback.handleError(this, response.getMessage());
            }
        }
    }

    private void handleUidlDataLine(String line) {
        if (".".equals(line)) {
            ServerUidlReplyHandler callback =
                    (ServerUidlReplyHandler) currentCallback;
            currentCallback = null;
            state = POP3State.TRANSACTION;
            callback.handleUidComplete(this);
        } else {
            ServerUidlReplyHandler callback =
                    (ServerUidlReplyHandler) currentCallback;
            parseUidlEntryMulti(line, callback);
        }
    }

    private void parseUidlEntry(String msg,
                                ServerUidlReplyHandler callback) {
        int spaceIdx = msg.indexOf(' ');
        if (spaceIdx > 0) {
            try {
                int num = Integer.parseInt(
                        msg.substring(0, spaceIdx));
                String uid = msg.substring(spaceIdx + 1).trim();
                callback.handleUid(this, num, uid);
                return;
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to parse UIDL response: " + msg, e);
            }
        }
        callback.handleError(this, msg);
    }

    private void parseUidlEntryMulti(String line,
                                     ServerUidlReplyHandler callback) {
        int spaceIdx = line.indexOf(' ');
        if (spaceIdx > 0) {
            try {
                int num = Integer.parseInt(
                        line.substring(0, spaceIdx));
                String uid = line.substring(spaceIdx + 1).trim();
                callback.handleUidEntry(num, uid);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to parse UIDL entry: " + line, e);
            }
        }
    }

    // ── RETR ──

    private void dispatchRetrReply(POP3Response response) {
        ServerRetrReplyHandler callback =
                (ServerRetrReplyHandler) currentCallback;

        if (response.isOk()) {
            state = POP3State.RETR_DATA;
            dotUnstuffer.reset();
            dotUnstufferActive = true;
        } else {
            currentCallback = null;
            state = POP3State.TRANSACTION;
            String msg = response.getMessage().toLowerCase();
            if (msg.contains("deleted")) {
                callback.handleMessageDeleted(this,
                        response.getMessage());
            } else {
                callback.handleNoSuchMessage(this,
                        response.getMessage());
            }
        }
    }

    // ── TOP ──

    private void dispatchTopReply(POP3Response response) {
        ServerTopReplyHandler callback =
                (ServerTopReplyHandler) currentCallback;

        if (response.isOk()) {
            state = POP3State.TOP_DATA;
            dotUnstuffer.reset();
            dotUnstufferActive = true;
        } else {
            currentCallback = null;
            state = POP3State.TRANSACTION;
            String msg = response.getMessage().toLowerCase();
            if (msg.contains("deleted")) {
                callback.handleMessageDeleted(this,
                        response.getMessage());
            } else {
                callback.handleNoSuchMessage(this,
                        response.getMessage());
            }
        }
    }

    // ── DELE ──

    private void dispatchDeleReply(POP3Response response) {
        ServerDeleReplyHandler callback =
                (ServerDeleReplyHandler) currentCallback;
        currentCallback = null;
        state = POP3State.TRANSACTION;

        if (response.isOk()) {
            callback.handleDeleted(this);
        } else {
            String msg = response.getMessage().toLowerCase();
            if (msg.contains("already deleted")
                    || msg.contains("already marked")) {
                callback.handleAlreadyDeleted(this,
                        response.getMessage());
            } else {
                callback.handleNoSuchMessage(this,
                        response.getMessage());
            }
        }
    }

    // ── RSET ──

    private void dispatchRsetReply(POP3Response response) {
        ServerRsetReplyHandler callback =
                (ServerRsetReplyHandler) currentCallback;
        currentCallback = null;
        state = POP3State.TRANSACTION;
        callback.handleResetOk(this);
    }

    // ── NOOP ──

    private void dispatchNoopReply(POP3Response response) {
        ServerNoopReplyHandler callback =
                (ServerNoopReplyHandler) currentCallback;
        currentCallback = null;
        state = POP3State.TRANSACTION;
        callback.handleOk(this);
    }
}
