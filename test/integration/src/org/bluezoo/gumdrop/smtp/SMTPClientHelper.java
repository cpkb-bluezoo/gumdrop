/*
 * SMTPClientHelper.java
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

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for making raw SMTP requests in integration tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPClientHelper {
    
    /**
     * Result of an SMTP command containing response code and message.
     */
    public static class SMTPResponse {
        public final int code;
        public final String message;
        public final List<String> lines;
        
        public SMTPResponse(int code, String message, List<String> lines) {
            this.code = code;
            this.message = message;
            this.lines = lines;
        }
        
        public boolean isPositive() {
            return code >= 200 && code < 400;
        }
        
        public boolean isIntermediate() {
            return code >= 300 && code < 400;
        }
        
        public boolean isPositiveCompletion() {
            return code >= 200 && code < 300;
        }
        
        public boolean isTransientNegative() {
            return code >= 400 && code < 500;
        }
        
        public boolean isPermanentNegative() {
            return code >= 500 && code < 600;
        }
        
        @Override
        public String toString() {
            return code + " " + message;
        }
    }
    
    /**
     * An SMTP session for sending commands and receiving responses.
     */
    public static class SMTPSession implements Closeable {
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;
        private SMTPResponse lastResponse;
        
        SMTPSession(Socket socket, BufferedReader reader, PrintWriter writer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }
        
        /**
         * Sends an SMTP command and reads the response.
         */
        public SMTPResponse sendCommand(String command) throws IOException {
            writer.print(command + "\r\n");
            writer.flush();
            lastResponse = readResponse();
            return lastResponse;
        }
        
        /**
         * Sends raw data (for DATA command content).
         */
        public void sendData(String data) throws IOException {
            writer.print(data);
            writer.flush();
        }
        
        /**
         * Reads an SMTP response (handles multiline responses).
         */
        public SMTPResponse readResponse() throws IOException {
            List<String> lines = new ArrayList<>();
            String lastLine = null;
            int code = 0;
            
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Connection closed unexpectedly");
                }
                lines.add(line);
                
                if (line.length() < 3) {
                    throw new IOException("Invalid SMTP response: " + line);
                }
                
                try {
                    code = Integer.parseInt(line.substring(0, 3));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid SMTP response code: " + line);
                }
                
                // Check if this is the last line (space after code, not hyphen)
                if (line.length() == 3 || line.charAt(3) == ' ') {
                    lastLine = line.length() > 4 ? line.substring(4) : "";
                    break;
                }
                // Multiline response continues (hyphen after code)
            }
            
            return new SMTPResponse(code, lastLine, lines);
        }
        
        /**
         * Gets the last response received.
         */
        public SMTPResponse getLastResponse() {
            return lastResponse;
        }
        
        /**
         * Closes the SMTP session.
         */
        @Override
        public void close() throws IOException {
            try {
                if (!socket.isClosed()) {
                    sendCommand("QUIT");
                }
            } catch (IOException e) {
                // Ignore errors during close
            } finally {
                socket.close();
            }
        }
    }
    
    /**
     * Default timeout for SMTP operations (2 seconds).
     */
    private static final int DEFAULT_TIMEOUT = 2000;
    
    /**
     * Opens an SMTP session to the specified host and port.
     * Reads and returns the initial greeting.
     */
    public static SMTPSession connect(String host, int port) throws IOException {
        return connect(host, port, DEFAULT_TIMEOUT);
    }
    
    /**
     * Opens an SMTP session to the specified host and port.
     * Reads and returns the initial greeting.
     */
    public static SMTPSession connect(String host, int port, int timeout) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(timeout);
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), timeout);
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), false);
        
        SMTPSession session = new SMTPSession(socket, reader, writer);
        
        // Read initial greeting
        session.lastResponse = session.readResponse();
        
        return session;
    }
    
    /**
     * Sends a complete email through SMTP.
     * Returns the final response from the DATA command.
     */
    public static SMTPResponse sendEmail(String host, int port, 
            String from, String to, String subject, String body) throws IOException {
        
        try (SMTPSession session = connect(host, port, DEFAULT_TIMEOUT)) {
            // Check greeting
            if (!session.getLastResponse().isPositiveCompletion()) {
                return session.getLastResponse();
            }
            
            // EHLO
            SMTPResponse ehloResponse = session.sendCommand("EHLO test.example.com");
            if (!ehloResponse.isPositiveCompletion()) {
                return ehloResponse;
            }
            
            // MAIL FROM
            SMTPResponse mailResponse = session.sendCommand("MAIL FROM:<" + from + ">");
            if (!mailResponse.isPositiveCompletion()) {
                return mailResponse;
            }
            
            // RCPT TO
            SMTPResponse rcptResponse = session.sendCommand("RCPT TO:<" + to + ">");
            if (!rcptResponse.isPositiveCompletion()) {
                return rcptResponse;
            }
            
            // DATA
            SMTPResponse dataResponse = session.sendCommand("DATA");
            if (dataResponse.code != 354) {
                return dataResponse;
            }
            
            // Send message content
            session.sendData("Subject: " + subject + "\r\n");
            session.sendData("From: " + from + "\r\n");
            session.sendData("To: " + to + "\r\n");
            session.sendData("\r\n");
            session.sendData(body + "\r\n");
            session.sendData(".\r\n");
            
            // Read final response
            return session.readResponse();
        }
    }
}

