/*
 * FileHandler.java
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

import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPDateFormat;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.quota.QuotaPolicy;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
 * HTTP request handler for serving files from the filesystem.
 *
 * <p>This handler supports GET, HEAD, OPTIONS, PUT, and DELETE methods
 * for serving and managing files within a configured document root.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FileHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(FileHandler.class.getName());
    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();
    
    // MIME type mappings
    private static final Map<String, String> CONTENT_TYPES = new HashMap<String, String>();
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

    // Request state
    private String method;
    private String requestPath;
    private Path path;
    private long ifModifiedSince = -1;
    private long requestContentLength = -1;
    
    // PUT request state
    private WritableByteChannel writeChannel;
    private long bytesReceived = 0;
    private boolean fileExistedBeforePut = false;
    private boolean requestBodyExpected = false;
    private boolean putFinalized = false;

    FileHandler(Path rootPath, boolean allowWrite, String allowedOptions, String[] welcomeFiles) {
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.allowedOptions = allowedOptions;
        this.welcomeFiles = welcomeFiles;
    }

    @Override
    public void headers(Headers headers, HTTPResponseState state) {
        // Extract request info from headers
        method = headers.getMethod();
        requestPath = headers.getPath();
        
        if ("*".equals(requestPath)) {
            path = null;
        } else if (requestPath != null) {
            path = validateAndResolvePath(requestPath);
        }
        
        // Parse conditional headers
        String ifModifiedSinceHeader = headers.getValue("if-modified-since");
        if (ifModifiedSinceHeader != null) {
            try {
                ifModifiedSince = dateFormat.parse(ifModifiedSinceHeader).getTime();
            } catch (ParseException e) {
                // ignore
            }
        }
        
        String contentLengthHeader = headers.getValue("content-length");
        if (contentLengthHeader != null) {
            try {
                requestContentLength = Long.parseLong(contentLengthHeader);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        // Process the request
        try {
            processRequest(state);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing file request", e);
            sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void requestBodyContent(ByteBuffer data, HTTPResponseState state) {
        if (!requestBodyExpected || writeChannel == null) {
            return;
        }
        
        try {
            int written = writeChannel.write(data);
            bytesReceived += written;
            
            if (requestContentLength > 0 && bytesReceived >= requestContentLength) {
                finalizePutRequest(state);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing request body to file", e);
            sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void endRequestBody(HTTPResponseState state) {
        // Finalize PUT request when body ends
        if (requestBodyExpected && writeChannel != null) {
            finalizePutRequest(state);
        }
    }

    @Override
    public void requestComplete(HTTPResponseState state) {
        // Clean up resources
        if (writeChannel != null) {
            try {
                writeChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing write channel", e);
            }
            writeChannel = null;
        }
    }

    private void processRequest(HTTPResponseState state) throws IOException {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            handleGetOrHead(state);
        } else if ("OPTIONS".equals(method)) {
            handleOptions(state);
        } else if ("DELETE".equals(method)) {
            handleDelete(state);
        } else if ("PUT".equals(method)) {
            handlePut(state);
        } else {
            sendError(state, HTTPStatus.METHOD_NOT_ALLOWED);
        }
    }

    private void handleGetOrHead(HTTPResponseState state) throws IOException {
        if (path == null || !Files.exists(path)) {
            sendError(state, HTTPStatus.NOT_FOUND);
            return;
        }
        
        if (Files.isDirectory(path)) {
            Path indexFile = findIndexFile(path);
            if (indexFile != null) {
                path = indexFile;
            } else {
                generateDirectoryListing(path, requestPath, state);
                return;
            }
        }
        
        if (!Files.isReadable(path)) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        // Get file info
        long size = Files.size(path);
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        
        // Check If-Modified-Since
        if (lastModified <= ifModifiedSince) {
            Headers response = new Headers();
            response.status(HTTPStatus.NOT_MODIFIED);
            response.add("Last-Modified", dateFormat.format(lastModified));
            state.headers(response);
            state.complete();
            return;
        }
        
        // Send response headers
        Headers response = new Headers();
        response.status(HTTPStatus.OK);
        response.add("Last-Modified", dateFormat.format(lastModified));
        response.add("Content-Type", getContentType(path));
        response.add("Content-Length", Long.toString(size));
        state.headers(response);
        
        // Send body for GET (not HEAD)
        if ("GET".equals(method)) {
            if (size > 0) {
                state.startResponseBody();
                sendFileContent(path, state);
                state.endResponseBody();
            }
        }
        
        state.complete();
    }

    private void sendFileContent(Path filePath, HTTPResponseState state) throws IOException {
        FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ);
        try {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            while (channel.read(buf) >= 0) {
                buf.flip();
                if (buf.hasRemaining()) {
                    state.responseBodyContent(buf);
                }
                buf.clear();
            }
        } finally {
            channel.close();
        }
    }

    private void handleOptions(HTTPResponseState state) {
        Headers response = new Headers();
        response.status(HTTPStatus.OK);
        response.add("Allow", allowedOptions);
        state.headers(response);
        state.complete();
    }

    private void handleDelete(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.METHOD_NOT_ALLOWED);
            return;
        }
        
        if (path == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        if (!Files.exists(path)) {
            sendError(state, HTTPStatus.NOT_FOUND);
            return;
        }
        
        if (Files.isDirectory(path)) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        try {
            Files.delete(path);
            Headers response = new Headers();
            response.status(HTTPStatus.NO_CONTENT);
            state.headers(response);
            state.complete();
            LOGGER.info("Deleted file: " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete file: " + path, e);
            sendError(state, HTTPStatus.FORBIDDEN);
        }
    }

    private void handlePut(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.METHOD_NOT_ALLOWED);
            return;
        }
        
        if (path == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        if (Files.exists(path) && Files.isDirectory(path)) {
            sendError(state, HTTPStatus.CONFLICT);
            return;
        }
        
        fileExistedBeforePut = Files.exists(path);
        
        // Create parent directories if needed
        Path parentDir = path.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to create parent directories for: " + path, e);
                sendError(state, HTTPStatus.CONFLICT);
                return;
            }
        }
        
        try {
            writeChannel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
            
            if (requestContentLength == 0) {
                // Empty body - finalize immediately
                finalizePutRequest(state);
            } else {
                // Expect body
                requestBodyExpected = true;
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create/open file for writing: " + path, e);
            sendError(state, HTTPStatus.FORBIDDEN);
        }
    }

    private void finalizePutRequest(HTTPResponseState state) {
        if (putFinalized) {
            return; // Already finalized
        }
        putFinalized = true;
        
        try {
            if (writeChannel != null) {
                writeChannel.close();
                writeChannel = null;
            }
            
            requestBodyExpected = false;
            
            HTTPStatus status = fileExistedBeforePut ? HTTPStatus.NO_CONTENT : HTTPStatus.CREATED;
            
            Headers response = new Headers();
            response.status(status);
            response.add("Content-Length", "0");
            state.headers(response);
            state.complete();
            
            LOGGER.info("PUT completed for file: " + path);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error finalizing PUT request", e);
            sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void sendError(HTTPResponseState state, HTTPStatus status) {
        Headers response = new Headers();
        response.status(status);
        response.add("Content-Length", "0");
        state.headers(response);
        state.complete();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path validation and utilities
    // ─────────────────────────────────────────────────────────────────────────

    private Path validateAndResolvePath(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            return null;
        }
        
        if (requestPath.contains("\0")) {
            LOGGER.warning("Rejected path containing null bytes");
            return null;
        }
        
        if (requestPath.length() > 2048) {
            LOGGER.warning("Rejected overly long path");
            return null;
        }
        
        try {
            Path resolvedPath = rootPath;
            // Parse path components separated by /
            int compStart = 0;
            int pathLen = requestPath.length();
            while (compStart <= pathLen) {
                int compEnd = requestPath.indexOf('/', compStart);
                if (compEnd < 0) {
                    compEnd = pathLen;
                }
                String component = requestPath.substring(compStart, compEnd);
                compStart = compEnd + 1;
                
                if (component.isEmpty()) {
                    continue;
                }
                
                String decodedComponent;
                try {
                    decodedComponent = URLDecoder.decode(component, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return null;
                }
                
                // Check for double encoding
                String doubleDecoded = decodedComponent;
                try {
                    doubleDecoded = URLDecoder.decode(decodedComponent, "UTF-8");
                } catch (Exception e) {
                    // Ignore
                }
                if (!doubleDecoded.equals(decodedComponent)) {
                    return null;
                }
                
                if (isDangerousPathComponent(decodedComponent)) {
                    LOGGER.warning("Rejected dangerous path component: " + decodedComponent);
                    return null;
                }
                
                resolvedPath = resolvedPath.resolve(decodedComponent);
            }
            
            resolvedPath = resolvedPath.normalize();
            
            if (!isWithinRoot(resolvedPath)) {
                return null;
            }
            
            if (Files.exists(resolvedPath)) {
                try {
                    Path realPath = resolvedPath.toRealPath();
                    if (!isWithinRoot(realPath)) {
                        return null;
                    }
                    resolvedPath = realPath;
                } catch (IOException e) {
                    // Allow to proceed
                }
            }
            
            return resolvedPath;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isDangerousPathComponent(String component) {
        if (component == null || component.isEmpty()) {
            return false;
        }
        if ("..".equals(component) || ".".equals(component)) {
            return true;
        }
        if (component.contains("..") || component.contains("./") || component.contains("/.")) {
            return true;
        }
        if (component.matches(".*[<>:\"|?*].*")) {
            return true;
        }
        for (char c : component.toCharArray()) {
            if (Character.isISOControl(c)) {
                return true;
            }
        }
        String upperComponent = component.toUpperCase();
        if (upperComponent.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")) {
            return true;
        }
        return false;
    }

    private boolean isWithinRoot(Path path) {
        try {
            Path canonicalRoot = rootPath.toRealPath();
            if (Files.exists(path)) {
                Path realPath = path.toRealPath();
                return realPath.startsWith(canonicalRoot);
            } else {
                Path normalizedPath = path.normalize();
                Path normalizedRoot = rootPath.normalize();
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
            return false;
        }
    }

    private Path findIndexFile(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        for (String welcomeFileName : welcomeFiles) {
            if (welcomeFileName != null && !welcomeFileName.isEmpty()) {
                Path welcomeFilePath = directory.resolve(welcomeFileName);
                if (Files.exists(welcomeFilePath) && Files.isReadable(welcomeFilePath) 
                        && !Files.isDirectory(welcomeFilePath)) {
                    return welcomeFilePath;
                }
            }
        }
        return null;
    }

    private String getContentType(Path filePath) {
        if (filePath == null) {
            return CONTENT_TYPES.get("");
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
        return CONTENT_TYPES.get("");
    }

    private void generateDirectoryListing(Path directory, String requestPath, HTTPResponseState state) 
            throws IOException {
        String displayPath = requestPath;
        if (displayPath == null || displayPath.isEmpty()) {
            displayPath = "/";
        }
        if (!displayPath.endsWith("/")) {
            displayPath += "/";
        }
        final String relativePath = displayPath;
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html><head><title>Directory listing for ").append(escapeHtml(relativePath)).append("</title></head>\n");
        html.append("<body>\n");
        html.append("<h1>Directory listing for ").append(escapeHtml(relativePath)).append("</h1>\n");
        html.append("<hr>\n<ul>\n");
        
        if (!relativePath.equals("/")) {
            String parentPath = relativePath.substring(0, relativePath.lastIndexOf('/', relativePath.length() - 2) + 1);
            html.append("<li><a href=\"").append(escapeHtml(parentPath)).append("\">../</a></li>\n");
        }
        
        try {
            File dir = directory.toFile();
            File[] children = dir.listFiles();
            if (children != null) {
                List<File> entries = new ArrayList<File>();
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
                            html.append(" (").append(formatFileSize(file.length())).append(")");
                        }
                        
                        html.append("</li>\n");
                    } catch (Exception e) {
                        // Skip
                    }
                }
            }
        } catch (Exception e) {
            html.append("<li><em>Error reading directory contents</em></li>\n");
        }
        
        html.append("</ul>\n<hr>\n");
        html.append("<address>gumdrop Server</address>\n");
        html.append("</body></html>\n");
        
        byte[] htmlBytes = html.toString().getBytes(StandardCharsets.UTF_8);
        
        Headers response = new Headers();
        response.status(HTTPStatus.OK);
        response.add("Content-Type", "text/html; charset=utf-8");
        response.add("Content-Length", String.valueOf(htmlBytes.length));
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(htmlBytes));
        state.endResponseBody();
        state.complete();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#x27;");
    }

    private String formatFileSize(long bytes) {
        return QuotaPolicy.formatSize(bytes);
    }

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

