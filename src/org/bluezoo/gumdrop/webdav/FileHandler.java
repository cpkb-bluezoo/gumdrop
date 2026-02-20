/*
 * FileHandler.java
 * Copyright (C) 2005, 2013, 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.webdav;

import org.bluezoo.gonzalez.XMLWriter;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPDateFormat;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.quota.QuotaPolicy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP request handler for serving files from the filesystem.
 *
 * <p>This handler supports GET, HEAD, OPTIONS, PUT, and DELETE methods
 * for serving and managing files within a configured document root.
 *
 * <p>When WebDAV is enabled, this handler additionally supports RFC 2518
 * distributed authoring methods: PROPFIND, PROPPATCH, MKCOL, COPY, MOVE,
 * LOCK, and UNLOCK.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FileHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(FileHandler.class.getName());
    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    private final Path rootPath;
    private final boolean allowWrite;
    private final boolean webdavEnabled;
    private final String allowedOptions;
    private final String[] welcomeFiles;
    private final Map<String, String> contentTypes;
    private final WebDAVLockManager lockManager;

    // Request state
    private String method;
    private String requestPath;
    private Path path;
    private long ifModifiedSince = -1;
    private long requestContentLength = -1;
    private int depth = DAVConstants.DEPTH_INFINITY;
    private String destination;
    private boolean overwrite = true;
    private String lockToken;
    private String ifHeader;
    
    // PUT/WebDAV request body state
    private WritableByteChannel writeChannel;
    private long bytesReceived = 0;
    private boolean fileExistedBeforePut = false;
    private boolean requestBodyExpected = false;
    private boolean putFinalized = false;
    
    // WebDAV request body accumulation
    private ByteBuffer requestBodyBuffer;
    private WebDAVRequestParser webdavParser;
    private Headers requestHeaders;

    FileHandler(Path rootPath, boolean allowWrite, boolean webdavEnabled,
                String allowedOptions, String[] welcomeFiles,
                Map<String, String> contentTypes, WebDAVLockManager lockManager) {
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.webdavEnabled = webdavEnabled;
        this.allowedOptions = allowedOptions;
        this.welcomeFiles = welcomeFiles;
        this.contentTypes = contentTypes;
        this.lockManager = lockManager;
    }

    @Override
    public void headers(HTTPResponseState state, Headers headers) {
        // Extract request info from headers
        this.requestHeaders = headers;
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
        
        // Parse WebDAV headers
        if (webdavEnabled) {
            String depthHeader = headers.getValue(DAVConstants.HEADER_DEPTH);
            if (depthHeader != null) {
                if ("0".equals(depthHeader)) {
                    depth = DAVConstants.DEPTH_0;
                } else if ("1".equals(depthHeader)) {
                    depth = DAVConstants.DEPTH_1;
                } else {
                    depth = DAVConstants.DEPTH_INFINITY;
                }
            }
            destination = headers.getValue(DAVConstants.HEADER_DESTINATION);
            String overwriteHeader = headers.getValue(DAVConstants.HEADER_OVERWRITE);
            overwrite = overwriteHeader == null || !"F".equalsIgnoreCase(overwriteHeader);
            lockToken = headers.getValue(DAVConstants.HEADER_LOCK_TOKEN);
            ifHeader = headers.getValue(DAVConstants.HEADER_IF);
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
    public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
        // Handle WebDAV request body (PROPFIND, PROPPATCH, LOCK)
        if (webdavParser != null) {
            try {
                webdavParser.receive(data.duplicate());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error parsing WebDAV request body", e);
            }
            return;
        }
        
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
        // Finalize WebDAV request
        if (webdavParser != null) {
            try {
                webdavParser.close();
                finalizeWebDAVRequest(state);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error finalizing WebDAV request", e);
                sendError(state, HTTPStatus.BAD_REQUEST);
            }
            return;
        }
        
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
        webdavParser = null;
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
        } else if (webdavEnabled && "PROPFIND".equals(method)) {
            handlePropfind(state);
        } else if (webdavEnabled && "PROPPATCH".equals(method)) {
            handleProppatch(state);
        } else if (webdavEnabled && "MKCOL".equals(method)) {
            handleMkcol(state);
        } else if (webdavEnabled && "COPY".equals(method)) {
            handleCopy(state);
        } else if (webdavEnabled && "MOVE".equals(method)) {
            handleMove(state);
        } else if (webdavEnabled && "LOCK".equals(method)) {
            handleLock(state);
        } else if (webdavEnabled && "UNLOCK".equals(method)) {
            handleUnlock(state);
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
        if (webdavEnabled) {
            response.add(DAVConstants.HEADER_DAV, "1,2");
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Methods (RFC 2518)
    // ─────────────────────────────────────────────────────────────────────────

    private void handlePropfind(HTTPResponseState state) throws IOException {
        if (path == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        if (!Files.exists(path)) {
            sendError(state, HTTPStatus.NOT_FOUND);
            return;
        }
        
        // If there's a request body, we need to parse it
        if (requestContentLength > 0) {
            webdavParser = new WebDAVRequestParser();
            requestBodyExpected = true;
            // Response will be sent in finalizeWebDAVRequest
        } else {
            // No body = allprop request
            sendPropfindResponse(state, WebDAVRequestParser.PropfindType.ALLPROP, null, null);
        }
    }

    private void handleProppatch(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        if (path == null || !Files.exists(path)) {
            sendError(state, HTTPStatus.NOT_FOUND);
            return;
        }
        
        // Check for locks
        if (!checkLockToken(path)) {
            sendError(state, HTTPStatus.LOCKED);
            return;
        }
        
        if (requestContentLength > 0) {
            webdavParser = new WebDAVRequestParser();
            requestBodyExpected = true;
        } else {
            sendError(state, HTTPStatus.BAD_REQUEST);
        }
    }

    private void handleMkcol(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        if (path == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        if (Files.exists(path)) {
            sendError(state, HTTPStatus.METHOD_NOT_ALLOWED);
            return;
        }
        
        // Parent must exist
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            sendError(state, HTTPStatus.CONFLICT);
            return;
        }
        
        // MKCOL must not have a body
        if (requestContentLength > 0) {
            sendError(state, HTTPStatus.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        
        try {
            Files.createDirectory(path);
            Headers response = new Headers();
            response.status(HTTPStatus.CREATED);
            state.headers(response);
            state.complete();
            LOGGER.info("Created collection: " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create collection: " + path, e);
            sendError(state, HTTPStatus.FORBIDDEN);
        }
    }

    private void handleCopy(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        if (path == null || !Files.exists(path)) {
            sendError(state, HTTPStatus.NOT_FOUND);
            return;
        }
        
        if (destination == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        Path destPath = resolveDestination(destination);
        if (destPath == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        // Check destination lock
        if (!checkLockToken(destPath)) {
            sendError(state, HTTPStatus.LOCKED);
            return;
        }
        
        boolean destExists = Files.exists(destPath);
        if (destExists && !overwrite) {
            sendError(state, HTTPStatus.PRECONDITION_FAILED);
            return;
        }
        
        try {
            if (Files.isDirectory(path)) {
                copyDirectory(path, destPath, depth);
            } else {
                if (overwrite) {
                    Files.copy(path, destPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(path, destPath);
                }
            }
            
            Headers response = new Headers();
            response.status(destExists ? HTTPStatus.NO_CONTENT : HTTPStatus.CREATED);
            state.headers(response);
            state.complete();
            LOGGER.info("Copied " + path + " to " + destPath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to copy: " + path, e);
            sendError(state, HTTPStatus.FORBIDDEN);
        }
    }

    private void handleMove(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        if (path == null || !Files.exists(path)) {
            sendError(state, HTTPStatus.NOT_FOUND);
            return;
        }
        
        if (destination == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        // Check source lock
        if (!checkLockToken(path)) {
            sendError(state, HTTPStatus.LOCKED);
            return;
        }
        
        Path destPath = resolveDestination(destination);
        if (destPath == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        // Check destination lock
        if (!checkLockToken(destPath)) {
            sendError(state, HTTPStatus.LOCKED);
            return;
        }
        
        boolean destExists = Files.exists(destPath);
        if (destExists && !overwrite) {
            sendError(state, HTTPStatus.PRECONDITION_FAILED);
            return;
        }
        
        try {
            if (overwrite && destExists) {
                if (Files.isDirectory(destPath)) {
                    deleteDirectory(destPath);
                } else {
                    Files.delete(destPath);
                }
            }
            Files.move(path, destPath);
            
            Headers response = new Headers();
            response.status(destExists ? HTTPStatus.NO_CONTENT : HTTPStatus.CREATED);
            state.headers(response);
            state.complete();
            LOGGER.info("Moved " + path + " to " + destPath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to move: " + path, e);
            sendError(state, HTTPStatus.FORBIDDEN);
        }
    }

    private void handleLock(HTTPResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        if (path == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        // Lock refresh if we have a lock token
        if (lockToken != null && requestContentLength == 0) {
            String token = extractLockToken(lockToken);
            if (token != null) {
                long timeout = parseTimeout(requestHeaders.getValue(DAVConstants.HEADER_TIMEOUT));
                WebDAVLock refreshed = lockManager.refresh(token, timeout);
                if (refreshed != null) {
                    sendLockResponse(state, refreshed, false);
                    return;
                }
            }
            sendError(state, HTTPStatus.PRECONDITION_FAILED);
            return;
        }
        
        // New lock request
        if (requestContentLength > 0) {
            webdavParser = new WebDAVRequestParser();
            requestBodyExpected = true;
        } else {
            // Default to exclusive write lock
            createLock(state, WebDAVLock.Scope.EXCLUSIVE, WebDAVLock.Type.WRITE, null);
        }
    }

    private void handleUnlock(HTTPResponseState state) {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }
        
        if (path == null || lockToken == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        String token = extractLockToken(lockToken);
        if (token == null) {
            sendError(state, HTTPStatus.BAD_REQUEST);
            return;
        }
        
        if (lockManager.unlock(token)) {
            Headers response = new Headers();
            response.status(HTTPStatus.NO_CONTENT);
            state.headers(response);
            state.complete();
            LOGGER.info("Unlocked: " + path);
        } else {
            sendError(state, HTTPStatus.CONFLICT);
        }
    }

    private void finalizeWebDAVRequest(HTTPResponseState state) throws IOException {
        WebDAVRequestParser.PropfindRequest propfind = webdavParser.getPropfindRequest();
        if (propfind != null) {
            sendPropfindResponse(state, propfind.type, propfind.properties, propfind.include);
            return;
        }
        
        WebDAVRequestParser.ProppatchRequest proppatch = webdavParser.getProppatchRequest();
        if (proppatch != null) {
            sendProppatchResponse(state, proppatch);
            return;
        }
        
        WebDAVRequestParser.LockRequest lockReq = webdavParser.getLockRequest();
        if (lockReq != null) {
            createLock(state, lockReq.scope, lockReq.type, lockReq.owner);
            return;
        }
        
        sendError(state, HTTPStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Response Generation
    // ─────────────────────────────────────────────────────────────────────────

    private void sendPropfindResponse(HTTPResponseState state, 
            WebDAVRequestParser.PropfindType type,
            List<WebDAVRequestParser.PropertyRef> requestedProps,
            List<WebDAVRequestParser.PropertyRef> include) throws IOException {
        
        List<Path> resources = collectResources(path, depth);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);
        
        xml.writeStartElement(DAVConstants.PREFIX, DAVConstants.ELEM_MULTISTATUS, DAVConstants.NAMESPACE);
        xml.writeNamespace(DAVConstants.PREFIX, DAVConstants.NAMESPACE);
        
        for (Path resource : resources) {
            writeResourceResponse(xml, resource, type, requestedProps);
        }
        
        xml.writeEndElement();
        xml.close();
        
        byte[] body = baos.toByteArray();
        Headers response = new Headers();
        response.status(HTTPStatus.MULTI_STATUS);
        response.add("Content-Type", DAVConstants.CONTENT_TYPE_XML);
        response.add("Content-Length", String.valueOf(body.length));
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(body));
        state.endResponseBody();
        state.complete();
    }

    private void writeResourceResponse(XMLWriter xml, Path resource,
            WebDAVRequestParser.PropfindType type,
            List<WebDAVRequestParser.PropertyRef> requestedProps) throws IOException {
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_RESPONSE);
        
        // Write href
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_HREF);
        String href = getHref(resource);
        xml.writeCharacters(href);
        xml.writeEndElement();
        
        // Write propstat
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_PROPSTAT);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_PROP);
        
        if (type == WebDAVRequestParser.PropfindType.PROPNAME) {
            writePropertyNames(xml, resource);
        } else if (type == WebDAVRequestParser.PropfindType.PROP && requestedProps != null) {
            writeRequestedProperties(xml, resource, requestedProps);
        } else {
            writeAllProperties(xml, resource);
        }
        
        xml.writeEndElement(); // prop
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_STATUS);
        xml.writeCharacters("HTTP/1.1 200 OK");
        xml.writeEndElement();
        
        xml.writeEndElement(); // propstat
        xml.writeEndElement(); // response
    }

    private void writePropertyNames(XMLWriter xml, Path resource) throws IOException {
        Set<String> names = getLivePropertyNames();
        for (String name : names) {
            xml.writeStartElement(DAVConstants.NAMESPACE, name);
            xml.writeEndElement();
        }
    }

    private void writeAllProperties(XMLWriter xml, Path resource) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(resource, BasicFileAttributes.class);
        
        // creationdate
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_CREATIONDATE);
        xml.writeCharacters(formatISO8601(attrs.creationTime().toMillis()));
        xml.writeEndElement();
        
        // displayname
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_DISPLAYNAME);
        Path fileName = resource.getFileName();
        xml.writeCharacters(fileName != null ? fileName.toString() : "");
        xml.writeEndElement();
        
        // getcontentlength (files only)
        if (!Files.isDirectory(resource)) {
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_GETCONTENTLENGTH);
            xml.writeCharacters(String.valueOf(attrs.size()));
            xml.writeEndElement();
        }
        
        // getcontenttype
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_GETCONTENTTYPE);
        xml.writeCharacters(Files.isDirectory(resource) ? "httpd/unix-directory" : getContentType(resource));
        xml.writeEndElement();
        
        // getetag
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_GETETAG);
        xml.writeCharacters("\"" + generateETag(resource, attrs) + "\"");
        xml.writeEndElement();
        
        // getlastmodified
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_GETLASTMODIFIED);
        xml.writeCharacters(dateFormat.format(attrs.lastModifiedTime().toMillis()));
        xml.writeEndElement();
        
        // lockdiscovery
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_LOCKDISCOVERY);
        writeLockDiscovery(xml, resource);
        xml.writeEndElement();
        
        // resourcetype
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_RESOURCETYPE);
        if (Files.isDirectory(resource)) {
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_COLLECTION);
            xml.writeEndElement();
        }
        xml.writeEndElement();
        
        // supportedlock
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_SUPPORTEDLOCK);
        writeSupportedLock(xml);
        xml.writeEndElement();
    }

    private void writeRequestedProperties(XMLWriter xml, Path resource,
            List<WebDAVRequestParser.PropertyRef> props) throws IOException {
        BasicFileAttributes attrs = null;
        
        for (WebDAVRequestParser.PropertyRef prop : props) {
            if (!DAVConstants.NAMESPACE.equals(prop.namespaceURI)) {
                // Unknown property - skip for now
                continue;
            }
            
            if (attrs == null) {
                attrs = Files.readAttributes(resource, BasicFileAttributes.class);
            }
            
            String name = prop.localName;
            if (DAVConstants.PROP_CREATIONDATE.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                xml.writeCharacters(formatISO8601(attrs.creationTime().toMillis()));
                xml.writeEndElement();
            } else if (DAVConstants.PROP_DISPLAYNAME.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                Path fileName = resource.getFileName();
                xml.writeCharacters(fileName != null ? fileName.toString() : "");
                xml.writeEndElement();
            } else if (DAVConstants.PROP_GETCONTENTLENGTH.equals(name)) {
                if (!Files.isDirectory(resource)) {
                    xml.writeStartElement(DAVConstants.NAMESPACE, name);
                    xml.writeCharacters(String.valueOf(attrs.size()));
                    xml.writeEndElement();
                }
            } else if (DAVConstants.PROP_GETCONTENTTYPE.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                xml.writeCharacters(Files.isDirectory(resource) ? "httpd/unix-directory" : getContentType(resource));
                xml.writeEndElement();
            } else if (DAVConstants.PROP_GETETAG.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                xml.writeCharacters("\"" + generateETag(resource, attrs) + "\"");
                xml.writeEndElement();
            } else if (DAVConstants.PROP_GETLASTMODIFIED.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                xml.writeCharacters(dateFormat.format(attrs.lastModifiedTime().toMillis()));
                xml.writeEndElement();
            } else if (DAVConstants.PROP_LOCKDISCOVERY.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                writeLockDiscovery(xml, resource);
                xml.writeEndElement();
            } else if (DAVConstants.PROP_RESOURCETYPE.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                if (Files.isDirectory(resource)) {
                    xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_COLLECTION);
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            } else if (DAVConstants.PROP_SUPPORTEDLOCK.equals(name)) {
                xml.writeStartElement(DAVConstants.NAMESPACE, name);
                writeSupportedLock(xml);
                xml.writeEndElement();
            }
        }
    }

    private void writeLockDiscovery(XMLWriter xml, Path resource) throws IOException {
        List<WebDAVLock> locks = lockManager.getCoveringLocks(resource);
        for (WebDAVLock lock : locks) {
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_ACTIVELOCK);
            
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKTYPE);
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_WRITE);
            xml.writeEndElement();
            xml.writeEndElement();
            
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKSCOPE);
            if (lock.getScope() == WebDAVLock.Scope.EXCLUSIVE) {
                xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_EXCLUSIVE);
            } else {
                xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_SHARED);
            }
            xml.writeEndElement();
            xml.writeEndElement();
            
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_DEPTH);
            xml.writeCharacters(lock.getDepth() == DAVConstants.DEPTH_INFINITY ? "infinity" : String.valueOf(lock.getDepth()));
            xml.writeEndElement();
            
            if (lock.getOwner() != null) {
                xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_OWNER);
                xml.writeCharacters(lock.getOwner());
                xml.writeEndElement();
            }
            
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_TIMEOUT);
            long remaining = lock.getRemainingTimeoutSeconds();
            xml.writeCharacters(remaining < 0 ? "Infinite" : "Second-" + remaining);
            xml.writeEndElement();
            
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKTOKEN);
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_HREF);
            xml.writeCharacters(lock.getToken());
            xml.writeEndElement();
            xml.writeEndElement();
            
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKROOT);
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_HREF);
            xml.writeCharacters(getHref(lock.getPath()));
            xml.writeEndElement();
            xml.writeEndElement();
            
            xml.writeEndElement(); // activelock
        }
    }

    private void writeSupportedLock(XMLWriter xml) throws IOException {
        // Exclusive write lock
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKENTRY);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKSCOPE);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_EXCLUSIVE);
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKTYPE);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_WRITE);
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndElement();
        
        // Shared write lock
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKENTRY);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKSCOPE);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_SHARED);
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKTYPE);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_WRITE);
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private void sendProppatchResponse(HTTPResponseState state, 
            WebDAVRequestParser.ProppatchRequest proppatch) throws IOException {
        // For now, we don't support dead properties, return 403 for all changes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);
        
        xml.writeStartElement(DAVConstants.PREFIX, DAVConstants.ELEM_MULTISTATUS, DAVConstants.NAMESPACE);
        xml.writeNamespace(DAVConstants.PREFIX, DAVConstants.NAMESPACE);
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_RESPONSE);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_HREF);
        xml.writeCharacters(getHref(path));
        xml.writeEndElement();
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_PROPSTAT);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_PROP);
        
        for (WebDAVRequestParser.PropertyUpdate update : proppatch.updates) {
            if (update.namespaceURI != null && !update.namespaceURI.isEmpty()) {
                xml.writeStartElement(update.namespaceURI, update.localName);
            } else {
                xml.writeStartElement(update.localName);
            }
            xml.writeEndElement();
        }
        
        xml.writeEndElement(); // prop
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_STATUS);
        xml.writeCharacters("HTTP/1.1 403 Forbidden");
        xml.writeEndElement();
        xml.writeEndElement(); // propstat
        xml.writeEndElement(); // response
        xml.writeEndElement(); // multistatus
        xml.close();
        
        byte[] body = baos.toByteArray();
        Headers response = new Headers();
        response.status(HTTPStatus.MULTI_STATUS);
        response.add("Content-Type", DAVConstants.CONTENT_TYPE_XML);
        response.add("Content-Length", String.valueOf(body.length));
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(body));
        state.endResponseBody();
        state.complete();
    }

    private void createLock(HTTPResponseState state, WebDAVLock.Scope scope,
            WebDAVLock.Type type, String owner) throws IOException {
        long timeout = parseTimeout(requestHeaders.getValue(DAVConstants.HEADER_TIMEOUT));
        
        // Create empty file if it doesn't exist (lock-null resource)
        boolean created = false;
        if (!Files.exists(path)) {
            Files.createFile(path);
            created = true;
        }
        
        WebDAVLock lock = lockManager.lock(path, scope, type, depth, owner, timeout);
        if (lock == null) {
            sendError(state, HTTPStatus.LOCKED);
            return;
        }
        
        sendLockResponse(state, lock, created);
    }

    private void sendLockResponse(HTTPResponseState state, WebDAVLock lock, 
            boolean created) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);
        
        xml.writeStartElement(DAVConstants.PREFIX, DAVConstants.ELEM_PROP, DAVConstants.NAMESPACE);
        xml.writeNamespace(DAVConstants.PREFIX, DAVConstants.NAMESPACE);
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.PROP_LOCKDISCOVERY);
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_ACTIVELOCK);
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKTYPE);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_WRITE);
        xml.writeEndElement();
        xml.writeEndElement();
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKSCOPE);
        if (lock.getScope() == WebDAVLock.Scope.EXCLUSIVE) {
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_EXCLUSIVE);
        } else {
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_SHARED);
        }
        xml.writeEndElement();
        xml.writeEndElement();
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_DEPTH);
        xml.writeCharacters(lock.getDepth() == DAVConstants.DEPTH_INFINITY ? "infinity" : String.valueOf(lock.getDepth()));
        xml.writeEndElement();
        
        if (lock.getOwner() != null) {
            xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_OWNER);
            xml.writeCharacters(lock.getOwner());
            xml.writeEndElement();
        }
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_TIMEOUT);
        long remaining = lock.getRemainingTimeoutSeconds();
        xml.writeCharacters(remaining < 0 ? "Infinite" : "Second-" + remaining);
        xml.writeEndElement();
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKTOKEN);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_HREF);
        xml.writeCharacters(lock.getToken());
        xml.writeEndElement();
        xml.writeEndElement();
        
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_LOCKROOT);
        xml.writeStartElement(DAVConstants.NAMESPACE, DAVConstants.ELEM_HREF);
        xml.writeCharacters(getHref(lock.getPath()));
        xml.writeEndElement();
        xml.writeEndElement();
        
        xml.writeEndElement(); // activelock
        xml.writeEndElement(); // lockdiscovery
        xml.writeEndElement(); // prop
        xml.close();
        
        byte[] body = baos.toByteArray();
        Headers response = new Headers();
        response.status(created ? HTTPStatus.CREATED : HTTPStatus.OK);
        response.add("Content-Type", DAVConstants.CONTENT_TYPE_XML);
        response.add("Content-Length", String.valueOf(body.length));
        response.add(DAVConstants.HEADER_LOCK_TOKEN, "<" + lock.getToken() + ">");
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(body));
        state.endResponseBody();
        state.complete();
        LOGGER.info("Locked: " + path + " with token " + lock.getToken());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private Set<String> getLivePropertyNames() {
        Set<String> names = new HashSet<String>();
        names.add(DAVConstants.PROP_CREATIONDATE);
        names.add(DAVConstants.PROP_DISPLAYNAME);
        names.add(DAVConstants.PROP_GETCONTENTLENGTH);
        names.add(DAVConstants.PROP_GETCONTENTTYPE);
        names.add(DAVConstants.PROP_GETETAG);
        names.add(DAVConstants.PROP_GETLASTMODIFIED);
        names.add(DAVConstants.PROP_LOCKDISCOVERY);
        names.add(DAVConstants.PROP_RESOURCETYPE);
        names.add(DAVConstants.PROP_SUPPORTEDLOCK);
        return names;
    }

    private List<Path> collectResources(Path root, int depth) throws IOException {
        List<Path> result = new ArrayList<Path>();
        result.add(root);
        
        if (depth > 0 && Files.isDirectory(root)) {
            File[] children = root.toFile().listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.getName().startsWith(".")) {
                        continue; // Skip hidden files
                    }
                    Path childPath = child.toPath();
                    result.add(childPath);
                    if (depth > 1 && child.isDirectory()) {
                        result.addAll(collectResources(childPath, depth - 1));
                    }
                }
            }
        }
        
        return result;
    }

    private String getHref(Path resource) {
        Path relative = rootPath.relativize(resource);
        StringBuilder href = new StringBuilder("/");
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                href.append("/");
            }
            href.append(encodeURIComponent(relative.getName(i).toString()));
        }
        if (Files.isDirectory(resource) && href.length() > 1) {
            href.append("/");
        }
        return href.toString();
    }

    private String encodeURIComponent(String s) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') 
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                result.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    result.append('%');
                    result.append(String.format("%02X", b & 0xff));
                }
            }
        }
        return result.toString();
    }

    private Path resolveDestination(String dest) {
        try {
            URI uri = new URI(dest);
            String destPath = uri.getPath();
            if (destPath == null) {
                return null;
            }
            return validateAndResolvePath(destPath);
        } catch (Exception e) {
            return null;
        }
    }

    private void copyDirectory(Path source, Path target, int depth) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectory(target);
        }
        
        if (depth <= 0) {
            return;
        }
        
        File[] children = source.toFile().listFiles();
        if (children != null) {
            for (File child : children) {
                Path childSource = child.toPath();
                Path childTarget = target.resolve(child.getName());
                
                if (child.isDirectory()) {
                    copyDirectory(childSource, childTarget, depth - 1);
                } else {
                    Files.copy(childSource, childTarget, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean checkLockToken(Path targetPath) {
        if (lockManager == null) {
            return true;
        }
        
        List<WebDAVLock> locks = lockManager.getCoveringLocks(targetPath);
        if (locks.isEmpty()) {
            return true;
        }
        
        // Check if we have a valid lock token in the If header
        if (ifHeader != null) {
            for (WebDAVLock lock : locks) {
                if (ifHeader.contains(lock.getToken())) {
                    return true;
                }
            }
        }
        
        // Check Lock-Token header
        if (lockToken != null) {
            String token = extractLockToken(lockToken);
            if (token != null) {
                return lockManager.validateToken(targetPath, token);
            }
        }
        
        return false;
    }

    private String extractLockToken(String header) {
        if (header == null) {
            return null;
        }
        // Format: <opaquelocktoken:...>
        int start = header.indexOf('<');
        int end = header.indexOf('>');
        if (start >= 0 && end > start) {
            return header.substring(start + 1, end);
        }
        return header;
    }

    private long parseTimeout(String timeout) {
        if (timeout == null) {
            return DAVConstants.DEFAULT_LOCK_TIMEOUT_SECONDS;
        }
        
        if (DAVConstants.TIMEOUT_INFINITE.equalsIgnoreCase(timeout)) {
            return -1;
        }
        
        if (timeout.startsWith(DAVConstants.TIMEOUT_SECOND_PREFIX)) {
            try {
                long seconds = Long.parseLong(timeout.substring(DAVConstants.TIMEOUT_SECOND_PREFIX.length()));
                return Math.min(seconds, DAVConstants.MAX_LOCK_TIMEOUT_SECONDS);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        return DAVConstants.DEFAULT_LOCK_TIMEOUT_SECONDS;
    }

    private String formatISO8601(long millis) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(millis);
        return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND));
    }

    private String generateETag(Path resource, BasicFileAttributes attrs) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(resource.toString().getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf(attrs.size()).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf(attrs.lastModifiedTime().toMillis()).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString().substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(resource.hashCode());
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
        if (filePath == null || contentTypes == null) {
            return "application/octet-stream";
        }
        Path fileName = filePath.getFileName();
        if (fileName == null) {
            return "application/octet-stream";
        }
        String name = fileName.toString().toLowerCase();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < name.length() - 1) {
            String extension = name.substring(lastDot + 1);
            String contentType = contentTypes.get(extension);
            if (contentType != null) {
                return contentType;
            }
        }
        return "application/octet-stream";
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
