/*
 * SMTPClientConnection.java
 * Copyright (C) 2025 Chris Burdess
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.handler.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;

/**
 * Event-driven, NIO-based SMTP client connection.
 * 
 * <p>This implementation provides a stage-based interface for SMTP client
 * operations. The connection implements various {@code Client*State} interfaces
 * that constrain which operations are valid at each stage of the SMTP protocol.
 * 
 * <p>The handler implements {@code Server*ReplyHandler} interfaces to receive
 * parsed responses from the server. Each response handler method receives the
 * appropriate state interface for the next stage of the protocol.
 * 
 * <p>Key features:
 * <ul>
 * <li>Type-safe state machine prevents out-of-order commands</li>
 * <li>Streaming message content without memory buffering</li>
 * <li>Automatic dot stuffing across chunk boundaries</li>
 * <li>Automatic base64 encoding/decoding for AUTH</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 */
class SMTPClientConnection extends Connection 
    implements WritableByteChannel, ClientHelloState, ClientSession, ClientPostTls,
               ClientAuthExchange, ClientEnvelope, ClientEnvelopeReady, ClientMessageData {
    
    private static final Logger logger = Logger.getLogger(SMTPClientConnection.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");
    
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final String CRLF = "\r\n";
    
    // SMTP Protocol state
    private SMTPState state = SMTPState.DISCONNECTED;
    
    protected final SMTPClient client;
    protected final ServerGreeting handler;

    // Protocol handling
    private final DotStuffer dotStuffer;
    private ByteBuffer readBuffer;
    
    // Current callback waiting for a response
    private Object currentCallback;
    
    // Multi-line response accumulation (for EHLO)
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
    
    // BDAT mode (uses CHUNKING instead of DATA when available)
    private boolean chunkingEnabled = true;  // Can be disabled for testing/compatibility
    private boolean useBdat;
    private int bdatPendingResponses;  // Count of BDAT responses we're waiting for
    
    /**
     * Creates SMTP client connection.
     * Threading and networking handled by Connection framework.
     */
    protected SMTPClientConnection(SMTPClient client, SocketChannel channel, 
                                   SSLEngine engine, boolean secure, ServerGreeting handler) {
        super(engine, secure);
        this.client = client;
        this.handler = handler;
        this.dotStuffer = new DotStuffer();
        this.readBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        this.multiLineResponse = new ArrayList<>();
    }
    
    /**
     * Sets whether to use BDAT (CHUNKING) when the server supports it.
     *
     * <p>When enabled (default), the client will use the BDAT command instead of
     * DATA if the server advertises CHUNKING support. Disable this for testing
     * or when connecting to servers with buggy CHUNKING implementations.
     *
     * @param enabled true to enable CHUNKING, false to always use DATA
     */
    void setChunkingEnabled(boolean enabled) {
        this.chunkingEnabled = enabled;
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Connection state methods
    // ─────────────────────────────────────────────────────────────────────────
    
    public boolean isConnected() {
        return state != SMTPState.DISCONNECTED && state != SMTPState.CLOSED && state != SMTPState.ERROR;
    }
    
    SMTPState getState() {
        return state;
    }
    
    @Override
    public boolean isOpen() {
        return isConnected();
    }
    
    @Override
    public void close() {
        if (state == SMTPState.CLOSED) {
            return;
        }
        
        logger.fine("Closing SMTP client connection in state: " + state);
        state = SMTPState.CLOSED;
        
        super.close();
        dotStuffer.reset();
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Connection lifecycle methods (called by Connection framework)
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void connected() {
        state = SMTPState.CONNECTING;
        
        if (!secure) {
            // Plaintext connection - wait for server greeting
            logger.fine("Client socket connected, waiting for server greeting");
            handler.onConnected(createConnectionInfo());
        } else {
            // SMTPS (implicit TLS) - initiate TLS handshake first
            // The server greeting will come after TLS is established
            logger.fine("TCP connected, initiating TLS handshake for SMTPS");
            initiateClientTLSHandshake();
            handler.onConnected(createConnectionInfo());
        }
    }
    
    @Override
    public void finishConnectFailed(IOException cause) {
        handleConnectionError(cause);
    }
    
    @Override
    public void receive(ByteBuffer buf) {
        try {
            appendToReadBuffer(buf);
            
            String line;
            while ((line = extractCompleteLine()) != null) {
                handleReplyLine(line);
            }
        } catch (Exception e) {
            handleError(new SMTPException("Error processing received data", e));
        }
    }
    
    @Override
    protected void disconnected() throws IOException {
        logger.info("SMTP connection disconnected");
        state = SMTPState.CLOSED;
        handler.onDisconnected();
    }

    @Override
    protected void handshakeComplete(String protocol) {
        super.handshakeComplete(protocol);
        logger.fine("TLS handshake complete, protocol: " + protocol);
        
        // Notify handler of TLS establishment
        handler.onTLSStarted(createTLSInfo());
        
        // After TLS, dispatch to the STARTTLS callback
        if (currentCallback instanceof ServerStarttlsReplyHandler) {
            ServerStarttlsReplyHandler callback = (ServerStarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = SMTPState.CONNECTED;
            callback.handleTlsEstablished(this);
        }
    }
    
    
    // ─────────────────────────────────────────────────────────────────────────
    // WritableByteChannel implementation (for DotStuffer)
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!isConnected()) {
            throw new IOException(L10N.getString("err.not_connected"));
        }
        
        int bytesToWrite = src.remaining();
        ByteBuffer copy = ByteBuffer.allocate(bytesToWrite);
        copy.put(src);
        copy.flip();
        send(copy);
        return bytesToWrite;
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientHelloState implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void ehlo(String hostname, ServerEhloReplyHandler callback) {
        this.currentCallback = callback;
        resetEhloCapabilities();
        sendCommand("EHLO " + hostname, SMTPState.EHLO_SENT);
    }
    
    @Override
    public void helo(String hostname, ServerHeloReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("HELO " + hostname, SMTPState.HELO_SENT);
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientSession implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void mailFrom(EmailAddress sender, ServerMailFromReplyHandler callback) {
        mailFrom(sender, 0, callback);
    }
    
    @Override
    public void mailFrom(EmailAddress sender, long size, ServerMailFromReplyHandler callback) {
        this.currentCallback = callback;
        this.acceptedRecipients = 0;
        
        StringBuilder cmd = new StringBuilder("MAIL FROM:<");
        cmd.append(sender != null ? sender.getEnvelopeAddress() : "");
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
    public void auth(String mechanism, byte[] initialResponse, ServerAuthReplyHandler callback) {
        this.currentCallback = callback;
        
        StringBuilder cmd = new StringBuilder("AUTH ");
        cmd.append(mechanism);
        
        if (initialResponse != null) {
            cmd.append(" ");
            cmd.append(Base64.getEncoder().encodeToString(initialResponse));
        }
        
        sendCommand(cmd.toString(), SMTPState.AUTH_SENT);
    }
    
    @Override
    public void quit() {
        sendCommand("QUIT", SMTPState.QUIT_SENT);
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientPostTls implementation (same as HelloState for our purposes)
    // ─────────────────────────────────────────────────────────────────────────
    
    // ehlo() and quit() are already implemented above
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientAuthExchange implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void respond(byte[] response, ServerAuthReplyHandler callback) {
        this.currentCallback = callback;
        String encoded = Base64.getEncoder().encodeToString(response);
        sendRawLine(encoded, SMTPState.AUTH_SENT);
    }
    
    @Override
    public void abort(ServerAuthAbortHandler callback) {
        this.currentCallback = callback;
        sendRawLine("*", SMTPState.AUTH_ABORT_SENT);
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientEnvelope / ClientEnvelopeState implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void rcptTo(EmailAddress recipient, ServerRcptToReplyHandler callback) {
        this.currentCallback = callback;
        sendCommand("RCPT TO:<" + recipient.getEnvelopeAddress() + ">", SMTPState.RCPT_TO_SENT);
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
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientEnvelopeReady implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void data(ServerDataReplyHandler callback) {
        if (acceptedRecipients == 0) {
            throw new IllegalStateException(L10N.getString("err.no_accepted_recipients"));
        }
        
        if (ehloChunking && chunkingEnabled) {
            // Use BDAT instead of DATA - no server roundtrip needed!
            // Content is streamed via writeContent() as BDAT chunks
            useBdat = true;
            bdatPendingResponses = 0;
            state = SMTPState.DATA_MODE;
            
            // Synthesize the "ready for data" callback immediately
            callback.handleReadyForData(this);
        } else {
            // Traditional DATA command
            useBdat = false;
            this.currentCallback = callback;
            sendCommand("DATA", SMTPState.DATA_COMMAND_SENT);
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // ClientMessageData implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    @Override
    public void writeContent(ByteBuffer content) {
        if (state != SMTPState.DATA_MODE) {
            throw new IllegalStateException(L10N.getString("err.not_in_data_mode"));
        }
        
        if (useBdat) {
            // BDAT mode: send chunk with exact byte count (no dot-stuffing needed)
            int size = content.remaining();
            if (size > 0) {
                sendBdatChunk(size, content);
                bdatPendingResponses++;
            }
        } else {
            // DATA mode: use dot-stuffer
            try {
                dotStuffer.processChunk(content, this);
            } catch (IOException e) {
                handleError(new SMTPException("Failed to write message content", e));
            }
        }
    }
    
    /**
     * Sends a BDAT chunk command followed by the content.
     */
    private void sendBdatChunk(int size, ByteBuffer content) {
        String command = "BDAT " + size + "\r\n";
        ByteBuffer cmdBuffer = ByteBuffer.wrap(command.getBytes(StandardCharsets.US_ASCII));
        send(cmdBuffer);
        send(content);
    }
    
    @Override
    public void endMessage(ServerMessageReplyHandler callback) {
        if (state != SMTPState.DATA_MODE) {
            throw new IllegalStateException(L10N.getString("err.not_in_data_mode"));
        }
        
        this.currentCallback = callback;
        
        if (useBdat) {
            // BDAT mode: send final empty chunk with LAST flag
            sendCommand("BDAT 0 LAST", SMTPState.DATA_END_SENT);
            bdatPendingResponses++;
        } else {
            // DATA mode: send CRLF.CRLF terminator
            try {
                dotStuffer.endMessage(this);
                state = SMTPState.DATA_END_SENT;
            } catch (IOException e) {
                handleError(new SMTPException("Failed to end message", e));
            }
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Internal: Command sending
    // ─────────────────────────────────────────────────────────────────────────
    
    private void sendCommand(String command, SMTPState newState) {
        if (!isConnected()) {
            handler.onError(new SMTPException("Not connected"));
            return;
        }
        
        this.state = newState;
        
        String fullCommand = command + CRLF;
        ByteBuffer commandBuffer = ByteBuffer.wrap(fullCommand.getBytes(StandardCharsets.US_ASCII));
        send(commandBuffer);
        
        if (logger.isLoggable(Level.FINE)) {
            // Don't log passwords in AUTH commands
            if (command.startsWith("AUTH ")) {
                logger.fine("Sent SMTP command: AUTH ***");
            } else {
                logger.fine("Sent SMTP command: " + command);
            }
        }
    }
    
    private void sendRawLine(String line, SMTPState newState) {
        if (!isConnected()) {
            handler.onError(new SMTPException("Not connected"));
            return;
        }
        
        this.state = newState;
        
        String fullLine = line + CRLF;
        ByteBuffer buffer = ByteBuffer.wrap(fullLine.getBytes(StandardCharsets.US_ASCII));
        send(buffer);
        logger.fine("Sent SMTP line: ***");
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Internal: Response parsing and buffering
    // ─────────────────────────────────────────────────────────────────────────
    
    private void appendToReadBuffer(ByteBuffer newData) {
        int newDataSize = newData.remaining();
        int currentDataSize = readBuffer.position();
        int requiredCapacity = currentDataSize + newDataSize;
        
        if (requiredCapacity > readBuffer.capacity()) {
            ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(requiredCapacity, readBuffer.capacity() * 2));
            readBuffer.flip();
            newBuffer.put(readBuffer);
            readBuffer = newBuffer;
        }
        
        readBuffer.put(newData);
    }
    
    private String extractCompleteLine() throws IOException {
        readBuffer.flip();
        
        int crlfIndex = -1;
        for (int i = 0; i < readBuffer.limit() - 1; i++) {
            if (readBuffer.get(i) == '\r' && readBuffer.get(i + 1) == '\n') {
                crlfIndex = i;
                break;
            }
        }
        
        if (crlfIndex >= 0) {
            byte[] lineBytes = new byte[crlfIndex];
            readBuffer.get(lineBytes);
            readBuffer.get(); // skip CR
            readBuffer.get(); // skip LF
            readBuffer.compact();
            return new String(lineBytes, StandardCharsets.US_ASCII);
        } else {
            readBuffer.compact();
            return null;
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Internal: Response handling and dispatch
    // ─────────────────────────────────────────────────────────────────────────
    
    private void handleReplyLine(String line) {
        try {
            if (line.length() < 3) {
                throw new SMTPException(MessageFormat.format(
                        L10N.getString("err.invalid_smtp_response"), line));
            }
            
            int code = Integer.parseInt(line.substring(0, 3));
            boolean continuation = line.length() > 3 && line.charAt(3) == '-';
            String message = line.length() > 4 ? line.substring(4) : "";
            
            // Check for 421 service closing at any point
            if (code == 421) {
                handle421ServiceClosing(message);
                return;
            }
            
            if (continuation) {
                // Multi-line response, accumulate
                if (!inMultiLineResponse) {
                    inMultiLineResponse = true;
                    multiLineResponse.clear();
                }
                multiLineResponse.add(message);
            } else {
                // Final line of response
                if (inMultiLineResponse) {
                    multiLineResponse.add(message);
                    inMultiLineResponse = false;
                    dispatchResponse(code, multiLineResponse);
                    multiLineResponse.clear();
                } else {
                    // Single-line response
                    List<String> singleLine = new ArrayList<>();
                    singleLine.add(message);
                    dispatchResponse(code, singleLine);
                }
            }
        } catch (NumberFormatException e) {
            handleError(new SMTPException("Invalid SMTP response code: " + line, e));
        } catch (Exception e) {
            handleError(new SMTPException("Failed to parse SMTP response: " + line, e));
        }
    }
    
    private void handle421ServiceClosing(String message) {
        state = SMTPState.CLOSED;
        
        // Notify current callback if it implements ServerReplyHandler
        if (currentCallback instanceof ServerReplyHandler) {
            ((ServerReplyHandler) currentCallback).handleServiceClosing(message);
        } else {
            // No current callback, notify the main handler
            handler.handleServiceUnavailable("421 " + message);
        }
        
        close();
    }
    
    private void dispatchResponse(int code, List<String> messages) {
        // Ignore responses after connection is closed
        if (state == SMTPState.CLOSED || state == SMTPState.DISCONNECTED) {
            logger.fine("Ignoring response in state " + state + ": " + code);
            return;
        }
        
        String message = messages.isEmpty() ? "" : messages.get(0);
        
        logger.fine("Received SMTP response: " + code + " " + message);
        
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
                // In BDAT mode, we receive 250 responses for each chunk during DATA_MODE
                if (useBdat) {
                    dispatchBdatChunkReply(code, message);
                } else {
                    logger.warning("Unexpected response in DATA_MODE: " + code + " " + message);
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
                logger.warning("Unexpected response in state " + state + ": " + code + " " + message);
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Internal: State-specific dispatch methods
    // ─────────────────────────────────────────────────────────────────────────
    
    private void dispatchGreeting(int code, String message) {
        if (code == 220) {
            state = SMTPState.CONNECTED;
            boolean esmtp = message.toUpperCase().contains("ESMTP");
            handler.handleGreeting(message, esmtp, this);
        } else {
            state = SMTPState.ERROR;
            handler.handleServiceUnavailable(code + " " + message);
            close();
        }
    }
    
    private void dispatchEhloReply(int code, List<String> messages) {
        ServerEhloReplyHandler callback = (ServerEhloReplyHandler) currentCallback;
        currentCallback = null;
        
        if (code == 250) {
            // Parse capabilities from multi-line response
            parseEhloCapabilities(messages);
            state = SMTPState.CONNECTED;
            callback.handleEhlo(ehloStarttls, ehloMaxSize, ehloAuthMethods, ehloPipelining, this);
        } else if (code == 502) {
            // EHLO not implemented, fall back to HELO
            state = SMTPState.CONNECTED;
            callback.handleEhloNotSupported(this);
        } else if (code >= 500) {
            state = SMTPState.ERROR;
            callback.handlePermanentFailure(messages.isEmpty() ? "" : messages.get(0));
            close();
        } else {
            // Unexpected code
            state = SMTPState.ERROR;
            callback.handlePermanentFailure(code + " " + (messages.isEmpty() ? "" : messages.get(0)));
            close();
        }
    }
    
    private void dispatchHeloReply(int code, String message) {
        ServerHeloReplyHandler callback = (ServerHeloReplyHandler) currentCallback;
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
        ServerStarttlsReplyHandler callback = (ServerStarttlsReplyHandler) currentCallback;
        // Don't clear currentCallback yet - handshakeComplete/handshakeFailed will use it
        
        if (code == 220) {
            // Server is ready for TLS, initiate handshake
            try {
                initializeSSLState();
                // Start the TLS handshake - this sends the ClientHello
                initiateClientTLSHandshake();
                // Callback will be invoked from handshakeComplete() or handshakeFailed()
            } catch (IOException e) {
                currentCallback = null;
                state = SMTPState.CONNECTED;
                callback.handleTlsUnavailable(this);
            }
        } else if (code == 454 || code == 502) {
            // TLS not available
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
        ServerAuthReplyHandler callback = (ServerAuthReplyHandler) currentCallback;
        currentCallback = null;
        
        if (code == 235) {
            // Auth success
            state = SMTPState.CONNECTED;
            callback.handleAuthSuccess(this);
        } else if (code == 334) {
            // Challenge - decode and pass to handler
            byte[] challenge = Base64.getDecoder().decode(message);
            callback.handleChallenge(challenge, this);
        } else if (code == 535) {
            // Auth failed
            state = SMTPState.CONNECTED;
            callback.handleAuthFailed(this);
        } else if (code == 504) {
            // Mechanism not supported
            state = SMTPState.CONNECTED;
            callback.handleMechanismNotSupported(this);
        } else if (code == 454) {
            // Temporary failure
            state = SMTPState.CONNECTED;
            callback.handleTemporaryFailure(this);
        } else {
            // Other error - treat as auth failed
            state = SMTPState.CONNECTED;
            callback.handleAuthFailed(this);
        }
    }
    
    private void dispatchAuthAbortReply(int code, String message) {
        ServerAuthAbortHandler callback = (ServerAuthAbortHandler) currentCallback;
        currentCallback = null;
        state = SMTPState.CONNECTED;
        callback.handleAborted(this);
    }
    
    private void dispatchMailFromReply(int code, String message) {
        ServerMailFromReplyHandler callback = (ServerMailFromReplyHandler) currentCallback;
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
        ServerRcptToReplyHandler callback = (ServerRcptToReplyHandler) currentCallback;
        currentCallback = null;
        
        if (code == 250 || code == 251 || code == 252) {
            acceptedRecipients++;
            state = SMTPState.RCPT_TO_ACCEPTED;
            callback.handleRcptToOk(this);
        } else if (code >= 400 && code < 500) {
            // Temporary failure - state depends on whether we have recipients
            state = acceptedRecipients > 0 ? SMTPState.RCPT_TO_ACCEPTED : SMTPState.MAIL_FROM_ACCEPTED;
            callback.handleTemporaryFailure(this);
        } else {
            // Permanent rejection
            state = acceptedRecipients > 0 ? SMTPState.RCPT_TO_ACCEPTED : SMTPState.MAIL_FROM_ACCEPTED;
            callback.handleRecipientRejected(this);
        }
    }
    
    private void dispatchDataReply(int code, String message) {
        ServerDataReplyHandler callback = (ServerDataReplyHandler) currentCallback;
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
    
    private void dispatchBdatChunkReply(int code, String message) {
        // Handle intermediate BDAT chunk responses (during DATA_MODE)
        bdatPendingResponses--;
        
        if (code == 250) {
            // Chunk accepted, continue - nothing to do
        } else if (code >= 400 && code < 500) {
            // Temporary failure on chunk - abort the transaction
            state = SMTPState.CONNECTED;
            useBdat = false;
            if (currentCallback instanceof ServerMessageReplyHandler) {
                ServerMessageReplyHandler callback = (ServerMessageReplyHandler) currentCallback;
                currentCallback = null;
                callback.handleTemporaryFailure(this);
            }
        } else if (code >= 500) {
            // Permanent failure on chunk - abort the transaction
            state = SMTPState.CONNECTED;
            useBdat = false;
            if (currentCallback instanceof ServerMessageReplyHandler) {
                ServerMessageReplyHandler callback = (ServerMessageReplyHandler) currentCallback;
                currentCallback = null;
                callback.handlePermanentFailure(message, this);
            }
        }
    }
    
    private void dispatchMessageReply(int code, String message) {
        ServerMessageReplyHandler callback = (ServerMessageReplyHandler) currentCallback;
        currentCallback = null;
        useBdat = false;  // Reset BDAT mode
        
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
        ServerRsetReplyHandler callback = (ServerRsetReplyHandler) currentCallback;
        currentCallback = null;
        state = SMTPState.CONNECTED;
        acceptedRecipients = 0;
        callback.handleResetOk(this);
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Internal: EHLO capability parsing
    // ─────────────────────────────────────────────────────────────────────────
    
    private void resetEhloCapabilities() {
        ehloStarttls = false;
        ehloMaxSize = 0;
        ehloAuthMethods = new ArrayList<>();
        ehloPipelining = false;
        ehloChunking = false;
    }
    
    private void parseEhloCapabilities(List<String> lines) {
        for (String line : lines) {
            String upper = line.toUpperCase();
            
            if (upper.equals("STARTTLS")) {
                ehloStarttls = true;
            } else if (upper.startsWith("SIZE")) {
                try {
                    if (upper.length() > 5) {
                        ehloMaxSize = Long.parseLong(line.substring(5).trim());
                    }
                } catch (NumberFormatException e) {
                    // Ignore malformed SIZE
                }
            } else if (upper.startsWith("AUTH")) {
                // AUTH PLAIN LOGIN CRAM-MD5 ...
                String[] parts = line.split("\\s+");
                for (int i = 1; i < parts.length; i++) {
                    ehloAuthMethods.add(parts[i].toUpperCase());
                }
            } else if (upper.equals("PIPELINING")) {
                ehloPipelining = true;
            } else if (upper.equals("CHUNKING")) {
                ehloChunking = true;
            }
        }
    }
    
    private String parseQueueId(String message) {
        // Common format: "Ok: queued as ABC123" or "250 2.0.0 ABC123 Message accepted"
        // Try to extract the queue ID
        if (message == null || message.isEmpty()) {
            return null;
        }
        
        // Look for common patterns
        String[] patterns = {"queued as ", "Message accepted for delivery "};
        for (String pattern : patterns) {
            int idx = message.toLowerCase().indexOf(pattern.toLowerCase());
            if (idx >= 0) {
                String remainder = message.substring(idx + pattern.length()).trim();
                // Take first word as queue ID
                int space = remainder.indexOf(' ');
                return space > 0 ? remainder.substring(0, space) : remainder;
            }
        }
        
        // Fall back to returning the whole message
        return null;
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Internal: Error handling
    // ─────────────────────────────────────────────────────────────────────────
    
    private void handleError(SMTPException error) {
        logger.warning("SMTP error: " + error.getMessage());
        state = SMTPState.ERROR;
        handler.onError(error);
    }
    
    private void handleConnectionError(IOException cause) {
        handleError(new SMTPException("Connection failed", cause));
    }
}
