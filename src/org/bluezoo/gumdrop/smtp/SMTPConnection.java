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
     * Creates a new SMTP connection.
     * @param connector the SMTP connector that created this connection
     * @param channel the socket channel for this connection
     * @param engine the SSL engine if this is a secure connection, null for plaintext
     * @param secure true if this connection should use TLS encryption
     */
    protected SMTPConnection(SMTPConnector connector, SocketChannel channel, SSLEngine engine, boolean secure) {
        super(engine, secure);
        this.connector = connector;
        this.channel = channel;
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
        // STARTTLS is only available on plaintext connections that can be upgraded
        if (!secure && engine == null) {
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
     * Processes a complete RFC822 message received during DATA command.
     * @param messageBuffer the complete message content as bytes
     */
    private void messageContent(ByteBuffer messageBuffer) {
        // TODO: Implement message processing
        // This should involve:
        // 1. Parsing message headers and body from RFC822 format
        // 2. Virus/spam checking
        // 3. Storing the message
        // 4. Forwarding to recipients
        
        if (LOGGER.isLoggable(Level.INFO)) {
            int messageSize = messageBuffer.position(); // Amount of data in buffer
            LOGGER.info("Received message from " + mailFrom + " to " + recipients + 
                       " (" + messageSize + " bytes)");
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
        
        // Authentication capability (placeholder for future implementation)
        if (isSecure() || connector.isSTARTTLSAvailable()) {
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

        if (args == null || !args.toUpperCase().startsWith("FROM:")) {
            reply(501, "5.0.0 Syntax: MAIL FROM:<address>");
            return;
        }

        // Parse email address from FROM:<email>
        String fromAddr = args.substring(5).trim();
        if (fromAddr.startsWith("<") && fromAddr.endsWith(">")) {
            fromAddr = fromAddr.substring(1, fromAddr.length() - 1);
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

        this.recipients.add(toAddr);
        this.state = SMTPState.RCPT;
        
        reply(250, "2.1.5 " + toAddr + "... Recipient ok");
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
            
            // Create SSL engine for this connection
            SSLEngine newEngine = connector.createSSLEngine(channel);
            if (newEngine == null) {
                throw new IOException("Failed to create SSL engine");
            }
            
            // Initialize SSL state for this connection
            // Note: This is a workaround since Connection.engine is final
            // We need to manually create and initialize the SSL state
            initializeSSLState(newEngine);
            
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
     * Initialize SSL state for STARTTLS upgrade.
     * This is a workaround for the Connection class having a final SSLEngine field.
     * @param sslEngine the SSL engine to use for the upgraded connection
     */
    private void initializeSSLState(SSLEngine sslEngine) throws IOException {
        // TODO: This needs to properly initialize the SSL state
        // The challenge is that Connection.engine is final and Connection.sslState is private
        // We may need to modify the Connection class or use reflection
        
        // For now, set the secure flag to indicate TLS is active
        secure = true;
        
        // The actual SSL handshake and state initialization would happen here
        // This might require modifications to the Connection base class to support
        // dynamic SSL upgrade, or using reflection to access private fields
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("SSL state initialized for STARTTLS upgrade");
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
     * Invoked when the client closes the connection.
     * Performs any necessary cleanup for the SMTP session.
     */
    @Override
    protected void disconnected() throws IOException {
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
