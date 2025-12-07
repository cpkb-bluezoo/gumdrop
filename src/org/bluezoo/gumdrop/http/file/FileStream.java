/*
 * FileStream.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.file;

import org.bluezoo.gumdrop.http.HTTPConnection;
import org.bluezoo.gumdrop.http.HTTPDateFormat;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.Stream;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ProtocolException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    // MIME type mappings
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();
    static {
        CONTENT_TYPES.put("html", "text/html");
        CONTENT_TYPES.put("htm", "text/html");
        CONTENT_TYPES.put("txt", "text/plain");
        CONTENT_TYPES.put("css", "text/css");
        CONTENT_TYPES.put("js", "application/javascript");
        CONTENT_TYPES.put("json", "application/json");
        CONTENT_TYPES.put("xml", "application/xml");
        CONTENT_TYPES.put("pdf", "application/pdf");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("zip", "application/zip");
        CONTENT_TYPES.put("jar", "application/java-archive");
        CONTENT_TYPES.put("mp3", "audio/mpeg");
        CONTENT_TYPES.put("mp4", "video/mp4");
        CONTENT_TYPES.put("webm", "video/webm");
        // Default fallback
        CONTENT_TYPES.put("", "application/octet-stream");
    }

    private final Path rootPath;
    private final boolean allowWrite;
    private final String allowedOptions;
    private final String[] welcomeFiles;

    private String method;
    private String requestPath; // Store the original request path
    private Path path;
    private FileChannel fileChannel;
    private WritableByteChannel writeChannel; // For PUT operations
    private long ifModifiedSince = -1;
    private long requestContentLength = -1; // Local copy of content length
    private long bytesReceived = 0;
    private boolean fileExistedBeforePut = false; // Track file existence before PUT
    private boolean requestBodyExpected = false;

    protected FileStream(HTTPConnection connection, int streamId, Path rootPath, 
                        boolean allowWrite, String allowedOptions, String welcomeFile) {
        super(connection, streamId);
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.allowedOptions = allowedOptions;
        
        // Parse comma-separated welcome file list
        if (welcomeFile != null && !welcomeFile.trim().isEmpty()) {
            String[] files = welcomeFile.split(",");
            welcomeFiles = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                welcomeFiles[i] = files[i].trim();
            }
        } else {
            welcomeFiles = new String[]{"index.html"}; // Default fallback
        }
    }

    @Override protected void endHeaders(Headers headers) {
        for (Header header : headers) {
            String name = header.getName();
            String value = header.getValue();
            if (":method".equals(name)) {
                method = value;
            } else if (":path".equals(name)) {
                if ("*".equals(value)) { // for OPTIONS
                    path = null;
                    requestPath = "*";
                } else {
                    requestPath = value; // Store original request path
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
                    requestContentLength = Long.parseLong(value);
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
        Headers responseHeaders = new Headers();
        int sc = 500; // default to server error

        if ("GET".equals(method) || "HEAD".equals(method)) {
            if (path == null || !Files.exists(path)) {
                sc = 404;
            } else if (Files.isDirectory(path)) {
                // Try to serve index files for directory requests
                try {
                    Path indexFile = findIndexFile(path);
                    if (indexFile != null) {
                        path = indexFile; // serve the index file instead
                        // Continue to serve the index file (fall through to file serving logic)
                    } else {
                        // Generate directory listing  
                        generateDirectoryListing(path, responseHeaders, requestPath);
                        return;
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error finding index file in directory: " + path, e);
                    sc = 403; // Fallback to Forbidden
                    sendResponseHeaders(sc, responseHeaders, true);
                    return;
                }
            }
            
            // Check if the (potentially updated) path is readable (only if we haven't already set an error status)
            if (sc == 500 && path != null && Files.exists(path) && !Files.isReadable(path)) {
                sc = 403; // Path exists but is not readable
            }
            
            if (sc != 500) {
                sendResponseHeaders(sc, responseHeaders, true);
                return;
            }
            
            // If we get here, the file exists and is readable
            {
                try {
                    long size = Files.size(path);
                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    responseHeaders.add(new Header("Last-Modified", dateFormat.format(lastModified)));
                    responseHeaders.add(new Header("Content-Type", getContentType(path)));
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
            if (requestContentLength > 0 && bytesReceived >= requestContentLength) {
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
        if (requestBodyExpected && writeChannel != null && requestContentLength <= 0) {
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
    private void handleDelete(Headers responseHeaders) throws IOException, ProtocolException {
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
    private void handlePut(Headers responseHeaders) throws IOException, ProtocolException {
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
        
        // Remember if file existed before PUT operation
        fileExistedBeforePut = Files.exists(path);
        
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
            if (requestContentLength != 0) { // 0 means empty body, -1 means no Content-Length header
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
            
            // Determine response code based on whether file existed before PUT
            // 201 Created: new file created
            // 204 No Content: existing file updated (per HTTP spec)
            int statusCode = fileExistedBeforePut ? 204 : 201;
            
            Headers responseHeaders = new Headers();
            if (Files.exists(path)) {
                long size = Files.size(path);
                responseHeaders.add("Content-Length", "0");
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
        if (path == null) {
            return "null";
        }
        // Remove control characters and limit length for safe logging
        String safe = path.replaceAll("[\\p{Cntrl}]", "?");
        if (safe.length() > 100) {
            safe = safe.substring(0, 100) + "...[truncated]";
        }
        return safe;
    }
    
    @Override
    protected long getContentLength() {
        return requestContentLength;
    }
    
    /**
     * Finds a welcome file in the given directory.
     * Tries configured welcome file names in order of preference.
     */
    private Path findIndexFile(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        
        for (String welcomeFileName : welcomeFiles) {
            if (welcomeFileName != null && !welcomeFileName.isEmpty()) {
                Path welcomeFilePath = directory.resolve(welcomeFileName);
                if (Files.exists(welcomeFilePath) && Files.isReadable(welcomeFilePath) && !Files.isDirectory(welcomeFilePath)) {
                    return welcomeFilePath;
                }
            }
        }
        
        return null; // No welcome file found
    }
    
    /**
     * Determines the Content-Type header value based on the file extension.
     */
    private String getContentType(Path filePath) {
        if (filePath == null) {
            return CONTENT_TYPES.get(""); // default
        }
        
        String fileName = filePath.getFileName().toString().toLowerCase();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fileName.length() - 1) {
            String extension = fileName.substring(lastDot + 1);
            String contentType = CONTENT_TYPES.get(extension);
            if (contentType != null) {
                return contentType;
            }
        }
        
        return CONTENT_TYPES.get(""); // default to application/octet-stream
    }

    protected void close() {
        // This method exists in the original - may be used for cleanup
    }
    
    /**
     * Generates an HTML directory listing for the given directory.
     */
    private void generateDirectoryListing(Path directory, Headers responseHeaders, String requestPath) 
            throws IOException, ProtocolException {
        // Use the original request path instead of trying to calculate from filesystem
        String displayPath = requestPath;
        if (displayPath == null || displayPath.isEmpty()) {
            displayPath = "/";
        }
        // Ensure it ends with / for directories
        if (!displayPath.endsWith("/")) {
            displayPath += "/";
        }
        final String relativePath = displayPath; // Make final for lambda
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html><head><title>Directory listing for ").append(escapeHtml(relativePath)).append("</title></head>\n");
        html.append("<body>\n");
        html.append("<h1>Directory listing for ").append(escapeHtml(relativePath)).append("</h1>\n");
        html.append("<hr>\n<ul>\n");
        
        // Add parent directory link if not at root
        if (!relativePath.equals("/")) {
            String parentPath = relativePath.substring(0, relativePath.lastIndexOf('/', relativePath.length() - 2) + 1);
            html.append("<li><a href=\"").append(escapeHtml(parentPath)).append("\">../</a></li>\n");
        }
        
        try {
            // List directory contents
            File dir = directory.toFile();
            File[] children = dir.listFiles();
            if (children != null) {
                // Sort: directories first, then files, both alphabetically
                List<File> entries = new ArrayList<>();
                for (File child : children) {
                    entries.add(child);
                }
                Collections.sort(entries, new DirectoryListingComparator());
                
                for (File file : entries) {
                    try {
                        String filename = file.getName();
                        boolean isDirectory = file.isDirectory();
                        String displayName = isDirectory ? filename + "/" : filename;
                        
                        html.append("<li><a href=\"");
                        html.append(escapeHtml(relativePath));
                        html.append(escapeHtml(filename));
                        html.append(isDirectory ? "/" : "");
                        html.append("\">");
                        html.append(escapeHtml(displayName));
                        html.append("</a>");
                        
                        if (!isDirectory) {
                            long size = file.length();
                            html.append(" (");
                            html.append(formatFileSize(size));
                            html.append(")");
                        }
                        
                        html.append("</li>\n");
                    } catch (Exception e) {
                        // Skip files that cause errors
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error listing directory contents: " + directory, e);
            html.append("<li><em>Error reading directory contents</em></li>\n");
        }
        
        html.append("</ul>\n<hr>\n");
        html.append("<address>gumdrop/0.3 Server</address>\n");
        html.append("</body></html>\n");
        
        byte[] htmlBytes = html.toString().getBytes(StandardCharsets.UTF_8);
        
        responseHeaders.add(new Header("Content-Type", "text/html; charset=utf-8"));
        responseHeaders.add(new Header("Content-Length", String.valueOf(htmlBytes.length)));
        
        sendResponseHeaders(200, responseHeaders, false);
        sendResponseBody(htmlBytes, true);
    }
    
    /**
     * Escapes HTML special characters.
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#x27;");
    }
    
    /**
     * Formats file size in human-readable format.
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Comparator for directory listing: directories first, then files, both alphabetically.
     */
    private static class DirectoryListingComparator implements Comparator<File> {
        @Override
        public int compare(File f1, File f2) {
            boolean f1IsDir = f1.isDirectory();
            boolean f2IsDir = f2.isDirectory();
            if (f1IsDir != f2IsDir) {
                return f1IsDir ? -1 : 1;
            }
            return f1.getName().compareToIgnoreCase(f2.getName());
        }
    }
    
}
