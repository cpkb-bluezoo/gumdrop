/*
 * SMTPConnection.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.util.LineInput;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
public class SMTPConnection extends Connection {

    private static final Logger LOGGER = Logger.getLogger(SMTPConnection.class.getName());

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
        NONE,           // No authentication in progress
        PLAIN_RESPONSE, // PLAIN: waiting for credentials
        LOGIN_USERNAME, // LOGIN: waiting for username
        LOGIN_PASSWORD  // LOGIN: waiting for password
    }


    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }

    private final SocketChannel channel;
    private final Map<String, IOConsumer<String>> commands;
    private final SMTPConnector connector;
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
    private String pendingAuthUsername = null; // For LOGIN mechanism
    
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
     * Creates connection metadata for handler notifications.
     * @return current connection metadata
     */
    private SMTPConnectionMetadata createMetadata() {
        try {
            InetSocketAddress clientAddr = (InetSocketAddress) channel.getRemoteAddress();
            InetSocketAddress serverAddr = (InetSocketAddress) channel.getLocalAddress();
            
            // SSL/TLS information - simplified since we can't access sslState directly
            String cipherSuite = null;
            String protocolVersion = null;
            java.security.cert.X509Certificate[] clientCerts = null;
            
            // Note: SSL session details would need to be exposed by Connection class
            // For now, we can only provide the secure status
            
            return new SMTPConnectionMetadata(
                clientAddr,
                serverAddr,
                isSecure(),
                clientCerts,
                cipherSuite,
                protocolVersion,
                authenticated,
                authenticatedUser,
                authMechanism,
                heloName,
                extendedSMTP,
                connectionTimeMillis,
                connector.getDescription()
            );
        } catch (IOException e) {
            // Fallback if we can't get addresses
            return new SMTPConnectionMetadata(
                null, null, isSecure(), null, null, null,
                authenticated, authenticatedUser, authMechanism,
                heloName, extendedSMTP, connectionTimeMillis,
                connector.getDescription()
            );
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
    protected SMTPConnection(SMTPConnector connector, SocketChannel channel, SSLEngine engine, boolean secure, SMTPConnectionHandler handler) {
        super(engine, secure);
        this.connector = connector;
        this.channel = channel;
        this.handler = handler;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.in = ByteBuffer.allocate(4096);
        this.lineReader = new LineReader();
        this.recipients = new ArrayList<>();
        this.commands = new HashMap<>();
        
        // Initialize buffers with RFC 5321 limits
        this.lineBuffer = ByteBuffer.allocate(MAX_COMMAND_LINE_LENGTH); // Buffer for accumulating partial lines
        this.controlBuffer = ByteBuffer.allocate(MAX_CONTROL_BUFFER_SIZE); // Small buffer for partial control sequences
        
        // Initialize SMTP command mappings
        commands.put("HELO", this::helo);
        commands.put("EHLO", this::ehlo);
        commands.put("MAIL", this::mail);
        commands.put("RCPT", this::rcpt);
        commands.put("DATA", this::data);
        commands.put("RSET", this::rset);
        commands.put("QUIT", this::quit);
        commands.put("NOOP", this::noop);
        commands.put("HELP", this::help);
        commands.put("VRFY", this::vrfy);
        commands.put("EXPN", this::expn);
        // STARTTLS is only available on plaintext connections that have an SSLEngine available
        if (!secure && engine != null) {
            commands.put("STARTTLS", this::starttls);
        }
        commands.put("AUTH", this::auth);
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
    protected synchronized void received(ByteBuffer buf) {
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
            if (state != SMTPState.DATA) return; // Terminated
        }
        
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

        // Handle authentication state
        if (authState != AuthState.NONE) {
            handleAuthData(line);
            return;
        }

        // Parse command and arguments
        int si = line.indexOf(' ');
        String command = (si > 0) ? line.substring(0, si).toUpperCase() : line.toUpperCase();
        String args = (si > 0) ? line.substring(si + 1) : null;

        IOConsumer<String> function = commands.get(command);
        if (function == null) {
            reply(500, "5.5.1 Command unrecognized: " + command);
        } else {
            function.accept(args);
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
            handler.messageContent(readOnlyBuffer, createMetadata());
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
            ByteBuffer newDest = ByteBuffer.allocate(dest.capacity() + Math.max(source.remaining(), 4096));
            dest.flip();
            newDest.put(dest);
            
            // Replace the buffer reference
            if (dest == lineBuffer) {
                lineBuffer = newDest;
            } else if (dest == controlBuffer) {
                controlBuffer = newDest;
            }
            dest = newDest;
        }
        
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
            
            // Create a view of just the chunk we want to send
            source.position(start);
            source.limit(end);
            ByteBuffer chunk = source.slice();
            
            // Restore the source buffer state
            source.position(savedPosition);
            source.limit(savedLimit);
            
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
        
        // Process as much as we can from the control buffer
        ByteBuffer tempBuf = controlBuffer.slice();
        controlBuffer.position(0);
        controlBuffer.limit(controlBuffer.capacity());
        controlBuffer.clear();
        
        // Reset position to continue processing
        newData.position(0);
        
        // Process the combined data
        handleDataContent(tempBuf);
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
        source.position(start);
        
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
        
        source.position(savedPosition);
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
        
        String localHostname = getLocalSocketAddress().toString(); // TODO: get proper hostname
        
        // Send multiline EHLO response with capabilities
        replyMultiline(250, localHostname + " Hello " + hostname);
        
        // Standard capabilities
        reply(250, "SIZE " + connector.getMaxMessageSize()); // Configurable maximum message size
        reply(250, "PIPELINING");   // Support command pipelining
        reply(250, "8BITMIME");     // Support 8-bit MIME
        reply(250, "ENHANCEDSTATUSCODES"); // Enhanced status codes (already using them)
        
        // STARTTLS capability (only if TLS is not already active and SSL context available)
        if (!isSecure() && connector.isSTARTTLSAvailable()) {
            reply(250, "STARTTLS");
        }
        
        // Authentication capability
        // Advertise AUTH if we have a realm configured and either:
        // - Connection is already secure, OR
        // - STARTTLS is available for securing the connection
        if (connector.getRealm() != null && (isSecure() || connector.isSTARTTLSAvailable())) {
            reply(250, "AUTH PLAIN LOGIN"); // Can add CRAM-MD5, DIGEST-MD5 later
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
        if (connector.isAuthRequired() && !authenticated) {
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

        // Apply sender policy checks with appropriate response codes
        SenderPolicyResult policyResult = evaluateSenderPolicy(fromAddr);
        if (policyResult != SenderPolicyResult.ACCEPT) {
            // Different rejection codes based on policy decision
            switch (policyResult) {
                case TEMP_REJECT_GREYLIST:
                    reply(450, "4.7.1 Greylisting in effect, please try again later");
                    return;
                case TEMP_REJECT_RATE_LIMIT:
                    reply(450, "4.7.1 Rate limit exceeded, please try again later");
                    return;
                case REJECT_BLOCKED_DOMAIN:
                    reply(550, "5.1.1 Sender domain blocked by policy");
                    return;
                case REJECT_INVALID_DOMAIN:
                    reply(550, "5.1.1 Sender domain does not exist");
                    return;
                case REJECT_POLICY_VIOLATION:
                    reply(553, "5.7.1 Sender address violates local policy");
                    return;
                case REJECT_SPAM_REPUTATION:
                    reply(554, "5.7.1 Sender has poor reputation");
                    return;
                case REJECT_SYNTAX_ERROR:
                    reply(501, "5.1.3 Invalid sender address format");
                    return;
                case REJECT_RELAY_DENIED:
                    reply(551, "5.7.1 Relaying denied");
                    return;
                case REJECT_STORAGE_FULL:
                    reply(452, "4.3.1 Insufficient system storage");
                    return;
                default:
                    reply(550, "5.0.0 Sender address rejected");
                    return;
            }
        }

        this.mailFrom = fromAddr;
        this.recipients.clear();
        this.state = SMTPState.MAIL;
        
        reply(250, "2.1.0 " + fromAddr + "... Sender ok");
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
        if (connector.isAuthRequired() && !authenticated) {
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

        // Evaluate recipient policy with appropriate response codes
        RecipientPolicyResult recipientResult = evaluateRecipientPolicy(toAddr);
        if (recipientResult != RecipientPolicyResult.ACCEPT && recipientResult != RecipientPolicyResult.ACCEPT_FORWARD) {
            // Different rejection codes based on recipient policy decision
            switch (recipientResult) {
                case TEMP_REJECT_UNAVAILABLE:
                    reply(450, "4.2.1 Mailbox temporarily unavailable");
                    return;
                case TEMP_REJECT_SYSTEM_ERROR:
                    reply(451, "4.3.0 Local error in processing");
                    return;
                case TEMP_REJECT_STORAGE_FULL:
                    reply(452, "4.3.1 Insufficient system storage");
                    return;
                case REJECT_MAILBOX_UNAVAILABLE:
                    reply(550, "5.1.1 Mailbox unavailable");
                    return;
                case REJECT_USER_NOT_LOCAL:
                    reply(551, "5.1.1 User not local; please try <forward-path>");
                    return;
                case REJECT_QUOTA_EXCEEDED:
                    reply(552, "5.2.2 Mailbox full, quota exceeded");
                    return;
                case REJECT_INVALID_MAILBOX:
                    reply(553, "5.1.3 Mailbox name not allowed");
                    return;
                case REJECT_TRANSACTION_FAILED:
                    reply(554, "5.0.0 Transaction failed");
                    return;
                case REJECT_SYNTAX_ERROR:
                    reply(501, "5.1.3 Invalid recipient address format");
                    return;
                case REJECT_RELAY_DENIED:
                    reply(551, "5.7.1 Relaying denied");
                    return;
                case REJECT_POLICY_VIOLATION:
                    reply(553, "5.7.1 Recipient violates local policy");
                    return;
                default:
                    reply(550, "5.1.1 Recipient address rejected");
                    return;
            }
        }

        this.recipients.add(toAddr);
        this.state = SMTPState.RCPT;
        
        // Handle different acceptance responses
        if (recipientResult == RecipientPolicyResult.ACCEPT_FORWARD) {
            reply(251, "2.1.5 User not local; will forward to " + toAddr);
        } else {
            reply(250, "2.1.5 " + toAddr + "... Recipient ok");
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
        if (connector.isAuthRequired() && !authenticated) {
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
        // Reset transaction state
        this.mailFrom = null;
        this.recipients.clear();
        resetDataState();
        this.state = SMTPState.READY;
        
        // Notify handler of reset
        if (handler != null) {
            handler.reset(createMetadata());
        }
        
        reply(250, "2.0.0 Reset state");
    }

    /**
     * QUIT command - Close the connection.
     * @param args should be null or empty for QUIT command  
     */
    private void quit(String args) throws IOException {
        this.state = SMTPState.QUIT;
        reply(221, "2.0.0 Service closing transmission channel");
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
        if (!connector.isSTARTTLSAvailable()) {
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

			// Remove STARTTLS command - no longer valid after upgrade
			commands.remove("STARTTLS");

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
		if (connector.getRealm() == null) {
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
		if (!isSecure() && !connector.isSTARTTLSAvailable()) {
			reply(538, "5.7.11 Encryption required for requested authentication mechanism");
			return;
		}

		if (args == null || args.trim().isEmpty()) {
			reply(501, "5.0.0 Syntax: AUTH mechanism [initial-response]");
			return;
		}

		String[] parts = args.trim().split("\\s+", 2);
		String mechanism = parts[0].toUpperCase();
		String initialResponse = (parts.length > 1) ? parts[1] : null;

		switch (mechanism) {
			case "PLAIN":
				handleAuthPlain(initialResponse);
				break;
			case "LOGIN":
				handleAuthLogin(initialResponse);
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
				reply(235, "2.7.0 Authentication successful");
				if (LOGGER.isLoggable(Level.INFO)) {
					LOGGER.info("SMTP AUTH PLAIN successful for user: " + username);
				}
			} else {
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
					} else {
						reply(535, "5.7.8 Authentication credentials invalid");
						if (LOGGER.isLoggable(Level.WARNING)) {
							LOGGER.warning("SMTP AUTH LOGIN failed for user: " + pendingAuthUsername);
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
	 * Authenticates a user against the configured realm.
	 * @param username the username
	 * @param password the password
	 * @return true if authentication succeeds, false otherwise
	 */
	private boolean authenticateUser(String username, String password) {
		if (connector.getRealm() == null) {
			return false;
		}

		return connector.getRealm().passwordMatch(username, password);
	}

	/**
	 * Resets authentication state after completion or failure.
	 */
	private void resetAuthState() {
		authState = AuthState.NONE;
		authMechanism = null;
		pendingAuthUsername = null;
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
		if (connector.getRealm() != null) {
			if (connector.getRealm().isMember(authenticatedUser, "admin") || 
					connector.getRealm().isMember(authenticatedUser, "postmaster")) {
				return true;
					}
		}

		// Future enhancement: Could add domain-based authorization, alias mapping, etc.

		return false; // Default: deny
	}

	/**
	 * Evaluates sender policy by delegating to the connection handler.
	 * 
	 * @param fromAddress the sender email address from MAIL FROM command
	 * @return policy result indicating accept/reject with appropriate SMTP response code
	 */
	private SenderPolicyResult evaluateSenderPolicy(String fromAddress) {
		if (handler != null) {
			return handler.mailFrom(fromAddress, createMetadata());
		}
		return SenderPolicyResult.ACCEPT; // Default: accept if no handler
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
	 * Evaluates recipient policy by delegating to the connection handler.
	 * 
	 * @param toAddress the recipient email address from RCPT TO command
	 * @return policy result indicating accept/reject with appropriate SMTP response code
	 */
	private RecipientPolicyResult evaluateRecipientPolicy(String toAddress) {
		if (handler != null) {
			return handler.rcptTo(toAddress, createMetadata());
		}
		return RecipientPolicyResult.ACCEPT; // Default: accept if no handler
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
		// Notify handler of disconnection
		if (handler != null) {
			handler.disconnected(createMetadata());
		}

		// Notify connector that connection is closed for tracking
		try {
			if (channel != null) {
				connector.connectionClosed((InetSocketAddress) channel.getRemoteAddress());
			}
		} catch (IOException e) {
			// Log but don't fail - cleanup is more important
			if (LOGGER.isLoggable(Level.WARNING)) {
				LOGGER.log(Level.WARNING, "Error notifying connector of connection close", e);
			}
		}

		// Clean up session state and buffers
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
