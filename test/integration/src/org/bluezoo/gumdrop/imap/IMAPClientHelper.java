/*
 * IMAPClientHelper.java
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

package org.bluezoo.gumdrop.imap;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for making raw IMAP requests in integration tests.
 * 
 * <p>IMAP uses tagged commands: each command has a unique tag prefix
 * that the server echoes in its response, allowing for command pipelining.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPClientHelper {
    
    /**
     * Default timeout for IMAP operations (2 seconds).
     */
    private static final int DEFAULT_TIMEOUT = 2000;
    
    /**
     * Result of an IMAP command containing status and all response lines.
     */
    public static class IMAPResponse {
        /** Whether the command succeeded (OK) */
        public final boolean ok;
        /** Whether the command failed with NO */
        public final boolean no;
        /** Whether the command had a syntax error (BAD) */
        public final boolean bad;
        /** The status message from the tagged response */
        public final String statusMessage;
        /** All untagged response lines (starting with *) */
        public final List<String> untaggedResponses;
        /** All response lines including the tagged response */
        public final List<String> allLines;
        /** The tag used for this command */
        public final String tag;
        
        public IMAPResponse(String tag, boolean ok, boolean no, boolean bad, 
                String statusMessage, List<String> untaggedResponses, List<String> allLines) {
            this.tag = tag;
            this.ok = ok;
            this.no = no;
            this.bad = bad;
            this.statusMessage = statusMessage;
            this.untaggedResponses = untaggedResponses;
            this.allLines = allLines;
        }
        
        @Override
        public String toString() {
            return tag + " " + (ok ? "OK" : no ? "NO" : "BAD") + " " + statusMessage;
        }
    }
    
    /**
     * An IMAP session for sending commands and receiving responses.
     */
    public static class IMAPSession implements Closeable {
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;
        private int tagCounter = 0;
        private String greeting;
        
        IMAPSession(Socket socket, BufferedReader reader, PrintWriter writer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }
        
        /**
         * Returns the server greeting.
         */
        public String getGreeting() {
            return greeting;
        }
        
        /**
         * Generates the next command tag.
         */
        private String nextTag() {
            return "A" + String.format("%03d", ++tagCounter);
        }
        
        /**
         * Sends an IMAP command and reads all responses until the tagged response.
         */
        public IMAPResponse sendCommand(String command) throws IOException {
            String tag = nextTag();
            writer.print(tag + " " + command + "\r\n");
            writer.flush();
            return readResponse(tag);
        }
        
        /**
         * Sends a raw line (for continuation data).
         */
        public void sendRaw(String data) throws IOException {
            writer.print(data + "\r\n");
            writer.flush();
        }
        
        /**
         * Reads IMAP responses until we get the tagged response.
         */
        public IMAPResponse readResponse(String expectedTag) throws IOException {
            List<String> untaggedResponses = new ArrayList<>();
            List<String> allLines = new ArrayList<>();
            
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Connection closed unexpectedly");
                }
                allLines.add(line);
                
                if (line.startsWith("* ")) {
                    // Untagged response
                    untaggedResponses.add(line.substring(2));
                } else if (line.startsWith(expectedTag + " ")) {
                    // Tagged response - parse status
                    String rest = line.substring(expectedTag.length() + 1);
                    boolean ok = false, no = false, bad = false;
                    String statusMessage = "";
                    
                    if (rest.startsWith("OK")) {
                        ok = true;
                        statusMessage = rest.length() > 3 ? rest.substring(3) : "";
                    } else if (rest.startsWith("NO")) {
                        no = true;
                        statusMessage = rest.length() > 3 ? rest.substring(3) : "";
                    } else if (rest.startsWith("BAD")) {
                        bad = true;
                        statusMessage = rest.length() > 4 ? rest.substring(4) : "";
                    }
                    
                    return new IMAPResponse(expectedTag, ok, no, bad, 
                            statusMessage, untaggedResponses, allLines);
                } else if (line.startsWith("+ ")) {
                    // Continuation request - return partial response
                    // Caller should send data and call readResponse again
                    return new IMAPResponse(expectedTag, false, false, false,
                            line.substring(2), untaggedResponses, allLines);
                }
                // Other lines (like literal data) are collected but not specially handled
            }
        }
        
        /**
         * Reads a single line from the server.
         */
        public String readLine() throws IOException {
            return reader.readLine();
        }
        
        /**
         * Closes the IMAP session.
         */
        @Override
        public void close() throws IOException {
            try {
                if (!socket.isClosed()) {
                    sendCommand("LOGOUT");
                }
            } catch (IOException e) {
                // Ignore errors during close
            } finally {
                socket.close();
            }
        }
    }
    
    /**
     * Opens an IMAP session to the specified host and port.
     * Reads the initial greeting.
     */
    public static IMAPSession connect(String host, int port) throws IOException {
        return connect(host, port, DEFAULT_TIMEOUT);
    }
    
    /**
     * Opens an IMAP session to the specified host and port with custom timeout.
     * Reads the initial greeting.
     */
    public static IMAPSession connect(String host, int port, int timeout) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(timeout);
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), timeout);
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), false);
        
        IMAPSession session = new IMAPSession(socket, reader, writer);
        
        // Read initial greeting (untagged OK or PREAUTH)
        session.greeting = reader.readLine();
        
        return session;
    }
    
    /**
     * Performs LOGIN authentication.
     * Returns true if authentication succeeded.
     */
    public static boolean login(IMAPSession session, String username, String password) 
            throws IOException {
        // Quote username and password to handle special characters
        String quotedUser = "\"" + username.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String quotedPass = "\"" + password.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        
        IMAPResponse response = session.sendCommand("LOGIN " + quotedUser + " " + quotedPass);
        return response.ok;
    }
}

