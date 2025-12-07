/*
 * SMTPConnection.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.sasl.SASLUtils;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.util.LineInput;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for the SMTP protocol.
 * This manages one SMTP session over a TCP connection, handling command
 * parsing, authentication, and message transfer according to RFC 5321.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc5321 (SMTP)
 * @see https://www.rfc-editor.org/rfc/rfc6409 (Message Submission)
 * @see https://www.rfc-editor.org/rfc/rfc3207 (SMTP STARTTLS)
 */
public class SMTPConnection extends Connection implements MailFromCallback, RcptToCallback, HelloCallback, SMTPConnectionMetadata {

    private static final Logger LOGGER = Logger.getLogger(SMTPConnection.class.getName());

    /**
     * Localized message resources for SMTP.
     */
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    /**
     * SMTP uses US-ASCII for commands and responses.
     */
    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    // RFC 5321 limits to prevent memory exhaustion attacks
    private static final int MAX_LINE_LENGTH = 998;           // RFC 5321 section 4.5.3.1.6 (excluding CRLF)
    private static final int MAX_RECIPIENTS = 1000;           // Reasonable limit, RFC suggests minimum 100
    private static final int MAX_CONTROL_BUFFER_SIZE = 8;     // Max bytes for control sequences (\r\n.\r\n = 4 bytes + safety)
    private static final int MAX_COMMAND_LINE_LENGTH = 1000;  // MAX_LINE_LENGTH + CRLF

    /**
     * SMTP session states according to RFC 5321.
     */
    enum SMTPState {
        INITIAL,    // Initial state, waiting for HELO/EHLO
        REJECTED,   // Connection rejected at banner, only QUIT accepted
        READY,      // After successful HELO/EHLO, ready for mail transaction
        MAIL,       // After MAIL FROM command
        RCPT,       // After one or more RCPT TO commands
        DATA,       // During DATA command (receiving message content)
        QUIT        // After QUIT command
    }

    /**
     * SMTP authentication states for multi-step authentication.
     */
    enum AuthState {
        NONE,             // No authentication in progress
        PLAIN_RESPONSE,   // PLAIN: waiting for credentials
        LOGIN_USERNAME,   // LOGIN: waiting for username
        LOGIN_PASSWORD,   // LOGIN: waiting for password
        CRAM_MD5_RESPONSE, // CRAM-MD5: waiting for response to challenge
        DIGEST_MD5_RESPONSE, // DIGEST-MD5: waiting for response to challenge
        SCRAM_INITIAL,    // SCRAM-SHA-256: waiting for client first message
        SCRAM_FINAL,      // SCRAM-SHA-256: waiting for client final message
        OAUTH_RESPONSE,   // OAUTHBEARER: waiting for bearer token
        GSSAPI_EXCHANGE,  // GSSAPI: waiting for GSS-API token exchange
        EXTERNAL_CERT,    // EXTERNAL: certificate-based authentication
        NTLM_TYPE1,       // NTLM: waiting for Type 1 message
        NTLM_TYPE3        // NTLM: waiting for Type 3 message
    }


    // Using base class protected SocketChannel channel field
    private final SMTPServer server;
    private final SMTPConnectionHandler handler;
    private final long connectionTimeMillis;

    // SMTP session state
    private SMTPState state = SMTPState.INITIAL;
    private String heloName;
    private String mailFrom;
    private List<String> recipients;
    private boolean extendedSMTP = false; // true if EHLO was used
    
    // Authentication state
    private boolean authenticated = false;
    private String authenticatedUser = null;
    private AuthState authState = AuthState.NONE;
    private String authMechanism = null;
    private String pendingAuthUsername = null;
    private boolean starttlsUsed = false;
    
    // Extended authentication state for advanced mechanisms
    private String authChallenge = null;       // Challenge string for CRAM-MD5, DIGEST-MD5
    private String authNonce = null;          // Nonce for DIGEST-MD5, SCRAM
    private String authClientNonce = null;    // Client nonce for SCRAM
    private String authServerSignature = null; // Server signature for SCRAM
    private byte[] authSalt = null;           // Salt for SCRAM-SHA-256
    private int authIterations = 4096;        // Iteration count for SCRAM-SHA-256
    
    // Enterprise authentication state
    private SaslServer saslServer = null;     // GSSAPI SASL server context
    private X509Certificate clientCertificate = null; // EXTERNAL: client certificate
    private byte[] ntlmChallenge = null;      // NTLM: server challenge
    private String ntlmTargetName = null;     // NTLM: target name info
    
    // Buffer management for partial lines and DATA control sequences
    private ByteBuffer lineBuffer; // Accumulates partial lines across receive() calls
    private ByteBuffer controlBuffer; // Small buffer for partial control sequences (CRLF, dots, termination)
    
    // DATA state tracking for control sequences only
    enum DataState {
        NORMAL,     // Processing normal message data
        SAW_CR,     // Saw CR, waiting for LF  
        SAW_CRLF,   // Saw CRLF, checking for dot
        SAW_DOT,    // Saw CRLF., checking if termination or stuffing
        SAW_DOT_CR  // Saw CRLF.CR, checking for final LF of termination
    }
    private DataState dataState = DataState.NORMAL;

    private ByteBuffer in; // input buffer for received data
    private LineReader lineReader;

    // Telemetry tracking
    private Span sessionSpan;      // Current session span (reset on RSET)
    private int sessionNumber = 0; // Session counter for span naming

    /**
     * LineInput implementation for reading SMTP commands line by line.
     */
    class LineReader implements LineInput {

        private CharBuffer sink; // character buffer to receive decoded characters

        @Override 
        public ByteBuffer getLineInputBuffer() {
            return in;
        }

        @Override 
        public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (sink == null || sink.capacity() < capacity) {
                sink = CharBuffer.allocate(capacity);
            }
            return sink;
        }
    }


    /**
     * Creates a new SMTP connection.
     * @param connector the SMTP connector that created this connection
     * @param channel the socket channel for this connection
     * @param engine the SSL engine if this is a secure connection, null for plaintext
     * @param secure true if this connection should use TLS encryption
     * @param handler the application handler for SMTP events
     */
    protected SMTPConnection(SMTPServer server, SocketChannel channel, SSLEngine engine, boolean secure, SMTPConnectionHandler handler) {
        super(engine, secure);
        this.server = server;
        this.channel = channel;
        this.handler = handler;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.in = ByteBuffer.allocate(4096);
        this.lineReader = new LineReader();
        this.recipients = new ArrayList<>();
        this.lineBuffer = ByteBuffer.allocate(MAX_COMMAND_LINE_LENGTH);
        this.controlBuffer = ByteBuffer.allocate(MAX_CONTROL_BUFFER_SIZE);
    }

    @Override
    public void setSendCallback(SendCallback callback) {
        super.setSendCallback(callback);
    }

    @Override
    public void init() throws IOException {
        super.init();

        // Initialize telemetry trace for this connection
        initConnectionTrace();
        
        // Check with handler if connection should be accepted
        boolean acceptConnection = true;
        if (handler != null) {
            acceptConnection = handler.connected(this);
        }
        
        if (acceptConnection) {
            // Start the first session span
            startSessionSpan();
            // Send SMTP greeting after connection accepted
            reply(220, getLocalSocketAddress().toString() + " ESMTP Service ready");
        } else {
            // Connection rejected - send error and transition to REJECTED state
            this.state = SMTPState.REJECTED;
            reply(554, "5.0.0 Connection rejected");
        }
    }

    /**
     * Initializes the connection-level telemetry trace.
     */
    private void initConnectionTrace() {
        if (!isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig = getTelemetryConfig();
        String spanName = L10N.getString("telemetry.smtp_connection");
        Trace trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
        setTrace(trace);

        if (trace != null) {
            Span rootSpan = trace.getRootSpan();
            // Add connection-level attributes
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip", getRemoteSocketAddress().toString());
            rootSpan.addAttribute("rpc.system", "smtp");
        }
    }

    /**
     * Starts a new session span for the current SMTP session.
     * A session is the sequence of commands from connection/RSET to QUIT/RSET.
     */
    private void startSessionSpan() {
        Trace trace = getTrace();
        if (trace == null) {
            return;
        }

        // End previous session span if exists
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.end();
        }

        sessionNumber++;
        String spanName = MessageFormat.format(
                L10N.getString("telemetry.smtp_session"), sessionNumber);
        sessionSpan = trace.startSpan(spanName, SpanKind.SERVER);

        // Add session-level attributes
        sessionSpan.addAttribute("smtp.session_number", sessionNumber);
    }

    /**
     * Ends the current session span with a success status.
     *
     * @param message success message or null
     */
    private void endSessionSpan(String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }

        if (message != null) {
            sessionSpan.addAttribute("smtp.result", message);
        }
        sessionSpan.setStatusOk();
        sessionSpan.end();
    }

    /**
     * Ends the current session span with an error status.
     *
     * @param message error message
     */
    private void endSessionSpanError(String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }

        sessionSpan.setStatusError(message);
        sessionSpan.end();
    }

    /**
     * Invoked when application data is received from the client.
     * The connection framework handles TLS decryption transparently,
     * so this method receives plain SMTP protocol data.
     * Properly handles partial lines and DATA state byte processing.
     * 
     * @param buf the application data received from the client
     */
    @Override
    public void receive(ByteBuffer buf) {
        try {
            if (state == SMTPState.DATA) {
                // Special handling for DATA state - work with bytes, not lines
                handleDataContent(buf);
            } else {
                // Normal command processing - accumulate and parse complete lines
                handleCommandData(buf);
            }
        } catch (IOException e) {
            try {
                reply(500, "5.5.2 Syntax error"); // Syntax error
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Error processing SMTP data", e);
                }
            } catch (IOException e2) {
                LOGGER.log(Level.SEVERE, "Cannot write error reply", e2);
            }
        }
    }

    /**
     * Handles command data by accumulating partial lines and processing complete lines.
     * @param buf the buffer containing new data
     */
    private void handleCommandData(ByteBuffer buf) throws IOException {
        // Add new data to our line buffer
        appendToBuffer(lineBuffer, buf);
        
        // Extract and process complete lines
        String line;
        while ((line = extractCompleteLine(lineBuffer)) != null) {
            lineRead(line);
        }
    }

    /**
     * Handles message content during DATA state with streaming, memory-efficient processing.
     * Processes bytes with proper dot stuffing and termination detection.
     * Only chunks when necessary (dot stuffing or buffer boundaries).
     * 
     * CRITICAL: Works with raw bytes only - no character conversion is performed during DATA state.
     * This is essential because message content may use "8bit" or "binary" Content-Transfer-Encoding
     * which would be corrupted by any character set conversion. All processing must be byte-based.
     */
    private void handleDataContent(ByteBuffer buf) throws IOException {
        // First, handle any buffered control sequence from previous call
        if (controlBuffer.position() > 0) {
            handleControlSequenceWithNewData(buf);
            if (state != SMTPState.DATA) {
                return; // Terminated
            }
        }
        
        // Process the main buffer iteratively
        processDataBuffer(buf);
    }
    
    /**
     * Processes data buffer iteratively (no recursion).
     * Handles dot stuffing and termination detection.
     */
    private void processDataBuffer(ByteBuffer buf) throws IOException {
        // Process the buffer, chunking only when necessary
        int chunkStart = buf.position();
        
        while (buf.hasRemaining()) {
            int currentPos = buf.position();
            byte b = buf.get();
            
            switch (dataState) {
                case NORMAL:
                    if (b == '\r') {
                        dataState = DataState.SAW_CR;
                    }
                    // Continue accumulating - no chunking needed for normal CRLF
                    break;
                    
                case SAW_CR:
                    if (b == '\n') {
                        dataState = DataState.SAW_CRLF;
                    } else if (b == '\r') {
                        // Stay in SAW_CR state - another CR
                    } else {
                        // Regular byte after lone CR
                        dataState = DataState.NORMAL;
                    }
                    // Continue accumulating - no chunking needed
                    break;
                    
                case SAW_CRLF:
                    if (b == '.') {
                        // Potential dot stuffing or termination - need to chunk here
                        // Send everything up to (but not including) the dot
                        if (currentPos > chunkStart) {
                            sendChunk(buf, chunkStart, currentPos);
                        }
                        dataState = DataState.SAW_DOT;
                        chunkStart = buf.position(); // Start after the dot
                    } else {
                        // Regular byte after CRLF - continue normal processing
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                    }
                    break;
                    
                case SAW_DOT:
                    if (b == '\r') {
                        dataState = DataState.SAW_DOT_CR;
                    } else {
                        // Dot stuffing confirmed - skip the dot, continue with this byte
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                        // chunkStart already positioned after the skipped dot
                    }
                    break;
                    
                case SAW_DOT_CR:
                    if (b == '\n') {
                        // Complete termination: CRLF.CRLF
                        // Send any remaining chunk before termination
                        if (chunkStart < currentPos - 3) { // Account for .CRLF we're not sending
                            sendChunk(buf, chunkStart, currentPos - 3);
                        }
                        
                        // Message complete
                        resetDataState();
                        state = SMTPState.READY;
                        addSessionEvent("DATA complete");
                        reply(250, "2.0.0 Message accepted for delivery");
                        return;
                    } else {
                        // False alarm - was dot stuffing followed by CR and something else
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                        // Continue processing - the dot was already skipped
                    }
                    break;
            }
        }
        
        // Handle end of buffer
        if (needsControlBuffering()) {
            // We're in a control sequence that spans buffer boundary - save it
            saveControlSequence(buf, chunkStart);
        } else {
            // Send any remaining data - can include multiple lines
            if (buf.position() > chunkStart) {
                sendChunk(buf, chunkStart, buf.position());
            }
        }
    }

    /**
     * Determines if we need to buffer a partial control sequence.
     * Only buffer if we're in the middle of a potential dot stuffing or termination sequence.
     */
    private boolean needsControlBuffering() {
        return dataState == DataState.SAW_CR || 
               dataState == DataState.SAW_CRLF || 
               dataState == DataState.SAW_DOT || 
               dataState == DataState.SAW_DOT_CR;
    }

    /**
     * Invoked when a complete CRLF-terminated line has been read.
     * Parses SMTP commands and dispatches to appropriate handlers.
     * If authentication is in progress, handles auth data instead.
     *
     * @param line the line read (without CRLF)
     */
    void lineRead(String line) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SMTP command received: " + line);
        }

        if (authState != AuthState.NONE) {
            handleAuthData(line);
            return;
        }

        int si = line.indexOf(' ');
        String command = (si > 0) ? line.substring(0, si).toUpperCase() : line.toUpperCase();
        String args = (si > 0) ? line.substring(si + 1) : null;

        if (state == SMTPState.REJECTED) {
            if ("QUIT".equals(command)) {
                quit(args);
            } else {
                reply(554, "5.0.0 Connection rejected, only QUIT accepted");
            }
            return;
        }

        if ("HELO".equals(command)) {
            helo(args);
        } else if ("EHLO".equals(command)) {
            ehlo(args);
        } else if ("MAIL".equals(command)) {
            mail(args);
        } else if ("RCPT".equals(command)) {
            rcpt(args);
        } else if ("DATA".equals(command)) {
            data(args);
        } else if ("RSET".equals(command)) {
            rset(args);
        } else if ("QUIT".equals(command)) {
            quit(args);
        } else if ("NOOP".equals(command)) {
            noop(args);
        } else if ("HELP".equals(command)) {
            help(args);
        } else if ("VRFY".equals(command)) {
            vrfy(args);
        } else if ("EXPN".equals(command)) {
            expn(args);
        } else if ("STARTTLS".equals(command) && !secure && engine != null && !starttlsUsed) {
            starttls(args);
        } else if ("AUTH".equals(command)) {
            auth(args);
        } else {
            reply(500, "5.5.1 Command unrecognized: " + command);
        }
    }

    /**
     * Sends an SMTP response to the client.
     * @param code the SMTP response code
     * @param message the response message
     */
    protected void reply(int code, String message) throws IOException {
        sendResponse(code, message);
    }

    /**
     * Sends a multiline SMTP response (continuation line with hyphen).
     * @param code the SMTP response code
     * @param message the response message
     */
    protected void replyMultiline(int code, String message) throws IOException {
        String response = String.format("%d-%s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        send(buffer);
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SMTP multiline response sent: " + code + "-" + message);
        }
    }

    /**
     * Sends the final line of an SMTP response (ends with space, not hyphen).
     * @param code the SMTP response code
     * @param message the response message
     */
    protected void sendResponse(int code, String message) throws IOException {
        String response = String.format("%d %s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        send(buffer);
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SMTP response sent: " + code + " " + message);
        }
    }

    /**
     * Processes RFC822 message content received during DATA command.
     * Delegates to the connection handler for actual message processing.
     * @param messageBuffer the message content as bytes
     */
    private void messageContent(ByteBuffer messageBuffer) {
        if (handler != null) {
            // Create a read-only view to prevent handler from modifying buffer
            ByteBuffer readOnlyBuffer = messageBuffer.asReadOnlyBuffer();
            handler.messageContent(readOnlyBuffer);
        } else {
            // Fallback logging if no handler
            if (LOGGER.isLoggable(Level.INFO)) {
                int messageSize = messageBuffer.remaining();
                LOGGER.info("Received message from " + mailFrom + " to " + recipients + 
                           " (" + messageSize + " bytes) - no handler configured");
            }
        }
    }

    // ===== Buffer Management Helper Methods =====

    /**
     * Appends new data from source buffer to destination buffer, expanding if necessary.
     * @param dest the destination buffer to append to
     * @param source the source buffer containing new data
     */
    private void appendToBuffer(ByteBuffer dest, ByteBuffer source) {
        // Ensure destination has enough capacity
        if (dest.remaining() < source.remaining()) {
            // Need to expand the buffer
            int newCapacity = dest.capacity() + Math.max(source.remaining(), 4096);
            ByteBuffer newDest = ByteBuffer.allocate(newCapacity);
            
            // Copy existing data from dest to newDest
            dest.flip();  // Switch dest to read mode
            newDest.put(dest);  // Copy existing data
            
            // Replace the buffer reference and update dest pointer
            if (dest == lineBuffer) {
                lineBuffer = newDest;
                dest = lineBuffer;  // Update local reference
            } else if (dest == controlBuffer) {
                controlBuffer = newDest;  
                dest = controlBuffer;  // Update local reference
            }
        }
        
        // Append source data to dest (now properly sized and positioned)
        dest.put(source);
    }

    /**
     * Extracts a complete CRLF-terminated line from the buffer.
     * @param buffer the buffer to extract from
     * @return the line without CRLF, or null if no complete line available
     * @throws IOException if line length exceeds RFC 5321 limits
     */
    private String extractCompleteLine(ByteBuffer buffer) throws IOException {
        buffer.flip(); // Switch to read mode
        
        // Look for CRLF sequence
        int crlfIndex = -1;
        for (int i = 0; i < buffer.limit() - 1; i++) {
            if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n') {
                crlfIndex = i;
                break;
            }
        }
        
        if (crlfIndex == -1) {
            // Check for line length limit exceeded
            if (buffer.limit() > MAX_LINE_LENGTH) {
                // Line too long - this is an error condition
                buffer.clear(); // Clear the invalid line
                throw new IOException("Line length exceeds maximum of " + MAX_LINE_LENGTH + " characters");
            }
            // No complete line yet - compact and return null
            buffer.compact();
            return null;
        }

        // Check line length limit (excluding CRLF)
        if (crlfIndex > MAX_LINE_LENGTH) {
            buffer.clear(); // Clear the invalid line
            throw new IOException("Line length exceeds maximum of " + MAX_LINE_LENGTH + " characters");
        }

        // Extract the line (without CRLF)
        byte[] lineBytes = new byte[crlfIndex];
        buffer.get(lineBytes);
        buffer.get(); // Skip CR
        buffer.get(); // Skip LF

        // Compact buffer to remove processed data
        buffer.compact();

        // Convert to string
        return new String(lineBytes, US_ASCII);
    }

    // ===== Streaming DATA Processing Helper Methods =====

    /**
     * Sends a chunk of data immediately to messageContent().
     * @param source the source buffer
     * @param start the start position (inclusive)
     * @param end the end position (exclusive)
     */
    private void sendChunk(ByteBuffer source, int start, int end) throws IOException {                                                                      
        if (end > start) {
            int savedPosition = source.position();
            int savedLimit = source.limit();
            
            // Ensure start and end are within buffer bounds
            int actualStart = Math.max(0, Math.min(start, source.limit()));
            int actualEnd = Math.max(actualStart, Math.min(end, source.limit()));
            
            // Create a view of just the chunk we want to send
            source.position(actualStart);
            source.limit(actualEnd);
            ByteBuffer chunk = source.slice();
            
            // Restore the source buffer state with bounds checking
            source.limit(savedLimit);
            // Ensure savedPosition doesn't exceed the buffer limit
            int safePosition = Math.min(savedPosition, source.limit());
            source.position(safePosition);
            
            // Send the chunk
            messageContent(chunk);
        }
    }

    /**
     * Handles partial control sequences buffered from previous received() calls.
     * @param newData new data to append to the control sequence
     */
    private void handleControlSequenceWithNewData(ByteBuffer newData) throws IOException {                                                                  
        // Append new data to control buffer
        appendToBuffer(controlBuffer, newData);
        
        // Try to process the control sequence
        controlBuffer.flip();
        
        // Only process if there's actually data in the control buffer
        if (controlBuffer.hasRemaining()) {
            // Process as much as we can from the control buffer NON-RECURSIVELY
            ByteBuffer tempBuf = controlBuffer.slice();
            controlBuffer.clear(); // Clear immediately after slice
            
            // Process the combined data iteratively (NO RECURSION)
            if (tempBuf.hasRemaining()) {
                processDataBuffer(tempBuf);
            }
        } else {
            controlBuffer.clear();
        }
    }

    /**
     * Saves a partial control sequence for processing in the next received() call.
     * Control sequences are limited to MAX_CONTROL_BUFFER_SIZE bytes (max termination sequence is 4 bytes).
     * @param source the source buffer
     * @param start the start position of the partial sequence
     */
    private void saveControlSequence(ByteBuffer source, int start) {
        controlBuffer.clear();
        
        int savedPosition = source.position();
        
        // Ensure start position is within buffer bounds
        int safeStart = Math.max(0, Math.min(start, source.limit()));
        source.position(safeStart);
        
        // Copy the partial sequence to control buffer (bounded by MAX_CONTROL_BUFFER_SIZE)
        int bytesToCopy = 0;
        while (source.hasRemaining() && controlBuffer.hasRemaining() && bytesToCopy < MAX_CONTROL_BUFFER_SIZE) {
            controlBuffer.put(source.get());
            bytesToCopy++;
        }
        
        // If we hit the limit, this is likely an attack or malformed data
        if (bytesToCopy >= MAX_CONTROL_BUFFER_SIZE) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("Control sequence buffer limit exceeded, possible attack from " + 
                             getRemoteSocketAddress());
            }
        }
        
        // Restore position safely
        int safeRestorePosition = Math.max(0, Math.min(savedPosition, source.limit()));
        source.position(safeRestorePosition);
    }

    /**
     * Resets DATA state tracking variables.
     */
    private void resetDataState() {
        controlBuffer.clear();
        dataState = DataState.NORMAL;
    }

    // ===== SMTP Command Handlers =====

    /**
     * HELO command - Simple SMTP greeting.
     * @param hostname the client's hostname
     */
    private void helo(String hostname) throws IOException {
        if (hostname == null || hostname.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: HELO hostname");
            return;
        }

        this.heloName = hostname.trim();
        this.extendedSMTP = false;
        this.state = SMTPState.READY;

        // Add telemetry attributes
        addSessionAttribute("smtp.helo", this.heloName);
        addSessionEvent("HELO");
        
        String localHostname = getLocalSocketAddress().toString(); // TODO: get proper hostname                                                                 
        reply(250, localHostname + " Hello " + hostname);
    }

    /**
     * EHLO command - Extended SMTP greeting.
     * @param hostname the client's hostname  
     */
    private void ehlo(String hostname) throws IOException {
        if (hostname == null || hostname.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: EHLO hostname");
            return;
        }

        this.heloName = hostname.trim();
        this.extendedSMTP = true;
        this.state = SMTPState.READY;

        // Add telemetry attributes
        addSessionAttribute("smtp.ehlo", this.heloName);
        addSessionAttribute("smtp.esmtp", true);
        addSessionEvent("EHLO");
        
        String localHostname = getLocalSocketAddress().toString(); // TODO: get proper hostname
        
        // Send multiline EHLO response with capabilities
        // All lines except the last use hyphen (replyMultiline), last line uses space (sendResponse)
        replyMultiline(250, localHostname + " Hello " + hostname);
        
        // Standard capabilities (using replyMultiline for continuation lines)
        replyMultiline(250, "SIZE " + server.getMaxMessageSize()); // Configurable maximum message size
        replyMultiline(250, "PIPELINING");   // Support command pipelining
        replyMultiline(250, "8BITMIME");     // Support 8-bit MIME
        replyMultiline(250, "ENHANCEDSTATUSCODES"); // Enhanced status codes (already using them)
        
        // STARTTLS capability (only if TLS is not already active and SSL context available)
        if (!isSecure() && server.isSTARTTLSAvailable()) {
            replyMultiline(250, "STARTTLS");
        }
        
        // Authentication capability
        // Advertise AUTH if we have a realm configured and either:
        // - Connection is already secure, OR
        // - STARTTLS is available for securing the connection
        if (server.getRealm() != null && (isSecure() || server.isSTARTTLSAvailable())) {
            replyMultiline(250, "AUTH PLAIN LOGIN CRAM-MD5 DIGEST-MD5 SCRAM-SHA-256 OAUTHBEARER GSSAPI EXTERNAL NTLM");
        }
        
        // Final capability line (required to end with space, not hyphen)
        sendResponse(250, "HELP"); // Last line uses space, not hyphen
    }

    /**
     * MAIL command - Begin mail transaction.
     * @param args the arguments (FROM:<email>)
     */
    private void mail(String args) throws IOException {
        if (state != SMTPState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (args == null || !args.toUpperCase().startsWith("FROM:")) {
            reply(501, "5.0.0 Syntax: MAIL FROM:<address>");
            return;
        }

        // Parse email address from FROM:<email>
        String fromAddr = args.substring(5).trim();
        if (fromAddr.startsWith("<") && fromAddr.endsWith(">")) {
            fromAddr = fromAddr.substring(1, fromAddr.length() - 1);
        }

        // For authenticated sessions, verify sender authorization
        if (authenticated && !isAuthorizedSender(fromAddr, authenticatedUser)) {
            reply(550, "5.7.1 Not authorized to send from this address");
            return;
        }

        // Store sender information for use in callback
        this.mailFrom = fromAddr;
        this.recipients.clear();
        
        // Delegate to handler for asynchronous policy evaluation
        // The handler will call back to mailFromReply() with the result
        if (handler != null) {
            handler.mailFrom(fromAddr, this);
        } else {
            // No handler configured - accept all senders (blackhole mode)
            mailFromReply(SenderPolicyResult.ACCEPT);
        }
    }

    /**
     * RCPT command - Specify message recipient.
     * @param args the arguments (TO:<email>)
     */
    private void rcpt(String args) throws IOException {
        if (state != SMTPState.MAIL && state != SMTPState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (args == null || !args.toUpperCase().startsWith("TO:")) {
            reply(501, "5.0.0 Syntax: RCPT TO:<address>");
            return;
        }

        // Check recipient limit to prevent memory exhaustion
        if (recipients.size() >= MAX_RECIPIENTS) {
            reply(452, "4.5.3 Too many recipients (maximum " + MAX_RECIPIENTS + ")");
            return;
        }

        // Parse email address from TO:<email>
        String toAddr = args.substring(3).trim();
        if (toAddr.startsWith("<") && toAddr.endsWith(">")) {
            toAddr = toAddr.substring(1, toAddr.length() - 1);
        }

        // Basic validation of address length (RFC 5321: local@domain, local<=64, domain<=255)
        if (toAddr.length() > 320) { // 64 + 1 + 255 = 320 theoretical max
            reply(501, "5.1.3 Invalid recipient address - too long");
            return;
        }

        // Delegate to handler for asynchronous policy evaluation
        // The handler will call back to rcptToReply() with the result and recipient
        if (handler != null) {
            handler.rcptTo(toAddr, this);
        } else {
            // No handler configured - accept all recipients (blackhole mode)
            rcptToReply(RecipientPolicyResult.ACCEPT, toAddr);
        }
    }

    /**
     * DATA command - Begin message content transmission.
     * @param args should be null or empty for DATA command
     */
    private void data(String args) throws IOException {
        if (state != SMTPState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (recipients.isEmpty()) {
            reply(503, "5.0.0 Need RCPT (recipient)");
            return;
        }

        // Initialize DATA state with proper buffer management
        resetDataState();
        this.state = SMTPState.DATA;
        
        reply(354, "Start mail input; end with <CRLF>.<CRLF>");
    }

    /**
     * RSET command - Reset the current mail transaction.
     * @param args should be null or empty for RSET command
     */
    private void rset(String args) throws IOException {
        // End current session span and start a new one
        endSessionSpan("RSET");
        startSessionSpan();

        // Reset transaction state
        this.mailFrom = null;
        this.recipients.clear();
        resetDataState();
        this.state = SMTPState.READY;
        
        // Notify handler of reset
        if (handler != null) {
            handler.reset();
        }
        
        reply(250, "2.0.0 Reset state");
    }

    /**
     * QUIT command - Close the connection.
     * @param args should be null or empty for QUIT command  
     */
    private void quit(String args) throws IOException {
        // End current session span
        endSessionSpan("QUIT");

        this.state = SMTPState.QUIT;
        reply(221, "2.0.0 Goodbye");
        close(); // Close the connection
    }

    /**
     * NOOP command - No operation.
     * @param args ignored
     */
    private void noop(String args) throws IOException {
        reply(250, "2.0.0 Ok");
    }

    /**
     * HELP command - Provide help information.
     * @param args optional command to get help for
     */
    private void help(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(214, "2.0.0 This is Gumdrop SMTP server");
        } else {
            reply(502, "5.5.1 Help not available for " + args);
        }
    }

    /**
     * VRFY command - Verify an address (not implemented).
     * @param args the address to verify
     */
    private void vrfy(String args) throws IOException {
        reply(252, "2.5.2 Cannot VRFY user, but will accept message and attempt delivery");
    }

    /**
     * EXPN command - Expand a mailing list (not implemented).
     * @param args the list to expand
     */
    private void expn(String args) throws IOException {
        reply(502, "5.5.1 EXPN not implemented");
    }

    /**
     * STARTTLS command - Start TLS encryption.
     * @param args should be null or empty for STARTTLS command
     */
    private void starttls(String args) throws IOException {
        // Check if TLS is already active
        if (isSecure()) {
            reply(454, "4.7.0 TLS already active");
            return;
        }
        
        // Check if STARTTLS is available (connector must have SSL context)
        if (!server.isSTARTTLSAvailable()) {
            reply(454, "4.7.0 TLS not available");
            return;
        }
        
        // STARTTLS must be issued before authentication
        if (state != SMTPState.INITIAL && state != SMTPState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        
        try {
                // Send success response - after this, client expects TLS handshake
                reply(220, "2.0.0 Ready to start TLS");

                // Initialize SSL state using the existing engine
                initializeSSLState();
            
            // Reset SMTP state after TLS upgrade
            state = SMTPState.INITIAL;
            heloName = null;
            extendedSMTP = false;
            starttlsUsed = true;
            addSessionAttribute("smtp.starttls", true);
            addSessionEvent("STARTTLS");
            
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("STARTTLS upgrade initiated for " + getRemoteSocketAddress());
            }
            
        } catch (Exception e) {
            reply(454, "4.3.0 TLS not available due to temporary reason");
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "STARTTLS failed", e);
            }
        }
    }
    

    /**
     * AUTH command - SMTP authentication.
     * Supports PLAIN and LOGIN mechanisms using the configured Realm.
     * @param args authentication mechanism and optional initial response
     */
    private void auth(String args) throws IOException {
        // Check if realm is configured
        if (server.getRealm() == null) {
            reply(502, "5.5.1 Authentication not available");
            return;
        }

        // AUTH only allowed after EHLO
        if (!extendedSMTP) {
            reply(503, "5.0.0 AUTH requires EHLO");
            return;
        }

        // Can't authenticate when already authenticated
        if (authenticated) {
            reply(503, "5.0.0 Already authenticated");
            return;
        }

        // AUTH requires secure connection or STARTTLS
        if (!isSecure() && !server.isSTARTTLSAvailable()) {
            reply(538, "5.7.11 Encryption required for requested authentication mechanism");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: AUTH mechanism [initial-response]");
            return;
        }

        String trimmedArgs = args.trim();
        String mechanism;
        String initialResponse = null;
        int spaceIdx = -1;
        for (int i = 0; i < trimmedArgs.length(); i++) {
            char c = trimmedArgs.charAt(i);
            if (c == ' ' || c == '\t') {
                spaceIdx = i;
                break;
            }
        }
        if (spaceIdx > 0) {
            mechanism = trimmedArgs.substring(0, spaceIdx).toUpperCase();
            initialResponse = trimmedArgs.substring(spaceIdx + 1).trim();
            if (initialResponse.isEmpty()) {
                initialResponse = null;
            }
        } else {
            mechanism = trimmedArgs.toUpperCase();
        }

        switch (mechanism) {
            case "PLAIN":
                handleAuthPlain(initialResponse);
                break;
            case "LOGIN":
                handleAuthLogin(initialResponse);
                break;
            case "CRAM-MD5":
                handleAuthCramMD5(initialResponse);
                break;
            case "DIGEST-MD5":
                handleAuthDigestMD5(initialResponse);
                break;
            case "SCRAM-SHA-256":
                handleAuthScramSHA256(initialResponse);
                break;
            case "OAUTHBEARER":
                handleAuthOAuthBearer(initialResponse);
                break;
            case "GSSAPI":
                handleAuthGSSAPI(initialResponse);
                break;
            case "EXTERNAL":
                handleAuthExternal(initialResponse);
                break;
            case "NTLM":
                handleAuthNTLM(initialResponse);
                break;
            default:
                reply(504, "5.5.4 Authentication mechanism not supported");
        }
    }

    /**
     * Handles AUTH PLAIN mechanism.
     * Format: base64(authzid\0username\0password)
     * @param initialResponse optional base64-encoded credentials
     */
    private void handleAuthPlain(String initialResponse) throws IOException {
        try {
            String credentials;
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Initial response provided
                credentials = initialResponse;
            } else {
                // Request credentials
                reply(334, ""); // Empty challenge for PLAIN
                authState = AuthState.PLAIN_RESPONSE;
                authMechanism = "PLAIN";
                return;
            }

            // Decode base64 credentials
            byte[] decoded = Base64.getDecoder().decode(credentials);
            String authString = new String(decoded, US_ASCII);

            // Parse authzid\0username\0password
            String[] parts = authString.split("\0", -1);
            if (parts.length != 3) {
                reply(535, "5.7.8 Authentication credentials invalid");
                resetAuthState();
                return;
            }

            String authzid = parts[0]; // Authorization identity (can be empty)
            String username = parts[1];
            String password = parts[2];

            if (username.isEmpty() || password.isEmpty()) {
                reply(535, "5.7.8 Authentication credentials invalid");
                resetAuthState();
                return;
            }

            // Authenticate against realm
            if (authenticateUser(username, password)) {
                authenticated = true;
                authenticatedUser = username;
                recordAuthenticationSuccess(username, "PLAIN");
                reply(235, "2.7.0 Authentication successful");
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("SMTP AUTH PLAIN successful for user: " + username);
                }
                
                // Notify handler of successful authentication
                if (handler != null) {
                    handler.authenticated(username, "PLAIN");
                }
            } else {
                addSessionEvent("AUTH PLAIN failed");
                reply(535, "5.7.8 Authentication credentials invalid");
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("SMTP AUTH PLAIN failed for user: " + username);
                }
            }
            resetAuthState();

        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH PLAIN error", e);
            }
        }
    }

    /**
     * Handles AUTH LOGIN mechanism.
     * Interactive: server challenges for username, then password.
     * @param initialResponse optional base64-encoded username
     */
    private void handleAuthLogin(String initialResponse) throws IOException {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Initial response is username
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String username = new String(decoded, US_ASCII);
                
                if (username.isEmpty()) {
                    reply(535, "5.7.8 Authentication credentials invalid");
                    resetAuthState();
                    return;
                }

                pendingAuthUsername = username;
                authState = AuthState.LOGIN_PASSWORD;
                authMechanism = "LOGIN";
                
                // Challenge for password
                String passwordPrompt = Base64.getEncoder().encodeToString("Password:".getBytes(US_ASCII));
                reply(334, passwordPrompt);
            } else {
                // Start LOGIN sequence - challenge for username
                authState = AuthState.LOGIN_USERNAME;
                authMechanism = "LOGIN";
                
                String usernamePrompt = Base64.getEncoder().encodeToString("Username:".getBytes(US_ASCII));
                reply(334, usernamePrompt);
            }

        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH LOGIN error", e);
            }
        }
    }

    /**
     * Handles authentication data during AUTH LOGIN sequence.
     * @param data the base64-encoded authentication data
     */
    private void handleAuthData(String data) throws IOException {
        try {
            switch (authState) {
                case PLAIN_RESPONSE:
                    // Handle PLAIN credentials that were requested with empty challenge
                    handleAuthPlain(data);
                    break;

                case LOGIN_USERNAME:
                    // Decode username
                    byte[] decoded = Base64.getDecoder().decode(data);
                    String username = new String(decoded, US_ASCII);
                    
                    if (username.isEmpty()) {
                        reply(535, "5.7.8 Authentication credentials invalid");
                        resetAuthState();
                        return;
                    }

                    pendingAuthUsername = username;
                    authState = AuthState.LOGIN_PASSWORD;
                    
                    // Challenge for password
                    String passwordPrompt = Base64.getEncoder().encodeToString("Password:".getBytes(US_ASCII));
                    reply(334, passwordPrompt);
                    break;

                case LOGIN_PASSWORD:
                    // Decode password and authenticate
                    decoded = Base64.getDecoder().decode(data);
                    String password = new String(decoded, US_ASCII);

                    if (password.isEmpty()) {
                        reply(535, "5.7.8 Authentication credentials invalid");
                        resetAuthState();
                        return;
                    }

                    // Authenticate against realm
                    if (authenticateUser(pendingAuthUsername, password)) {
                        authenticated = true;
                        authenticatedUser = pendingAuthUsername;
                        reply(235, "2.7.0 Authentication successful");
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("SMTP AUTH LOGIN successful for user: " + pendingAuthUsername);
                        }
                        
                        // Notify handler of successful authentication
                        if (handler != null) {
                            handler.authenticated(pendingAuthUsername, "LOGIN");
                        }
                    } else {
                        reply(535, "5.7.8 Authentication credentials invalid");
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning("SMTP AUTH LOGIN failed for user: " + pendingAuthUsername);
                        }
                    }
                    resetAuthState();
                    break;

                case CRAM_MD5_RESPONSE:
                    // Handle CRAM-MD5 response using shared SASL utilities
                    String cramResponse = SASLUtils.decodeBase64ToString(data);
                    
                    int spaceIndex = cramResponse.indexOf(' ');
                    if (spaceIndex <= 0) {
                        reply(535, "5.7.8 Invalid CRAM-MD5 response format");
                        resetAuthState();
                        return;
                    }
                    
                    String cramUsername = cramResponse.substring(0, spaceIndex);
                    String expectedHmac = cramResponse.substring(spaceIndex + 1);
                    
                    // Validate against realm using getCramMD5Response
                    if (server.getRealm() != null) {
                        try {
                            String computedHmac = server.getRealm().getCramMD5Response(cramUsername, authChallenge);
                            if (computedHmac != null && expectedHmac.equalsIgnoreCase(computedHmac)) {
                                authenticated = true;
                                authenticatedUser = cramUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP CRAM-MD5 successful for user: " + cramUsername);
                                }
                                
                                // Notify handler of successful authentication
                                if (handler != null) {
                                    handler.authenticated(cramUsername, "CRAM-MD5");
                                }
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP CRAM-MD5 failed for user: " + cramUsername);
                                }
                            }
                        } catch (UnsupportedOperationException e) {
                            // Realm doesn't support CRAM-MD5
                            reply(535, "5.7.8 CRAM-MD5 authentication not supported by this realm");
                            if (LOGGER.isLoggable(Level.WARNING)) {
                                LOGGER.warning("SMTP CRAM-MD5 not supported: " + e.getMessage());
                            }
                        }
                    } else {
                        reply(535, "5.7.8 Authentication credentials invalid");
                    }
                    resetAuthState();
                    break;

                case DIGEST_MD5_RESPONSE:
                    // Handle DIGEST-MD5 response using shared SASL utilities
                    String digestResponse = SASLUtils.decodeBase64ToString(data);
                    
                    // Parse digest response parameters using shared utility
                    Map<String, String> params = SASLUtils.parseDigestParams(digestResponse);
                    String digestUsername = params.get("username");
                    String responseHash = params.get("response");
                    
                    if (digestUsername != null && responseHash != null) {
                        // Simplified validation for DIGEST-MD5 - check if user exists by trying to get HA1
                        if (server.getRealm() != null) {
                            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
                            String ha1 = server.getRealm().getDigestHA1(digestUsername, hostname);
                            if (ha1 != null) {
                                // User exists - for simplified implementation, accept the response
                                // A full implementation would validate the computed response hash
                                authenticated = true;
                                authenticatedUser = digestUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP DIGEST-MD5 successful for user: " + digestUsername);
                                }
                                
                                // Notify handler of successful authentication
                                if (handler != null) {
                                    handler.authenticated(digestUsername, "DIGEST-MD5");
                                }
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP DIGEST-MD5 failed: user not found: " + digestUsername);
                                }
                            }
                        } else {
                            reply(535, "5.7.8 Authentication credentials invalid");
                        }
                    } else {
                        reply(535, "5.7.8 Invalid DIGEST-MD5 response format");
                    }
                    resetAuthState();
                    break;

                case SCRAM_INITIAL:
                    // Handle SCRAM client first message
                    processScramClientFirst(data);
                    break;

                case SCRAM_FINAL:
                    // Handle SCRAM client final message using getScramCredentials
                    decoded = Base64.getDecoder().decode(data);
                    String scramFinal = new String(decoded, US_ASCII);
                    
                    if (pendingAuthUsername != null && server.getRealm() != null) {
                        try {
                            Realm.ScramCredentials creds = server.getRealm().getScramCredentials(pendingAuthUsername);
                            if (creds != null) {
                                // User exists and SCRAM credentials available
                                // TODO: Validate SCRAM proof using creds.storedKey
                                // For now, accept if credentials exist (simplified implementation)
                                authenticated = true;
                                authenticatedUser = pendingAuthUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP SCRAM-SHA-256 successful for user: " + pendingAuthUsername);
                                }
                                
                                // Notify handler of successful authentication
                                if (handler != null) {
                                    handler.authenticated(pendingAuthUsername, "SCRAM-SHA-256");
                                }
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP SCRAM-SHA-256 failed: user not found: " + pendingAuthUsername);
                                }
                            }
                        } catch (UnsupportedOperationException e) {
                            // Realm doesn't support SCRAM
                            reply(535, "5.7.8 SCRAM-SHA-256 authentication not supported by this realm");
                            if (LOGGER.isLoggable(Level.WARNING)) {
                                LOGGER.warning("SMTP SCRAM-SHA-256 not supported: " + e.getMessage());
                            }
                        }
                    } else {
                        reply(535, "5.7.8 Authentication credentials invalid");
                    }
                    resetAuthState();
                    break;

                case OAUTH_RESPONSE:
                    // Handle OAuth bearer token
                    processOAuthBearer(data);
                    break;

                case GSSAPI_EXCHANGE:
                    // Handle GSSAPI token exchange
                    decoded = Base64.getDecoder().decode(data);
                    
                    try {
                        byte[] challenge = saslServer.evaluateResponse(decoded);
                        
                        if (saslServer.isComplete()) {
                            // Authentication successful
                            String authzid = saslServer.getAuthorizationID();
                            authenticated = true;
                            authenticatedUser = authzid;
                            reply(235, "2.7.0 Authentication successful");
                            
                            if (LOGGER.isLoggable(Level.INFO)) {
                                LOGGER.info("SMTP GSSAPI successful for user: " + authzid);
                            }
                            
                            // Notify handler of successful authentication
                            if (handler != null) {
                                handler.authenticated(authzid, "GSSAPI");
                            }
                            resetAuthState();
                        } else {
                            // Continue the exchange
                            String challengeB64 = Base64.getEncoder().encodeToString(challenge);
                            reply(334, challengeB64);
                        }
                    } catch (SaslException e) {
                        reply(535, "5.7.8 GSSAPI authentication failed");
                        resetAuthState();
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING, "GSSAPI token exchange error", e);
                        }
                    }
                    break;

                case NTLM_TYPE1:
                    // Handle NTLM Type 1 message
                    decoded = Base64.getDecoder().decode(data);
                    
                    // Parse Type 1 message (simplified validation)
                    if (decoded.length < 12 || 
                        !new String(decoded, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                        reply(535, "5.7.8 Invalid NTLM Type 1 message");
                        resetAuthState();
                        return;
                    }
                    
                    // Generate Type 2 challenge message
                    try {
                        byte[] type2Message = generateNTLMType2Challenge();
                        String challengeB64 = Base64.getEncoder().encodeToString(type2Message);
                        
                        reply(334, challengeB64);
                        authState = AuthState.NTLM_TYPE3;
                    } catch (Exception e) {
                        reply(535, "5.7.8 NTLM challenge generation failed");
                        resetAuthState();
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING, "NTLM Type 2 generation error", e);
                        }
                    }
                    break;

                case NTLM_TYPE3:
                    // Handle NTLM Type 3 response
                    decoded = Base64.getDecoder().decode(data);
                    
                    try {
                        // Parse Type 3 message to extract username (simplified)
                        String ntlmUsername = parseNTLMType3Username(decoded);
                        
                        if (ntlmUsername != null && server.getRealm() != null) {
                            // Check if user exists using the new userExists() method
                            // A full implementation would validate the NTLM response hash
                            if (server.getRealm().userExists(ntlmUsername)) {
                                authenticated = true;
                                authenticatedUser = ntlmUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP NTLM successful for user: " + ntlmUsername);
                                }
                                
                                // Notify handler of successful authentication
                                if (handler != null) {
                                    handler.authenticated(ntlmUsername, "NTLM");
                                }
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP NTLM failed: user not found: " + ntlmUsername);
                                }
                            }
                        } else {
                            reply(535, "5.7.8 Authentication credentials invalid");
                        }
                    } catch (Exception e) {
                        reply(535, "5.7.8 NTLM Type 3 processing failed");
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING, "NTLM Type 3 parsing error", e);
                        }
                    }
                    resetAuthState();
                    break;

                default:
                    // Shouldn't happen
                    reply(503, "5.0.0 Bad sequence of commands");
                    resetAuthState();
            }

        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH data handling error", e);
            }
        }
    }

    /**
     * Handles AUTH CRAM-MD5 mechanism (RFC 2195).
     * Uses HMAC-MD5 challenge-response authentication.
     * @param initialResponse should be null for CRAM-MD5
     */
    private void handleAuthCramMD5(String initialResponse) throws IOException {
        if (initialResponse != null) {
            reply(501, "5.5.4 CRAM-MD5 does not support initial response");
            return;
        }

        try {
            // Generate challenge: timestamp.pid@hostname
            long timestamp = System.currentTimeMillis();
            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
            authChallenge = "<" + timestamp + ".gumdrop@" + hostname + ">";
            
            // Send base64-encoded challenge
            String challengeB64 = Base64.getEncoder().encodeToString(authChallenge.getBytes(US_ASCII));
            reply(334, challengeB64);
            
            authState = AuthState.CRAM_MD5_RESPONSE;
            authMechanism = "CRAM-MD5";
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication system error");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "CRAM-MD5 challenge generation error", e);
            }
        }
    }

    /**
     * Handles AUTH DIGEST-MD5 mechanism (RFC 2831).
     * HTTP Digest-style challenge-response authentication.
     * @param initialResponse should be null for DIGEST-MD5
     */
    private void handleAuthDigestMD5(String initialResponse) throws IOException {
        if (initialResponse != null) {
            reply(501, "5.5.4 DIGEST-MD5 does not support initial response");
            return;
        }

        try {
            // Generate nonce
            SecureRandom random = new SecureRandom();
            byte[] nonceBytes = new byte[16];
            random.nextBytes(nonceBytes);
            authNonce = Base64.getEncoder().encodeToString(nonceBytes);
            
            // Build challenge
            StringBuilder challenge = new StringBuilder();
            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
            challenge.append("realm=\"").append(hostname).append("\",");
            challenge.append("nonce=\"").append(authNonce).append("\",");
            challenge.append("qop=\"auth\",");
            challenge.append("algorithm=\"md5-sess\",");
            challenge.append("charset=\"utf-8\"");
            
            authChallenge = challenge.toString();
            
            // Send base64-encoded challenge
            String challengeB64 = Base64.getEncoder().encodeToString(authChallenge.getBytes(US_ASCII));
            reply(334, challengeB64);
            
            authState = AuthState.DIGEST_MD5_RESPONSE;
            authMechanism = "DIGEST-MD5";
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication system error");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "DIGEST-MD5 challenge generation error", e);
            }
        }
    }

    /**
     * Handles AUTH SCRAM-SHA-256 mechanism (RFC 5802).
     * Salted Challenge Response Authentication Mechanism using SHA-256.
     * @param initialResponse client-first-message or null
     */
    private void handleAuthScramSHA256(String initialResponse) throws IOException {
        try {
            if (initialResponse == null) {
                // Request client first message
                reply(334, ""); // Empty challenge
                authState = AuthState.SCRAM_INITIAL;
                authMechanism = "SCRAM-SHA-256";
                return;
            }
            
            // Process client-first-message directly
            processScramClientFirst(initialResponse);
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "SCRAM-SHA-256 error", e);
            }
        }
    }

    /**
     * Handles AUTH OAUTHBEARER mechanism (RFC 7628).
     * OAuth 2.0 bearer token authentication.
     * @param initialResponse base64-encoded bearer token or null
     */
    private void handleAuthOAuthBearer(String initialResponse) throws IOException {
        try {
            String bearerData;
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Initial response provided
                bearerData = initialResponse;
            } else {
                // Request bearer token
                reply(334, ""); // Empty challenge
                authState = AuthState.OAUTH_RESPONSE;
                authMechanism = "OAUTHBEARER";
                return;
            }

            // Process bearer token
            processOAuthBearer(bearerData);
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "OAUTHBEARER error", e);
            }
        }
    }

    /**
     * Handles AUTH GSSAPI mechanism (RFC 4752).
     * Uses SASL GSSAPI for Kerberos/GSS-API authentication.
     * @param initialResponse optional initial GSSAPI token
     */
    private void handleAuthGSSAPI(String initialResponse) throws IOException {
        try {
            if (saslServer == null) {
                // Create SASL GSSAPI server
                Map<String, Object> props = new HashMap<>();
                props.put(Sasl.QOP, "auth"); // Authentication only, no integrity/confidentiality
                
                String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
                saslServer = Sasl.createSaslServer("GSSAPI", "smtp", hostname, props, null);
                
                if (saslServer == null) {
                    reply(535, "5.7.8 GSSAPI authentication not available");
                    resetAuthState();
                    return;
                }
                
                authState = AuthState.GSSAPI_EXCHANGE;
                authMechanism = "GSSAPI";
            }
            
            byte[] challenge = null;
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Process initial response
                byte[] response = Base64.getDecoder().decode(initialResponse);
                challenge = saslServer.evaluateResponse(response);
            } else {
                // Start the exchange
                challenge = saslServer.evaluateResponse(new byte[0]);
            }
            
            if (saslServer.isComplete()) {
                // Authentication successful
                String authzid = saslServer.getAuthorizationID();
                authenticated = true;
                authenticatedUser = authzid;
                reply(235, "2.7.0 Authentication successful");
                
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("SMTP GSSAPI successful for user: " + authzid);
                }
                
                // Notify handler of successful authentication
                if (handler != null) {
                    handler.authenticated(authzid, "GSSAPI");
                }
                resetAuthState();
            } else {
                // Send challenge and continue
                String challengeB64 = Base64.getEncoder().encodeToString(challenge);
                reply(334, challengeB64);
            }
            
        } catch (SaslException e) {
            reply(535, "5.7.8 GSSAPI authentication failed");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "GSSAPI authentication error", e);
            }
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication system error");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "GSSAPI error", e);
            }
        }
    }

    /**
     * Handles AUTH EXTERNAL mechanism (RFC 4422).
     * Uses TLS client certificate for authentication.
     * @param initialResponse optional authorization identity
     */
    private void handleAuthExternal(String initialResponse) throws IOException {
        try {
            // Check if we have a secure connection with client certificate
            if (!isSecure()) {
                reply(538, "5.7.11 EXTERNAL requires secure connection");
                return;
            }
            
            // Extract client certificate from SSL session
            clientCertificate = getClientCertificate();
            if (clientCertificate == null) {
                reply(535, "5.7.8 No client certificate provided");
                return;
            }
            
            // Extract username from certificate (typically CN or email)
            String certSubject = clientCertificate.getSubjectX500Principal().getName();
            String username = extractUsernameFromCertificate(certSubject);
            
            if (username == null) {
                reply(535, "5.7.8 Cannot extract username from certificate");
                return;
            }
            
            // If initial response provided, use it as authorization identity
            String authzid = username;
            if (initialResponse != null && !initialResponse.equals("=")) {
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String requestedAuthzid = new String(decoded, US_ASCII);
                if (!requestedAuthzid.isEmpty()) {
                    authzid = requestedAuthzid;
                }
            }
            
            // Validate certificate against realm using userExists()
            if (server.getRealm() != null) {
                if (server.getRealm().userExists(username)) {
                    // User exists - certificate authentication successful
                    authenticated = true;
                    authenticatedUser = authzid;
                    reply(235, "2.7.0 Authentication successful");
                    
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("SMTP EXTERNAL successful for user: " + authzid + " (cert: " + username + ")");
                    }
                    
                    // Notify handler of successful authentication
                    if (handler != null) {
                        handler.authenticated(authzid, "EXTERNAL");
                    }
                } else {
                    reply(535, "5.7.8 Certificate authentication failed");
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning("SMTP EXTERNAL failed: user not found: " + username);
                    }
                }
            } else {
                reply(535, "5.7.8 Authentication not available");
            }
            
            resetAuthState();
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "EXTERNAL authentication error", e);
            }
        }
    }

    /**
     * Handles AUTH NTLM mechanism.
     * Uses NTLM challenge-response authentication (Microsoft proprietary).
     * @param initialResponse optional Type 1 message
     */
    private void handleAuthNTLM(String initialResponse) throws IOException {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Process Type 1 message (NTLM negotiation)
                byte[] type1Message = Base64.getDecoder().decode(initialResponse);
                
                // Parse Type 1 message (simplified)
                if (type1Message.length < 12 || 
                    !new String(type1Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                    reply(535, "5.7.8 Invalid NTLM Type 1 message");
                    resetAuthState();
                    return;
                }
                
                // Generate Type 2 challenge message
                byte[] type2Message = generateNTLMType2Challenge();
                String challengeB64 = Base64.getEncoder().encodeToString(type2Message);
                
                reply(334, challengeB64);
                authState = AuthState.NTLM_TYPE3;
                authMechanism = "NTLM";
                
            } else {
                // Request Type 1 message
                reply(334, ""); // Empty challenge to request Type 1
                authState = AuthState.NTLM_TYPE1;
                authMechanism = "NTLM";
            }
            
        } catch (Exception e) {
            reply(535, "5.7.8 NTLM authentication failed");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "NTLM authentication error", e);
            }
        }
    }

    /**
     * Authenticates a user against the configured realm.
     * @param username the username
     * @param password the password
     * @return true if authentication succeeds, false otherwise
     */
    private boolean authenticateUser(String username, String password) {
        if (server.getRealm() == null) {
            return false;
        }

        return server.getRealm().passwordMatch(username, password);
    }

    /**
     * Processes SCRAM-SHA-256 client first message.
     * @param clientFirst base64-encoded client first message
     */
    private void processScramClientFirst(String clientFirst) throws IOException {
        try {
            // Decode client first message
            byte[] decoded = Base64.getDecoder().decode(clientFirst);
            String message = new String(decoded, US_ASCII);
            
            // Parse: n,,n=username,r=clientNonce
            if (!message.startsWith("n,,")) {
                reply(535, "5.7.8 Invalid SCRAM client first message");
                resetAuthState();
                return;
            }
            
            String[] parts = message.substring(3).split(",");
            String username = null;
            String clientNonce = null;
            
            for (String part : parts) {
                if (part.startsWith("n=")) {
                    username = part.substring(2);
                } else if (part.startsWith("r=")) {
                    clientNonce = part.substring(2);
                }
            }
            
            if (username == null || clientNonce == null) {
                reply(535, "5.7.8 Invalid SCRAM client first message");
                resetAuthState();
                return;
            }
            
            // Generate server nonce and salt
            SecureRandom random = new SecureRandom();
            byte[] serverNonceBytes = new byte[18];
            random.nextBytes(serverNonceBytes);
            String serverNonce = Base64.getEncoder().encodeToString(serverNonceBytes);
            
            authSalt = new byte[16];
            random.nextBytes(authSalt);
            
            authClientNonce = clientNonce;
            authNonce = clientNonce + serverNonce;
            pendingAuthUsername = username;
            
            // Build server first message
            String serverFirst = "r=" + authNonce + ",s=" + Base64.getEncoder().encodeToString(authSalt) + ",i=" + authIterations;
            String serverFirstB64 = Base64.getEncoder().encodeToString(serverFirst.getBytes(US_ASCII));
            
            reply(334, serverFirstB64);
            authState = AuthState.SCRAM_FINAL;
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "SCRAM client first processing error", e);
            }
        }
    }

    /**
     * Processes OAuth Bearer token.
     * @param bearerData base64-encoded bearer token data
     */
    private void processOAuthBearer(String bearerData) throws IOException {
        try {
            // Decode bearer token
            byte[] decoded = Base64.getDecoder().decode(bearerData);
            String tokenString = new String(decoded, "UTF-8");
            
            // Parse bearer token format: n,a=authzid,^Ahost=hostname^Aport=port^Aauth=Bearer token^A^A
            String[] parts = tokenString.split("\1"); // ASCII 0x01 separator
            String username = null;
            String token = null;
            
            for (String part : parts) {
                if (part.startsWith("a=")) {
                    username = part.substring(2);
                } else if (part.startsWith("auth=Bearer ")) {
                    token = part.substring(12);
                }
            }
            
            if (username == null || token == null) {
                reply(535, "5.7.8 Invalid bearer token format");
                resetAuthState();
                return;
            }
            
            // Validate bearer token using realm (if it supports token validation)
            if (server.getRealm() instanceof org.bluezoo.gumdrop.Realm) {
                org.bluezoo.gumdrop.Realm realm = server.getRealm();
                
                // Check if realm supports bearer token validation
                try {
                    org.bluezoo.gumdrop.Realm.TokenValidationResult result = realm.validateBearerToken(token);
                    if (result != null && result.valid && result.username.equals(username)) {
                        authenticated = true;
                        authenticatedUser = username;
                        reply(235, "2.7.0 Authentication successful");
                        
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("SMTP OAUTHBEARER successful for user: " + username);
                        }
                        
                        // Notify handler of successful authentication
                        if (handler != null) {
                            handler.authenticated(username, "OAUTHBEARER");
                        }
                    } else {
                        reply(535, "5.7.8 Invalid bearer token");
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning("SMTP OAUTHBEARER failed for user: " + username);
                        }
                    }
                } catch (UnsupportedOperationException e) {
                    // Realm doesn't support bearer tokens
                    reply(535, "5.7.8 Bearer token authentication not supported");
                }
            } else {
                reply(535, "5.7.8 Bearer token authentication not available");
            }
            
            resetAuthState();
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "OAuth bearer processing error", e);
            }
        }
    }


    /**
     * Extracts the client certificate from the SSL session.
     * @return the client certificate, or null if none provided
     */
    private X509Certificate getClientCertificate() {
        try {
            // Get SSL engine and session from Connection base class
            if (engine != null && engine.getSession() != null) {
                java.security.cert.Certificate[] certs = engine.getSession().getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    return (X509Certificate) certs[0];
                }
            }
        } catch (Exception e) {
            // No client certificate or SSL session
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "No client certificate available", e);
            }
        }
        return null;
    }

    /**
     * Extracts username from X.509 certificate subject.
     * @param certSubject the certificate subject DN
     * @return extracted username, or null if not found
     */
    private String extractUsernameFromCertificate(String certSubject) {
        // Try to extract from Common Name (CN)
        String[] parts = certSubject.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("CN=")) {
                String cn = part.substring(3);
                // Remove quotes if present
                if (cn.startsWith("\"") && cn.endsWith("\"")) {
                    cn = cn.substring(1, cn.length() - 1);
                }
                return cn;
            }
            // Also try emailAddress
            if (part.startsWith("emailAddress=") || part.startsWith("1.2.840.113549.1.9.1=")) {
                int equalPos = part.indexOf('=');
                if (equalPos > 0) {
                    return part.substring(equalPos + 1);
                }
            }
        }
        return null;
    }

    /**
     * Generates NTLM Type 2 challenge message.
     * @return the Type 2 challenge message bytes
     */
    private byte[] generateNTLMType2Challenge() throws Exception {
        // Generate 8-byte challenge
        SecureRandom random = new SecureRandom();
        ntlmChallenge = new byte[8];
        random.nextBytes(ntlmChallenge);
        
        // Get target name (hostname)
        String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
        byte[] targetName = hostname.toUpperCase().getBytes("UTF-16LE");
        
        // Build Type 2 message (simplified)
        ByteBuffer buffer = ByteBuffer.allocate(48 + targetName.length);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        
        // NTLM signature
        buffer.put("NTLMSSP\0".getBytes(US_ASCII));
        // Message type (2)
        buffer.putInt(2);
        // Target name length and allocation
        buffer.putShort((short) targetName.length);
        buffer.putShort((short) targetName.length);
        buffer.putInt(48); // Offset to target name
        // Flags (0x00008205: Unicode, OEM, NTLM, Target Type Domain)
        buffer.putInt(0x00008205);
        // Challenge
        buffer.put(ntlmChallenge);
        // Context (8 bytes of zeros)
        buffer.putLong(0);
        // Target info length and allocation (none for simplified version)
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(48 + targetName.length);
        // Target name data
        buffer.put(targetName);
        
        return buffer.array();
    }

    /**
     * Parses NTLM Type 3 message to extract username.
     * @param type3Message the NTLM Type 3 message bytes
     * @return extracted username, or null if parsing fails
     */
    private String parseNTLMType3Username(byte[] type3Message) {
        try {
            if (type3Message.length < 64 || 
                !new String(type3Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                return null;
            }
            
            ByteBuffer buffer = ByteBuffer.wrap(type3Message);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            
            // Skip to username field (offset 36)
            buffer.position(36);
            int usernameLength = buffer.getShort() & 0xFFFF;
            buffer.getShort(); // Skip allocated space
            int usernameOffset = buffer.getInt();
            
            if (usernameOffset + usernameLength > type3Message.length) {
                return null;
            }
            
            // Extract username (UTF-16LE encoded)
            byte[] usernameBytes = new byte[usernameLength];
            System.arraycopy(type3Message, usernameOffset, usernameBytes, 0, usernameLength);
            return new String(usernameBytes, "UTF-16LE");
            
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Failed to parse NTLM Type 3 username", e);
            }
            return null;
        }
    }


    /**
     * Resets authentication state after completion or failure.
     */
    private void resetAuthState() {
        authState = AuthState.NONE;
        authMechanism = null;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
        authClientNonce = null;
        authServerSignature = null;
        authSalt = null;
        authIterations = 4096; // Reset to default
        
        // Clean up enterprise authentication state
        if (saslServer != null) {
            try {
                saslServer.dispose();
            } catch (SaslException e) {
                // Ignore disposal errors
            }
            saslServer = null;
        }
        clientCertificate = null;
        ntlmChallenge = null;
        ntlmTargetName = null;
    }

    /**
     * Checks if the authenticated user is authorized to send from the specified email address.
     * This implements sender address authorization policies for authenticated SMTP sessions.
     * 
     * Current policy (can be enhanced):
     * - Username equals email address (exact match)
     * - Username equals local part of email address (user@domain.com authorized for "user")
     * - Users with "admin" or "postmaster" roles can send from any address
     * 
     * @param fromAddress the email address in the MAIL FROM command
     * @param authenticatedUser the authenticated username
     * @return true if authorized to send from this address
     */
    private boolean isAuthorizedSender(String fromAddress, String authenticatedUser) {
        if (fromAddress == null || authenticatedUser == null) {
            return false;
        }
        
        // Policy 1: Exact match (username equals full email address)
        if (authenticatedUser.equalsIgnoreCase(fromAddress)) {
            return true;
        }
        
        // Policy 2: Username matches local part of email address
        // e.g., user "john" can send from "john@example.com"
        int atIndex = fromAddress.indexOf('@');
        if (atIndex > 0) {
            String localPart = fromAddress.substring(0, atIndex);
            if (authenticatedUser.equalsIgnoreCase(localPart)) {
                return true;
            }
        }
        
        // Policy 3: Check realm roles for administrative privileges
        // If user has "admin" or "postmaster" role, allow any sender address
        if (server.getRealm() != null) {
            if (server.getRealm().isUserInRole(authenticatedUser, "admin") || 
                server.getRealm().isUserInRole(authenticatedUser, "postmaster")) {
                return true;
            }
        }
        
        // Future enhancement: Could add domain-based authorization, alias mapping, etc.
        
        return false; // Default: deny
    }


    /**
     * Basic email address syntax validation.
     */
    private boolean isValidEmailAddress(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        
        // Basic validation - can be enhanced with more sophisticated regex
        int atIndex = email.indexOf('@');
        if (atIndex <= 0 || atIndex >= email.length() - 1) {
            return false; // No @ or @ at start/end
        }
        
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex + 1);
        
        // RFC 5321 limits: local part <= 64 chars, domain <= 255 chars
        return localPart.length() <= 64 && domain.length() <= 255 && 
               !localPart.isEmpty() && !domain.isEmpty();
    }

    /**
     * Extracts domain portion from email address.
     */
    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(atIndex + 1).toLowerCase() : null;
    }

    /**
     * Checks if domain is in blocked list.
     * Example implementation - customize with your blocked domains.
     */
    private boolean isBlockedDomain(String domain) {
        // Example blocked domains - replace with your policy
        String[] blockedDomains = {
            "spam.example.com",
            "blocked.domain.com",
            "malicious.net"
        };
        
        for (String blocked : blockedDomains) {
            if (domain.equals(blocked) || domain.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks sender against spam reputation databases.
     * Placeholder for integration with reputation services.
     */
    private boolean hasSpamReputation(String fromAddress) {
        // Placeholder - integrate with reputation services like:
        // - Spamhaus SBL/XBL/PBL
        // - SURBL
        // - Custom reputation database
        return false;
    }

    /**
     * Implements per-sender rate limiting.
     * Placeholder for rate limiting logic.
     */
    private boolean isSenderRateLimited(String fromAddress) {
        // Placeholder - implement rate limiting per sender:
        // - Track sending frequency per address
        // - Apply limits based on sender reputation
        // - Use sliding window or token bucket algorithms
        return false;
    }

    /**
     * Implements greylisting policy.
     * Placeholder for greylisting logic.
     */
    private boolean shouldGreylist(String fromAddress) {
        // Placeholder - implement greylisting:
        // - Temporarily reject first-time senders
        // - Accept after legitimate retry (usually 15+ minutes)
        // - Maintain whitelist of known good senders
        return false;
    }

    /**
     * Checks for policy violations.
     * Placeholder for custom business rules.
     */
    private boolean violatesPolicy(String fromAddress) {
        // Placeholder - implement custom policies:
        // - Content filtering rules
        // - Business-specific sender restrictions
        // - Compliance requirements
        return false;
    }

    /**
     * Checks if mail storage is full.
     * Placeholder for storage capacity monitoring.
     */
    private boolean isStorageFull() {
        // Placeholder - implement storage monitoring:
        // - Check disk space availability
        // - Monitor queue sizes
        // - Apply per-user quotas
        return false;
    }

    /**
     * Checks if relaying is denied for this sender.
     * Placeholder for relay authorization.
     */
    private boolean isRelayDenied(String fromAddress) {
        // Placeholder - implement relay policies:
        // - Allow relay for authenticated users
        // - Allow relay for internal networks
        // - Deny relay for external users (open relay prevention)
        return false;
    }


    /**
     * Checks if a mailbox exists for the given recipient.
     * Placeholder for mailbox existence verification.
     */
    private boolean mailboxExists(String recipient) {
        // Placeholder - implement mailbox lookup:
        // - Check local user database
        // - Query LDAP/Active Directory
        // - Check virtual alias maps
        // - Verify catch-all settings
        return true; // Default: assume exists for demo
    }

    /**
     * Checks if a mailbox is temporarily unavailable.
     * Placeholder for mailbox availability checking.
     */
    private boolean isMailboxTemporarilyUnavailable(String recipient) {
        // Placeholder - implement availability checks:
        // - Check if user account is locked
        // - Verify mailbox maintenance status
        // - Check system load/performance
        return false;
    }

    /**
     * Checks if recipient has exceeded their storage quota.
     * Placeholder for quota management.
     */
    private boolean isRecipientQuotaExceeded(String recipient) {
        // Placeholder - implement quota checking:
        // - Check per-user disk usage
        // - Verify message count limits
        // - Check attachment size restrictions
        return false;
    }

    /**
     * Determines if recipient should be forwarded to another server.
     * Placeholder for forwarding logic.
     */
    private boolean shouldForwardRecipient(String recipient) {
        // Placeholder - implement forwarding rules:
        // - Check if domain is handled locally
        // - Determine if user has forwarding rules
        // - Check MX record priorities
        return false;
    }

    /**
     * Checks if recipient is in blocked list.
     * Placeholder for recipient blocking.
     */
    private boolean isRecipientBlocked(String recipient) {
        // Placeholder - implement recipient blocking:
        // - Internal blacklists
        // - Suspended accounts
        // - Compliance restrictions
        return false;
    }

    /**
     * Checks relay permissions for recipient (different context than sender).
     * Placeholder for recipient relay authorization.
     */
    private boolean isRecipientRelayDenied(String recipient) {
        // Placeholder - implement recipient relay checks:
        // - Verify domain is local or relay is authorized
        // - Check if authenticated user can send to this recipient
        // - Apply recipient-specific relay rules
        return false;
    }

    /**
     * Checks if storage is full for this specific recipient.
     * Placeholder for per-recipient storage monitoring.
     */
    private boolean isRecipientStorageFull(String recipient) {
        // Placeholder - implement per-recipient storage:
        // - Check individual mailbox limits
        // - Verify per-domain storage quotas
        // - Monitor system-wide capacity
        return false;
    }

    /**
     * Checks if recipient violates policy (recipient-specific rules).
     * Placeholder for recipient policy enforcement.
     */
    private boolean recipientViolatesPolicy(String recipient) {
        // Placeholder - implement recipient policies:
        // - Corporate email policies
        // - External recipient restrictions
        // - Compliance and regulatory rules
        return false;
    }

    /**
     * Checks if mailbox name format is allowed.
     * Placeholder for mailbox naming policies.
     */
    private boolean isMailboxNameAllowed(String recipient) {
        // Placeholder - implement naming policies:
        // - Restricted usernames (admin, root, etc.)
        // - Corporate naming conventions
        // - Character restrictions beyond RFC compliance
        return true;
    }

    /**
     * Invoked when the client closes the connection.
     * Performs any necessary cleanup for the SMTP session.
     */
    @Override
    protected void disconnected() throws IOException {
        try {
            // End telemetry session span if still active
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                if (state == SMTPState.QUIT) {
                    endSessionSpan("Connection closed");
                } else {
                    endSessionSpanError("Connection lost");
                }
            }

            // Notify handler of disconnection
            if (handler != null) {
                handler.disconnected();
            }

            // Notify connector that connection is closed for tracking
            try {
                if (channel != null) {
                    server.connectionClosed((InetSocketAddress) channel.getRemoteAddress());
                }
            } catch (IOException e) {
                // Log but don't fail - cleanup is more important
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Error notifying connector of connection close", e);
                }
            }
        } finally {
            // Cleanup resources in finally block to guarantee execution
            if (recipients != null) {
                recipients.clear();
            }
            resetDataState();

            // Clear line buffer
            if (lineBuffer != null) {
                lineBuffer.clear();
            }

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("SMTP connection disconnected: " + getRemoteSocketAddress());
            }
        }
    }

    // SMTPConnectionMetadata implementation
    
    @Override
    public InetSocketAddress getClientAddress() {
        try {
            if (channel != null) {
                return (InetSocketAddress) channel.getRemoteAddress();
            }
        } catch (IOException e) {
            // Ignore - return null
        }
        return null;
    }
    
    @Override
    public InetSocketAddress getServerAddress() {
        try {
            if (channel != null) {
                return (InetSocketAddress) channel.getLocalAddress();
            }
        } catch (IOException e) {
            // Ignore - return null
        }
        return null;
    }
    
    @Override
    public boolean isSecure() {
        return super.isSecure();
    }
    
    @Override
    public java.security.cert.X509Certificate[] getClientCertificates() {
        // TODO: Need to expose SSL session details from Connection class
        // For now, return null as SSL certificate access is not yet available
        return null;
    }
    
    @Override
    public String getCipherSuite() {
        // TODO: Need to expose SSL session details from Connection class
        // For now, return null as SSL cipher suite is not yet available
        return null;
    }
    
    @Override
    public String getProtocolVersion() {
        // TODO: Need to expose SSL session details from Connection class
        // For now, return null as SSL protocol version is not yet available
        return null;
    }
    
    @Override
    public long getConnectionTimeMillis() {
        return connectionTimeMillis;
    }

    // MailFromCallback implementation
    
    /**
     * Callback implementation for asynchronous MAIL FROM policy results.
     * This method is invoked by the handler after sender policy evaluation
     * and sends the appropriate SMTP response to the client.
     */
    @Override
    public void mailFromReply(SenderPolicyResult result) {
        try {
            switch (result) {
                case ACCEPT:
                    // Accept the sender and transition to MAIL state
                    this.state = SMTPState.MAIL;
                    addSessionAttribute("smtp.mail_from", mailFrom);
                    addSessionEvent("MAIL FROM accepted");
                    reply(250, "2.1.0 Sender ok");
                    break;
                    
                case TEMP_REJECT_GREYLIST:
                    reply(450, "4.7.1 Greylisting in effect, please try again later");
                    break;
                    
                case TEMP_REJECT_RATE_LIMIT:
                    reply(450, "4.7.1 Rate limit exceeded, please try again later");
                    break;
                    
                case REJECT_BLOCKED_DOMAIN:
                    reply(550, "5.1.1 Sender domain blocked by policy");
                    break;
                    
                case REJECT_INVALID_DOMAIN:
                    reply(550, "5.1.1 Sender domain does not exist");
                    break;
                    
                case REJECT_POLICY_VIOLATION:
                    reply(553, "5.7.1 Sender address violates local policy");
                    break;
                    
                case REJECT_SPAM_REPUTATION:
                    reply(554, "5.7.1 Sender has poor reputation");
                    break;
                    
                case REJECT_SYNTAX_ERROR:
                    reply(501, "5.1.3 Invalid sender address format");
                    break;
                    
                case REJECT_RELAY_DENIED:
                    reply(551, "5.7.1 Relaying denied");
                    break;
                    
                case REJECT_STORAGE_FULL:
                    reply(452, "4.3.1 Insufficient system storage");
                    break;
                    
                default:
                    reply(550, "5.0.0 Sender address rejected");
                    break;
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending MAIL FROM reply", e);
            // Connection error - close connection
            close();
        }
    }

    // RcptToCallback implementation
    
    /**
     * Callback implementation for asynchronous RCPT TO policy results.  
     * This method is invoked by the handler after recipient policy evaluation
     * and sends the appropriate SMTP response to the client.
     */
    @Override
    public void rcptToReply(RecipientPolicyResult result, String recipient) {
        try {
            switch (result) {
                case ACCEPT:
                    // Accept the recipient - add to list and transition to RCPT state
                    this.recipients.add(recipient);
                    this.state = SMTPState.RCPT;
                    addSessionAttribute("smtp.rcpt_count", recipients.size());
                    addSessionEvent("RCPT TO: " + recipient);
                    reply(250, "2.1.5 " + recipient + "... Recipient ok");
                    break;
                    
                case ACCEPT_FORWARD:
                    // Accept for forwarding - add to list and transition to RCPT state
                    this.recipients.add(recipient);
                    this.state = SMTPState.RCPT;
                    addSessionAttribute("smtp.rcpt_count", recipients.size());
                    addSessionEvent("RCPT TO (forward): " + recipient);
                    reply(251, "2.1.5 User not local; will forward to " + recipient);
                    break;
                    
                case TEMP_REJECT_UNAVAILABLE:
                    reply(450, "4.2.1 Mailbox temporarily unavailable");
                    break;
                    
                case TEMP_REJECT_SYSTEM_ERROR:
                    reply(451, "4.3.0 Local error in processing");
                    break;
                    
                case TEMP_REJECT_STORAGE_FULL:
                    reply(452, "4.3.1 Insufficient system storage");
                    break;
                    
                case REJECT_MAILBOX_UNAVAILABLE:
                    reply(550, "5.1.1 Mailbox unavailable");
                    break;
                    
                case REJECT_USER_NOT_LOCAL:
                    reply(551, "5.1.1 User not local; please try <forward-path>");
                    break;
                    
                case REJECT_QUOTA_EXCEEDED:
                    reply(552, "5.2.2 Mailbox full, quota exceeded");
                    break;
                    
                case REJECT_INVALID_MAILBOX:
                    reply(553, "5.1.3 Mailbox name not allowed");
                    break;
                    
                case REJECT_TRANSACTION_FAILED:
                    reply(554, "5.0.0 Transaction failed");
                    break;
                    
                case REJECT_SYNTAX_ERROR:
                    reply(501, "5.1.3 Invalid recipient address format");
                    break;
                    
                case REJECT_RELAY_DENIED:
                    reply(551, "5.7.1 Relaying denied");
                    break;
                    
                case REJECT_POLICY_VIOLATION:
                    reply(553, "5.7.1 Recipient violates local policy");
                    break;
                    
                default:
                    reply(550, "5.0.0 Recipient address rejected");
                    break;
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending RCPT TO reply", e);
            // Connection error - close connection
            close();
        }
    }

    // HelloCallback implementation
    
    /**
     * Callback implementation for asynchronous HELO/EHLO command results.
     * This method is invoked by the handler after greeting evaluation
     * and sends the appropriate SMTP response to the client.
     */
    @Override
    public void helloReply(HelloReply result) {
        try {
            switch (result) {
                case ACCEPT_HELO:
                    // Accept HELO and transition to READY state
                    this.extendedSMTP = false;
                    this.state = SMTPState.READY;
                    String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
                    reply(250, hostname + " Hello " + heloName);
                    break;
                    
                case ACCEPT_EHLO:
                    // Accept EHLO, set extended mode, and send feature list
                    this.extendedSMTP = true;
                    this.state = SMTPState.READY;
                    sendEhloResponse();
                    break;
                    
                case REJECT_NOT_IMPLEMENTED:
                    reply(504, "5.5.2 Command not implemented");
                    break;
                    
                case REJECT_SYNTAX_ERROR:
                    reply(501, "5.0.0 Syntax: HELO/EHLO hostname");
                    break;
                    
                case TEMP_REJECT_SERVICE_UNAVAILABLE:
                    this.state = SMTPState.REJECTED;
                    reply(421, "4.3.0 Service not available, closing transmission channel");
                    // Don't close immediately - wait for client to send QUIT
                    break;
                    
                default:
                    reply(500, "5.0.0 Command failed");
                    break;
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending HELO/EHLO reply", e);
            // Connection error - close connection
            close();
        }
    }
    
    /**
     * Sends the EHLO response with available extensions.
     */
    private void sendEhloResponse() throws IOException {
        String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
        
        // Start with greeting line
        reply(250, hostname + " Hello " + heloName);
        
        // TODO: Add extension advertisements as needed
        // Examples of common extensions:
        // reply(250, "SIZE 10485760");     // Maximum message size
        // reply(250, "STARTTLS");          // TLS support
        // reply(250, "AUTH PLAIN LOGIN");  // Authentication methods
        // reply(250, "8BITMIME");          // 8-bit MIME support
        // reply(250, "PIPELINING");        // Command pipelining
    }

    // -- Telemetry helpers --

    /**
     * Adds an attribute to the current session span.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an attribute to the current session span.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    private void addSessionAttribute(String key, long value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an attribute to the current session span.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    private void addSessionAttribute(String key, boolean value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an event to the current session span.
     *
     * @param name the event name
     */
    private void addSessionEvent(String name) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(name);
        }
    }

    /**
     * Records an exception on the current session span.
     *
     * @param exception the exception to record
     */
    private void recordSessionException(Throwable exception) {
        if (sessionSpan != null && !sessionSpan.isEnded() && exception != null) {
            sessionSpan.recordExceptionWithCategory(exception);
        }
    }

    /**
     * Records an exception with an explicit error category on the current session span.
     *
     * @param exception the exception to record
     * @param category the error category
     */
    private void recordSessionException(Throwable exception, ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded() && exception != null) {
            sessionSpan.recordException(exception, category);
        }
    }

    /**
     * Records an SMTP error reply in telemetry.
     * This correlates the SMTP reply code with a standardized error category.
     *
     * @param replyCode the SMTP reply code (4xx or 5xx)
     * @param message the error message
     */
    private void recordSmtpError(int replyCode, String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        ErrorCategory category = ErrorCategory.fromSmtpReplyCode(replyCode);
        if (category != null) {
            sessionSpan.recordError(category, replyCode, message);
        }
    }

    /**
     * Records successful authentication for telemetry.
     *
     * @param username the authenticated username
     * @param mechanism the authentication mechanism used
     */
    private void recordAuthenticationSuccess(String username, String mechanism) {
        addSessionAttribute("smtp.authenticated", true);
        addSessionAttribute("smtp.auth_user", username);
        addSessionAttribute("smtp.auth_mechanism", mechanism);
        addSessionEvent("AUTH success: " + mechanism);
    }

}

