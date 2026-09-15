/*
 * Pop3ClientProtocolHandler.java
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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.pop3.client.handler.ClientAuthExchange;
import org.bluezoo.gumdrop.pop3.client.handler.ClientAuthorizationState;
import org.bluezoo.gumdrop.pop3.client.handler.ClientPasswordState;
import org.bluezoo.gumdrop.pop3.client.handler.ClientPostStls;
import org.bluezoo.gumdrop.pop3.client.handler.ClientTransactionState;
import org.bluezoo.gumdrop.pop3.client.handler.ApopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.AuthAbortHandler;
import org.bluezoo.gumdrop.pop3.client.handler.AuthReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.CapaReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.DeleReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.RemoteGreeting;
import org.bluezoo.gumdrop.pop3.client.handler.ListReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.NoopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.PassReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.RetrReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.RsetReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.StatReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.StlsReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.TopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.UidlReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.UserReplyHandler;

/**
 * POP3 client protocol handler (RFC 1939).
 *
 * <p>Implements a type-safe POP3 client state machine
 * ({@code ClientAuthorizationState}, {@code ClientTransactionState}, etc.)
 * and delegates all transport operations to a transport-agnostic
 * {@link Endpoint}.
 *
 * <p>Response line parsing uses a streaming {@link Pop3ClientLexer} (issue
 * #85): bytes are tokenised as they arrive rather than buffered into whole
 * lines — see {@link ByteStreamLexer}. Multi-line message content (RETR,
 * TOP) is dot-unstuffed transparently via {@link DotUnstuffer} (RFC 1939
 * section 3) and delivered as ByteBuffer chunks; {@link #receive} still
 * drives it directly from raw bytes exactly as before, since it already
 * processes dot-terminated, dot-stuffed content correctly in constant
 * memory across chunk boundaries — see {@link Pop3ClientLexer} for why
 * that is deliberately not reimplemented as a lexer escape.
 *
 * <p>Supported features:
 * <ul>
 *   <li>CAPA — RFC 2449 (capability discovery)</li>
 *   <li>STLS — RFC 2595 section 4 (STARTTLS upgrade)</li>
 *   <li>AUTH — RFC 5034 (SASL authentication)</li>
 *   <li>APOP — RFC 1939 section 7 (challenge-response auth)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see RemoteGreeting
 * @see Pop3ClientLexer
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 — POP3</a>
 */
public final class Pop3ClientProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<Pop3ClientLexer.Token>,
        DotUnstuffer.Callback,
        ClientAuthorizationState, ClientPasswordState,
        ClientPostStls, ClientTransactionState,
        ClientAuthExchange {

    private static final Logger LOGGER =
            Logger.getLogger(Pop3ClientProtocolHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.pop3.L10N");

    private static final String CRLF = "\r\n";

    private final RemoteGreeting handler;

    private Endpoint endpoint;
    private Pop3State state = Pop3State.DISCONNECTED;
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

    // Streaming lexer (issue #85) and per-line parse state. No overall
    // line-length cap: the pre-streaming LineParser.parse(data, this)
    // two-arg call already had none (delegates to Integer.MAX_VALUE) —
    // the client trusts the server it connected to, unlike the server
    // side defending against untrusted clients. The transport's own
    // maxNetInSize is therefore the only backstop, exactly as before.
    private final Pop3ClientLexer lexer = new Pop3ClientLexer(this, Integer.MAX_VALUE);
    private Pop3Response.Status pendingStatus;
    private String pendingWordText;
    private boolean pendingHasSp;
    private final StringBuilder textBuilder = new StringBuilder();

    /**
     * Creates a POP3 client protocol handler.
     *
     * @param handler the server greeting handler
     */
    public Pop3ClientProtocolHandler(RemoteGreeting handler) {
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
        state = Pop3State.CONNECTING;

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
                lexer.feed(data);
            }
        } else {
            lexer.feed(data);
        }
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(L10N.getString("info.pop3_client_disconnected"));
        }
        state = Pop3State.CLOSED;
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS established: " + info.getCipherSuite());
        }

        handler.onSecurityEstablished(info);

        if (currentCallback instanceof StlsReplyHandler) {
            StlsReplyHandler callback =
                    (StlsReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.AUTHORIZATION;
            callback.handleTlsEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "POP3 transport error", cause);
        }
        state = Pop3State.ERROR;
        handler.onError(cause);
    }

    // ── ByteStreamLexer.Handler implementation (issue #85) ──

    // RFC 1939 section 3: WORD [SP TEXT] CRLF. In CAPA_DATA/LIST_DATA/
    // UIDL_DATA state, WORD is the first field of a data line (or the
    // lone "." terminator) and the whole line is reconstructed verbatim
    // for the existing handleXxxDataLine(String) methods, unchanged. In
    // any other state, WORD is matched directly against the known status
    // markers — no String allocated, no decode — resolving to a
    // Pop3Response.Status enum at the token itself, the same pattern used
    // for command verbs on the server side.
    @Override
    public boolean token(Pop3ClientLexer.Token type, ByteBuffer window) {
        switch (type) {
            case WORD:
                if (isDataLineState()) {
                    pendingWordText = decodeLenient(window);
                    pendingStatus = null;
                } else {
                    pendingStatus = matchStatus(window);
                    pendingWordText = pendingStatus == null
                            ? decodeLenient(window) : null;
                }
                return false;
            case SP:
                pendingHasSp = true;
                return true; // latch text mode for the rest of the line
            case TEXT:
                // No cap: matches the pre-streaming unbounded behaviour
                // (see the lexer field's Javadoc).
                textBuilder.append(decodeLenient(window));
                return false;
            case CRLF:
                dispatchLine();
                return false;
            default:
                return false;
        }
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        // RETR/TOP content bypasses this lexer entirely (see
        // Pop3ClientLexer's class Javadoc) — structurally unreachable.
        LOGGER.warning(L10N.getString("warn.unexpected_raw_bytes_client"));
    }

    @Override
    public void tokenTooLong() {
        // maxTokenLength is Integer.MAX_VALUE for this lexer (no cap);
        // structurally unreachable.
        LOGGER.warning(L10N.getString("warn.unexpected_token_too_long_client"));
    }

    private boolean isDataLineState() {
        return state == Pop3State.CAPA_DATA
                || state == Pop3State.LIST_DATA
                || state == Pop3State.UIDL_DATA;
    }

    private static String decodeLenient(ByteBuffer window) {
        byte[] bytes = new byte[window.remaining()];
        window.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    // RFC 1939 section 3 — status markers: "+OK", "-ERR", or the SASL
    // continuation prefix "+" (RFC 5034 section 4). Matched directly
    // against the WORD token's raw bytes, case-sensitively (real servers
    // never vary case here, and the pre-streaming Pop3Response.parse()
    // this replaces was also case-sensitive via String.startsWith).
    private static Pop3Response.Status matchStatus(ByteBuffer window) {
        int len = window.remaining();
        int base = window.position();
        if (len == 1 && window.get(base) == '+') {
            return Pop3Response.Status.CONTINUATION;
        }
        if (len == 3 && window.get(base) == '+'
                && window.get(base + 1) == 'O' && window.get(base + 2) == 'K') {
            return Pop3Response.Status.OK;
        }
        if (len == 4 && window.get(base) == '-' && window.get(base + 1) == 'E'
                && window.get(base + 2) == 'R' && window.get(base + 3) == 'R') {
            return Pop3Response.Status.ERR;
        }
        return null;
    }

    private void resetLineState() {
        pendingStatus = null;
        pendingWordText = null;
        pendingHasSp = false;
        textBuilder.setLength(0);
    }

    // A complete response or data line has been lexed; dispatch it
    // exactly as the pre-streaming handleResponseLine(String) did.
    private void dispatchLine() {
        Pop3Response.Status status = pendingStatus;
        String wordText = pendingWordText != null ? pendingWordText : "";
        String text = textBuilder.toString();
        boolean hadSp = pendingHasSp;
        boolean dataLineMode = isDataLineState();
        resetLineState();

        try {
            if (dataLineMode) {
                String line = hadSp ? (wordText + " " + text) : wordText;
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
                        return;
                }
            }

            if (status == null) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    String line = hadSp ? (wordText + " " + text) : wordText;
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.unparseable_pop3_response"), line));
                }
                return;
            }

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Received POP3 response: "
                        + (hadSp ? (wordText + " " + text) : wordText));
            }

            dispatchResponse(new Pop3Response(status, text));
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "Error handling POP3 response", e);
            }
            handler.onError(e);
        }
    }

    // ── DotUnstuffer.Callback ──

    @Override
    public void content(ByteBuffer data) {
        if (state == Pop3State.RETR_DATA) {
            RetrReplyHandler retrCallback =
                    (RetrReplyHandler) currentCallback;
            retrCallback.handleMessageContent(data);
            if (retrCallback.wantsPause()) {
                retrCallback.setResumeCallback(
                        new ContentResumeTask());
                endpoint.pauseRead();
            }
        } else if (state == Pop3State.TOP_DATA) {
            TopReplyHandler topCallback =
                    (TopReplyHandler) currentCallback;
            topCallback.handleTopContent(data);
            if (topCallback.wantsPause()) {
                topCallback.setResumeCallback(
                        new ContentResumeTask());
                endpoint.pauseRead();
            }
        }
    }

    @Override
    public void complete() {
        dotUnstufferActive = false;

        if (state == Pop3State.RETR_DATA) {
            RetrReplyHandler callback =
                    (RetrReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.TRANSACTION;
            callback.handleMessageComplete(this);
        } else if (state == Pop3State.TOP_DATA) {
            TopReplyHandler callback =
                    (TopReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.TRANSACTION;
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
                && state != Pop3State.DISCONNECTED
                && state != Pop3State.CLOSED
                && state != Pop3State.ERROR;
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (state == Pop3State.CLOSED) {
            return;
        }
        state = Pop3State.CLOSED;
        dotUnstuffer.reset();
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ClientAuthorizationState (RFC 1939 section 4) ──

    // RFC 2449 section 5 — CAPA command
    @Override
    public void capa(CapaReplyHandler callback) {
        this.currentCallback = callback;
        capaLines.clear();
        sendCommand("CAPA", Pop3State.CAPA_SENT);
    }

    // RFC 1939 section 7 — USER command
    @Override
    public void user(String username, UserReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("USER " + username, Pop3State.USER_SENT);
    }

    // RFC 1939 section 7 — APOP command
    @Override
    public void apop(String username, String digest,
                     ApopReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("APOP " + username + " " + digest,
                Pop3State.APOP_SENT);
    }

    // RFC 5034 section 4 — AUTH command with optional initial response
    @Override
    public void auth(String mechanism, byte[] initialResponse,
                     AuthReplyHandler callback) {
        this.currentCallback = callback;

        StringBuilder cmd = new StringBuilder("AUTH ");
        cmd.append(mechanism);
        if (initialResponse != null) {
            cmd.append(" ");
            cmd.append(Base64.getEncoder()
                    .encodeToString(initialResponse));
        }
        sendCommand(cmd.toString(), Pop3State.AUTH_SENT);
    }

    // RFC 2595 section 4 — STLS command
    @Override
    public void stls(StlsReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("STLS", Pop3State.STLS_SENT);
    }

    // ── ClientPasswordState (RFC 1939 section 7 — PASS after USER) ──

    // RFC 1939 section 7 — PASS command
    @Override
    public void pass(String password, PassReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("PASS " + password, Pop3State.PASS_SENT);
    }

    // ── ClientTransactionState (RFC 1939 section 5) ──

    // RFC 1939 section 5 — STAT command
    @Override
    public void stat(StatReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("STAT", Pop3State.STAT_SENT);
    }

    // RFC 1939 section 5 — LIST command (all messages)
    @Override
    public void list(ListReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = true;
        sendCommand("LIST", Pop3State.LIST_SENT);
    }

    // RFC 1939 section 5 — LIST command (single message)
    @Override
    public void list(int messageNumber,
                     ListReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = false;
        sendCommand("LIST " + messageNumber, Pop3State.LIST_SENT);
    }

    // RFC 1939 section 5 — RETR command
    @Override
    public void retr(int messageNumber,
                     RetrReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RETR " + messageNumber, Pop3State.RETR_SENT);
    }

    // RFC 1939 section 5 — DELE command
    @Override
    public void dele(int messageNumber,
                     DeleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("DELE " + messageNumber, Pop3State.DELE_SENT);
    }

    // RFC 1939 section 5 — RSET command
    @Override
    public void rset(RsetReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RSET", Pop3State.RSET_SENT);
    }

    // RFC 1939 section 7 — TOP command
    @Override
    public void top(int messageNumber, int lines,
                    TopReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("TOP " + messageNumber + " " + lines,
                Pop3State.TOP_SENT);
    }

    // RFC 1939 section 7 — UIDL command (all messages)
    @Override
    public void uidl(UidlReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = true;
        sendCommand("UIDL", Pop3State.UIDL_SENT);
    }

    // RFC 1939 section 7 — UIDL command (single message)
    @Override
    public void uidl(int messageNumber,
                     UidlReplyHandler callback) {
        this.currentCallback = callback;
        this.multiLineList = false;
        sendCommand("UIDL " + messageNumber, Pop3State.UIDL_SENT);
    }

    // RFC 1939 section 5 — NOOP command
    @Override
    public void noop(NoopReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("NOOP", Pop3State.NOOP_SENT);
    }

    // RFC 1939 section 6 — QUIT command
    @Override
    public void quit() {
        sendCommand("QUIT", Pop3State.QUIT_SENT);
    }

    // ── ClientAuthExchange (RFC 5034 section 4 — SASL continuation) ──

    // RFC 5034 section 4 — send SASL response to server challenge
    @Override
    public void respond(byte[] response,
                        AuthReplyHandler callback) {
        this.currentCallback = callback;
        String encoded = Base64.getEncoder().encodeToString(response);
        sendRawLine(encoded, Pop3State.AUTH_SENT);
    }

    // RFC 5034 section 4 — abort SASL exchange with "*"
    @Override
    public void abort(AuthAbortHandler callback) {
        this.currentCallback = callback;
        sendRawLine("*", Pop3State.AUTH_ABORT_SENT);
    }

    // ── Command sending ──

    private void sendCommand(String command, Pop3State newState) {
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

    private void sendRawLine(String line, Pop3State newState) {
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

    private void dispatchResponse(Pop3Response response) {
        if (state == Pop3State.CLOSED
                || state == Pop3State.DISCONNECTED) {
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
                state = Pop3State.CLOSED;
                close();
                break;
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.unexpected_response_in_state"), state, response));
                }
        }
    }

    // ── Greeting ──

    // RFC 1939 section 4 — parse server greeting (+OK or -ERR)
    private void dispatchGreeting(Pop3Response response) {
        if (response.isOk()) {
            state = Pop3State.AUTHORIZATION;
            String message = response.getMessage();
            String apopTimestamp = parseApopTimestamp(message);
            handler.onConnected(endpoint);
            handler.handleGreeting(this, message, apopTimestamp);
        } else {
            state = Pop3State.ERROR;
            handler.handleServiceUnavailable(response.getMessage());
            close();
        }
    }

    // RFC 1939 section 7 — extract APOP timestamp from greeting
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

    private void dispatchCapaReply(Pop3Response response) {
        if (response.isOk()) {
            state = Pop3State.CAPA_DATA;
            capaLines.clear();
            resetCapabilities();
        } else {
            CapaReplyHandler callback =
                    (CapaReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.AUTHORIZATION;
            callback.handleError(this, response.getMessage());
        }
    }

    private void handleCapaDataLine(String line) {
        if (".".equals(line)) {
            parseCapabilities(capaLines);

            CapaReplyHandler callback =
                    (CapaReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.AUTHORIZATION;
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

    private void dispatchUserReply(Pop3Response response) {
        UserReplyHandler callback =
                (UserReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = Pop3State.AUTHORIZATION;
            callback.handleUserAccepted(this);
        } else {
            state = Pop3State.AUTHORIZATION;
            callback.handleRejected(this, response.getMessage());
        }
    }

    // ── PASS ──

    private void dispatchPassReply(Pop3Response response) {
        PassReplyHandler callback =
                (PassReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = Pop3State.TRANSACTION;
            callback.handleAuthenticated(this);
        } else {
            state = Pop3State.AUTHORIZATION;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    // ── APOP ──

    private void dispatchApopReply(Pop3Response response) {
        ApopReplyHandler callback =
                (ApopReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = Pop3State.TRANSACTION;
            callback.handleAuthenticated(this);
        } else {
            state = Pop3State.AUTHORIZATION;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    // ── STLS ──

    private void dispatchStlsReply(Pop3Response response) {
        if (response.isOk()) {
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                StlsReplyHandler callback =
                        (StlsReplyHandler) currentCallback;
                currentCallback = null;
                state = Pop3State.AUTHORIZATION;
                callback.handleTlsUnavailable(this);
            }
        } else {
            StlsReplyHandler callback =
                    (StlsReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.AUTHORIZATION;
            callback.handleTlsUnavailable(this);
        }
    }

    // ── AUTH ──

    private void dispatchAuthReply(Pop3Response response) {
        AuthReplyHandler callback =
                (AuthReplyHandler) currentCallback;

        if (response.isOk()) {
            currentCallback = null;
            state = Pop3State.TRANSACTION;
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
            state = Pop3State.AUTHORIZATION;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    // ── AUTH abort ──

    private void dispatchAuthAbortReply(Pop3Response response) {
        AuthAbortHandler callback =
                (AuthAbortHandler) currentCallback;
        currentCallback = null;
        state = Pop3State.AUTHORIZATION;
        callback.handleAborted(this);
    }

    // ── STAT ──

    private void dispatchStatReply(Pop3Response response) {
        StatReplyHandler callback =
                (StatReplyHandler) currentCallback;
        currentCallback = null;
        state = Pop3State.TRANSACTION;

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

    private void dispatchListReply(Pop3Response response) {
        ListReplyHandler callback =
                (ListReplyHandler) currentCallback;

        if (response.isOk()) {
            if (multiLineList) {
                state = Pop3State.LIST_DATA;
            } else {
                currentCallback = null;
                state = Pop3State.TRANSACTION;
                parseListEntry(response.getMessage(), callback);
            }
        } else {
            currentCallback = null;
            state = Pop3State.TRANSACTION;
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
            ListReplyHandler callback =
                    (ListReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.TRANSACTION;
            callback.handleListComplete(this);
        } else {
            ListReplyHandler callback =
                    (ListReplyHandler) currentCallback;
            parseListEntryMulti(line, callback);
        }
    }

    private void parseListEntry(String msg,
                                ListReplyHandler callback) {
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
                                     ListReplyHandler callback) {
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

    private void dispatchUidlReply(Pop3Response response) {
        UidlReplyHandler callback =
                (UidlReplyHandler) currentCallback;

        if (response.isOk()) {
            if (multiLineList) {
                state = Pop3State.UIDL_DATA;
            } else {
                currentCallback = null;
                state = Pop3State.TRANSACTION;
                parseUidlEntry(response.getMessage(), callback);
            }
        } else {
            currentCallback = null;
            state = Pop3State.TRANSACTION;
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
            UidlReplyHandler callback =
                    (UidlReplyHandler) currentCallback;
            currentCallback = null;
            state = Pop3State.TRANSACTION;
            callback.handleUidComplete(this);
        } else {
            UidlReplyHandler callback =
                    (UidlReplyHandler) currentCallback;
            parseUidlEntryMulti(line, callback);
        }
    }

    private void parseUidlEntry(String msg,
                                UidlReplyHandler callback) {
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
                                     UidlReplyHandler callback) {
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

    private void dispatchRetrReply(Pop3Response response) {
        RetrReplyHandler callback =
                (RetrReplyHandler) currentCallback;

        if (response.isOk()) {
            state = Pop3State.RETR_DATA;
            dotUnstuffer.reset();
            dotUnstufferActive = true;
            lexer.stopForHandoff();
        } else {
            currentCallback = null;
            state = Pop3State.TRANSACTION;
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

    private void dispatchTopReply(Pop3Response response) {
        TopReplyHandler callback =
                (TopReplyHandler) currentCallback;

        if (response.isOk()) {
            state = Pop3State.TOP_DATA;
            dotUnstuffer.reset();
            dotUnstufferActive = true;
            lexer.stopForHandoff();
        } else {
            currentCallback = null;
            state = Pop3State.TRANSACTION;
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

    private void dispatchDeleReply(Pop3Response response) {
        DeleReplyHandler callback =
                (DeleReplyHandler) currentCallback;
        currentCallback = null;
        state = Pop3State.TRANSACTION;

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

    private void dispatchRsetReply(Pop3Response response) {
        RsetReplyHandler callback =
                (RsetReplyHandler) currentCallback;
        currentCallback = null;
        state = Pop3State.TRANSACTION;
        callback.handleResetOk(this);
    }

    // ── NOOP ──

    private void dispatchNoopReply(Pop3Response response) {
        NoopReplyHandler callback =
                (NoopReplyHandler) currentCallback;
        currentCallback = null;
        state = Pop3State.TRANSACTION;
        callback.handleOk(this);
    }

    /**
     * Task to resume reading after a message content handler
     * signals readiness for more data.
     */
    private class ContentResumeTask implements Runnable {
        @Override
        public void run() {
            endpoint.resumeRead();
        }
    }
}
