/*
 * FTPClientProtocolHandler.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ftp.client.handler.ClientAccountState;
import org.bluezoo.gumdrop.ftp.client.handler.ClientAuthenticatedState;
import org.bluezoo.gumdrop.ftp.client.handler.ClientLoginState;
import org.bluezoo.gumdrop.ftp.client.handler.ClientPasswordState;
import org.bluezoo.gumdrop.ftp.client.handler.ServerAcctReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerAuthTlsReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerCwdReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.ftp.client.handler.ServerMkdReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerPassReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerPwdReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerSimpleReplyHandler;
import org.bluezoo.gumdrop.ftp.client.handler.ServerUserReplyHandler;

/**
 * FTP client protocol handler implementing RFC 959 (FTP) control-connection
 * commands, following the same architecture as {@code
 * SMTPClientProtocolHandler} and {@code POP3ClientProtocolHandler}.
 *
 * <p>Implements a type-safe FTP client state machine ({@code
 * ClientLoginState}, {@code ClientAuthenticatedState}, etc.) and delegates
 * all transport operations to a transport-agnostic {@link Endpoint}.
 *
 * <p>Reply parsing uses a streaming {@link FTPClientLexer} (issue #85):
 * bytes are tokenised as they arrive rather than buffered into whole
 * lines — see {@link ByteStreamLexer}. FTP replies share SMTP's {@code
 * CODE SEP TEXT CRLF} grammar (RFC 959 §4.2), including multi-line
 * continuation via a dash after the code.
 *
 * <p>This handler covers the control-connection-only commands: USER, PASS,
 * ACCT, AUTH TLS, CWD, CDUP, PWD, TYPE, STRU, MODE, DELE, RMD, MKD, QUIT.
 * Data-connection commands (PASV/EPSV/PORT/EPRT, RETR/STOR/APPE/LIST/
 * NLST/MLSD) are added once the data-connection coordinator is in place.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see ServerGreeting
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959 - FTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4217">RFC 4217 - AUTH TLS</a>
 */
public class FTPClientProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<FTPClientLexer.Token>,
        ClientLoginState, ClientPasswordState, ClientAccountState,
        ClientAuthenticatedState {

    private static final Logger LOGGER =
            Logger.getLogger(FTPClientProtocolHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");

    private static final String CRLF = "\r\n";

    private final ServerGreeting handler;

    private Endpoint endpoint;
    private FTPState state = FTPState.DISCONNECTED;
    private boolean secure;

    // Current callback waiting for a response
    private Object currentCallback;

    // Multi-line response accumulation
    private final List<String> multiLineResponse = new ArrayList<String>();
    private boolean inMultiLineResponse;

    // Streaming lexer (issue #85) and per-line parse state. No cap on
    // structured tokens: this client trusts the remote server, same
    // principle as SMTPClientLexer/POP3ClientLexer.
    private final FTPClientLexer lexer = new FTPClientLexer(this, Integer.MAX_VALUE);
    private boolean pendingHasCode;
    private int pendingCode;
    private String pendingCodeError;
    private final StringBuilder replyTextBuilder = new StringBuilder();
    private boolean pendingContinuation;

    /**
     * Creates an FTP client protocol handler.
     *
     * @param handler the server greeting handler
     */
    public FTPClientProtocolHandler(ServerGreeting handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
    }

    /**
     * Sets whether this connection started in secure mode.
     *
     * @param secure true for implicit TLS (e.g., FTPS port 990)
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    // ── ProtocolHandler (RFC 959 §4.1.1 — session initiation) ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        state = FTPState.CONNECTING;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("FTP client connected to " + ep.getRemoteAddress());
        }
    }

    /** RFC 959 §4.2 — replies are line-oriented: code SP text CRLF. */
    @Override
    public void receive(ByteBuffer data) {
        lexer.feed(data);
    }

    @Override
    public void disconnected() {
        LOGGER.info(L10N.getString("client.info.connection_disconnected"));
        state = FTPState.CLOSED;
        handler.onDisconnected();
    }

    /** RFC 4217 §4 — TLS handshake complete on the control connection. */
    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS established: " + info.getCipherSuite());
        }

        if (currentCallback instanceof ServerAuthTlsReplyHandler) {
            ServerAuthTlsReplyHandler callback =
                    (ServerAuthTlsReplyHandler) currentCallback;
            currentCallback = null;
            state = FTPState.CONNECTED;
            callback.handleTlsEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        handleError(new FTPException("Connection error", cause));
    }

    // ── ByteStreamLexer.Handler implementation (issue #85) ──

    // RFC 959 §4.2: CODE [SEP TEXT] CRLF, identical grammar to SMTP (RFC
    // 5321 §4.2) — see SMTPClientProtocolHandler.token() for the mirrored
    // implementation this is based on.
    @Override
    public boolean token(FTPClientLexer.Token type, ByteBuffer window) {
        switch (type) {
            case CODE:
                pendingHasCode = true;
                if (window.remaining() != 3) {
                    pendingCodeError = MessageFormat.format(
                            L10N.getString("err.invalid_ftp_response"),
                            decodeAscii(window));
                } else {
                    try {
                        pendingCode = parseCode(window);
                    } catch (NumberFormatException e) {
                        pendingCodeError = MessageFormat.format(
                                L10N.getString("err.invalid_ftp_response"),
                                decodeAscii(window));
                    }
                }
                return false;
            case DASH:
                pendingContinuation = true;
                return true; // latch text mode for the rest of the line
            case SP:
                pendingContinuation = false;
                return true; // latch text mode for the rest of the line
            case TEXT:
                replyTextBuilder.append(decodeAscii(window));
                return false;
            case CRLF:
                lexer.resetForNextLine();
                dispatchLine();
                return false;
            default:
                return false;
        }
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        // The FTP control connection never carries raw binary content —
        // file content flows over the separate data connection — so the
        // lexer never enters a raw escape and this is structurally
        // unreachable.
        LOGGER.warning("Unexpected rawBytes() call on FTP client lexer");
    }

    @Override
    public void tokenTooLong() {
        // FTPClientLexer is constructed with an unbounded per-token cap
        // (Integer.MAX_VALUE) — this client trusts the remote server —
        // so this is structurally unreachable.
        LOGGER.warning("Unexpected tokenTooLong() call on FTP client lexer");
    }

    private static String decodeAscii(ByteBuffer window) {
        byte[] bytes = new byte[window.remaining()];
        window.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static int parseCode(ByteBuffer window) {
        int base = window.position();
        int code = 0;
        for (int i = 0; i < 3; i++) {
            byte b = window.get(base + i);
            if (b < '0' || b > '9') {
                throw new NumberFormatException();
            }
            code = code * 10 + (b - '0');
        }
        return code;
    }

    private void resetLineState() {
        pendingHasCode = false;
        pendingCode = 0;
        pendingCodeError = null;
        pendingContinuation = false;
        replyTextBuilder.setLength(0);
    }

    // RFC 959 §4.2 — a complete reply line has been lexed; the code was
    // already resolved from the CODE token's bytes.
    private void dispatchLine() {
        boolean hasCode = pendingHasCode;
        int code = pendingCode;
        String error = pendingCodeError;
        boolean continuation = pendingContinuation;
        String message = replyTextBuilder.toString();
        resetLineState();

        if (!hasCode) {
            // A bare CRLF with no reply code at all — silently ignored.
            return;
        }

        try {
            if (error != null) {
                throw new FTPException(error);
            }

            if (code == 421) {
                handle421ServiceClosing(message);
                return;
            }

            if (continuation) {
                if (!inMultiLineResponse) {
                    inMultiLineResponse = true;
                    multiLineResponse.clear();
                }
                multiLineResponse.add(message);
            } else {
                if (inMultiLineResponse) {
                    multiLineResponse.add(message);
                    inMultiLineResponse = false;
                    dispatchResponse(code, multiLineResponse);
                    multiLineResponse.clear();
                } else {
                    List<String> singleLine = new ArrayList<String>();
                    singleLine.add(message);
                    dispatchResponse(code, singleLine);
                }
            }
        } catch (FTPException e) {
            handleError(e);
        } catch (Exception e) {
            handleError(new FTPException("Failed to parse FTP response", e));
        }
    }

    // ── Connection state ──

    /**
     * Returns whether the connection is in a usable state.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return state != FTPState.DISCONNECTED
                && state != FTPState.CLOSED
                && state != FTPState.ERROR;
    }

    /**
     * Returns whether the connection is open.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        return isConnected() && endpoint != null && endpoint.isOpen();
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (state == FTPState.CLOSED) {
            return;
        }
        state = FTPState.CLOSED;
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ClientLoginState (RFC 959 §4.1.1 / RFC 4217 §4) ──

    /** RFC 959 §4.1.1 — USER command. */
    @Override
    public void user(String username, ServerUserReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("USER " + username, FTPState.USER_SENT);
    }

    /** RFC 4217 §4 — AUTH TLS command. */
    @Override
    public void authTls(ServerAuthTlsReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("AUTH TLS", FTPState.AUTH_TLS_SENT);
    }

    /** RFC 959 §4.1.1 — QUIT command. */
    @Override
    public void quit() {
        sendCommand("QUIT", FTPState.QUIT_SENT);
    }

    // ── ClientPasswordState (RFC 959 §4.1.1 — PASS after USER) ──

    /** RFC 959 §4.1.1 — PASS command. */
    @Override
    public void pass(String password, ServerPassReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("PASS " + password, FTPState.PASS_SENT);
    }

    // ── ClientAccountState (RFC 959 §4.1.1 — ACCT after 332) ──

    /** RFC 959 §4.1.1 — ACCT command. */
    @Override
    public void acct(String account, ServerAcctReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("ACCT " + account, FTPState.ACCT_SENT);
    }

    // ── ClientAuthenticatedState (RFC 959 §4.1.2 / §4.1.3) ──

    /** RFC 959 §4.1.3 — CWD command. */
    @Override
    public void cwd(String pathname, ServerCwdReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("CWD " + pathname, FTPState.CWD_SENT);
    }

    /** RFC 959 §4.1.3 — CDUP command. */
    @Override
    public void cdup(ServerSimpleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("CDUP", FTPState.CDUP_SENT);
    }

    /** RFC 959 §4.1.3 — PWD command. */
    @Override
    public void pwd(ServerPwdReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("PWD", FTPState.PWD_SENT);
    }

    /** RFC 959 §4.1.2 — TYPE command. */
    @Override
    public void type(String type, ServerSimpleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("TYPE " + type, FTPState.TYPE_SENT);
    }

    /** RFC 959 §4.1.2 — STRU command. */
    @Override
    public void stru(String structure, ServerSimpleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("STRU " + structure, FTPState.STRU_SENT);
    }

    /** RFC 959 §4.1.2 — MODE command. */
    @Override
    public void mode(String mode, ServerSimpleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("MODE " + mode, FTPState.MODE_SENT);
    }

    /** RFC 959 §4.1.3 — DELE command. */
    @Override
    public void dele(String pathname, ServerSimpleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("DELE " + pathname, FTPState.DELE_SENT);
    }

    /** RFC 959 §4.1.3 — RMD command. */
    @Override
    public void rmd(String pathname, ServerSimpleReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RMD " + pathname, FTPState.RMD_SENT);
    }

    /** RFC 959 §4.1.3 — MKD command. */
    @Override
    public void mkd(String pathname, ServerMkdReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("MKD " + pathname, FTPState.MKD_SENT);
    }

    // ── Command sending ──

    private static void rejectCrlf(String text) {
        if (text != null && (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("FTP command must not contain CRLF");
        }
    }

    private void sendCommand(String command, FTPState newState) {
        if (!isConnected()) {
            handler.onError(new FTPException("Not connected"));
            return;
        }
        rejectCrlf(command);

        this.state = newState;

        String full = command + CRLF;
        ByteBuffer buf = ByteBuffer.wrap(full.getBytes(StandardCharsets.US_ASCII));
        endpoint.send(buf);

        if (LOGGER.isLoggable(Level.FINE)) {
            if (command.startsWith("PASS ")) {
                LOGGER.fine("Sent FTP command: PASS ***");
            } else {
                LOGGER.fine("Sent FTP command: " + command);
            }
        }
    }

    // ── Response handling ──

    /** RFC 959 §4.2 — 421 service not available, closing control connection. */
    private void handle421ServiceClosing(String message) {
        state = FTPState.CLOSED;

        if (currentCallback instanceof ServerReplyHandler) {
            ((ServerReplyHandler) currentCallback).handleServiceClosing(message);
        } else {
            handler.handleServiceUnavailable("421 " + message);
        }

        close();
    }

    private void dispatchResponse(int code, List<String> messages) {
        if (state == FTPState.CLOSED || state == FTPState.DISCONNECTED) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Ignoring response in state " + state + ": " + code);
            }
            return;
        }

        String message = messages.isEmpty() ? "" : messages.get(0);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Received FTP response: " + code + " " + message);
        }

        switch (state) {
            case CONNECTING:
                dispatchGreeting(code, message);
                break;
            case USER_SENT:
                dispatchUserReply(code, message);
                break;
            case PASS_SENT:
                dispatchPassReply(code, message);
                break;
            case ACCT_SENT:
                dispatchAcctReply(code, message);
                break;
            case AUTH_TLS_SENT:
                dispatchAuthTlsReply(code, message);
                break;
            case CWD_SENT:
                dispatchCwdReply(code, message);
                break;
            case CDUP_SENT:
                dispatchSimpleReply(code, message);
                break;
            case PWD_SENT:
                dispatchPwdReply(code, message);
                break;
            case TYPE_SENT:
            case STRU_SENT:
            case MODE_SENT:
            case DELE_SENT:
            case RMD_SENT:
                dispatchSimpleReply(code, message);
                break;
            case MKD_SENT:
                dispatchMkdReply(code, message);
                break;
            case QUIT_SENT:
                state = FTPState.CLOSED;
                close();
                break;
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unexpected response in state "
                            + state + ": " + code + " " + message);
                }
        }
    }

    /** RFC 959 §4.2 — 220 greeting or service unavailable. */
    private void dispatchGreeting(int code, String message) {
        if (code == 220) {
            state = FTPState.CONNECTED;
            handler.onConnected(endpoint);
            handler.handleGreeting(this, message);
        } else {
            state = FTPState.ERROR;
            handler.handleServiceUnavailable(code + " " + message);
            close();
        }
    }

    /** RFC 959 §4.1.1 — 230 logged in, 331 need password, 332 need account. */
    private void dispatchUserReply(int code, String message) {
        ServerUserReplyHandler callback = (ServerUserReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 230) {
            state = FTPState.AUTHENTICATED;
            callback.handleUserAccepted(this);
        } else if (code == 331) {
            state = FTPState.CONNECTED;
            callback.handlePasswordRequired(this);
        } else if (code == 332) {
            state = FTPState.CONNECTED;
            callback.handleAccountRequired(this);
        } else {
            state = FTPState.CONNECTED;
            callback.handleRejected(this, message);
        }
    }

    /** RFC 959 §4.1.1 — 230 logged in, 332 need account, else rejected. */
    private void dispatchPassReply(int code, String message) {
        ServerPassReplyHandler callback = (ServerPassReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 230) {
            state = FTPState.AUTHENTICATED;
            callback.handleAuthenticated(this);
        } else if (code == 332) {
            state = FTPState.CONNECTED;
            callback.handleAccountRequired(this);
        } else {
            state = FTPState.CONNECTED;
            callback.handleAuthFailed(this, message);
        }
    }

    /** RFC 959 §4.1.1 — 230 logged in, else rejected. */
    private void dispatchAcctReply(int code, String message) {
        ServerAcctReplyHandler callback = (ServerAcctReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 230) {
            state = FTPState.AUTHENTICATED;
            callback.handleAuthenticated(this);
        } else {
            state = FTPState.CONNECTED;
            callback.handleAuthFailed(this, message);
        }
    }

    /** RFC 4217 §4 — 234 ready for TLS, else unavailable. */
    private void dispatchAuthTlsReply(int code, String message) {
        ServerAuthTlsReplyHandler callback = (ServerAuthTlsReplyHandler) currentCallback;

        if (code == 234) {
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                currentCallback = null;
                state = FTPState.CONNECTED;
                callback.handleTlsUnavailable(this);
            }
        } else {
            currentCallback = null;
            state = FTPState.CONNECTED;
            callback.handleTlsUnavailable(this);
        }
    }

    /** RFC 959 §4.1.3 — 250 directory changed, else error. */
    private void dispatchCwdReply(int code, String message) {
        ServerCwdReplyHandler callback = (ServerCwdReplyHandler) currentCallback;
        currentCallback = null;
        state = FTPState.AUTHENTICATED;

        if (code == 250) {
            callback.handleOk(this);
        } else {
            callback.handleError(this, code, message);
        }
    }

    /** RFC 959 §4.1.3 — 257 "pathname" [commentary]. */
    private void dispatchPwdReply(int code, String message) {
        ServerPwdReplyHandler callback = (ServerPwdReplyHandler) currentCallback;
        currentCallback = null;
        state = FTPState.AUTHENTICATED;

        if (code == 257) {
            callback.handlePathname(parseQuotedPathname(message), this);
        } else {
            callback.handleError(this, code, message);
        }
    }

    /** RFC 959 §4.1.3 — 257 "pathname" [commentary]. */
    private void dispatchMkdReply(int code, String message) {
        ServerMkdReplyHandler callback = (ServerMkdReplyHandler) currentCallback;
        currentCallback = null;
        state = FTPState.AUTHENTICATED;

        if (code == 257) {
            callback.handlePathname(parseQuotedPathname(message), this);
        } else {
            callback.handleError(this, code, message);
        }
    }

    /** CDUP/TYPE/STRU/MODE/DELE/RMD — plain 2xx success, else error. */
    private void dispatchSimpleReply(int code, String message) {
        ServerSimpleReplyHandler callback = (ServerSimpleReplyHandler) currentCallback;
        currentCallback = null;
        state = FTPState.AUTHENTICATED;

        if (code >= 200 && code < 300) {
            callback.handleOk(this);
        } else {
            callback.handleError(this, code, message);
        }
    }

    /**
     * Parses a 257 reply's quoted pathname, undoing RFC 959 §4.1.3's
     * doubled-quote escaping (a literal {@code "} inside the pathname is
     * sent as {@code ""}). Any trailing commentary after the closing quote
     * is discarded.
     *
     * @param message the reply text following the 257 code, e.g.
     *      {@code "/foo/bar" is current directory}
     * @return the unescaped pathname, or the raw message if it is not
     *      quoted (lenient, in case a server omits the quotes)
     */
    private static String parseQuotedPathname(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) != '"') {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        int len = message.length();
        int i = 1;
        while (i < len) {
            char c = message.charAt(i);
            if (c == '"') {
                if (i + 1 < len && message.charAt(i + 1) == '"') {
                    sb.append('"');
                    i += 2;
                    continue;
                }
                break; // closing quote
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ── Error handling ──

    private void handleError(FTPException error) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("FTP client error: " + error.getMessage());
        }
        state = FTPState.ERROR;
        handler.onError(error);
    }
}
