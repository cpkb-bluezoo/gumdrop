/*
 * POP3ClientHelper.java
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

package org.bluezoo.gumdrop.pop3;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for making raw POP3 requests in integration tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ClientHelper {
    
    /**
     * Default timeout for POP3 operations (2 seconds).
     */
    private static final int DEFAULT_TIMEOUT = 2000;
    
    /**
     * Result of a POP3 command containing status and message.
     */
    public static class POP3Response {
        public final boolean ok;
        public final String message;
        public final List<String> lines;
        
        public POP3Response(boolean ok, String message, List<String> lines) {
            this.ok = ok;
            this.message = message;
            this.lines = lines;
        }
        
        @Override
        public String toString() {
            return (ok ? "+OK" : "-ERR") + " " + message;
        }
    }
    
    /**
     * A POP3 session for sending commands and receiving responses.
     */
    public static class POP3Session implements Closeable {
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;
        private POP3Response lastResponse;
        
        POP3Session(Socket socket, BufferedReader reader, PrintWriter writer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }
        
        /**
         * Sends a POP3 command and reads the single-line response.
         */
        public POP3Response sendCommand(String command) throws IOException {
            writer.print(command + "\r\n");
            writer.flush();
            lastResponse = readSingleLineResponse();
            return lastResponse;
        }
        
        /**
         * Sends a POP3 command and reads a multi-line response.
         * Multi-line responses end with a line containing only ".".
         */
        public POP3Response sendMultiLineCommand(String command) throws IOException {
            writer.print(command + "\r\n");
            writer.flush();
            lastResponse = readMultiLineResponse();
            return lastResponse;
        }
        
        /**
         * Reads a single-line POP3 response.
         */
        private POP3Response readSingleLineResponse() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Connection closed unexpectedly");
            }
            
            boolean ok = line.startsWith("+OK");
            String message = "";
            if (line.length() > 4) {
                message = line.substring(4).trim();
            } else if (line.startsWith("-ERR") && line.length() > 5) {
                message = line.substring(5).trim();
            }
            
            List<String> lines = new ArrayList<>();
            lines.add(line);
            return new POP3Response(ok, message, lines);
        }
        
        /**
         * Reads a multi-line POP3 response.
         * The response ends when a line containing only "." is received.
         */
        private POP3Response readMultiLineResponse() throws IOException {
            String firstLine = reader.readLine();
            if (firstLine == null) {
                throw new IOException("Connection closed unexpectedly");
            }
            
            boolean ok = firstLine.startsWith("+OK");
            String message = "";
            if (firstLine.length() > 4) {
                message = firstLine.substring(4).trim();
            } else if (firstLine.startsWith("-ERR") && firstLine.length() > 5) {
                message = firstLine.substring(5).trim();
            }
            
            List<String> lines = new ArrayList<>();
            lines.add(firstLine);
            
            // If it's an error response, it's single-line
            if (!ok) {
                return new POP3Response(ok, message, lines);
            }
            
            // Read multi-line content until we get a line with just "."
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Connection closed unexpectedly");
                }
                
                if (".".equals(line)) {
                    break;
                }
                
                // Handle byte-stuffing: lines starting with "." have extra "." prepended
                if (line.startsWith(".")) {
                    line = line.substring(1);
                }
                
                lines.add(line);
            }
            
            return new POP3Response(ok, message, lines);
        }
        
        /**
         * Gets the last response received.
         */
        public POP3Response getLastResponse() {
            return lastResponse;
        }
        
        /**
         * Closes the POP3 session.
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
     * Opens a POP3 session to the specified host and port.
     * Reads and returns the initial greeting.
     */
    public static POP3Session connect(String host, int port) throws IOException {
        return connect(host, port, DEFAULT_TIMEOUT);
    }
    
    /**
     * Opens a POP3 session to the specified host and port.
     * Reads and returns the initial greeting.
     */
    public static POP3Session connect(String host, int port, int timeout) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(timeout);
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), timeout);
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), false);
        
        POP3Session session = new POP3Session(socket, reader, writer);
        
        // Read initial greeting
        session.lastResponse = session.readSingleLineResponse();
        
        return session;
    }
    
    /**
     * Authenticates with USER/PASS and returns true if successful.
     */
    public static boolean authenticate(POP3Session session, String user, String pass) throws IOException {
        POP3Response userResponse = session.sendCommand("USER " + user);
        if (!userResponse.ok) {
            return false;
        }
        
        POP3Response passResponse = session.sendCommand("PASS " + pass);
        return passResponse.ok;
    }
}


