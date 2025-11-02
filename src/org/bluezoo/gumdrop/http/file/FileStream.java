/*
 * FileStream.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.file;

import org.bluezoo.gumdrop.http.HTTPConnection;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Stream;
import gnu.inet.http.HTTPDateFormat;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ProtocolException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stream implementation for serving a file from the filesystem.
 * This handles a single HTTP request/response pair for file operations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FileStream extends Stream {

    private static final Logger LOGGER = Logger.getLogger(FileStream.class.getName());
    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    private final Path rootPath;
    private final boolean allowWrite;
    private final String allowedOptions;

    private String method;
    private Path path;
    private FileChannel fileChannel;
    private WritableByteChannel writeChannel; // For PUT operations
    private long ifModifiedSince = -1;
    private long contentLength = -1;
    private long bytesReceived = 0;
    private boolean requestBodyExpected = false;

    protected FileStream(HTTPConnection connection, int streamId, Path rootPath, 
                        boolean allowWrite, String allowedOptions) {
        super(connection, streamId);
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.allowedOptions = allowedOptions;
    }

    @Override protected void endHeaders(Collection<Header> headers) {
        for (Header header : headers) {
            String name = header.getName();
            String value = header.getValue();
            if (":method".equals(name)) {
                method = value;
            } else if (":path".equals(name)) {
                if ("*".equals(value)) { // for OPTIONS
                    path = null;
                } else {
                    path = validateAndResolvePath(value);
                }
            } else if ("if-modified-since".equals(name.toLowerCase())) {
                try {
                    ifModifiedSince = dateFormat.parse(value).getTime();
                } catch (ParseException e) {
                    // ignore
                }
            } else if ("content-length".equals(name.toLowerCase())) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    // ignore invalid content-length
                }
            }
        }

        // Process the request
        try {
            processRequest();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing file request", e);
            try {
                sendError(500);
            } catch (ProtocolException pe) {
                LOGGER.log(Level.SEVERE, "Error sending 500 response", pe);
            }
        }
    }

    private void processRequest() throws IOException, ProtocolException {
        List<Header> responseHeaders = new ArrayList<>();
        int sc = 500; // default to server error

        if ("GET".equals(method) || "HEAD".equals(method)) {
            if (path == null || !Files.exists(path)) {
                sc = 404;
            } else if (Files.isDirectory(path)) {
                sc = 403; // TODO: could implement directory listing
            } else if (!Files.isReadable(path)) {
                sc = 403;
            } else {
                try {
                    long size = Files.size(path);
                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    responseHeaders.add(new Header("Last-Modified", dateFormat.format(lastModified)));
                    if (lastModified <= ifModifiedSince) {
                        responseHeaders.add(new Header("Content-Length", "0"));
                        sc = 304;
                    } else {
                        responseHeaders.add(new Header("Content-Length", Long.toString(size)));
                        sc = 200;
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    sc = 403;
                }
            }
            if (sc != 200) {
                sendResponseHeaders(sc, responseHeaders, true);
                return;
            }
            
            // Send file content for GET (not HEAD)
            if ("GET".equals(method)) {
                try {
                    fileChannel = FileChannel.open(path, StandardOpenOption.READ);
                    long size = Files.size(path);
                    ByteBuffer buf = ByteBuffer.allocate(8192);
                    int len = fileChannel.read(buf);
                    long total = 0;
                    if (len == -1) {
                        sendResponseHeaders(sc, responseHeaders, true);
                    } else {
                        sendResponseHeaders(sc, responseHeaders, false);
                        do {
                            byte[] data = new byte[len];
                            buf.flip();
                            buf.get(data);
                            buf.clear();
                            total += len;
                            int nextLen = fileChannel.read(buf);
                            sendResponseBody(data, total >= size);
                            len = nextLen;
                        } while (len >= 0);
                    }
                    close();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    sendResponseHeaders(500, responseHeaders, true);
                }
            } else {
                // HEAD request - just send headers
                sendResponseHeaders(sc, responseHeaders, true);
            }
            return;
        } else if ("OPTIONS".equals(method)) {
            responseHeaders.add(new Header("Allow", allowedOptions));
            sendResponseHeaders(200, responseHeaders, true);
            return;
        } else if ("DELETE".equals(method)) {
            handleDelete(responseHeaders);
            return;
        } else if ("PUT".equals(method)) {
            handlePut(responseHeaders);
            return;
        } else {
            sendError(405); // Method Not Allowed
            return;
        }
    }

    @Override protected void receiveRequestBody(byte[] buf) {
        if (!requestBodyExpected || writeChannel == null) {
            return; // Not expecting a body or not set up for writing
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(buf);
            int written = writeChannel.write(buffer);
            bytesReceived += written;
            
            // If we've received all expected data, close the write channel
            if (contentLength > 0 && bytesReceived >= contentLength) {
                finalizePutRequest();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing request body to file", e);
            try {
                sendError(500);
            } catch (ProtocolException pe) {
                LOGGER.log(Level.SEVERE, "Error sending 500 response", pe);
            }
        }
    }

    @Override protected void endRequest() {
        // For PUT requests without Content-Length, finalize when request ends
        if (requestBodyExpected && writeChannel != null && contentLength <= 0) {
            finalizePutRequest();
        }
        
        // Clean up resources
        if (fileChannel != null) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing file channel", e);
            }
        }
        if (writeChannel != null && writeChannel != fileChannel) {
            try {
                writeChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing write channel", e);
            }
        }
    }

    /**
     * Handle DELETE request - remove the specified file.
     */
    private void handleDelete(List<Header> responseHeaders) throws IOException, ProtocolException {
        if (!allowWrite) {
            sendError(405); // Method Not Allowed
            return;
        }
        
        if (path == null) {
            sendError(400); // Bad Request - invalid path
            return;
        }
        
        if (!Files.exists(path)) {
            sendError(404); // Not Found
            return;
        }
        
        if (Files.isDirectory(path)) {
            sendError(403); // Forbidden - cannot delete directories
            return;
        }
        
        try {
            Files.delete(path);
            sendResponseHeaders(204, responseHeaders, true); // No Content - successfully deleted
            LOGGER.info("Deleted file: " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete file: " + path, e);
            sendError(403); // Forbidden - permission denied or other issue
        }
    }
    
    /**
     * Handle PUT request - create or overwrite the specified file.
     */
    private void handlePut(List<Header> responseHeaders) throws IOException, ProtocolException {
        if (!allowWrite) {
            sendError(405); // Method Not Allowed
            return;
        }
        
        if (path == null) {
            sendError(400); // Bad Request - invalid path
            return;
        }
        
        // Check if we're trying to PUT to a directory
        if (Files.exists(path) && Files.isDirectory(path)) {
            sendError(409); // Conflict - target is a directory
            return;
        }
        
        // Create parent directories if they don't exist
        Path parentDir = path.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to create parent directories for: " + path, e);
                sendError(409); // Conflict - cannot create parent directories
                return;
            }
        }
        
        boolean fileExists = Files.exists(path);
        
        try {
            // Open file for writing (create or truncate)
            writeChannel = FileChannel.open(path, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE, 
                StandardOpenOption.TRUNCATE_EXISTING);
            
            // If we expect a request body, set up to receive it
            if (contentLength != 0) { // 0 means empty body, -1 means no Content-Length header
                requestBodyExpected = true;
                // Don't send response yet - wait for body to be received
                return;
            } else {
                // Empty body - finalize immediately
                finalizePutRequest();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create/open file for writing: " + path, e);
            sendError(403); // Forbidden - cannot create/write file
        }
    }
    
    /**
     * Finalize PUT request - close write channel and send response.
     */
    private void finalizePutRequest() {
        try {
            if (writeChannel != null) {
                writeChannel.close();
                writeChannel = null;
            }
            
            requestBodyExpected = false;
            
            // Determine response code based on whether file was created or updated
            boolean fileExisted = bytesReceived > 0 || Files.exists(path);
            int statusCode = fileExisted ? 200 : 201; // OK vs Created
            
            List<Header> responseHeaders = new ArrayList<>();
            if (Files.exists(path)) {
                long size = Files.size(path);
                responseHeaders.add(new Header("Content-Length", "0"));
                LOGGER.info("PUT completed for file: " + path + " (size: " + size + " bytes)");
            }
            
            sendResponseHeaders(statusCode, responseHeaders, true);
            
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error finalizing PUT request", e);
            try {
                sendError(500);
            } catch (ProtocolException pe) {
                LOGGER.log(Level.SEVERE, "Error sending 500 response", pe);
            }
        }
    }

    /**
     * Validates and resolves a request path to ensure it stays within the filesystem root.
     * This method implements comprehensive security checks to prevent directory traversal attacks.
     * 
     * @param requestPath the raw HTTP request path
     * @return the resolved Path within the root, or null if invalid/outside root
     */
    private Path validateAndResolvePath(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            LOGGER.warning("Rejected empty or null path");
            return null;
        }
        
        // Security check: Reject paths with null bytes (historical attack vector)
        if (requestPath.contains("\0")) {
            LOGGER.warning("Rejected path containing null bytes: " + logSafePath(requestPath));
            return null;
        }
        
        // Security check: Reject overly long paths (potential DoS)
        if (requestPath.length() > 2048) {
            LOGGER.warning("Rejected overly long path (length: " + requestPath.length() + ")");
            return null;
        }
        
        try {
            // Start with the root path
            Path resolvedPath = rootPath;
            
            // Split path and process each component
            String[] pathComponents = requestPath.split("/");
            
            for (String component : pathComponents) {
                // Skip empty components (multiple slashes)
                if (component.isEmpty()) {
                    continue;
                }
                
                // URL decode the component
                String decodedComponent;
                try {
                    decodedComponent = URLDecoder.decode(component, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // This should never happen with UTF-8
                    LOGGER.severe("UTF-8 decoding failed for component: " + component);
                    return null;
                }
                
                // Security check: Additional decoding attempts to catch double encoding
                String doubleDecoded = decodedComponent;
                try {
                    doubleDecoded = URLDecoder.decode(decodedComponent, "UTF-8");
                } catch (Exception e) {
                    // Ignore - single decoding is sufficient
                }
                if (!doubleDecoded.equals(decodedComponent)) {
                    LOGGER.warning("Rejected double-encoded path component: " + logSafePath(component));
                    return null;
                }
                
                // Security check: Reject dangerous path components
                if (isDangerousPathComponent(decodedComponent)) {
                    LOGGER.warning("Rejected dangerous path component: " + logSafePath(decodedComponent));
                    return null;
                }
                
                // Resolve the component
                resolvedPath = resolvedPath.resolve(decodedComponent);
            }
            
            // Normalize the path to resolve . and .. components
            resolvedPath = resolvedPath.normalize();
            
            // Security check: Ensure the normalized path is still within root
            if (!isWithinRoot(resolvedPath)) {
                LOGGER.warning("Rejected path outside root: " + logSafePath(resolvedPath.toString()));
                return null;
            }
            
            // Security check: If file exists, resolve real path to handle symbolic links
            if (Files.exists(resolvedPath)) {
                try {
                    Path realPath = resolvedPath.toRealPath();
                    if (!isWithinRoot(realPath)) {
                        LOGGER.warning("Rejected symbolic link outside root: " + logSafePath(realPath.toString()));
                        return null;
                    }
                    resolvedPath = realPath;
                } catch (IOException e) {
                    // Could be a broken symlink or permission issue - allow it to proceed
                    // The actual file operation will handle the error appropriately
                    LOGGER.fine("Could not resolve real path for: " + resolvedPath + " - " + e.getMessage());
                }
            }
            
            LOGGER.fine("Validated path: " + requestPath + " -> " + resolvedPath);
            return resolvedPath;
            
        } catch (Exception e) {
            LOGGER.warning("Path validation error for: " + logSafePath(requestPath) + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Checks if a path component is dangerous (potential security risk).
     */
    private boolean isDangerousPathComponent(String component) {
        if (component == null || component.isEmpty()) {
            return false;
        }
        
        // Reject path traversal attempts
        if ("..".equals(component) || ".".equals(component)) {
            return true;
        }
        
        // Reject components with path traversal sequences
        if (component.contains("..")) {
            return true;
        }
        
        // Reject components that are just "." or contain suspicious patterns
        if (component.contains("./") || component.contains("/.")) {
            return true;
        }
        
        // Reject Windows-specific dangerous patterns
        if (component.matches(".*[<>:\"|?*].*")) {
            return true;
        }
        
        // Reject control characters
        for (char c : component.toCharArray()) {
            if (Character.isISOControl(c)) {
                return true;
            }
        }
        
        // Reject Windows device names (CON, PRN, AUX, NUL, etc.)
        String upperComponent = component.toUpperCase();
        if (upperComponent.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if a resolved path is within the allowed root directory.
     * This uses both logical and physical path checking for maximum security.
     */
    private boolean isWithinRoot(Path path) {
        try {
            // Get the canonical root path
            Path canonicalRoot = rootPath.toRealPath();
            
            // For existing paths, use real path resolution
            if (Files.exists(path)) {
                Path realPath = path.toRealPath();
                return realPath.startsWith(canonicalRoot);
            } else {
                // For non-existent paths, check the normalized logical path
                Path normalizedPath = path.normalize();
                Path normalizedRoot = rootPath.normalize();
                
                // Also check that when we resolve the real path of the parent,
                // it's still within the root
                Path parent = normalizedPath.getParent();
                while (parent != null && !Files.exists(parent)) {
                    parent = parent.getParent();
                }
                
                if (parent != null) {
                    Path realParent = parent.toRealPath();
                    if (!realParent.startsWith(canonicalRoot)) {
                        return false;
                    }
                }
                
                return normalizedPath.startsWith(normalizedRoot);
            }
        } catch (IOException e) {
            // If we can't resolve paths, err on the side of caution
            LOGGER.warning("Could not resolve paths for security check: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Creates a log-safe version of a path string by removing potentially dangerous characters.
     */
    private String logSafePath(String path) {
        if (path == null) return "null";
        // Remove control characters and limit length for safe logging
        String safe = path.replaceAll("[\\p{Cntrl}]", "?");
        if (safe.length() > 100) {
            safe = safe.substring(0, 100) + "...[truncated]";
        }
        return safe;
    }

    protected void close() {
        // This method exists in the original - may be used for cleanup
    }
}
