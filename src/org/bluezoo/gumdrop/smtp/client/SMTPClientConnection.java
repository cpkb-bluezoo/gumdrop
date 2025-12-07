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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;

/**
 * Event-driven, NIO-based SMTP client connection.
 * 
 * <p>This implementation focuses purely on SMTP protocol logic.
 * All networking, SSL, and threading concerns are handled by the 
 * Connection base class and SMTPClient.
 * 
 * <p>Key features:
 * <ul>
 * <li>Sequential SMTP command processing (wait for response before next command)</li>
 * <li>Streaming message content without memory buffering</li>
 * <li>Automatic dot stuffing across chunk boundaries</li>
 * <li>Simple callback-based response handling</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPClientConnection extends Connection implements WritableByteChannel {
    
    private static final Logger logger = Logger.getLogger(SMTPClientConnection.class.getName());
    
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final String CRLF = "\r\n";
    
    // SMTP Protocol state - Connection handles all networking
    private SMTPState state = SMTPState.DISCONNECTED;
    
    protected final SMTPClient client;
    protected final SMTPClientHandler handler;

    // Protocol handling
    private final ResponseParser responseParser;
    private final DotStuffer dotStuffer;
    private ByteBuffer readBuffer;
    
    /**
     * Creates SMTP client connection.
     * Threading and networking handled by Connection framework.
     */
    protected SMTPClientConnection(SMTPClient client, SocketChannel channel, SSLEngine engine, boolean secure, SMTPClientHandler handler) {
        super(engine, secure);
        this.client = client;
        this.handler = handler;
        this.responseParser = new ResponseParser();
        this.dotStuffer = new DotStuffer();
        this.readBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        // Note: channel is set by SelectorLoop when connection is registered
    }
    
    // Connection state methods
    
    public boolean isConnected() {
        return state != SMTPState.DISCONNECTED && state != SMTPState.CLOSED && state != SMTPState.ERROR;
    }
    
    public SMTPState getState() {
        return state;
    }
    
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
        
        // Channel closing is handled by Connection base class
        super.close();
        
        responseParser.reset();
        dotStuffer.reset();
    }
    
    // Connection lifecycle methods
    
    @Override
    public void connected() {
        state = SMTPState.CONNECTING;
        logger.fine("Client socket connected, waiting for server greeting");
        handler.onConnected();
    }
    
    @Override
    public void finishConnectFailed(IOException cause) {
        handleConnectionError(cause);
    }
    
    @Override
    public void receive(ByteBuffer buf) {
        try {
            // SMTP client only receives line-based responses (never DATA content)
            // Handle similar to server side command processing
            
            // Add new data to our read buffer
            appendToReadBuffer(buf);
            
            // Extract and process complete responses (lines)
            String line;
            while ((line = extractCompleteLine()) != null) {
                handleReply(line);
            }
        } catch (Exception e) {
            handleError(new SMTPException("Error processing received data", e));
        }
    }
    
    @Override
    protected void disconnected() throws IOException {
        handleDisconnection();
    }

    @Override
    protected void handshakeComplete(String protocol) {
        super.handshakeComplete(protocol);
        
        // After successful TLS upgrade, reset SMTP state as per RFC
        // SMTP requires that client re-issue EHLO after STARTTLS
        state = SMTPState.CONNECTED;
        
        logger.fine("TLS handshake complete, notifying handler");
        handler.onTLSStarted();
    }
    
    // WritableByteChannel implementation for DotStuffer
    
    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected");
        }
        
        int bytesToWrite = src.remaining();
        
        // Create a copy of the data since src might be reused/modified
        ByteBuffer copy = ByteBuffer.allocate(bytesToWrite);
        copy.put(src);
        copy.flip();
        
        // Use inherited send() method from Connection
        send(copy);
        
        return bytesToWrite;
    }
    
    // SMTP Command methods

    /**
     * Issue a HELO to the server.
     * @param hostname the hostname to send
     */    
    public void helo(String hostname) {
        sendCommand("HELO " + hostname, SMTPState.HELO_SENT);
    }
    
    /**
     * Issue an EHLO to the server.
     * @param hostname the hostname to send
     */
    public void ehlo(String hostname) {
        sendCommand("EHLO " + hostname, SMTPState.EHLO_SENT);
    }
    
    /**
     * Issue a MAIL FROM command to the server.
     * @param sender the sender from which the mail should be from
     */
    public void mailFrom(String sender) {
        sendCommand("MAIL FROM:<" + sender + ">", SMTPState.MAIL_FROM_SENT);
    }
    
    /**
     * Issue a RCPT TO command to the server.
     * @param recipient the address of the recipient
     */
    public void rcptTo(String recipient) {
        sendCommand("RCPT TO:<" + recipient + ">", SMTPState.RCPT_TO_SENT);
    }
    
    /**
     * Issue a DATA command to the server.
     * This indicates that message content will follow.
     */
    public void data() {
        sendCommand("DATA", SMTPState.DATA_COMMAND_SENT);
    }
    
    /**
     * Sends message content to the server.
     * The content bytes will be dot stuffed as necessary.
     * @param content the RFC822 message content
     */
    public void messageContent(ByteBuffer content) throws IOException {
        if (state != SMTPState.DATA_MODE) {
            throw new IllegalStateException("Not in data mode");
        }
        dotStuffer.processChunk(content, this);
    }
    
    /**
     * Notifies the server that we have completed sending the message.
     */
    public void endData() throws IOException {
        if (state != SMTPState.DATA_MODE) {
            throw new IllegalStateException("Not in data mode");
        }
        
        dotStuffer.endMessage(this);
        
        this.state = SMTPState.DATA_END_SENT;
    }
    
    /**
     * Issues a RSET command to the server.
     * This can be used to reset the connection to send another message.
     */
    public void rset() {
        sendCommand("RSET", SMTPState.RSET_SENT);
    }
    
    /**
     * Sends a QUIT command to the server.
     * This is used to shut down the connection cleanly when we have no more
     * messages to send.
     */
    public void quit() {
        sendCommand("QUIT", SMTPState.QUIT_SENT);
    }
    
    /**
     * Requests an upgrade to a TLS connection.
     */
    public void starttls() {
        sendCommand("STARTTLS", SMTPState.STARTTLS_SENT);
    }
    
    // Internal methods
    
    private void sendCommand(String command, SMTPState newState) {
        if (!isConnected()) {
            handler.onError(new SMTPException("Not connected"));
            return;
        }
        
        this.state = newState;
        
        try {
            String fullCommand = command + CRLF;
            ByteBuffer commandBuffer = ByteBuffer.wrap(fullCommand.getBytes("ASCII"));
            
            // Create a copy since the wrapped byte array might be reused
            ByteBuffer copy = ByteBuffer.allocate(commandBuffer.remaining());
            copy.put(commandBuffer);
            copy.flip();
            
            // Use inherited send() method from Connection
            send(copy);
            
            logger.fine("Sent SMTP command: " + command);
        } catch (IOException e) {
            handleError(new SMTPException("Failed to send command: " + command, e));
        }
    }
    
    /**
     * Appends new data to the read buffer, expanding if necessary.
     */
    private void appendToReadBuffer(ByteBuffer newData) {
        int newDataSize = newData.remaining();
        int currentDataSize = readBuffer.position();
        int requiredCapacity = currentDataSize + newDataSize;
        
        if (requiredCapacity > readBuffer.capacity()) {
            // Expand buffer
            ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(requiredCapacity, readBuffer.capacity() * 2));
            readBuffer.flip();
            newBuffer.put(readBuffer);
            readBuffer = newBuffer;
        }
        
        // Add new data
        readBuffer.put(newData);
    }
    
    /**
     * Extracts a complete CRLF-terminated line from the read buffer.
     * Similar to server-side extractCompleteLine method.
     */
    private String extractCompleteLine() throws IOException {
        readBuffer.flip(); // Switch to read mode
        
        // Look for CRLF sequence
        int crlfIndex = -1;
        for (int i = 0; i < readBuffer.limit() - 1; i++) {
            if (readBuffer.get(i) == '\r' && readBuffer.get(i + 1) == '\n') {
                crlfIndex = i;
                break;
            }
        }
        
        if (crlfIndex >= 0) {
            // Found complete line - extract it
            byte[] lineBytes = new byte[crlfIndex];
            readBuffer.get(lineBytes);
            
            // Skip the CRLF
            readBuffer.get(); // skip CR
            readBuffer.get(); // skip LF
            
            // Compact remaining data
            readBuffer.compact();
            
            // Convert to string (SMTP is ASCII-based)
            return new String(lineBytes, "ASCII");
        } else {
            // No complete line yet - restore buffer state
            readBuffer.compact();
            return null;
        }
    }
    
    /**
     * Handles a complete SMTP reply line.
     * Parses the line into an SMTPResponse and determines success/failure.
     */
    private void handleReply(String line) {
        try {
            // Parse the line into an SMTPResponse
            SMTPResponse response = parseResponseLine(line);
            handleResponse(response);
        } catch (Exception e) {
            handleError(new SMTPException("Failed to parse SMTP response: " + line, e));
        }
    }
    
    /**
     * Parses a single SMTP response line into an SMTPResponse object.
     */
    private SMTPResponse parseResponseLine(String line) throws SMTPException {
        if (line == null || line.length() < 3) {
            throw new SMTPException("Invalid SMTP response: " + line);
        }
        
        try {
            // Parse response code (first 3 characters)
            int code = Integer.parseInt(line.substring(0, 3));
            
            // Get message (everything after code and space/hyphen)
            String message = "";
            if (line.length() > 3) {
                if (line.charAt(3) == ' ' || line.charAt(3) == '-') {
                    message = line.substring(4);
                } else {
                    throw new SMTPException("Invalid SMTP response format: " + line);
                }
            }
            
            return new SMTPResponse(code, message);
        } catch (NumberFormatException e) {
            throw new SMTPException("Invalid SMTP response code: " + line, e);
        }
    }
    
    private void handleResponse(SMTPResponse response) {
        logger.fine("Received SMTP response: " + response.getCode() + " " + response.getMessage());
        
        // Distinguish between greeting and regular replies
        boolean isGreeting = (state == SMTPState.CONNECTING);
        
        // Update state based on response and current state
        switch (state) {
            case CONNECTING:
                if (response.isSuccess()) {
                    state = SMTPState.CONNECTED;
                } else {
                    state = SMTPState.ERROR;
                }
                break;
                
            case HELO_SENT:
            case EHLO_SENT:
                state = response.isSuccess() ? SMTPState.CONNECTED : SMTPState.ERROR;
                break;
                
            case MAIL_FROM_SENT:
            case RCPT_TO_SENT:
                // Stay in same state after successful command
                if (!response.isSuccess()) {
                    state = SMTPState.ERROR;
                }
                break;
                
            case DATA_COMMAND_SENT:
                if (response.isSuccess() && response.getCode() == 354) {
                    state = SMTPState.DATA_MODE;
                } else {
                    state = SMTPState.ERROR;
                }
                break;
                
            case DATA_END_SENT:
                state = response.isSuccess() ? SMTPState.CONNECTED : SMTPState.ERROR;
                break;
                
            case RSET_SENT:
                state = response.isSuccess() ? SMTPState.CONNECTED : SMTPState.ERROR;
                break;
                
            case QUIT_SENT:
                state = SMTPState.CLOSED;
                break;
                
            case STARTTLS_SENT:
                if (response.isSuccess()) {
                    // Server accepted STARTTLS, initiate TLS upgrade
                    try {
                        initializeSSLState();
                        // State will be updated in handshakeComplete() after TLS handshake
                        logger.fine("TLS upgrade initiated after STARTTLS response");
                    } catch (IOException e) {
                        logger.warning("Failed to initialize TLS after STARTTLS: " + e.getMessage());
                        state = SMTPState.ERROR;
                        handler.onError(new SMTPException("TLS initialization failed", e));
                    }
                } else {
                    state = SMTPState.ERROR;
                }
                break;
                
            default:
                logger.warning("Unexpected response in state " + state + ": " + response);
        }
        
        // Notify handler with appropriate method
        if (isGreeting) {
            handler.onGreeting(response, this);
        } else {
            handler.onReply(response, this);
        }
    }
    
    private void handleError(SMTPException error) {
        logger.warning("SMTP error: " + error.getMessage());
        
        state = SMTPState.ERROR;
        handler.onError(error);
    }
    
    private void handleConnectionError(IOException cause) {
        handleError(new SMTPException("Connection failed", cause));
    }
    
    private void handleDisconnection() {
        logger.info("SMTP connection disconnected");
        
        state = SMTPState.CLOSED;
        handler.onDisconnected();
    }
}
