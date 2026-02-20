/*
 * SMTPClientProtocolHandler.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.handler.ClientAuthExchange;
import org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelope;
import org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeReady;
import org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState;
import org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData;
import org.bluezoo.gumdrop.smtp.client.handler.ClientPostTls;
import org.bluezoo.gumdrop.smtp.client.handler.ClientSession;
import org.bluezoo.gumdrop.smtp.client.handler.ServerAuthAbortHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerAuthReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerDataReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerEhloReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.smtp.client.handler.ServerHeloReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerMailFromReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerMessageReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerRcptToReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerRsetReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerStarttlsReplyHandler;

/**
 * SMTP client protocol handler implementing {@link ProtocolHandler}.
 *
 * <p>Implements a type-safe SMTP client state machine
 * ({@code ClientHelloState}, {@code ClientSession}, etc.) and
 * delegates all transport operations to a transport-agnostic
 * {@link Endpoint}.
 *
 * <p>Line parsing is handled by the composable {@link LineParser} utility.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * ClientEndpoint client = new ClientEndpoint(factory, "smtp.example.com", 587);
 * client.connect(new SMTPClientProtocolHandler(new ServerGreeting() {
 *     public void handleGreeting(ClientHelloState hello, String msg, boolean esmtp) {
 *         hello.ehlo("myhostname", ehloHandler);
 *     }
 *     public void handleServiceUnavailable(String msg) { ... }
 * }));
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see ServerGreeting
 */
public class SMTPClientProtocolHandler
        implements ProtocolHandler, LineParser.Callback,
        WritableByteChannel, ClientHelloState, ClientSession,
        ClientPostTls, ClientAuthExchange, ClientEnvelope,
        ClientEnvelopeReady, ClientMessageData {

    private static final Logger LOGGER =
            Logger.getLogger(SMTPClientProtocolHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private static final String CRLF = "\r\n";

    private final ServerGreeting handler;
    private final DotStuffer dotStuffer;

    private Endpoint endpoint;
    private SMTPState state = SMTPState.DISCONNECTED;
    private boolean secure;

    // Current callback waiting for a response
    private Object currentCallback;

    // Multi-line response accumulation
    private List<String> multiLineResponse;
    private boolean inMultiLineResponse;

    // EHLO capability parsing
    private boolean ehloStarttls;
    private long ehloMaxSize;
    private List<String> ehloAuthMethods;
    private boolean ehloPipelining;
    private boolean ehloChunking;

    // Envelope state
    private int acceptedRecipients;

    // BDAT mode
    private boolean chunkingEnabled = true;
    private boolean useBdat;
    private int bdatPendingResponses;

    // Line assembly buffer for LineParser
    private StringBuilder lineBuilder;

    /**
     * Creates an SMTP client endpoint handler.
     *
     * @param handler the server greeting handler
     */
    public SMTPClientProtocolHandler(ServerGreeting handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.dotStuffer = new DotStuffer();
        this.multiLineResponse = new ArrayList<String>();
        this.lineBuilder = new StringBuilder();
    }

    /**
     * Sets whether to use BDAT (CHUNKING) when the server supports it.
     *
     * @param enabled true to enable CHUNKING
     */
    public void setChunkingEnabled(boolean enabled) {
        this.chunkingEnabled = enabled;
    }

    /**
     * Returns whether BDAT (CHUNKING) is enabled.
     *
     * @return true if enabled
     */
    public boolean isChunkingEnabled() {
        return chunkingEnabled;
    }

    /**
     * Sets whether this connection started in secure mode.
     *
     * @param secure true for implicit TLS (e.g., SMTPS port 465)
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        state = SMTPState.CONNECTING;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("SMTP client connected to "
                    + ep.getRemoteAddress());
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        LineParser.parse(data, this);
    }

    @Override
    public void disconnected() {
        LOGGER.info(L10N.getString("client.info.connection_disconnected"));
        state = SMTPState.CLOSED;
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS established: " + info.getCipherSuite());
        }

        if (currentCallback instanceof ServerStarttlsReplyHandler) {
            ServerStarttlsReplyHandler callback =
                    (ServerStarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = SMTPState.CONNECTED;
            callback.handleTlsEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        handleError(new SMTPException("Connection error", cause));
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
        line.get();
        line.get();
        String text = new String(bytes, StandardCharsets.US_ASCII);
        handleReplyLine(text);
    }

    @Override
    public boolean continueLineProcessing() {
        return true;
    }

    // ── Connection state ──

    /**
     * Returns whether the connection is in a usable state.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return state != SMTPState.DISCONNECTED
                && state != SMTPState.CLOSED
                && state != SMTPState.ERROR;
    }

    // ── WritableByteChannel (for DotStuffer) ──

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!isConnected()) {
            throw new IOException(L10N.getString("err.not_connected"));
        }
        int bytesToWrite = src.remaining();
        ByteBuffer copy = ByteBuffer.allocate(bytesToWrite);
        copy.put(src);
        copy.flip();
        endpoint.send(copy);
        return bytesToWrite;
    }

    @Override
    public boolean isOpen() {
        return isConnected() && endpoint != null && endpoint.isOpen();
    }

    @Override
    public void close() {
        if (state == SMTPState.CLOSED) {
            return;
        }
        state = SMTPState.CLOSED;
        dotStuffer.reset();
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ClientHelloState ──

    @Override
    public void ehlo(String hostname,
                     ServerEhloReplyHandler callback) {
        this.currentCallback = callback;
        resetEhloCapabilities();
        sendCommand("EHLO " + hostname, SMTPState.EHLO_SENT);
    }

    @Override
    public void helo(String hostname,
                     ServerHeloReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("HELO " + hostname, SMTPState.HELO_SENT);
    }

    // ── ClientSession ──

    @Override
    public void mailFrom(EmailAddress sender,
                         ServerMailFromReplyHandler callback) {
        mailFrom(sender, 0, callback);
    }

    @Override
    public void mailFrom(EmailAddress sender, long size,
                         ServerMailFromReplyHandler callback) {
        this.currentCallback = callback;
        this.acceptedRecipients = 0;

        StringBuilder cmd = new StringBuilder("MAIL FROM:<");
        if (sender != null) {
            cmd.append(sender.getEnvelopeAddress());
        }
        cmd.append(">");

        if (size > 0 && ehloMaxSize > 0) {
            cmd.append(" SIZE=").append(size);
        }

        sendCommand(cmd.toString(), SMTPState.MAIL_FROM_SENT);
    }

    @Override
    public void starttls(ServerStarttlsReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("STARTTLS", SMTPState.STARTTLS_SENT);
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

        sendCommand(cmd.toString(), SMTPState.AUTH_SENT);
    }

    @Override
    public void quit() {
        sendCommand("QUIT", SMTPState.QUIT_SENT);
    }

    // ── ClientAuthExchange ──

    @Override
    public void respond(byte[] response,
                        ServerAuthReplyHandler callback) {
        this.currentCallback = callback;
        String encoded = Base64.getEncoder().encodeToString(response);
        sendRawLine(encoded, SMTPState.AUTH_SENT);
    }

    @Override
    public void abort(ServerAuthAbortHandler callback) {
        this.currentCallback = callback;
        sendRawLine("*", SMTPState.AUTH_ABORT_SENT);
    }

    // ── ClientEnvelope ──

    @Override
    public void rcptTo(EmailAddress recipient,
                       ServerRcptToReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RCPT TO:<"
                + recipient.getEnvelopeAddress() + ">",
                SMTPState.RCPT_TO_SENT);
    }

    @Override
    public void rset(ServerRsetReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RSET", SMTPState.RSET_SENT);
    }

    @Override
    public boolean hasAcceptedRecipients() {
        return acceptedRecipients > 0;
    }

    // ── ClientEnvelopeReady ──

    @Override
    public void data(ServerDataReplyHandler callback) {
        if (acceptedRecipients == 0) {
            throw new IllegalStateException(
                    L10N.getString("err.no_accepted_recipients"));
        }

        if (ehloChunking && chunkingEnabled) {
            useBdat = true;
            bdatPendingResponses = 0;
            state = SMTPState.DATA_MODE;
            callback.handleReadyForData(this);
        } else {
            useBdat = false;
            this.currentCallback = callback;
            sendCommand("DATA", SMTPState.DATA_COMMAND_SENT);
        }
    }

    // ── ClientMessageData ──

    @Override
    public void writeContent(ByteBuffer content) {
        if (state != SMTPState.DATA_MODE) {
            throw new IllegalStateException(
                    L10N.getString("err.not_in_data_mode"));
        }

        if (useBdat) {
            int size = content.remaining();
            if (size > 0) {
                sendBdatChunk(size, content);
                bdatPendingResponses++;
            }
        } else {
            try {
                dotStuffer.processChunk(content, this);
            } catch (IOException e) {
                handleError(new SMTPException(
                        "Failed to write message content", e));
            }
        }
    }

    @Override
    public void endMessage(ServerMessageReplyHandler callback) {
        if (state != SMTPState.DATA_MODE) {
            throw new IllegalStateException(
                    L10N.getString("err.not_in_data_mode"));
        }

        this.currentCallback = callback;

        if (useBdat) {
            sendCommand("BDAT 0 LAST", SMTPState.DATA_END_SENT);
            bdatPendingResponses++;
        } else {
            try {
                dotStuffer.endMessage(this);
                state = SMTPState.DATA_END_SENT;
            } catch (IOException e) {
                handleError(new SMTPException(
                        "Failed to end message", e));
            }
        }
    }

    // ── Command sending ──

    private void sendCommand(String command, SMTPState newState) {
        if (!isConnected()) {
            handler.onError(new SMTPException("Not connected"));
            return;
        }

        this.state = newState;

        String full = command + CRLF;
        ByteBuffer buf = ByteBuffer.wrap(
                full.getBytes(StandardCharsets.US_ASCII));
        endpoint.send(buf);

        if (LOGGER.isLoggable(Level.FINE)) {
            if (command.startsWith("AUTH ")) {
                LOGGER.fine("Sent SMTP command: AUTH ***");
            } else {
                LOGGER.fine("Sent SMTP command: " + command);
            }
        }
    }

    private void sendRawLine(String line, SMTPState newState) {
        if (!isConnected()) {
            handler.onError(new SMTPException("Not connected"));
            return;
        }

        this.state = newState;

        String full = line + CRLF;
        ByteBuffer buf = ByteBuffer.wrap(
                full.getBytes(StandardCharsets.US_ASCII));
        endpoint.send(buf);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Sent SMTP line: ***");
        }
    }

    private void sendBdatChunk(int size, ByteBuffer content) {
        String command = "BDAT " + size + CRLF;
        ByteBuffer cmdBuf = ByteBuffer.wrap(
                command.getBytes(StandardCharsets.US_ASCII));
        endpoint.send(cmdBuf);
        endpoint.send(content);
    }

    // ── Response handling ──

    private void handleReplyLine(String line) {
        try {
            if (line.length() < 3) {
                throw new SMTPException(MessageFormat.format(
                        L10N.getString("err.invalid_smtp_response"),
                        line));
            }

            int code = Integer.parseInt(line.substring(0, 3));
            boolean continuation =
                    line.length() > 3 && line.charAt(3) == '-';
            String message =
                    line.length() > 4 ? line.substring(4) : "";

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
        } catch (NumberFormatException e) {
            handleError(new SMTPException(
                    "Invalid SMTP response code: " + line, e));
        } catch (SMTPException e) {
            handleError(e);
        } catch (Exception e) {
            handleError(new SMTPException(
                    "Failed to parse SMTP response: " + line, e));
        }
    }

    private void handle421ServiceClosing(String message) {
        state = SMTPState.CLOSED;

        if (currentCallback instanceof ServerReplyHandler) {
            ((ServerReplyHandler) currentCallback)
                    .handleServiceClosing(message);
        } else {
            handler.handleServiceUnavailable("421 " + message);
        }

        close();
    }

    private void dispatchResponse(int code, List<String> messages) {
        if (state == SMTPState.CLOSED
                || state == SMTPState.DISCONNECTED) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Ignoring response in state "
                        + state + ": " + code);
            }
            return;
        }

        String message = messages.isEmpty()
                ? "" : messages.get(0);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Received SMTP response: "
                    + code + " " + message);
        }

        switch (state) {
            case CONNECTING:
                dispatchGreeting(code, message);
                break;
            case EHLO_SENT:
                dispatchEhloReply(code, messages);
                break;
            case HELO_SENT:
                dispatchHeloReply(code, message);
                break;
            case STARTTLS_SENT:
                dispatchStarttlsReply(code, message);
                break;
            case AUTH_SENT:
                dispatchAuthReply(code, message);
                break;
            case AUTH_ABORT_SENT:
                dispatchAuthAbortReply(code, message);
                break;
            case MAIL_FROM_SENT:
                dispatchMailFromReply(code, message);
                break;
            case RCPT_TO_SENT:
                dispatchRcptToReply(code, message);
                break;
            case DATA_COMMAND_SENT:
                dispatchDataReply(code, message);
                break;
            case DATA_MODE:
                if (useBdat) {
                    dispatchBdatChunkReply(code, message);
                } else {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning(MessageFormat.format(
                                L10N.getString(
                                        "client.warn.unexpected_response_data_mode"),
                                code, message));
                    }
                }
                break;
            case DATA_END_SENT:
                dispatchMessageReply(code, message);
                break;
            case RSET_SENT:
                dispatchRsetReply(code, message);
                break;
            case QUIT_SENT:
                state = SMTPState.CLOSED;
                close();
                break;
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString(
                                    "client.warn.unexpected_response_in_state"),
                            state, code, message));
                }
        }
    }

    // ── State-specific dispatch ──

    private void dispatchGreeting(int code, String message) {
        if (code == 220) {
            state = SMTPState.CONNECTED;
            boolean esmtp =
                    message.toUpperCase().indexOf("ESMTP") >= 0;
            handler.handleGreeting(this, message, esmtp);
        } else {
            state = SMTPState.ERROR;
            handler.handleServiceUnavailable(
                    code + " " + message);
            close();
        }
    }

    private void dispatchEhloReply(int code,
                                   List<String> messages) {
        ServerEhloReplyHandler callback =
                (ServerEhloReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 250) {
            parseEhloCapabilities(messages);
            state = SMTPState.CONNECTED;
            callback.handleEhlo(this, ehloStarttls, ehloMaxSize,
                    ehloAuthMethods, ehloPipelining);
        } else if (code == 502) {
            state = SMTPState.CONNECTED;
            callback.handleEhloNotSupported(this);
        } else if (code >= 500) {
            state = SMTPState.ERROR;
            callback.handlePermanentFailure(
                    messages.isEmpty() ? "" : messages.get(0));
            close();
        } else {
            state = SMTPState.ERROR;
            callback.handlePermanentFailure(code + " "
                    + (messages.isEmpty() ? "" : messages.get(0)));
            close();
        }
    }

    private void dispatchHeloReply(int code, String message) {
        ServerHeloReplyHandler callback =
                (ServerHeloReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 250) {
            state = SMTPState.CONNECTED;
            callback.handleHelo(this);
        } else {
            state = SMTPState.ERROR;
            callback.handlePermanentFailure(message);
            close();
        }
    }

    private void dispatchStarttlsReply(int code, String message) {
        ServerStarttlsReplyHandler callback =
                (ServerStarttlsReplyHandler) currentCallback;

        if (code == 220) {
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                currentCallback = null;
                state = SMTPState.CONNECTED;
                callback.handleTlsUnavailable(this);
            }
        } else if (code == 454 || code == 502) {
            currentCallback = null;
            state = SMTPState.CONNECTED;
            callback.handleTlsUnavailable(this);
        } else if (code >= 500) {
            currentCallback = null;
            state = SMTPState.ERROR;
            callback.handlePermanentFailure(message);
            close();
        }
    }

    private void dispatchAuthReply(int code, String message) {
        ServerAuthReplyHandler callback =
                (ServerAuthReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 235) {
            state = SMTPState.CONNECTED;
            callback.handleAuthSuccess(this);
        } else if (code == 334) {
            byte[] challenge =
                    Base64.getDecoder().decode(message);
            callback.handleChallenge(challenge, this);
        } else if (code == 535) {
            state = SMTPState.CONNECTED;
            callback.handleAuthFailed(this);
        } else if (code == 504) {
            state = SMTPState.CONNECTED;
            callback.handleMechanismNotSupported(this);
        } else if (code == 454) {
            state = SMTPState.CONNECTED;
            callback.handleTemporaryFailure(this);
        } else {
            state = SMTPState.CONNECTED;
            callback.handleAuthFailed(this);
        }
    }

    private void dispatchAuthAbortReply(int code,
                                        String message) {
        ServerAuthAbortHandler callback =
                (ServerAuthAbortHandler) currentCallback;
        currentCallback = null;
        state = SMTPState.CONNECTED;
        callback.handleAborted(this);
    }

    private void dispatchMailFromReply(int code, String message) {
        ServerMailFromReplyHandler callback =
                (ServerMailFromReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 250) {
            state = SMTPState.MAIL_FROM_ACCEPTED;
            callback.handleMailFromOk(this);
        } else if (code >= 400 && code < 500) {
            state = SMTPState.CONNECTED;
            callback.handleTemporaryFailure(this);
        } else {
            state = SMTPState.CONNECTED;
            callback.handlePermanentFailure(message);
        }
    }

    private void dispatchRcptToReply(int code, String message) {
        ServerRcptToReplyHandler callback =
                (ServerRcptToReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 250 || code == 251 || code == 252) {
            acceptedRecipients++;
            state = SMTPState.RCPT_TO_ACCEPTED;
            callback.handleRcptToOk(this);
        } else if (code >= 400 && code < 500) {
            state = acceptedRecipients > 0
                    ? SMTPState.RCPT_TO_ACCEPTED
                    : SMTPState.MAIL_FROM_ACCEPTED;
            callback.handleTemporaryFailure(this);
        } else {
            state = acceptedRecipients > 0
                    ? SMTPState.RCPT_TO_ACCEPTED
                    : SMTPState.MAIL_FROM_ACCEPTED;
            callback.handleRecipientRejected(this);
        }
    }

    private void dispatchDataReply(int code, String message) {
        ServerDataReplyHandler callback =
                (ServerDataReplyHandler) currentCallback;
        currentCallback = null;

        if (code == 354) {
            state = SMTPState.DATA_MODE;
            dotStuffer.reset();
            callback.handleReadyForData(this);
        } else if (code >= 400 && code < 500) {
            state = SMTPState.RCPT_TO_ACCEPTED;
            callback.handleTemporaryFailure(this);
        } else {
            state = SMTPState.CONNECTED;
            callback.handlePermanentFailure(message);
        }
    }

    private void dispatchBdatChunkReply(int code,
                                        String message) {
        bdatPendingResponses--;

        if (code == 250) {
            // Chunk accepted
        } else if (code >= 400 && code < 500) {
            state = SMTPState.CONNECTED;
            useBdat = false;
            if (currentCallback
                    instanceof ServerMessageReplyHandler) {
                ServerMessageReplyHandler callback =
                        (ServerMessageReplyHandler) currentCallback;
                currentCallback = null;
                callback.handleTemporaryFailure(this);
            }
        } else if (code >= 500) {
            state = SMTPState.CONNECTED;
            useBdat = false;
            if (currentCallback
                    instanceof ServerMessageReplyHandler) {
                ServerMessageReplyHandler callback =
                        (ServerMessageReplyHandler) currentCallback;
                currentCallback = null;
                callback.handlePermanentFailure(message, this);
            }
        }
    }

    private void dispatchMessageReply(int code, String message) {
        ServerMessageReplyHandler callback =
                (ServerMessageReplyHandler) currentCallback;
        currentCallback = null;
        useBdat = false;

        if (code == 250) {
            state = SMTPState.CONNECTED;
            String queueId = parseQueueId(message);
            callback.handleMessageAccepted(queueId, this);
        } else if (code >= 400 && code < 500) {
            state = SMTPState.CONNECTED;
            callback.handleTemporaryFailure(this);
        } else {
            state = SMTPState.CONNECTED;
            callback.handlePermanentFailure(message, this);
        }
    }

    private void dispatchRsetReply(int code, String message) {
        ServerRsetReplyHandler callback =
                (ServerRsetReplyHandler) currentCallback;
        currentCallback = null;
        state = SMTPState.CONNECTED;
        acceptedRecipients = 0;
        callback.handleResetOk(this);
    }

    // ── EHLO capability parsing ──

    private void resetEhloCapabilities() {
        ehloStarttls = false;
        ehloMaxSize = 0;
        ehloAuthMethods = new ArrayList<String>();
        ehloPipelining = false;
        ehloChunking = false;
    }

    private void parseEhloCapabilities(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String upper = line.toUpperCase();

            if (upper.equals("STARTTLS")) {
                ehloStarttls = true;
            } else if (upper.startsWith("SIZE")) {
                if (upper.length() > 5) {
                    try {
                        ehloMaxSize = Long.parseLong(
                                line.substring(5).trim());
                    } catch (NumberFormatException e) {
                        // Ignore malformed SIZE
                    }
                }
            } else if (upper.startsWith("AUTH")) {
                int start = 4;
                int length = line.length();
                while (start < length) {
                    while (start < length
                            && Character.isWhitespace(
                            line.charAt(start))) {
                        start++;
                    }
                    if (start >= length) {
                        break;
                    }
                    int end = start;
                    while (end < length
                            && !Character.isWhitespace(
                            line.charAt(end))) {
                        end++;
                    }
                    ehloAuthMethods.add(
                            line.substring(start, end)
                                    .toUpperCase());
                    start = end;
                }
            } else if (upper.equals("PIPELINING")) {
                ehloPipelining = true;
            } else if (upper.equals("CHUNKING")) {
                ehloChunking = true;
            }
        }
    }

    private String parseQueueId(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        String lower = message.toLowerCase();
        String[] patterns = {
            "queued as ",
            "message accepted for delivery "
        };
        for (int i = 0; i < patterns.length; i++) {
            int idx = lower.indexOf(patterns[i]);
            if (idx >= 0) {
                String remainder = message.substring(
                        idx + patterns[i].length()).trim();
                int space = remainder.indexOf(' ');
                if (space > 0) {
                    return remainder.substring(0, space);
                }
                return remainder;
            }
        }

        return null;
    }

    // ── Error handling ──

    private void handleError(SMTPException error) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("client.warn.smtp_error"),
                    error.getMessage()));
        }
        state = SMTPState.ERROR;
        handler.onError(error);
    }
}
