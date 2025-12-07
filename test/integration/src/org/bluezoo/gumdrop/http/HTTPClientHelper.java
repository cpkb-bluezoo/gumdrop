/*
 * HTTPClientHelper.java
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

package org.bluezoo.gumdrop.http;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

/**
 * Helper class for making raw HTTP/HTTPS requests in integration tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientHelper {
    
    /**
     * Result of an HTTP request containing status, headers, and body.
     */
    public static class HTTPResponse {
        public final String statusLine;
        public final int statusCode;
        public final String headers;
        public final String body;
        public final String fullResponse;
        
        public HTTPResponse(String statusLine, int statusCode, String headers, String body, String fullResponse) {
            this.statusLine = statusLine;
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.fullResponse = fullResponse;
        }
        
        public boolean hasHeader(String headerName) {
            String lowerName = headerName.toLowerCase();
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith(lowerName + ":")) {
                    return true;
                }
            }
            return false;
        }
        
        public String getHeader(String headerName) {
            String lowerName = headerName.toLowerCase();
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith(lowerName + ":")) {
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0 && colonIdx < line.length() - 1) {
                        return line.substring(colonIdx + 1).trim();
                    }
                }
            }
            return null;
        }
    }
    
    /**
     * Sends a raw HTTP request and returns the response.
     */
    public static HTTPResponse sendRequest(String host, int port, String request) throws IOException {
        return sendRequest(host, port, request, false, 5000);
    }
    
    /**
     * Sends a raw HTTP request and returns the response.
     */
    public static HTTPResponse sendRequest(String host, int port, String request, boolean secure, int timeout) 
            throws IOException {
        Socket socket = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            if (secure) {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[] { new TrustAllTrustManager() }, new java.security.SecureRandom());
                SSLSocketFactory factory = sslContext.getSocketFactory();
                SSLSocket sslSocket = (SSLSocket) factory.createSocket();
                sslSocket.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
                sslSocket.setSoTimeout(timeout);
                sslSocket.setTcpNoDelay(true);
                sslSocket.setKeepAlive(false);
                sslSocket.setReuseAddress(true);
                sslSocket.connect(new java.net.InetSocketAddress(host, port), timeout);
                sslSocket.startHandshake();
                socket = sslSocket;
            } else {
                socket = new Socket();
                socket.setSoTimeout(timeout);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(false);
                socket.setReuseAddress(true);
                socket.connect(new java.net.InetSocketAddress(host, port), timeout);
            }
            
            out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            
            in = socket.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            long readStart = System.currentTimeMillis();
            boolean hasConnectionClose = request.toLowerCase().contains("connection: close");
            
            while ((bytesRead = in.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
                if (isResponseComplete(baos.toByteArray(), hasConnectionClose)) {
                    break;
                }
                if (System.currentTimeMillis() - readStart > timeout) {
                    break;
                }
            }
            
            String response = baos.toString(StandardCharsets.UTF_8.name());
            return parseResponse(response);
            
        } catch (Exception e) {
            throw new IOException("Failed to send HTTP request", e);
        } finally {
            // For SSL sockets, just close the socket directly - it handles close_notify
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            // Extra delay to ensure socket is fully released
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }
    
    private static boolean isResponseComplete(byte[] responseBytes, boolean hasConnectionClose) {
        if (responseBytes.length < 4) {
            return false;
        }
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        int headerEnd = response.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            return false;
        }
        
        String headers = response.substring(0, headerEnd);
        String body = response.substring(headerEnd + 4);
        
        // Check for 1xx informational responses - they have no body
        // (100 Continue, 101 Switching Protocols, 102 Processing, 103 Early Hints)
        if (headers.contains("HTTP/1.1 10") || headers.contains("HTTP/1.0 10")) {
            return true;
        }
        
        // Check for 204 No Content and 304 Not Modified - no body
        if (headers.contains(" 204 ") || headers.contains(" 304 ")) {
            return true;
        }
        
        // Check for Connection: close - if we have the full headers and response has Connection: close,
        // we need to wait for the server to close or check Content-Length
        if (hasConnectionClose || headers.toLowerCase().contains("connection: close")) {
            // Check if Content-Length is 0 (response complete with no body)
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        int length = Integer.parseInt(line.substring(15).trim());
                        return body.length() >= length;
                    } catch (NumberFormatException e) {
                        // Continue to fallback
                    }
                }
            }
            // No Content-Length, wait for any body or server close
            return body.length() > 0;
        }
        
        // Check Content-Length
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    int length = Integer.parseInt(line.substring(15).trim());
                    return body.length() >= length;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        
        // Check for chunked encoding termination
        if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
            return body.endsWith("0\r\n\r\n");
        }
        
        return false;
    }
    
    private static HTTPResponse parseResponse(String response) {
        String[] lines = response.split("\r\n", -1);
        
        if (lines.length == 0) {
            return new HTTPResponse("", 0, "", "", response);
        }
        
        String statusLine = lines[0];
        int statusCode = 0;
        try {
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length >= 2) {
                statusCode = Integer.parseInt(statusParts[1]);
            }
        } catch (NumberFormatException e) {
            // Invalid status code
        }
        
        int emptyLineIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                emptyLineIndex = i;
                break;
            }
        }
        
        StringBuilder headersBuilder = new StringBuilder();
        if (emptyLineIndex > 0) {
            for (int i = 1; i < emptyLineIndex; i++) {
                headersBuilder.append(lines[i]).append("\r\n");
            }
        }
        
        String body = "";
        if (emptyLineIndex > 0 && emptyLineIndex < lines.length - 1) {
            int bodyStartIndex = response.indexOf("\r\n\r\n");
            if (bodyStartIndex > 0) {
                body = response.substring(bodyStartIndex + 4);
            }
        }
        
        return new HTTPResponse(statusLine, statusCode, headersBuilder.toString(), body, response);
    }
    
    /**
     * Trust manager that accepts all certificates (for testing only!).
     */
    private static class TrustAllTrustManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }
        
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }
        
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
