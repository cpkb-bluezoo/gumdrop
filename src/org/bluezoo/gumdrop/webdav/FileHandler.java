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
import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.util.ByteBufferPool;
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
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * HTTP request handler for serving files from the filesystem.
 *
 * <p>This handler supports GET, HEAD, OPTIONS, PUT, and DELETE methods
 * (RFC 9110) for serving and managing files within a configured
 * document root.
 *
 * <p>When WebDAV is enabled, this handler additionally supports
 * RFC 4918 distributed authoring methods: PROPFIND, PROPPATCH, MKCOL,
 * COPY, MOVE, LOCK, and UNLOCK.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918 - WebDAV</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110 - HTTP Semantics</a>
 */
class FileHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(FileHandler.class.getName());

    /** Pre-compiled patterns for dangerous path component detection */
    private static final Pattern DANGEROUS_CHARS = Pattern.compile(".*[<>:\"|?*].*");
    private static final Pattern RESERVED_NAMES = Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");
    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    private final Path rootPath;
    private final boolean allowWrite;
    private final boolean webdavEnabled;
    private final String allowedOptions;
    private final String[] welcomeFiles;
    private final Map<String, String> contentTypes;
    private final WebDAVLockManager lockManager;
    private final DeadPropertyStore deadPropertyStore;

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
    private AsynchronousFileChannel asyncWriteChannel;
    private long writePosition = 0;
    private long bytesReceived = 0;
    private boolean fileExistedBeforePut = false;
    private boolean requestBodyExpected = false;
    private boolean putFinalized = false;
    private boolean allRequestBodyReceived = false;
    
    // GET response body state
    private AsynchronousFileChannel asyncReadChannel;
    private long readPosition = 0;
    
    // WebDAV request body accumulation
    private ByteBuffer requestBodyBuffer;
    private WebDAVRequestParser webdavParser;
    private Headers requestHeaders;

    FileHandler(Path rootPath, boolean allowWrite, boolean webdavEnabled,
                String allowedOptions, String[] welcomeFiles,
                Map<String, String> contentTypes, WebDAVLockManager lockManager,
                DeadPropertyStore deadPropertyStore) {
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.webdavEnabled = webdavEnabled;
        this.allowedOptions = allowedOptions;
        this.welcomeFiles = welcomeFiles;
        this.contentTypes = contentTypes;
        this.lockManager = lockManager;
        this.deadPropertyStore = deadPropertyStore;
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
        
        if (!requestBodyExpected || asyncWriteChannel == null) {
            return;
        }
        
        // Copy data - the buffer is only valid during the callback
        int len = data.remaining();
        ByteBuffer copy = ByteBufferPool.acquire(len);
        copy.put(data).flip();
        bytesReceived += len;
        
        if (requestContentLength > 0 && bytesReceived >= requestContentLength) {
            allRequestBodyReceived = true;
        }
        
        state.pauseRequestBody();
        long pos = writePosition;
        asyncWriteChannel.write(copy, pos, copy, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                int bytesWritten = result;
                state.execute(() -> {
                    attachment.position(attachment.position() + bytesWritten);
                    writePosition += bytesWritten;
                    if (attachment.hasRemaining()) {
                        // Partial write - retry with remaining data
                        asyncWriteChannel.write(attachment, writePosition, attachment, this);
                    } else {
                        ByteBufferPool.release(attachment);
                        state.resumeRequestBody();
                        if (allRequestBodyReceived && writePosition >= bytesReceived) {
                            finalizePutRequest(state);
                        }
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                LOGGER.log(Level.SEVERE, "Error writing request body to file", exc);
                closeWriteChannel();
                ByteBufferPool.release(attachment);
                state.execute(() -> sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR));
            }
        });
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
        if (requestBodyExpected && asyncWriteChannel != null) {
            allRequestBodyReceived = true;
            if (writePosition >= bytesReceived) {
                finalizePutRequest(state);
            }
        }
    }

    @Override
    public void requestComplete(HTTPResponseState state) {
        // Clean up resources
        closeWriteChannel();
        closeReadChannel();
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

    /**
     * Runs a blocking filesystem operation on the shared {@link StorageExecutor}
     * and delivers the outcome back on this request's SelectorLoop thread (via
     * {@link HTTPResponseState#execute}), so the {@code onDone}/{@code onError}
     * continuation may safely touch the {@link HTTPResponseState}.
     *
     * <p>Handlers must call this instead of blocking the loop with
     * {@code java.nio.file.Files} metadata/tree operations, which would stall
     * every other connection multiplexed on the same loop.
     *
     * <p>If no executor is available (e.g. a unit-test harness with no running
     * server), the operation runs inline; callers therefore observe identical
     * behaviour whether or not the pool is present.
     *
     * @param <T> the result type of the blocking operation
     * @param state the response state used to marshal the callback to the loop
     * @param op the blocking work to run off the loop
     * @param onDone invoked on the loop with the operation's result
     * @param onError invoked on the loop if the operation threw or was rejected
     */
    private <T> void offload(final HTTPResponseState state,
            final Callable<T> op, final Consumer<T> onDone,
            final Consumer<Throwable> onError) {
        Gumdrop gumdrop = Gumdrop.getInstance();
        StorageExecutor exec =
                (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
        if (exec == null) {
            T result;
            try {
                result = op.call();
            } catch (Throwable t) {
                onError.accept(t);
                return;
            }
            onDone.accept(result);
            return;
        }
        exec.submit(new Executor() {
            @Override
            public void execute(Runnable command) {
                state.execute(command);
            }
        }, op, new StorageExecutor.Callback<T>() {
            @Override
            public void completed(T result) {
                onDone.accept(result);
            }

            @Override
            public void failed(Throwable error) {
                onError.accept(error);
            }
        });
    }

    /**
     * Plan for a GET/HEAD response, computed off the loop by
     * {@link #computeGetPlan()} so the emitting stage never blocks.
     */
    private static final class GetPlan {
        HTTPStatus error;       // if set: send this error status
        boolean notModified;    // 304 Not Modified
        long lastModified;      // for 200/304 Last-Modified header
        byte[] listingHtml;     // directory-listing body, if a directory
        Path file;              // file to serve (200 with body)
        long size;              // file size
        String contentType;     // file content type
    }

    /** RFC 9110 §9.3.1 (GET), §9.3.2 (HEAD), §13.1.3 (If-Modified-Since). */
    private void handleGetOrHead(HTTPResponseState state) {
        offload(state, new Callable<GetPlan>() {
            @Override
            public GetPlan call() throws IOException {
                return computeGetPlan();
            }
        }, new Consumer<GetPlan>() {
            @Override
            public void accept(GetPlan plan) {
                emitGetPlan(state, plan);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.SEVERE, "Error processing GET/HEAD", error);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Gathers all metadata for a GET/HEAD off the loop (blocking). */
    private GetPlan computeGetPlan() throws IOException {
        GetPlan plan = new GetPlan();
        if (path == null || !Files.exists(path)
                || DeadPropertyStore.isSidecarFile(path)) {
            plan.error = HTTPStatus.NOT_FOUND;
            return plan;
        }

        Path target = path;
        if (Files.isDirectory(target)) {
            Path indexFile = findIndexFile(target);
            if (indexFile != null) {
                target = indexFile;
            } else {
                plan.listingHtml =
                        buildDirectoryListing(target, requestPath);
                return plan;
            }
        }

        if (!Files.isReadable(target)) {
            plan.error = HTTPStatus.FORBIDDEN;
            return plan;
        }

        plan.file = target;
        plan.size = Files.size(target);
        plan.lastModified = Files.getLastModifiedTime(target).toMillis();
        plan.contentType = getContentType(target);
        if (plan.lastModified <= ifModifiedSince) {
            plan.notModified = true;
        }
        return plan;
    }

    /** Emits a {@link GetPlan} on the loop thread. */
    private void emitGetPlan(HTTPResponseState state, GetPlan plan) {
        if (plan.error != null) {
            sendError(state, plan.error);
            return;
        }

        if (plan.listingHtml != null) {
            Headers response = new Headers();
            response.status(HTTPStatus.OK);
            response.add("Content-Type", "text/html; charset=utf-8");
            response.add("Content-Length",
                    String.valueOf(plan.listingHtml.length));
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(plan.listingHtml));
            state.endResponseBody();
            state.complete();
            return;
        }

        if (plan.notModified) {
            Headers response = new Headers();
            response.status(HTTPStatus.NOT_MODIFIED);
            response.add("Last-Modified", dateFormat.format(plan.lastModified));
            state.headers(response);
            state.complete();
            return;
        }

        Headers response = new Headers();
        response.status(HTTPStatus.OK);
        response.add("Last-Modified", dateFormat.format(plan.lastModified));
        response.add("Content-Type", plan.contentType);
        response.add("Content-Length", Long.toString(plan.size));
        state.headers(response);

        if ("GET".equals(method)) {
            if (plan.size > 0) {
                state.startResponseBody();
                sendFileContent(plan.file, state);
                // endResponseBody()/complete() invoked from sendFileContent
            } else {
                state.complete();
            }
        } else {
            state.complete();
        }
    }

    private void sendFileContent(Path filePath, HTTPResponseState state) {
        try {
            asyncReadChannel = AsynchronousFileChannel.open(filePath, StandardOpenOption.READ);
            readPosition = 0;
            readNextChunk(state);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error opening file for read: " + filePath, e);
            sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void readNextChunk(HTTPResponseState state) {
        ByteBuffer buf = ByteBufferPool.acquire(8192);
        long pos = readPosition;
        asyncReadChannel.read(buf, pos, buf, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                int bytesRead = result;
                if (bytesRead <= 0) {
                    // EOF (bytesRead < 0) or empty read at end (bytesRead == 0)
                    closeReadChannel();
                    ByteBufferPool.release(attachment);
                    state.execute(() -> {
                        state.endResponseBody();
                        state.complete();
                    });
                    return;
                }
                readPosition += bytesRead;
                state.execute(() -> {
                    attachment.flip();
                    if (attachment.hasRemaining()) {
                        state.responseBodyContent(attachment);
                    }
                    ByteBufferPool.release(attachment);
                    state.onWritable(() -> readNextChunk(state));
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                LOGGER.log(Level.SEVERE, "Error reading file", exc);
                closeReadChannel();
                ByteBufferPool.release(attachment);
                state.execute(() -> {
                    sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
                });
            }
        });
    }

    private void closeReadChannel() {
        if (asyncReadChannel != null) {
            try {
                asyncReadChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing read channel", e);
            }
            asyncReadChannel = null;
        }
    }

    /** RFC 9110 §9.3.7 (OPTIONS); RFC 4918 §18 (DAV header, compliance classes 1,2). */
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

    /**
     * RFC 9110 §9.3.5 (DELETE); RFC 4918 §9.6.1 (collection DELETE).
     *
     * <p>For files, deletes the resource and returns 204 No Content.
     * For collections (directories), recursively deletes all member resources
     * depth-first. If any individual deletion fails, a 207 Multi-Status
     * response is returned listing the failed resources.
     */
    private void handleDelete(HTTPResponseState state) {
        if (!allowWrite) {
            sendError(state, HTTPStatus.METHOD_NOT_ALLOWED);
            return;
        }

        offload(state, new Callable<DeletePlan>() {
            @Override
            public DeletePlan call() throws IOException {
                return computeDeletePlan();
            }
        }, new Consumer<DeletePlan>() {
            @Override
            public void accept(DeletePlan plan) {
                emitDeletePlan(state, plan);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.SEVERE, "Error processing DELETE", error);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /**
     * Outcome of a DELETE, computed off the loop: either a single status or,
     * for a partially failed collection DELETE, a 207 Multi-Status error list.
     */
    private static final class DeletePlan {
        HTTPStatus status;
        List<String[]> multiStatus;
    }

    /** Performs the blocking DELETE work off the loop. */
    private DeletePlan computeDeletePlan() throws IOException {
        DeletePlan plan = new DeletePlan();
        if (path == null) {
            plan.status = HTTPStatus.BAD_REQUEST;
            return plan;
        }
        if (!Files.exists(path)) {
            plan.status = HTTPStatus.NOT_FOUND;
            return plan;
        }
        if (!checkLockToken(path)) {
            plan.status = HTTPStatus.LOCKED;
            return plan;
        }

        if (Files.isDirectory(path)) {
            List<String[]> errors = collectionDelete();
            if (errors.isEmpty()) {
                plan.status = HTTPStatus.NO_CONTENT;
                LOGGER.info("Deleted collection: " + path);
            } else {
                plan.multiStatus = errors;
            }
            return plan;
        }

        try {
            Files.delete(path);
            if (deadPropertyStore != null) {
                deadPropertyStore.deleteProperties(path);
            }
            plan.status = HTTPStatus.NO_CONTENT;
            LOGGER.info("Deleted file: " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete file: " + path, e);
            plan.status = HTTPStatus.FORBIDDEN;
        }
        return plan;
    }

    /** Emits a {@link DeletePlan} on the loop thread. */
    private void emitDeletePlan(HTTPResponseState state, DeletePlan plan) {
        if (plan.multiStatus != null) {
            try {
                sendDeleteMultiStatus(state, plan.multiStatus);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "DELETE multi-status error", e);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
            return;
        }
        if (plan.status == HTTPStatus.NO_CONTENT) {
            Headers response = new Headers();
            response.status(HTTPStatus.NO_CONTENT);
            state.headers(response);
            state.complete();
        } else {
            sendError(state, plan.status);
        }
    }

    /**
     * RFC 4918 §9.6.1 — recursively deletes a collection depth-first (files
     * first, then empty directories, then the collection root), returning the
     * list of per-resource failures (empty if fully successful). Runs off the
     * loop as it walks the whole tree.
     */
    private List<String[]> collectionDelete() throws IOException {
        final List<String[]> errors = new ArrayList<>();

        // Walk the tree depth-first: delete files first, then directories
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    errors.add(new String[]{getHref(file), "HTTP/1.1 403 Forbidden"});
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                errors.add(new String[]{getHref(file), "HTTP/1.1 403 Forbidden"});
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (!dir.equals(path)) {
                    try {
                        Files.delete(dir);
                    } catch (IOException e) {
                        errors.add(new String[]{getHref(dir), "HTTP/1.1 403 Forbidden"});
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Delete the collection itself
        if (errors.isEmpty()) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                errors.add(new String[]{getHref(path), "HTTP/1.1 403 Forbidden"});
            }
        }

        return errors;
    }

    /**
     * Sends a 207 Multi-Status response for a partially failed collection DELETE.
     * RFC 4918 §9.6.1 — only resources that failed are listed.
     */
    private void sendDeleteMultiStatus(HTTPResponseState state, List<String[]> errors)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);

        davStart(xml, DAVConstants.ELEM_MULTISTATUS);
        xml.writeNamespace(DAVConstants.PREFIX, DAVConstants.NAMESPACE);

        for (String[] error : errors) {
            davStart(xml, DAVConstants.ELEM_RESPONSE);

            davStart(xml, DAVConstants.ELEM_HREF);
            davText(xml, error[0]);
            davEnd(xml, DAVConstants.ELEM_HREF);

            davStart(xml, DAVConstants.ELEM_STATUS);
            davText(xml, error[1]);
            davEnd(xml, DAVConstants.ELEM_STATUS);

            davEnd(xml, DAVConstants.ELEM_RESPONSE);
        }

        davEnd(xml, DAVConstants.ELEM_MULTISTATUS);
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

    /** RFC 9110 §9.3.4 (PUT) — 201 Created / 204 No Content. */
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
            asyncWriteChannel = AsynchronousFileChannel.open(path,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            writePosition = 0;
            
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

    private void closeWriteChannel() {
        if (asyncWriteChannel != null) {
            try {
                asyncWriteChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing write channel", e);
            }
            asyncWriteChannel = null;
        }
    }

    private void finalizePutRequest(HTTPResponseState state) {
        if (putFinalized) {
            return;
        }
        putFinalized = true;

        closeWriteChannel();
        requestBodyExpected = false;

        HTTPStatus status = fileExistedBeforePut ? HTTPStatus.NO_CONTENT : HTTPStatus.CREATED;

        Headers response = new Headers();
        response.status(status);
        response.add("Content-Length", "0");
        state.headers(response);
        state.complete();

        LOGGER.info("PUT completed for file: " + path);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Methods (RFC 4918)
    // ─────────────────────────────────────────────────────────────────────────

    /** RFC 4918 §9.1 — PROPFIND (allprop, propname, or named properties). */
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

    /**
     * RFC 4918 section 9.2 -- PROPPATCH (set/remove properties).
     * Dead properties are persisted via {@link DeadPropertyStore}.
     */
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

    /**
     * Emits the response for a body-less write method (MKCOL/COPY/MOVE): a
     * bare success status (201/204/200) or, for any other status, an error.
     */
    private void emitWriteResult(HTTPResponseState state, HTTPStatus status) {
        if (status == HTTPStatus.CREATED || status == HTTPStatus.NO_CONTENT
                || status == HTTPStatus.OK) {
            Headers response = new Headers();
            response.status(status);
            state.headers(response);
            state.complete();
        } else {
            sendError(state, status);
        }
    }

    /** RFC 4918 §9.3 — MKCOL (create collection). */
    private void handleMkcol(HTTPResponseState state) {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }

        offload(state, new Callable<HTTPStatus>() {
            @Override
            public HTTPStatus call() {
                return computeMkcol();
            }
        }, new Consumer<HTTPStatus>() {
            @Override
            public void accept(HTTPStatus status) {
                emitWriteResult(state, status);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.SEVERE, "Error processing MKCOL", error);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs the blocking MKCOL work off the loop. */
    private HTTPStatus computeMkcol() {
        if (path == null) {
            return HTTPStatus.BAD_REQUEST;
        }
        if (Files.exists(path)) {
            return HTTPStatus.METHOD_NOT_ALLOWED;
        }
        // Parent must exist
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            return HTTPStatus.CONFLICT;
        }
        // MKCOL must not have a body
        if (requestContentLength > 0) {
            return HTTPStatus.UNSUPPORTED_MEDIA_TYPE;
        }
        try {
            Files.createDirectory(path);
            LOGGER.info("Created collection: " + path);
            return HTTPStatus.CREATED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create collection: " + path, e);
            return HTTPStatus.FORBIDDEN;
        }
    }

    /** RFC 4918 §9.8 — COPY with Destination, Overwrite, Depth. */
    private void handleCopy(HTTPResponseState state) {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }

        offload(state, new Callable<HTTPStatus>() {
            @Override
            public HTTPStatus call() throws IOException {
                return computeCopy();
            }
        }, new Consumer<HTTPStatus>() {
            @Override
            public void accept(HTTPStatus status) {
                emitWriteResult(state, status);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.SEVERE, "Error processing COPY", error);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs the blocking COPY work (incl. recursive copy) off the loop. */
    private HTTPStatus computeCopy() throws IOException {
        if (path == null || !Files.exists(path)) {
            return HTTPStatus.NOT_FOUND;
        }
        if (destination == null) {
            return HTTPStatus.BAD_REQUEST;
        }
        Path destPath = resolveDestination(destination);
        if (destPath == null) {
            return HTTPStatus.BAD_REQUEST;
        }
        // Check destination lock
        if (!checkLockToken(destPath)) {
            return HTTPStatus.LOCKED;
        }
        boolean destExists = Files.exists(destPath);
        if (destExists && !overwrite) {
            return HTTPStatus.PRECONDITION_FAILED;
        }

        try {
            if (Files.isDirectory(path)) {
                copyDirectory(path, destPath, depth);
            } else {
                if (overwrite) {
                    Files.copy(path, destPath,
                            StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(path, destPath);
                }
                if (deadPropertyStore != null) {
                    deadPropertyStore.copyProperties(path, destPath);
                }
            }
            LOGGER.info("Copied " + path + " to " + destPath);
            return destExists ? HTTPStatus.NO_CONTENT : HTTPStatus.CREATED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to copy: " + path, e);
            return HTTPStatus.FORBIDDEN;
        }
    }

    /** RFC 4918 §9.9 — MOVE with Destination, Overwrite, lock checks. */
    private void handleMove(HTTPResponseState state) {
        if (!allowWrite) {
            sendError(state, HTTPStatus.FORBIDDEN);
            return;
        }

        offload(state, new Callable<HTTPStatus>() {
            @Override
            public HTTPStatus call() throws IOException {
                return computeMove();
            }
        }, new Consumer<HTTPStatus>() {
            @Override
            public void accept(HTTPStatus status) {
                emitWriteResult(state, status);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.SEVERE, "Error processing MOVE", error);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs the blocking MOVE work (incl. recursive delete) off the loop. */
    private HTTPStatus computeMove() throws IOException {
        if (path == null || !Files.exists(path)) {
            return HTTPStatus.NOT_FOUND;
        }
        if (destination == null) {
            return HTTPStatus.BAD_REQUEST;
        }
        // Check source lock
        if (!checkLockToken(path)) {
            return HTTPStatus.LOCKED;
        }
        Path destPath = resolveDestination(destination);
        if (destPath == null) {
            return HTTPStatus.BAD_REQUEST;
        }
        // Check destination lock
        if (!checkLockToken(destPath)) {
            return HTTPStatus.LOCKED;
        }
        boolean destExists = Files.exists(destPath);
        if (destExists && !overwrite) {
            return HTTPStatus.PRECONDITION_FAILED;
        }

        try {
            if (overwrite && destExists) {
                if (Files.isDirectory(destPath)) {
                    deleteDirectory(destPath);
                } else {
                    Files.delete(destPath);
                }
            }

            Path srcSidecar = null;
            if (deadPropertyStore != null
                    && !Files.isDirectory(path)) {
                srcSidecar = DeadPropertyStore.sidecarPath(path);
                if (!Files.exists(srcSidecar)) {
                    srcSidecar = null;
                }
            }

            Files.move(path, destPath);

            if (srcSidecar != null) {
                Path dstSidecar =
                        DeadPropertyStore.sidecarPath(destPath);
                try {
                    Files.move(srcSidecar, dstSidecar,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Sidecar move failed", e);
                }
            }

            LOGGER.info("Moved " + path + " to " + destPath);
            return destExists ? HTTPStatus.NO_CONTENT : HTTPStatus.CREATED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to move: " + path, e);
            return HTTPStatus.FORBIDDEN;
        }
    }

    /** RFC 4918 §9.10 — LOCK (new lock or refresh). */
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

    /** RFC 4918 §9.11 — UNLOCK by Lock-Token header. */
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

    /**
     * RFC 4918 section 9.1 -- 207 Multi-Status PROPFIND response.
     * Pre-loads dead properties for all resources (async), then
     * builds the XML response synchronously.
     */
    private void sendPropfindResponse(final HTTPResponseState state,
            final WebDAVRequestParser.PropfindType type,
            final List<WebDAVRequestParser.PropertyRef> requestedProps,
            final List<WebDAVRequestParser.PropertyRef> include) {

        offload(state, new Callable<PropfindData>() {
            @Override
            public PropfindData call() throws IOException {
                return gatherPropfindData();
            }
        }, new Consumer<PropfindData>() {
            @Override
            public void accept(PropfindData data) {
                if (deadPropertyStore != null
                        && deadPropertyStore.getMode()
                                != DeadPropertyStore.Mode.NONE) {
                    loadDeadPropertiesChain(data.resources, 0,
                            new HashMap<Path, Map<String, DeadProperty>>(),
                            state, type, requestedProps, data.attrs);
                } else {
                    buildPropfindResponse(state, data.resources, type,
                            requestedProps,
                            new HashMap<Path, Map<String, DeadProperty>>(),
                            data.attrs);
                }
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.SEVERE, "PROPFIND enumeration error", error);
                sendError(state, HTTPStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /**
     * Enumerated PROPFIND resources plus their pre-fetched attributes, gathered
     * off the loop so the XML response can be built without per-resource stats.
     */
    private static final class PropfindData {
        List<Path> resources;
        Map<Path, BasicFileAttributes> attrs;
    }

    /**
     * Performs the blocking PROPFIND enumeration off the loop: walks the tree
     * (bounded by Depth) and reads each resource's attributes up front.
     * Resources that vanish or become unreadable mid-walk are dropped rather
     * than aborting the whole Multi-Status.
     */
    private PropfindData gatherPropfindData() throws IOException {
        List<Path> resources = collectResources(path, depth);
        Map<Path, BasicFileAttributes> attrs =
                new HashMap<Path, BasicFileAttributes>();
        List<Path> usable = new ArrayList<Path>(resources.size());
        for (int i = 0; i < resources.size(); i++) {
            Path resource = resources.get(i);
            try {
                attrs.put(resource, Files.readAttributes(resource,
                        BasicFileAttributes.class));
                usable.add(resource);
            } catch (IOException e) {
                LOGGER.log(Level.FINE,
                        "Skipping unreadable PROPFIND resource: " + resource, e);
            }
        }
        PropfindData data = new PropfindData();
        data.resources = usable;
        data.attrs = attrs;
        return data;
    }

    /**
     * Chains async dead property loading for each resource.
     */
    private void loadDeadPropertiesChain(
            final List<Path> resources, final int index,
            final Map<Path, Map<String, DeadProperty>> allDeadProps,
            final HTTPResponseState state,
            final WebDAVRequestParser.PropfindType type,
            final List<WebDAVRequestParser.PropertyRef> requestedProps,
            final Map<Path, BasicFileAttributes> attrsMap) {
        if (index >= resources.size()) {
            buildPropfindResponse(state, resources, type,
                    requestedProps, allDeadProps, attrsMap);
            return;
        }
        final Path resource = resources.get(index);
        deadPropertyStore.getProperties(resource,
                new DeadPropertyCallback() {
                    @Override
                    public void onProperties(
                            Map<String, DeadProperty> props) {
                        if (props != null && !props.isEmpty()) {
                            allDeadProps.put(resource, props);
                        }
                        loadDeadPropertiesChain(resources, index + 1,
                                allDeadProps, state, type,
                                requestedProps, attrsMap);
                    }

                    @Override
                    public void onError(String error) {
                        loadDeadPropertiesChain(resources, index + 1,
                                allDeadProps, state, type,
                                requestedProps, attrsMap);
                    }
                });
    }

    private void buildPropfindResponse(HTTPResponseState state,
            List<Path> resources,
            WebDAVRequestParser.PropfindType type,
            List<WebDAVRequestParser.PropertyRef> requestedProps,
            Map<Path, Map<String, DeadProperty>> allDeadProps,
            Map<Path, BasicFileAttributes> attrsMap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            XMLWriter xml = new XMLWriter(baos);

            davStart(xml, DAVConstants.ELEM_MULTISTATUS);
            xml.writeNamespace(DAVConstants.PREFIX,
                    DAVConstants.NAMESPACE);

            for (int i = 0; i < resources.size(); i++) {
                Path resource = resources.get(i);
                Map<String, DeadProperty> deadProps =
                        allDeadProps.get(resource);
                writeResourceResponse(xml, resource, type,
                        requestedProps, deadProps, attrsMap);
            }

            davEnd(xml, DAVConstants.ELEM_MULTISTATUS);
            xml.close();

            byte[] body = baos.toByteArray();
            Headers response = new Headers();
            response.status(HTTPStatus.MULTI_STATUS);
            response.add("Content-Type",
                    DAVConstants.CONTENT_TYPE_XML);
            response.add("Content-Length",
                    String.valueOf(body.length));
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(body));
            state.endResponseBody();
            state.complete();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "PROPFIND response error", e);
        }
    }

    private void writeResourceResponse(XMLWriter xml, Path resource,
            WebDAVRequestParser.PropfindType type,
            List<WebDAVRequestParser.PropertyRef> requestedProps,
            Map<String, DeadProperty> deadProps,
            Map<Path, BasicFileAttributes> attrsMap)
            throws IOException {

        BasicFileAttributes attrs = attrsMap.get(resource);
        boolean isDir = attrs != null && attrs.isDirectory();

        davStart(xml, DAVConstants.ELEM_RESPONSE);

        davStart(xml, DAVConstants.ELEM_HREF);
        davText(xml, getHref(resource, isDir));
        davEnd(xml, DAVConstants.ELEM_HREF);

        davStart(xml, DAVConstants.ELEM_PROPSTAT);
        davStart(xml, DAVConstants.ELEM_PROP);

        if (type == WebDAVRequestParser.PropfindType.PROPNAME) {
            writePropertyNames(xml, resource, deadProps);
        } else if (type == WebDAVRequestParser.PropfindType.PROP
                && requestedProps != null) {
            writeRequestedProperties(xml, resource, requestedProps,
                    deadProps, attrs);
        } else {
            writeAllProperties(xml, resource, deadProps, attrs);
        }

        davEnd(xml, DAVConstants.ELEM_PROP);

        davStart(xml, DAVConstants.ELEM_STATUS);
        davText(xml, "HTTP/1.1 200 OK");
        davEnd(xml, DAVConstants.ELEM_STATUS);

        davEnd(xml, DAVConstants.ELEM_PROPSTAT);
        davEnd(xml, DAVConstants.ELEM_RESPONSE);
    }

    private void writePropertyNames(XMLWriter xml, Path resource,
            Map<String, DeadProperty> deadProps)
            throws IOException {
        Set<String> names = getLivePropertyNames();
        for (String name : names) {
            davEmpty(xml, name);
        }
        if (deadProps != null) {
            for (Map.Entry<String, DeadProperty> entry
                    : deadProps.entrySet()) {
                DeadProperty dp = entry.getValue();
                writeDeadPropertyElement(xml, dp, true);
            }
        }
    }

    /** RFC 4918 section 15 -- write all live + dead properties. */
    private void writeAllProperties(XMLWriter xml, Path resource,
            Map<String, DeadProperty> deadProps,
            BasicFileAttributes attrs)
            throws IOException {
        boolean isDir = attrs.isDirectory();

        davStartText(xml, DAVConstants.PROP_CREATIONDATE,
                formatISO8601(attrs.creationTime().toMillis()));

        Path fileName = resource.getFileName();
        davStartText(xml, DAVConstants.PROP_DISPLAYNAME,
                fileName != null ? fileName.toString() : "");

        if (!isDir) {
            davStartText(xml, DAVConstants.PROP_GETCONTENTLENGTH,
                    String.valueOf(attrs.size()));
        }

        davStartText(xml, DAVConstants.PROP_GETCONTENTTYPE,
                isDir
                        ? "httpd/unix-directory"
                        : getContentType(resource));

        davStartText(xml, DAVConstants.PROP_GETETAG,
                "\"" + generateETag(resource, attrs) + "\"");

        davStartText(xml, DAVConstants.PROP_GETLASTMODIFIED,
                dateFormat.format(
                        attrs.lastModifiedTime().toMillis()));

        davStart(xml, DAVConstants.PROP_LOCKDISCOVERY);
        writeLockDiscovery(xml, resource);
        davEnd(xml, DAVConstants.PROP_LOCKDISCOVERY);

        davStart(xml, DAVConstants.PROP_RESOURCETYPE);
        if (isDir) {
            davEmpty(xml, DAVConstants.ELEM_COLLECTION);
        }
        davEnd(xml, DAVConstants.PROP_RESOURCETYPE);

        davStart(xml, DAVConstants.PROP_SUPPORTEDLOCK);
        writeSupportedLock(xml);
        davEnd(xml, DAVConstants.PROP_SUPPORTEDLOCK);

        if (deadProps != null) {
            for (Map.Entry<String, DeadProperty> entry
                    : deadProps.entrySet()) {
                writeDeadPropertyElement(xml, entry.getValue(), false);
            }
        }
    }

    private void writeRequestedProperties(XMLWriter xml, Path resource,
            List<WebDAVRequestParser.PropertyRef> props,
            Map<String, DeadProperty> deadProps,
            BasicFileAttributes attrs)
            throws IOException {
        boolean isDir = attrs != null && attrs.isDirectory();

        for (int i = 0; i < props.size(); i++) {
            WebDAVRequestParser.PropertyRef prop = props.get(i);
            String ns = prop.namespaceURI;
            String name = prop.localName;

            if (DAVConstants.NAMESPACE.equals(ns)) {
                if (DAVConstants.PROP_CREATIONDATE.equals(name)) {
                    davStartText(xml, name,
                            formatISO8601(
                                    attrs.creationTime().toMillis()));
                } else if (DAVConstants.PROP_DISPLAYNAME
                        .equals(name)) {
                    Path fileName = resource.getFileName();
                    davStartText(xml, name,
                            fileName != null
                                    ? fileName.toString() : "");
                } else if (DAVConstants.PROP_GETCONTENTLENGTH
                        .equals(name)) {
                    if (!isDir) {
                        davStartText(xml, name,
                                String.valueOf(attrs.size()));
                    }
                } else if (DAVConstants.PROP_GETCONTENTTYPE
                        .equals(name)) {
                    davStartText(xml, name,
                            isDir
                                    ? "httpd/unix-directory"
                                    : getContentType(resource));
                } else if (DAVConstants.PROP_GETETAG.equals(name)) {
                    davStartText(xml, name,
                            "\"" + generateETag(resource, attrs)
                                    + "\"");
                } else if (DAVConstants.PROP_GETLASTMODIFIED
                        .equals(name)) {
                    davStartText(xml, name,
                            dateFormat.format(
                                    attrs.lastModifiedTime()
                                            .toMillis()));
                } else if (DAVConstants.PROP_LOCKDISCOVERY
                        .equals(name)) {
                    davStart(xml, name);
                    writeLockDiscovery(xml, resource);
                    davEnd(xml, name);
                } else if (DAVConstants.PROP_RESOURCETYPE
                        .equals(name)) {
                    davStart(xml, name);
                    if (isDir) {
                        davEmpty(xml,
                                DAVConstants.ELEM_COLLECTION);
                    }
                    davEnd(xml, name);
                } else if (DAVConstants.PROP_SUPPORTEDLOCK
                        .equals(name)) {
                    davStart(xml, name);
                    writeSupportedLock(xml);
                    davEnd(xml, name);
                }
            } else {
                String key = DeadProperty.makeKey(
                        ns != null ? ns : "", name);
                if (deadProps != null
                        && deadProps.containsKey(key)) {
                    writeDeadPropertyElement(xml,
                            deadProps.get(key), false);
                } else {
                    if (ns != null && !ns.isEmpty()) {
                        xml.writeStartElement(ns, name);
                    } else {
                        xml.writeStartElement(name);
                    }
                    xml.writeEndElement();
                }
            }
        }
    }

    /**
     * Writes a dead property element to the XML response.
     *
     * @param xml the XMLWriter
     * @param dp the dead property
     * @param nameOnly true for propname responses (empty element)
     */
    private void writeDeadPropertyElement(XMLWriter xml,
            DeadProperty dp, boolean nameOnly)
            throws IOException {
        String ns = dp.getNamespaceURI();
        String name = dp.getLocalName();
        if (ns != null && !ns.isEmpty()) {
            xml.writeStartElement(ns, name);
        } else {
            xml.writeStartElement(name);
        }
        if (!nameOnly && dp.getValue() != null) {
            xml.writeCharacters(dp.getValue());
        }
        xml.writeEndElement();
    }

    /** RFC 4918 section 15.8 -- lockdiscovery property (active locks). */
    private void writeLockDiscovery(XMLWriter xml, Path resource)
            throws IOException {
        List<WebDAVLock> locks = lockManager.getCoveringLocks(resource);
        for (WebDAVLock lock : locks) {
            davStart(xml, DAVConstants.ELEM_ACTIVELOCK);
            
            davStart(xml, DAVConstants.ELEM_LOCKTYPE);
            davEmpty(xml, DAVConstants.ELEM_WRITE);
            davEnd(xml, DAVConstants.ELEM_LOCKTYPE);
            
            davStart(xml, DAVConstants.ELEM_LOCKSCOPE);
            davEmpty(xml, lock.getScope() == WebDAVLock.Scope.EXCLUSIVE
                    ? DAVConstants.ELEM_EXCLUSIVE : DAVConstants.ELEM_SHARED);
            davEnd(xml, DAVConstants.ELEM_LOCKSCOPE);
            
            davStartText(xml, DAVConstants.ELEM_DEPTH,
                    lock.getDepth() == DAVConstants.DEPTH_INFINITY
                            ? "infinity" : String.valueOf(lock.getDepth()));
            
            if (lock.getOwner() != null) {
                davStartText(xml, DAVConstants.ELEM_OWNER, lock.getOwner());
            }
            
            long remaining = lock.getRemainingTimeoutSeconds();
            davStartText(xml, DAVConstants.ELEM_TIMEOUT,
                    remaining < 0 ? "Infinite" : "Second-" + remaining);
            
            davStart(xml, DAVConstants.ELEM_LOCKTOKEN);
            davStartText(xml, DAVConstants.ELEM_HREF, lock.getToken());
            davEnd(xml, DAVConstants.ELEM_LOCKTOKEN);
            
            davStart(xml, DAVConstants.ELEM_LOCKROOT);
            davStartText(xml, DAVConstants.ELEM_HREF, getHref(lock.getPath()));
            davEnd(xml, DAVConstants.ELEM_LOCKROOT);
            
            davEnd(xml, DAVConstants.ELEM_ACTIVELOCK);
        }
    }

    /** RFC 4918 §15.10 — supportedlock property (exclusive + shared write). */
    private void writeSupportedLock(XMLWriter xml) throws IOException {
        // Exclusive write lock
        davStart(xml, DAVConstants.ELEM_LOCKENTRY);
        davStart(xml, DAVConstants.ELEM_LOCKSCOPE);
        davEmpty(xml, DAVConstants.ELEM_EXCLUSIVE);
        davEnd(xml, DAVConstants.ELEM_LOCKSCOPE);
        davStart(xml, DAVConstants.ELEM_LOCKTYPE);
        davEmpty(xml, DAVConstants.ELEM_WRITE);
        davEnd(xml, DAVConstants.ELEM_LOCKTYPE);
        davEnd(xml, DAVConstants.ELEM_LOCKENTRY);
        
        // Shared write lock
        davStart(xml, DAVConstants.ELEM_LOCKENTRY);
        davStart(xml, DAVConstants.ELEM_LOCKSCOPE);
        davEmpty(xml, DAVConstants.ELEM_SHARED);
        davEnd(xml, DAVConstants.ELEM_LOCKSCOPE);
        davStart(xml, DAVConstants.ELEM_LOCKTYPE);
        davEmpty(xml, DAVConstants.ELEM_WRITE);
        davEnd(xml, DAVConstants.ELEM_LOCKTYPE);
        davEnd(xml, DAVConstants.ELEM_LOCKENTRY);
    }

    private static void davStart(XMLWriter xml, String localName)
            throws IOException {
        xml.writeStartElement(DAVConstants.PREFIX, localName, DAVConstants.NAMESPACE);
    }

    private static void davEnd(XMLWriter xml, String localName)
            throws IOException {
        xml.writeEndElement();
    }

    private static void davEmpty(XMLWriter xml, String localName)
            throws IOException {
        davStart(xml, localName);
        davEnd(xml, localName);
    }

    private static void davText(XMLWriter xml, String text)
            throws IOException {
        xml.writeCharacters(text);
    }

    private static void davStartText(XMLWriter xml, String localName,
            String text) throws IOException {
        davStart(xml, localName);
        davText(xml, text);
        davEnd(xml, localName);
    }

    /**
     * RFC 4918 section 9.2 -- 207 Multi-Status PROPPATCH response.
     * Applies each property update via {@link DeadPropertyStore}
     * and returns per-property status.
     */
    private void sendProppatchResponse(final HTTPResponseState state,
            final WebDAVRequestParser.ProppatchRequest proppatch)
            throws IOException {
        if (deadPropertyStore == null
                || deadPropertyStore.getMode()
                        == DeadPropertyStore.Mode.NONE) {
            sendProppatchForbidden(state, proppatch);
            return;
        }
        applyProppatchUpdate(state, proppatch, 0,
                new ArrayList<Boolean>());
    }

    /**
     * Applies PROPPATCH updates one at a time (async chain),
     * collecting per-property success/failure results.
     */
    private void applyProppatchUpdate(
            final HTTPResponseState state,
            final WebDAVRequestParser.ProppatchRequest proppatch,
            final int index,
            final List<Boolean> results) {
        if (index >= proppatch.updates.size()) {
            sendProppatchResult(state, proppatch, results);
            return;
        }

        final WebDAVRequestParser.PropertyUpdate update =
                proppatch.updates.get(index);
        String ns = update.namespaceURI != null
                ? update.namespaceURI : "";
        String name = update.localName;

        if (DAVConstants.NAMESPACE.equals(ns)
                && getLivePropertyNames().contains(name)) {
            results.add(Boolean.FALSE);
            applyProppatchUpdate(state, proppatch, index + 1,
                    results);
            return;
        }

        if (update.operation
                == WebDAVRequestParser.PropPatchOp.REMOVE) {
            deadPropertyStore.removeProperty(path, ns, name,
                    new DeadPropertyCallback() {
                        @Override
                        public void onProperties(
                                Map<String, DeadProperty> props) {
                            results.add(Boolean.TRUE);
                            applyProppatchUpdate(state, proppatch,
                                    index + 1, results);
                        }

                        @Override
                        public void onError(String error) {
                            results.add(Boolean.FALSE);
                            applyProppatchUpdate(state, proppatch,
                                    index + 1, results);
                        }
                    });
        } else {
            deadPropertyStore.setProperty(path, ns, name,
                    update.value, update.isXML,
                    new DeadPropertyCallback() {
                        @Override
                        public void onProperties(
                                Map<String, DeadProperty> props) {
                            results.add(Boolean.TRUE);
                            applyProppatchUpdate(state, proppatch,
                                    index + 1, results);
                        }

                        @Override
                        public void onError(String error) {
                            results.add(Boolean.FALSE);
                            applyProppatchUpdate(state, proppatch,
                                    index + 1, results);
                        }
                    });
        }
    }

    private void sendProppatchResult(HTTPResponseState state,
            WebDAVRequestParser.ProppatchRequest proppatch,
            List<Boolean> results) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            XMLWriter xml = new XMLWriter(baos);

            davStart(xml, DAVConstants.ELEM_MULTISTATUS);
            xml.writeNamespace(DAVConstants.PREFIX,
                    DAVConstants.NAMESPACE);

            davStart(xml, DAVConstants.ELEM_RESPONSE);
            davStartText(xml, DAVConstants.ELEM_HREF, getHref(path));

            List<Integer> okIndices = new ArrayList<Integer>();
            List<Integer> failIndices = new ArrayList<Integer>();
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).booleanValue()) {
                    okIndices.add(i);
                } else {
                    failIndices.add(i);
                }
            }

            if (!okIndices.isEmpty()) {
                davStart(xml, DAVConstants.ELEM_PROPSTAT);
                davStart(xml, DAVConstants.ELEM_PROP);
                for (int i = 0; i < okIndices.size(); i++) {
                    writePropElement(xml,
                            proppatch.updates.get(
                                    okIndices.get(i).intValue()));
                }
                davEnd(xml, DAVConstants.ELEM_PROP);
                davStartText(xml, DAVConstants.ELEM_STATUS,
                        "HTTP/1.1 200 OK");
                davEnd(xml, DAVConstants.ELEM_PROPSTAT);
            }

            if (!failIndices.isEmpty()) {
                davStart(xml, DAVConstants.ELEM_PROPSTAT);
                davStart(xml, DAVConstants.ELEM_PROP);
                for (int i = 0; i < failIndices.size(); i++) {
                    writePropElement(xml,
                            proppatch.updates.get(
                                    failIndices.get(i).intValue()));
                }
                davEnd(xml, DAVConstants.ELEM_PROP);
                davStartText(xml, DAVConstants.ELEM_STATUS,
                        "HTTP/1.1 403 Forbidden");
                davEnd(xml, DAVConstants.ELEM_PROPSTAT);
            }

            davEnd(xml, DAVConstants.ELEM_RESPONSE);
            davEnd(xml, DAVConstants.ELEM_MULTISTATUS);
            xml.close();

            byte[] body = baos.toByteArray();
            Headers response = new Headers();
            response.status(HTTPStatus.MULTI_STATUS);
            response.add("Content-Type",
                    DAVConstants.CONTENT_TYPE_XML);
            response.add("Content-Length",
                    String.valueOf(body.length));
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(body));
            state.endResponseBody();
            state.complete();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "PROPPATCH response error", e);
        }
    }

    private void writePropElement(XMLWriter xml,
            WebDAVRequestParser.PropertyUpdate update)
            throws IOException {
        String ns = update.namespaceURI != null
                && !update.namespaceURI.isEmpty()
                ? update.namespaceURI : "";
        if (DAVConstants.NAMESPACE.equals(ns)) {
            xml.writeStartElement(DAVConstants.PREFIX,
                    update.localName, ns);
        } else if (!ns.isEmpty()) {
            xml.writeStartElement(ns, update.localName);
        } else {
            xml.writeStartElement(update.localName);
        }
        xml.writeEndElement();
    }

    /** Fallback when dead property store is not available. */
    private void sendProppatchForbidden(HTTPResponseState state,
            WebDAVRequestParser.ProppatchRequest proppatch)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);

        davStart(xml, DAVConstants.ELEM_MULTISTATUS);
        xml.writeNamespace(DAVConstants.PREFIX,
                DAVConstants.NAMESPACE);

        davStart(xml, DAVConstants.ELEM_RESPONSE);
        davStartText(xml, DAVConstants.ELEM_HREF, getHref(path));

        davStart(xml, DAVConstants.ELEM_PROPSTAT);
        davStart(xml, DAVConstants.ELEM_PROP);
        for (WebDAVRequestParser.PropertyUpdate update
                : proppatch.updates) {
            writePropElement(xml, update);
        }
        davEnd(xml, DAVConstants.ELEM_PROP);
        davStartText(xml, DAVConstants.ELEM_STATUS,
                "HTTP/1.1 403 Forbidden");
        davEnd(xml, DAVConstants.ELEM_PROPSTAT);
        davEnd(xml, DAVConstants.ELEM_RESPONSE);
        davEnd(xml, DAVConstants.ELEM_MULTISTATUS);
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

    /** RFC 4918 §9.10 — create lock; §7.3 — lock-null resource creation. */
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
        
        davStart(xml, DAVConstants.ELEM_PROP);
        xml.writeNamespace(DAVConstants.PREFIX, DAVConstants.NAMESPACE);
        
        davStart(xml, DAVConstants.PROP_LOCKDISCOVERY);
        
        davStart(xml, DAVConstants.ELEM_ACTIVELOCK);
        
        davStart(xml, DAVConstants.ELEM_LOCKTYPE);
        davEmpty(xml, DAVConstants.ELEM_WRITE);
        davEnd(xml, DAVConstants.ELEM_LOCKTYPE);
        
        davStart(xml, DAVConstants.ELEM_LOCKSCOPE);
        davEmpty(xml, lock.getScope() == WebDAVLock.Scope.EXCLUSIVE
                ? DAVConstants.ELEM_EXCLUSIVE : DAVConstants.ELEM_SHARED);
        davEnd(xml, DAVConstants.ELEM_LOCKSCOPE);
        
        davStartText(xml, DAVConstants.ELEM_DEPTH,
                lock.getDepth() == DAVConstants.DEPTH_INFINITY
                        ? "infinity" : String.valueOf(lock.getDepth()));
        
        if (lock.getOwner() != null) {
            davStartText(xml, DAVConstants.ELEM_OWNER, lock.getOwner());
        }
        
        long remaining = lock.getRemainingTimeoutSeconds();
        davStartText(xml, DAVConstants.ELEM_TIMEOUT,
                remaining < 0 ? "Infinite" : "Second-" + remaining);
        
        davStart(xml, DAVConstants.ELEM_LOCKTOKEN);
        davStartText(xml, DAVConstants.ELEM_HREF, lock.getToken());
        davEnd(xml, DAVConstants.ELEM_LOCKTOKEN);
        
        davStart(xml, DAVConstants.ELEM_LOCKROOT);
        davStartText(xml, DAVConstants.ELEM_HREF, getHref(lock.getPath()));
        davEnd(xml, DAVConstants.ELEM_LOCKROOT);
        
        davEnd(xml, DAVConstants.ELEM_ACTIVELOCK);
        davEnd(xml, DAVConstants.PROP_LOCKDISCOVERY);
        davEnd(xml, DAVConstants.ELEM_PROP);
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
        return getHref(resource, Files.isDirectory(resource));
    }

    /**
     * As {@link #getHref(Path)} but with the directory flag supplied by the
     * caller, avoiding a blocking {@code Files.isDirectory} stat when the
     * attribute is already known (e.g. pre-fetched during PROPFIND).
     */
    private String getHref(Path resource, boolean isDir) {
        Path relative = rootPath.relativize(resource);
        StringBuilder href = new StringBuilder("/");
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                href.append("/");
            }
            href.append(encodeURIComponent(relative.getName(i).toString()));
        }
        if (isDir && href.length() > 1) {
            href.append("/");
        }
        return href.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

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
                    result.append(HEX[(b >> 4) & 0x0F]);
                    result.append(HEX[b & 0x0F]);
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

    private void copyDirectory(Path source, Path target, int depth)
            throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectory(target);
        }

        if (depth <= 0) {
            return;
        }

        File[] children = source.toFile().listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                File child = children[i];
                if (DeadPropertyStore.isSidecarName(
                        child.getName())) {
                    continue;
                }
                Path childSource = child.toPath();
                Path childTarget = target.resolve(child.getName());

                if (child.isDirectory()) {
                    copyDirectory(childSource, childTarget,
                            depth - 1);
                } else {
                    Files.copy(childSource, childTarget,
                            StandardCopyOption.REPLACE_EXISTING);
                    if (deadPropertyStore != null) {
                        deadPropertyStore.copyProperties(
                                childSource, childTarget);
                    }
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

    /**
     * RFC 4918 §10.4 — full If header conditional evaluation.
     *
     * <p>Parses the If header using the complete grammar (tagged-list,
     * no-tag-list, Not conditions, state-tokens, and entity-tag
     * conditions) and evaluates it against the resource's current state.
     * Falls back to the Lock-Token header if no If header is present.
     */
    private boolean checkLockToken(Path targetPath) {
        if (lockManager == null) {
            return true;
        }
        
        List<WebDAVLock> locks = lockManager.getCoveringLocks(targetPath);
        if (locks.isEmpty()) {
            return true;
        }
        
        // Evaluate the full If header grammar (RFC 4918 §10.4)
        if (ifHeader != null) {
            IfHeaderParser parser = new IfHeaderParser(ifHeader);
            List<IfHeaderParser.IfGroup> groups = parser.parse();
            if (!groups.isEmpty()) {
                String currentETag = getResourceETag(targetPath);
                String href = getHref(targetPath);
                return IfHeaderParser.evaluate(groups, targetPath, href,
                        lockManager, currentETag);
            }
        }
        
        // Check Lock-Token header as fallback
        if (lockToken != null) {
            String token = extractLockToken(lockToken);
            if (token != null) {
                return lockManager.validateToken(targetPath, token);
            }
        }
        
        return false;
    }

    /**
     * Returns the current ETag for a resource, or null if unavailable.
     */
    private String getResourceETag(Path resource) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(resource, BasicFileAttributes.class);
            return "\"" + generateETag(resource, attrs) + "\"";
        } catch (IOException e) {
            return null;
        }
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
            return ByteArrays.toHexString(digest).substring(0, 16);
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
        if (DANGEROUS_CHARS.matcher(component).matches()) {
            return true;
        }
        for (char c : component.toCharArray()) {
            if (Character.isISOControl(c)) {
                return true;
            }
        }
        String upperComponent = component.toUpperCase();
        if (RESERVED_NAMES.matcher(upperComponent).matches()) {
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

    /**
     * Renders an HTML directory listing to UTF-8 bytes. This performs the
     * blocking directory enumeration ({@code listFiles}, per-entry
     * {@code isDirectory}/{@code length}) and must therefore run off the loop.
     */
    private byte[] buildDirectoryListing(Path directory, String requestPath) {
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
                        if (DeadPropertyStore.isSidecarName(
                                filename)) {
                            continue;
                        }
                        boolean isDirectory = file.isDirectory();
                        String displayName = isDirectory
                                ? filename + "/" : filename;
                        
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
        
        return html.toString().getBytes(StandardCharsets.UTF_8);
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
