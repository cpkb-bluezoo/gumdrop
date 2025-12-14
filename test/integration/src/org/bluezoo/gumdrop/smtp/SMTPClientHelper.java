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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple synchronous SMTP client helper for integration testing.
 *
 * <p>This class provides a synchronous, blocking interface for testing SMTP
 * servers. It is NOT suitable for production use - use {@link SMTPClient} for
 * that. This helper is designed for test clarity and simplicity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPClientHelper {

    /**
     * Represents an SMTP response from the server.
     */
    public static class SMTPResponse {
        /** The 3-digit response code. */
        public final int code;
        /** The response text from the final line. */
        public final String message;
        /** All response lines (for multiline responses like EHLO). */
        public final List<String> lines;

        public SMTPResponse(int code, String message, List<String> lines) {
            this.code = code;
            this.message = message;
            this.lines = lines;
        }

        /** Returns true for 2xx codes. */
        public boolean isPositiveCompletion() {
            return code >= 200 && code < 300;
        }

        /** Returns true for 3xx codes. */
        public boolean isPositiveIntermediate() {
            return code >= 300 && code < 400;
        }

        /** Returns true for 4xx codes. */
        public boolean isTransientNegative() {
            return code >= 400 && code < 500;
        }

        /** Returns true for 5xx codes. */
        public boolean isPermanentNegative() {
            return code >= 500 && code < 600;
        }

        @Override
        public String toString() {
            return code + " " + message;
        }
    }

    /**
     * An active SMTP session with a server.
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
         * Sends a command and reads the response.
         *
         * @param command the command to send (without CRLF)
         * @return the server's response
         */
        public SMTPResponse sendCommand(String command) throws IOException {
            writer.print(command + "\r\n");
            writer.flush();
            lastResponse = readResponse();
            return lastResponse;
        }

        /**
         * Sends raw data (typically message content after DATA).
         *
         * @param data the data to send
         */
        public void sendData(String data) throws IOException {
            writer.print(data);
            writer.flush();
        }

        /**
         * Reads a complete SMTP response (handles multiline).
         *
         * @return the response
         */
        public SMTPResponse readResponse() throws IOException {
            List<String> lines = new ArrayList<String>();
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

                // Check for continuation (hyphen after code) or final line (space or end)
                if (line.length() == 3 || line.charAt(3) == ' ') {
                    lastLine = line.length() > 4 ? line.substring(4) : "";
                    break;
                }
            }
            return new SMTPResponse(code, lastLine, lines);
        }

        /**
         * Gets the last response received.
         *
         * @return the last response
         */
        public SMTPResponse getLastResponse() {
            return lastResponse;
        }

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

    private static final int DEFAULT_TIMEOUT = 5000;

    /**
     * Connects to an SMTP server.
     *
     * @param host the server host
     * @param port the server port
     * @return an active session
     */
    public static SMTPSession connect(String host, int port) throws IOException {
        return connect(host, port, DEFAULT_TIMEOUT);
    }

    /**
     * Connects to an SMTP server with a custom timeout.
     *
     * @param host the server host
     * @param port the server port
     * @param timeout connection and read timeout in milliseconds
     * @return an active session
     */
    public static SMTPSession connect(String host, int port, int timeout) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(timeout);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeout);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), false);

        SMTPSession session = new SMTPSession(socket, reader, writer);
        // Read the server's greeting
        session.lastResponse = session.readResponse();
        return session;
    }

    /**
     * Convenience method to send a complete email.
     *
     * @param host the server host
     * @param port the server port
     * @param from the sender address
     * @param to the recipient address
     * @param subject the email subject
     * @param body the email body
     * @return the final response (after message submission)
     */
    public static SMTPResponse sendEmail(String host, int port,
                                         String from, String to, String subject, String body)
            throws IOException {

        SMTPSession session = connect(host, port, DEFAULT_TIMEOUT);
        try {
            if (!session.getLastResponse().isPositiveCompletion()) {
                return session.getLastResponse();
            }

            SMTPResponse ehloResponse = session.sendCommand("EHLO test.example.com");
            if (!ehloResponse.isPositiveCompletion()) {
                return ehloResponse;
            }

            SMTPResponse mailResponse = session.sendCommand("MAIL FROM:<" + from + ">");
            if (!mailResponse.isPositiveCompletion()) {
                return mailResponse;
            }

            SMTPResponse rcptResponse = session.sendCommand("RCPT TO:<" + to + ">");
            if (!rcptResponse.isPositiveCompletion()) {
                return rcptResponse;
            }

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
        } finally {
            session.close();
        }
    }
}
