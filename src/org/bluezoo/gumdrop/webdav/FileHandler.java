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
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.util.AsyncFile;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.http.HttpConditionalRequests;
import org.bluezoo.gumdrop.http.HttpDateFormat;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.quota.QuotaPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

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
class FileHandler extends DefaultHttpRequestHandler {

    @Override
    public boolean decodeRequestContentCoding() {
        return true;
    }

    @Override
    public boolean encodeResponseContentCoding() {
        return true;
    }

    private static final Logger LOGGER = Logger.getLogger(FileHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.webdav.L10N");

    private static final HttpDateFormat dateFormat = new HttpDateFormat();

    private final Path rootPath;
    /** Cached {@link Path#toRealPath()} of {@link #rootPath}; computed at construction. */
    private final Path canonicalRoot;
    private final boolean allowWrite;
    private final boolean webdavEnabled;
    private final String allowedOptions;
    private final String[] welcomeFiles;
    private final Map<String, String> contentTypes;
    private final WebDAVLockManager lockManager;
    private final DeadPropertyStore deadPropertyStore;

    /**
     * The unbound {@link Realm} RFC 3744 privileges are checked
     * against, or null if ACL support isn't configured.
     * See {@link #aclEnabled}.
     */
    private final Realm serverRealm;
    /** True when both WebDAV and RFC 3744 ACL support are active for this handler. */
    private final boolean aclEnabled;
    /** {@link #serverRealm} bound to this stream's {@link SelectorLoop} (see {@code Realm#forSelectorLoop}), lazily set in {@link #headers}. */
    private Realm realm;
    /**
     * The authenticated principal for this request, or null if
     * unauthenticated -- read once in {@link #headers} from
     * {@link HttpResponseState#getPrincipal()}, which is populated by
     * whatever HTTP authentication (Basic, Digest, Bearer, mTLS) is
     * configured on the listener this handler is deployed behind, not
     * by this class.
     */
    private Principal principal;

    // Request state
    private String method;
    private String requestPath;
    private Path path;
    /** Set during offloaded PROPPATCH/LOCK prep when known; used for hrefs. */
    private boolean pathIsDirectory;
    private long requestContentLength = -1;
    private int depth = DavConstants.DEPTH_INFINITY;
    private String destination;
    private boolean overwrite = true;
    private String lockToken;
    private String ifHeader;
    
    // PUT/WebDAV request body state
    private AsyncFile asyncWriteChannel;
    private long writePosition = 0;
    private long bytesReceived = 0;
    private boolean fileExistedBeforePut = false;
    private boolean requestBodyExpected = false;
    private boolean putFinalized = false;
    private boolean allRequestBodyReceived = false;
    
    // GET response body state
    private AsyncFile asyncReadChannel;
    private long readPosition = 0;
    
    // WebDAV request body accumulation
    private ByteBuffer requestBodyBuffer;
    private WebDAVRequestParser webdavParser;
    private Headers requestHeaders;

    /**
     * Maximum size, in bytes, of a WebDAV request body (PROPFIND/PROPPATCH/
     * LOCK). These are small control documents; a cap bounds the work done by
     * the XML parser and mitigates entity-expansion ("billion laughs") and
     * oversized-body denial-of-service attempts.
     */
    private static final long MAX_WEBDAV_REQUEST_BODY = 1L << 20; // 1 MiB
    private long webdavBytesReceived = 0;
    private boolean webdavBodyTooLarge = false;

    /**
     * What to do if this request turns out to have no body at all --
     * e.g. RFC 4918 §9.1's "empty PROPFIND body means allprop", or
     * §9.10's "LOCK with no body means a default exclusive write lock".
     * Set by a handler method that has eagerly started expecting a body
     * (see the {@link #webdavParser} field), so it doesn't have to
     * decide up front whether one is actually coming -- it can't: the
     * request-handler event sequence only calls
     * {@link #startRequestBody}/{@link #endRequestBody} at all if the
     * request has a body, and {@link #headers} (where these methods
     * run) fires before that's known. In particular, {@code
     * Content-Length} is not a reliable signal here -- it's absent for
     * chunked transfer-coding (RFC 9112 §7.1) and never sent at all
     * under HTTP/2/3 unless the client chooses to (RFC 9113/9114 place
     * no such requirement on it). Cleared by {@link #startRequestBody}
     * as soon as a real body is confirmed to be arriving; run from
     * {@link #requestComplete} if still set once the stream closes --
     * the only point "no body ever arrived" can be known for certain.
     */
    private Runnable pendingNoBodyAction;

    FileHandler(Path rootPath, boolean allowWrite, boolean webdavEnabled,
                String allowedOptions, String[] welcomeFiles,
                Map<String, String> contentTypes, WebDAVLockManager lockManager,
                DeadPropertyStore deadPropertyStore, Realm realm) {
        this.rootPath = rootPath;
        Path canonical;
        try {
            canonical = rootPath.toRealPath();
        } catch (IOException e) {
            canonical = rootPath.toAbsolutePath().normalize();
        }
        this.canonicalRoot = canonical;
        this.allowWrite = allowWrite;
        this.webdavEnabled = webdavEnabled;
        this.allowedOptions = allowedOptions;
        this.welcomeFiles = welcomeFiles;
        this.contentTypes = contentTypes;
        this.lockManager = lockManager;
        this.deadPropertyStore = deadPropertyStore;
        this.serverRealm = realm;
        this.aclEnabled = webdavEnabled && realm != null;
    }

    @Override
    public void headers(HttpResponseState state, Headers headers) {
        SelectorLoop loop = state.getSelectorLoop();
        if (deadPropertyStore != null) {
            deadPropertyStore.setGumdrop((loop != null) ? loop.getGumdrop() : null);
        }
        if (aclEnabled) {
            // RFC 3744: privileges are checked against whoever the
            // listener's own HTTP authentication (Basic/Digest/Bearer/
            // mTLS -- configured independently of WebDAV, see
            // WebDAVRequestHandler.Builder#realm) already authenticated
            // this request as; this class performs no authentication of
            // its own.
            principal = state.getPrincipal();
            realm = (loop != null) ? serverRealm.forSelectorLoop(loop) : serverRealm;
        }
        // Extract request info from headers
        this.requestHeaders = headers;
        method = headers.getMethod();
        requestPath = headers.getPath();
        
        if ("*".equals(requestPath)) {
            path = null;
        } else if (requestPath != null) {
            // Lexical resolve only — no Files.* on the SelectorLoop.
            // Disk containment / toRealPath run inside each method's offload.
            path = resolvePathLexical(requestPath);
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
            String depthHeader = headers.getValue(DavConstants.HEADER_DEPTH);
            if (depthHeader != null) {
                if ("0".equals(depthHeader)) {
                    depth = DavConstants.DEPTH_0;
                } else if ("1".equals(depthHeader)) {
                    depth = DavConstants.DEPTH_1;
                } else {
                    depth = DavConstants.DEPTH_INFINITY;
                }
            }
            destination = headers.getValue(DavConstants.HEADER_DESTINATION);
            String overwriteHeader = headers.getValue(DavConstants.HEADER_OVERWRITE);
            overwrite = overwriteHeader == null || !"F".equalsIgnoreCase(overwriteHeader);
            lockToken = headers.getValue(DavConstants.HEADER_LOCK_TOKEN);
            ifHeader = headers.getValue(DavConstants.HEADER_IF);
        }
        
        // Process the request
        try {
            processRequest(state);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_file_request"), e);
            sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
        // Handle WebDAV request body (PROPFIND, PROPPATCH, LOCK)
        if (webdavParser != null) {
            webdavBytesReceived += data.remaining();
            if (webdavBytesReceived > MAX_WEBDAV_REQUEST_BODY) {
                // Stop feeding the parser; the request is rejected with 413 in
                // endRequestBody. This bounds parser work regardless of the
                // declared Content-Length or transfer encoding.
                if (!webdavBodyTooLarge) {
                    webdavBodyTooLarge = true;
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.request_body_too_large"), MAX_WEBDAV_REQUEST_BODY));
                }
                return;
            }
            try {
                webdavParser.receive(data.duplicate());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.error_parsing_webdav_request_body"), e);
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
                final CompletionHandler<Integer, ByteBuffer> handler = this;
                state.execute(new Runnable() {
                    @Override
                    public void run() {
                        attachment.position(attachment.position() + bytesWritten);
                        writePosition += bytesWritten;
                        if (attachment.hasRemaining()) {
                            // Partial write - retry with remaining data
                            asyncWriteChannel.write(attachment, writePosition,
                                    attachment, handler);
                        } else {
                            ByteBufferPool.release(attachment);
                            state.resumeRequestBody();
                            if (allRequestBodyReceived
                                    && writePosition >= bytesReceived) {
                                finalizePutRequest(state);
                            }
                        }
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_writing_request_body_to_file"), exc);
                closeWriteChannel();
                ByteBufferPool.release(attachment);
                state.execute(new Runnable() {
                    @Override
                    public void run() {
                        sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                });
            }
        });
    }

    @Override
    public void endRequestBody(HttpResponseState state) {
        // Finalize WebDAV request
        if (webdavParser != null) {
            if (webdavBodyTooLarge) {
                sendError(state, HttpStatus.PAYLOAD_TOO_LARGE);
                return;
            }
            try {
                webdavParser.close();
                finalizeWebDAVRequest(state);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.error_finalizing_webdav_request"), e);
                sendError(state, HttpStatus.BAD_REQUEST);
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
    public void startRequestBody(HttpResponseState state) {
        // A real body is confirmed to be arriving -- see the
        // pendingNoBodyAction field comment.
        pendingNoBodyAction = null;
    }

    @Override
    public void requestComplete(HttpResponseState state) {
        // No startRequestBody ever fired for this request -- it
        // genuinely has no body (see the pendingNoBodyAction field
        // comment for why this, not Content-Length, is what's checked).
        if (pendingNoBodyAction != null) {
            Runnable action = pendingNoBodyAction;
            pendingNoBodyAction = null;
            action.run();
        }
        // Clean up resources
        closeWriteChannel();
        closeReadChannel();
        webdavParser = null;
    }

    private void processRequest(HttpResponseState state) throws IOException {
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
        } else if (aclEnabled && "ACL".equals(method)) {
            handleAcl(state);
        } else {
            sendError(state, HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    /**
     * Returns the shared {@link StorageExecutor}, or null when no server is
     * running (a unit-test harness, say).
     */
    private static StorageExecutor storageExecutor(HttpResponseState state) {
        SelectorLoop loop = state.getSelectorLoop();
        Gumdrop gumdrop = (loop != null) ? loop.getGumdrop() : null;
        return (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
    }

    /**
     * Runs a blocking filesystem operation on the shared {@link StorageExecutor}
     * and delivers the outcome back on this request's SelectorLoop thread (via
     * {@link HttpResponseState#execute}), so the callback continuation may
     * safely touch the {@link HttpResponseState}.
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
     * @param callback invoked on the loop with the operation's result or error
     */
    private <T> void offload(final HttpResponseState state,
            final Callable<T> op, final StorageExecutor.Callback<T> callback) {
        StorageExecutor exec = storageExecutor(state);
        if (exec == null) {
            T result;
            try {
                result = op.call();
            } catch (Throwable t) {
                callback.failed(t);
                return;
            }
            callback.completed(result);
            return;
        }
        exec.submit(new Executor() {
            @Override
            public void execute(Runnable command) {
                state.execute(command);
            }
        }, op, callback);
    }

    /**
     * Plan for a GET/HEAD response, computed off the loop by
     * {@link #computeGetPlan()} so the emitting stage never blocks.
     */
    private static final class GetPlan {
        HttpStatus error;       // if set: send this error status
        boolean notModified;    // 304 Not Modified
        long lastModified;      // for 200/304 Last-Modified header
        String entityTag;         // for 200/304 ETag header
        byte[] listingHtml;     // directory-listing body, if a directory
        Path file;              // file to serve (200 with body)
        long size;              // file size
        String contentType;     // file content type
        AsyncFile channel; // opened off-loop for GET body
    }

    /** RFC 9110 section 13 (conditional GET/HEAD), RFC 9111 validators. */
    private void handleGetOrHead(HttpResponseState state) {
        final StorageExecutor storage = storageExecutor(state);
        offload(state, new Callable<GetPlan>() {
            @Override
            public GetPlan call() throws IOException {
                return computeGetPlan(storage);
            }
        }, new StorageExecutor.Callback<GetPlan>() {
            @Override
            public void completed(GetPlan plan) {
                emitGetPlan(state, plan);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_get_head"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Gathers all metadata for a GET/HEAD off the loop (blocking). */
    private GetPlan computeGetPlan(StorageExecutor storage) throws IOException {
        GetPlan plan = new GetPlan();
        if (path == null || !bindCanonicalPath() || !Files.exists(path)
                || DeadPropertyStore.isSidecarFile(path)) {
            plan.error = HttpStatus.NOT_FOUND;
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
            plan.error = HttpStatus.FORBIDDEN;
            return plan;
        }

        plan.file = target;
        BasicFileAttributes attrs =
                Files.readAttributes(target, BasicFileAttributes.class);
        plan.size = attrs.size();
        plan.lastModified = attrs.lastModifiedTime().toMillis();
        plan.contentType = getContentType(target);
        plan.entityTag = "\"" + generateETag(target, attrs) + "\"";
        String ifNoneMatch = requestHeaders != null
                ? requestHeaders.getValue("if-none-match") : null;
        String ifModifiedSince = requestHeaders != null
                ? requestHeaders.getValue("if-modified-since") : null;
        if (HttpConditionalRequests.shouldReturnNotModified(
                ifNoneMatch, ifModifiedSince, plan.lastModified,
                plan.entityTag)) {
            plan.notModified = true;
            return plan;
        }
        // Opening is blocking — must stay inside this offloaded plan.
        if ("GET".equals(method) && plan.size > 0) {
            plan.channel = AsyncFile.open(storage, target,
                    StandardOpenOption.READ);
        }
        return plan;
    }

    /** Emits a {@link GetPlan} on the loop thread. */
    private void emitGetPlan(HttpResponseState state, GetPlan plan) {
        if (plan.error != null) {
            sendError(state, plan.error);
            return;
        }

        if (plan.listingHtml != null) {
            Headers response = new Headers();
            response.status(HttpStatus.OK);
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
            response.status(HttpStatus.NOT_MODIFIED);
            response.add("Last-Modified", dateFormat.format(plan.lastModified));
            if (plan.entityTag != null) {
                response.add("ETag", plan.entityTag);
            }
            state.headers(response);
            state.complete();
            return;
        }

        Headers response = new Headers();
        response.status(HttpStatus.OK);
        response.add("Last-Modified", dateFormat.format(plan.lastModified));
        if (plan.entityTag != null) {
            response.add("ETag", plan.entityTag);
        }
        response.add("Content-Type", plan.contentType);
        response.add("Content-Length", Long.toString(plan.size));
        state.headers(response);

        if ("GET".equals(method)) {
            if (plan.size > 0 && plan.channel != null) {
                asyncReadChannel = plan.channel;
                readPosition = 0;
                state.startResponseBody();
                readNextChunk(state);
                // endResponseBody()/complete() invoked from readNextChunk
            } else {
                if (plan.channel != null) {
                    try {
                        plan.channel.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
                state.complete();
            }
        } else {
            if (plan.channel != null) {
                try {
                    plan.channel.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            state.complete();
        }
    }

    private void readNextChunk(HttpResponseState state) {
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
                    state.execute(new Runnable() {
                        @Override
                        public void run() {
                            state.endResponseBody();
                            state.complete();
                        }
                    });
                    return;
                }
                readPosition += bytesRead;
                state.execute(new Runnable() {
                    @Override
                    public void run() {
                        attachment.flip();
                        if (attachment.hasRemaining()) {
                            state.responseBodyContent(attachment);
                        }
                        ByteBufferPool.release(attachment);
                        state.onWritable(new Runnable() {
                            @Override
                            public void run() {
                                readNextChunk(state);
                            }
                        });
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_reading_file"), exc);
                closeReadChannel();
                ByteBufferPool.release(attachment);
                state.execute(new Runnable() {
                    @Override
                    public void run() {
                        sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                });
            }
        });
    }

    private void closeReadChannel() {
        if (asyncReadChannel != null) {
            try {
                asyncReadChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.error_closing_read_channel"), e);
            }
            asyncReadChannel = null;
        }
    }

    /** RFC 9110 §9.3.7 (OPTIONS); RFC 4918 §18 (DAV header, compliance classes 1,2). */
    private void handleOptions(HttpResponseState state) {
        Headers response = new Headers();
        response.status(HttpStatus.OK);
        response.add("Allow", allowedOptions);
        if (webdavEnabled) {
            // RFC 3744 §2: servers supporting the ACL extension MUST
            // include "access-control" as a field in this header.
            response.add(DavConstants.HEADER_DAV, aclEnabled ? "1,2,access-control" : "1,2");
        }
        state.headers(response);
        state.complete();
    }

    /**
     * RFC 3744 §8.1 (ACL method). This server derives RFC 3744
     * privileges from {@link Realm#isUserInRole} rather than storing a
     * separate, independently-modifiable ACL for each resource, so an
     * actual ACE grant/deny mutation has nowhere meaningful to be
     * written to. RFC 3744 explicitly anticipates this: a server MAY
     * refuse any ACL modification it can't support (§8.1, "a server
     * MAY reject the ACL request"), so this always responds 403
     * Forbidden. {@code DAV:acl}/{@code DAV:current-user-privilege-set}
     * remain fully readable via PROPFIND.
     */
    private void handleAcl(HttpResponseState state) {
        sendError(state, HttpStatus.FORBIDDEN);
    }

    /**
     * RFC 9110 §9.3.5 (DELETE); RFC 4918 §9.6.1 (collection DELETE).
     *
     * <p>For files, deletes the resource and returns 204 No Content.
     * For collections (directories), recursively deletes all member resources
     * depth-first. If any individual deletion fails, a 207 Multi-Status
     * response is returned listing the failed resources.
     */
    private void handleDelete(HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.METHOD_NOT_ALLOWED);
            return;
        }

        offload(state, new Callable<DeletePlan>() {
            @Override
            public DeletePlan call() throws IOException {
                return computeDeletePlan();
            }
        }, new StorageExecutor.Callback<DeletePlan>() {
            @Override
            public void completed(DeletePlan plan) {
                emitDeletePlan(state, plan);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_delete"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /**
     * Outcome of a DELETE, computed off the loop: either a single status or,
     * for a partially failed collection DELETE, a 207 Multi-Status error list.
     */
    private static final class DeletePlan {
        HttpStatus status;
        List<String[]> multiStatus;
    }

    /** Performs the blocking DELETE work off the loop. */
    private DeletePlan computeDeletePlan() throws IOException {
        DeletePlan plan = new DeletePlan();
        if (path == null || !bindCanonicalPath()) {
            plan.status = HttpStatus.BAD_REQUEST;
            return plan;
        }
        if (!Files.exists(path)) {
            plan.status = HttpStatus.NOT_FOUND;
            return plan;
        }
        if (!checkLockToken(path)) {
            plan.status = HttpStatus.LOCKED;
            return plan;
        }

        if (Files.isDirectory(path)) {
            List<String[]> errors = collectionDelete();
            if (errors.isEmpty()) {
                plan.status = HttpStatus.NO_CONTENT;
                LOGGER.info(MessageFormat.format(L10N.getString("info.deleted_collection"), path));
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
            plan.status = HttpStatus.NO_CONTENT;
            LOGGER.info(MessageFormat.format(L10N.getString("info.deleted_file"), path));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.failed_delete_file"), path), e);
            plan.status = HttpStatus.FORBIDDEN;
        }
        return plan;
    }

    /** Emits a {@link DeletePlan} on the loop thread. */
    private void emitDeletePlan(HttpResponseState state, DeletePlan plan) {
        if (plan.multiStatus != null) {
            try {
                sendDeleteMultiStatus(state, plan.multiStatus);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.delete_multi_status_error"), e);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return;
        }
        if (plan.status == HttpStatus.NO_CONTENT) {
            Headers response = new Headers();
            response.status(HttpStatus.NO_CONTENT);
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
                    errors.add(new String[]{getHref(file, false), "HTTP/1.1 403 Forbidden"});
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                errors.add(new String[]{getHref(file, false), "HTTP/1.1 403 Forbidden"});
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (!dir.equals(path)) {
                    try {
                        Files.delete(dir);
                    } catch (IOException e) {
                        errors.add(new String[]{getHref(dir, true), "HTTP/1.1 403 Forbidden"});
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
                errors.add(new String[]{getHref(path, true), "HTTP/1.1 403 Forbidden"});
            }
        }

        return errors;
    }

    /**
     * Sends a 207 Multi-Status response for a partially failed collection DELETE.
     * RFC 4918 §9.6.1 — only resources that failed are listed.
     */
    private void sendDeleteMultiStatus(HttpResponseState state, List<String[]> errors)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);

        davStart(xml, DavConstants.ELEM_MULTISTATUS);
        xml.writeNamespace(DavConstants.PREFIX, DavConstants.NAMESPACE);

        for (String[] error : errors) {
            davStart(xml, DavConstants.ELEM_RESPONSE);

            davStart(xml, DavConstants.ELEM_HREF);
            davText(xml, error[0]);
            davEnd(xml, DavConstants.ELEM_HREF);

            davStart(xml, DavConstants.ELEM_STATUS);
            davText(xml, error[1]);
            davEnd(xml, DavConstants.ELEM_STATUS);

            davEnd(xml, DavConstants.ELEM_RESPONSE);
        }

        davEnd(xml, DavConstants.ELEM_MULTISTATUS);
        xml.close();

        byte[] body = baos.toByteArray();
        Headers response = new Headers();
        response.status(HttpStatus.MULTI_STATUS);
        response.add("Content-Type", DavConstants.CONTENT_TYPE_XML);
        response.add("Content-Length", String.valueOf(body.length));
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(body));
        state.endResponseBody();
        state.complete();
    }

    /**
     * Outcome of PUT open/setup, computed off the loop so exists /
     * createDirectories / {@code AFC.open} never run on the SelectorLoop.
     */
    private static final class PutPlan {
        HttpStatus error;
        boolean existed;
        AsyncFile channel;
    }

    /** RFC 9110 §9.3.4 (PUT) — 201 Created / 204 No Content. */
    private void handlePut(HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.METHOD_NOT_ALLOWED);
            return;
        }

        if (path == null) {
            sendError(state, HttpStatus.BAD_REQUEST);
            return;
        }

        // Pause body until the write channel is open (AFC.open is offloaded).
        state.pauseRequestBody();
        final StorageExecutor storage = storageExecutor(state);
        offload(state, new Callable<PutPlan>() {
            @Override
            public PutPlan call() throws IOException {
                return computePutPlan(storage);
            }
        }, new StorageExecutor.Callback<PutPlan>() {
            @Override
            public void completed(PutPlan plan) {
                emitPutPlan(state, plan);
            }

            @Override
            public void failed(Throwable error) {
                state.resumeRequestBody();
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_put"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs exists / createDirectories / AFC.open off the loop. */
    private PutPlan computePutPlan(StorageExecutor storage) throws IOException {
        PutPlan plan = new PutPlan();
        if (path == null || !bindCanonicalPath()) {
            plan.error = HttpStatus.BAD_REQUEST;
            return plan;
        }
        if (Files.exists(path) && Files.isDirectory(path)) {
            plan.error = HttpStatus.CONFLICT;
            return plan;
        }
        plan.existed = Files.exists(path);

        Path parentDir = path.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("warn.put_create_parent_dirs_failed"), path), e);
                plan.error = HttpStatus.CONFLICT;
                return plan;
            }
        }

        try {
            plan.channel = AsyncFile.open(storage, path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.put_open_file_failed"), path), e);
            plan.error = HttpStatus.FORBIDDEN;
        }
        return plan;
    }

    /** Applies a {@link PutPlan} on the loop and starts accepting the body. */
    private void emitPutPlan(HttpResponseState state, PutPlan plan) {
        if (plan.error != null) {
            state.resumeRequestBody();
            sendError(state, plan.error);
            return;
        }

        fileExistedBeforePut = plan.existed;
        asyncWriteChannel = plan.channel;
        writePosition = 0;

        if (requestContentLength == 0) {
            state.resumeRequestBody();
            finalizePutRequest(state);
        } else {
            requestBodyExpected = true;
            state.resumeRequestBody();
        }
    }

    private void closeWriteChannel() {
        if (asyncWriteChannel != null) {
            try {
                asyncWriteChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.error_closing_write_channel"), e);
            }
            asyncWriteChannel = null;
        }
    }

    private void finalizePutRequest(HttpResponseState state) {
        if (putFinalized) {
            return;
        }
        putFinalized = true;

        closeWriteChannel();
        requestBodyExpected = false;

        HttpStatus status = fileExistedBeforePut ? HttpStatus.NO_CONTENT : HttpStatus.CREATED;

        Headers response = new Headers();
        response.status(status);
        response.add("Content-Length", "0");
        state.headers(response);
        state.complete();

        LOGGER.info(MessageFormat.format(L10N.getString("info.put_completed"), path));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Methods (RFC 4918)
    // ─────────────────────────────────────────────────────────────────────────

    /** RFC 4918 §9.1 — PROPFIND (allprop, propname, or named properties). */
    private void handlePropfind(final HttpResponseState state) {
        if (path == null) {
            sendError(state, HttpStatus.BAD_REQUEST);
            return;
        }

        // Existence is checked inside the offloaded PropfindData gather;
        // do not Files.exists on the loop here.
        //
        // Always set up to consume a body -- whether one is actually
        // coming can't be decided here (see the pendingNoBodyAction
        // field comment). If none ever arrives, RFC 4918 §9.1 says an
        // empty body means allprop.
        webdavParser = new WebDAVRequestParser();
        requestBodyExpected = true;
        pendingNoBodyAction = new Runnable() {
            @Override
            public void run() {
                webdavParser = null;
                sendPropfindResponse(state, WebDAVRequestParser.PropfindType.ALLPROP, null, null);
            }
        };
        // If a body does arrive, the response is sent from finalizeWebDAVRequest.
    }

    /**
     * RFC 4918 section 9.2 -- PROPPATCH (set/remove properties).
     * Dead properties are persisted via {@link DeadPropertyStore}.
     */
    private void handleProppatch(final HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.FORBIDDEN);
            return;
        }

        if (path == null) {
            sendError(state, HttpStatus.NOT_FOUND);
            return;
        }

        // RFC 4918 §9.2 requires a body; if none ever arrives that's
        // genuinely a bad request -- but (see the pendingNoBodyAction
        // field comment) that can only be known once the stream closes
        // with no body ever having started, not from Content-Length.
        pendingNoBodyAction = new Runnable() {
            @Override
            public void run() {
                sendError(state, HttpStatus.BAD_REQUEST);
            }
        };

        // Exists + lock ETag checks run off the loop before accepting the body.
        state.pauseRequestBody();
        offload(state, new Callable<ProppatchPrep>() {
            @Override
            public ProppatchPrep call() {
                return computeProppatchPrep();
            }
        }, new StorageExecutor.Callback<ProppatchPrep>() {
            @Override
            public void completed(ProppatchPrep prep) {
                if (prep.error != null) {
                    state.resumeRequestBody();
                    sendError(state, prep.error);
                    return;
                }
                pathIsDirectory = prep.isDirectory;
                webdavParser = new WebDAVRequestParser();
                requestBodyExpected = true;
                state.resumeRequestBody();
            }

            @Override
            public void failed(Throwable error) {
                state.resumeRequestBody();
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_preparing_proppatch"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /**
     * Exists / lock prep for PROPPATCH, computed off the loop.
     */
    private static final class ProppatchPrep {
        HttpStatus error;
        boolean isDirectory;
    }

    private ProppatchPrep computeProppatchPrep() {
        ProppatchPrep prep = new ProppatchPrep();
        if (path == null || !bindCanonicalPath()) {
            prep.error = HttpStatus.NOT_FOUND;
            return prep;
        }
        if (!Files.exists(path)) {
            prep.error = HttpStatus.NOT_FOUND;
            return prep;
        }
        if (!checkLockToken(path)) {
            prep.error = HttpStatus.LOCKED;
            return prep;
        }
        prep.isDirectory = Files.isDirectory(path);
        return prep;
    }

    /**
     * Emits the response for a body-less write method (MKCOL/COPY/MOVE): a
     * bare success status (201/204/200) or, for any other status, an error.
     */
    private void emitWriteResult(HttpResponseState state, HttpStatus status) {
        if (status == HttpStatus.CREATED || status == HttpStatus.NO_CONTENT
                || status == HttpStatus.OK) {
            Headers response = new Headers();
            response.status(status);
            state.headers(response);
            state.complete();
        } else {
            sendError(state, status);
        }
    }

    /** RFC 4918 §9.3 — MKCOL (create collection). */
    private void handleMkcol(HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.FORBIDDEN);
            return;
        }

        offload(state, new Callable<HttpStatus>() {
            @Override
            public HttpStatus call() {
                return computeMkcol();
            }
        }, new StorageExecutor.Callback<HttpStatus>() {
            @Override
            public void completed(HttpStatus status) {
                emitWriteResult(state, status);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_mkcol"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs the blocking MKCOL work off the loop. */
    private HttpStatus computeMkcol() {
        if (path == null || !bindCanonicalPath()) {
            return HttpStatus.BAD_REQUEST;
        }
        if (Files.exists(path)) {
            return HttpStatus.METHOD_NOT_ALLOWED;
        }
        // Parent must exist
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            return HttpStatus.CONFLICT;
        }
        // MKCOL must not have a body
        if (requestContentLength > 0) {
            return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        }
        try {
            Files.createDirectory(path);
            LOGGER.info(MessageFormat.format(L10N.getString("info.created_collection"), path));
            return HttpStatus.CREATED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.failed_create_collection"), path), e);
            return HttpStatus.FORBIDDEN;
        }
    }

    /** RFC 4918 §9.8 — COPY with Destination, Overwrite, Depth. */
    private void handleCopy(HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.FORBIDDEN);
            return;
        }

        offload(state, new Callable<HttpStatus>() {
            @Override
            public HttpStatus call() throws IOException {
                return computeCopy();
            }
        }, new StorageExecutor.Callback<HttpStatus>() {
            @Override
            public void completed(HttpStatus status) {
                emitWriteResult(state, status);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_copy"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs the blocking COPY work (incl. recursive copy) off the loop. */
    private HttpStatus computeCopy() throws IOException {
        if (path == null || !bindCanonicalPath() || !Files.exists(path)) {
            return HttpStatus.NOT_FOUND;
        }
        if (destination == null) {
            return HttpStatus.BAD_REQUEST;
        }
        Path destPath = resolveDestination(destination);
        if (destPath == null) {
            return HttpStatus.BAD_REQUEST;
        }
        // Check destination lock
        if (!checkLockToken(destPath)) {
            return HttpStatus.LOCKED;
        }
        boolean destExists = Files.exists(destPath);
        if (destExists && !overwrite) {
            return HttpStatus.PRECONDITION_FAILED;
        }

        try {
            if (Files.isDirectory(path)) {
                Path sourceReal = path.toRealPath();
                Path destReal = realPathAllowingMissing(destPath);
                if (depth != 0 && destReal.startsWith(sourceReal)) {
                    // RFC 4918 section 9.8.5: a collection cannot be
                    // copied into itself.
                    return HttpStatus.FORBIDDEN;
                }
                copyDirectory(path, destPath, depth,
                        new CopyGuard(sourceReal, destReal));
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
            LOGGER.info(MessageFormat.format(L10N.getString("info.copied"), path, destPath));
            return destExists ? HttpStatus.NO_CONTENT : HttpStatus.CREATED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.failed_copy"), path), e);
            return HttpStatus.FORBIDDEN;
        }
    }

    /** RFC 4918 §9.9 — MOVE with Destination, Overwrite, lock checks. */
    private void handleMove(HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.FORBIDDEN);
            return;
        }

        offload(state, new Callable<HttpStatus>() {
            @Override
            public HttpStatus call() throws IOException {
                return computeMove();
            }
        }, new StorageExecutor.Callback<HttpStatus>() {
            @Override
            public void completed(HttpStatus status) {
                emitWriteResult(state, status);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_move"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /** Performs the blocking MOVE work (incl. recursive delete) off the loop. */
    private HttpStatus computeMove() throws IOException {
        if (path == null || !bindCanonicalPath() || !Files.exists(path)) {
            return HttpStatus.NOT_FOUND;
        }
        if (destination == null) {
            return HttpStatus.BAD_REQUEST;
        }
        // Check source lock
        if (!checkLockToken(path)) {
            return HttpStatus.LOCKED;
        }
        Path destPath = resolveDestination(destination);
        if (destPath == null) {
            return HttpStatus.BAD_REQUEST;
        }
        // Check destination lock
        if (!checkLockToken(destPath)) {
            return HttpStatus.LOCKED;
        }
        boolean destExists = Files.exists(destPath);
        if (destExists && !overwrite) {
            return HttpStatus.PRECONDITION_FAILED;
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
            boolean srcIsDir = Files.isDirectory(path);
            if (deadPropertyStore != null && !srcIsDir) {
                srcSidecar = DeadPropertyStore.sidecarPath(path, false);
                if (!Files.exists(srcSidecar)) {
                    srcSidecar = null;
                }
            }

            Files.move(path, destPath);

            if (srcSidecar != null) {
                Path dstSidecar =
                        DeadPropertyStore.sidecarPath(destPath, false);
                try {
                    Files.move(srcSidecar, dstSidecar,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, L10N.getString("fine.sidecar_move_failed"), e);
                }
            }

            LOGGER.info(MessageFormat.format(L10N.getString("info.moved"), path, destPath));
            return destExists ? HttpStatus.NO_CONTENT : HttpStatus.CREATED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.failed_move"), path), e);
            return HttpStatus.FORBIDDEN;
        }
    }

    /** RFC 4918 §9.10 — LOCK (new lock or refresh). */
    private void handleLock(final HttpResponseState state) throws IOException {
        if (!allowWrite) {
            sendError(state, HttpStatus.FORBIDDEN);
            return;
        }

        if (path == null) {
            sendError(state, HttpStatus.BAD_REQUEST);
            return;
        }

        if (lockToken != null) {
            // RFC 4918 §9.10.2 -- refresh: doesn't need a body, and
            // works the same whether or not the client happens to send
            // one, so (unlike the new-lock case below) there's no need
            // to wait and see whether one arrives.
            refreshLock(state);
            return;
        }

        // New lock request. RFC 4918 §9.10: an empty body means a
        // default exclusive write lock -- but whether a body is
        // actually coming can only be known once the stream closes
        // with none having arrived (see the pendingNoBodyAction field
        // comment for why Content-Length can't be trusted here).
        webdavParser = new WebDAVRequestParser();
        requestBodyExpected = true;
        pendingNoBodyAction = new Runnable() {
            @Override
            public void run() {
                webdavParser = null;
                createLock(state, WebDAVLock.Scope.EXCLUSIVE, WebDAVLock.Type.WRITE, null);
            }
        };
    }

    /** RFC 4918 §9.10.2 -- refreshes an existing lock named by the Lock-Token header. */
    private void refreshLock(HttpResponseState state) throws IOException {
        String token = extractLockToken(lockToken);
        if (token != null) {
            long timeout = parseTimeout(requestHeaders.getValue(DavConstants.HEADER_TIMEOUT));
            WebDAVLock refreshed = lockManager.refresh(token, timeout);
            if (refreshed != null) {
                boolean isDir = requestPath != null && requestPath.endsWith("/");
                sendLockResponse(state, refreshed, false, isDir);
                return;
            }
        }
        sendError(state, HttpStatus.PRECONDITION_FAILED);
    }

    /** RFC 4918 §9.11 — UNLOCK by Lock-Token header. */
    private void handleUnlock(HttpResponseState state) {
        if (!allowWrite) {
            sendError(state, HttpStatus.FORBIDDEN);
            return;
        }
        
        if (path == null || lockToken == null) {
            sendError(state, HttpStatus.BAD_REQUEST);
            return;
        }
        
        String token = extractLockToken(lockToken);
        if (token == null) {
            sendError(state, HttpStatus.BAD_REQUEST);
            return;
        }
        
        if (lockManager.unlock(token)) {
            Headers response = new Headers();
            response.status(HttpStatus.NO_CONTENT);
            state.headers(response);
            state.complete();
            LOGGER.info(MessageFormat.format(L10N.getString("info.unlocked"), path));
        } else {
            sendError(state, HttpStatus.CONFLICT);
        }
    }

    private void finalizeWebDAVRequest(HttpResponseState state) throws IOException {
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
        
        sendError(state, HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Response Generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RFC 4918 section 9.1 -- 207 Multi-Status PROPFIND response.
     * Pre-loads dead properties for all resources (async), then
     * builds the XML response synchronously.
     */
    private void sendPropfindResponse(final HttpResponseState state,
            final WebDAVRequestParser.PropfindType type,
            final List<WebDAVRequestParser.PropertyRef> requestedProps,
            final List<WebDAVRequestParser.PropertyRef> include) {

        offload(state, new Callable<PropfindData>() {
            @Override
            public PropfindData call() throws IOException {
                return gatherPropfindData();
            }
        }, new StorageExecutor.Callback<PropfindData>() {
            @Override
            public void completed(PropfindData data) {
                if (data.error != null) {
                    sendError(state, data.error);
                    return;
                }
                if (deadPropertyStore != null
                        && deadPropertyStore.getMode()
                                != DeadPropertyStore.Mode.NONE) {
                    loadDeadPropertiesParallel(data.resources,
                            new HashMap<Path, Map<String, DeadProperty>>(),
                            state, type, requestedProps, data.attrs);
                } else {
                    buildPropfindResponse(state, data.resources, type,
                            requestedProps,
                            new HashMap<Path, Map<String, DeadProperty>>(),
                            data.attrs);
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.propfind_enumeration_error"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    /**
     * Enumerated PROPFIND resources plus their pre-fetched attributes, gathered
     * off the loop so the XML response can be built without per-resource stats.
     */
    private static final class PropfindData {
        HttpStatus error;
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
        PropfindData data = new PropfindData();
        if (path == null || !bindCanonicalPath() || !Files.exists(path)) {
            data.error = HttpStatus.NOT_FOUND;
            return data;
        }
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
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("fine.propfind_skip_unreadable"), resource), e);
            }
        }
        data.resources = usable;
        data.attrs = attrs;
        return data;
    }

    /**
     * Loads dead properties for all PROPFIND resources in parallel via
     * {@link DeadPropertyStore}, then builds the XML response when every
     * submission has completed.
     */
    private void loadDeadPropertiesParallel(
            final List<Path> resources,
            final Map<Path, Map<String, DeadProperty>> allDeadProps,
            final HttpResponseState state,
            final WebDAVRequestParser.PropfindType type,
            final List<WebDAVRequestParser.PropertyRef> requestedProps,
            final Map<Path, BasicFileAttributes> attrsMap) {
        if (resources.isEmpty()) {
            buildPropfindResponse(state, resources, type,
                    requestedProps, allDeadProps, attrsMap);
            return;
        }

        final AtomicInteger remaining = new AtomicInteger(resources.size());

        for (int i = 0; i < resources.size(); i++) {
            final Path resource = resources.get(i);
            BasicFileAttributes attrs = attrsMap.get(resource);
            boolean isDir = attrs != null && attrs.isDirectory();
            deadPropertyStore.getProperties(resource,
                    Boolean.valueOf(isDir),
                    new DeadPropertyCallback() {
                        @Override
                        public void onProperties(
                                Map<String, DeadProperty> props) {
                            if (props != null && !props.isEmpty()) {
                                synchronized (allDeadProps) {
                                    allDeadProps.put(resource, props);
                                }
                            }
                            if (remaining.decrementAndGet() == 0) {
                                buildPropfindResponse(state, resources, type,
                                        requestedProps, allDeadProps, attrsMap);
                            }
                        }

                        @Override
                        public void onError(String error) {
                            if (remaining.decrementAndGet() == 0) {
                                buildPropfindResponse(state, resources, type,
                                        requestedProps, allDeadProps, attrsMap);
                            }
                        }
                    });
        }
    }

    private void buildPropfindResponse(HttpResponseState state,
            List<Path> resources,
            WebDAVRequestParser.PropfindType type,
            List<WebDAVRequestParser.PropertyRef> requestedProps,
            Map<Path, Map<String, DeadProperty>> allDeadProps,
            Map<Path, BasicFileAttributes> attrsMap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            XMLWriter xml = new XMLWriter(baos);

            davStart(xml, DavConstants.ELEM_MULTISTATUS);
            xml.writeNamespace(DavConstants.PREFIX,
                    DavConstants.NAMESPACE);

            for (int i = 0; i < resources.size(); i++) {
                Path resource = resources.get(i);
                Map<String, DeadProperty> deadProps =
                        allDeadProps.get(resource);
                writeResourceResponse(xml, resource, type,
                        requestedProps, deadProps, attrsMap);
            }

            davEnd(xml, DavConstants.ELEM_MULTISTATUS);
            xml.close();

            byte[] body = baos.toByteArray();
            Headers response = new Headers();
            response.status(HttpStatus.MULTI_STATUS);
            response.add("Content-Type",
                    DavConstants.CONTENT_TYPE_XML);
            response.add("Content-Length",
                    String.valueOf(body.length));
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(body));
            state.endResponseBody();
            state.complete();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, L10N.getString("severe.propfind_response_error"), e);
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

        davStart(xml, DavConstants.ELEM_RESPONSE);

        davStart(xml, DavConstants.ELEM_HREF);
        davText(xml, getHref(resource, isDir));
        davEnd(xml, DavConstants.ELEM_HREF);

        davStart(xml, DavConstants.ELEM_PROPSTAT);
        davStart(xml, DavConstants.ELEM_PROP);

        if (type == WebDAVRequestParser.PropfindType.PROPNAME) {
            writePropertyNames(xml, resource, deadProps);
        } else if (type == WebDAVRequestParser.PropfindType.PROP
                && requestedProps != null) {
            writeRequestedProperties(xml, resource, requestedProps,
                    deadProps, attrs);
        } else {
            writeAllProperties(xml, resource, deadProps, attrs);
        }

        davEnd(xml, DavConstants.ELEM_PROP);

        davStart(xml, DavConstants.ELEM_STATUS);
        davText(xml, "HTTP/1.1 200 OK");
        davEnd(xml, DavConstants.ELEM_STATUS);

        davEnd(xml, DavConstants.ELEM_PROPSTAT);
        davEnd(xml, DavConstants.ELEM_RESPONSE);
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

        davStartText(xml, DavConstants.PROP_CREATIONDATE,
                formatISO8601(attrs.creationTime().toMillis()));

        Path fileName = resource.getFileName();
        davStartText(xml, DavConstants.PROP_DISPLAYNAME,
                fileName != null ? fileName.toString() : "");

        if (!isDir) {
            davStartText(xml, DavConstants.PROP_GETCONTENTLENGTH,
                    String.valueOf(attrs.size()));
        }

        davStartText(xml, DavConstants.PROP_GETCONTENTTYPE,
                isDir
                        ? "httpd/unix-directory"
                        : getContentType(resource));

        davStartText(xml, DavConstants.PROP_GETETAG,
                "\"" + generateETag(resource, attrs) + "\"");

        davStartText(xml, DavConstants.PROP_GETLASTMODIFIED,
                dateFormat.format(
                        attrs.lastModifiedTime().toMillis()));

        davStart(xml, DavConstants.PROP_LOCKDISCOVERY);
        writeLockDiscovery(xml, resource, isDir);
        davEnd(xml, DavConstants.PROP_LOCKDISCOVERY);

        davStart(xml, DavConstants.PROP_RESOURCETYPE);
        if (isDir) {
            davEmpty(xml, DavConstants.ELEM_COLLECTION);
        }
        davEnd(xml, DavConstants.PROP_RESOURCETYPE);

        davStart(xml, DavConstants.PROP_SUPPORTEDLOCK);
        writeSupportedLock(xml);
        davEnd(xml, DavConstants.PROP_SUPPORTEDLOCK);

        if (aclEnabled) {
            writeAclProperties(xml);
        }

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

            if (DavConstants.NAMESPACE.equals(ns)) {
                if (DavConstants.PROP_CREATIONDATE.equals(name)) {
                    davStartText(xml, name,
                            formatISO8601(
                                    attrs.creationTime().toMillis()));
                } else if (DavConstants.PROP_DISPLAYNAME
                        .equals(name)) {
                    Path fileName = resource.getFileName();
                    davStartText(xml, name,
                            fileName != null
                                    ? fileName.toString() : "");
                } else if (DavConstants.PROP_GETCONTENTLENGTH
                        .equals(name)) {
                    if (!isDir) {
                        davStartText(xml, name,
                                String.valueOf(attrs.size()));
                    }
                } else if (DavConstants.PROP_GETCONTENTTYPE
                        .equals(name)) {
                    davStartText(xml, name,
                            isDir
                                    ? "httpd/unix-directory"
                                    : getContentType(resource));
                } else if (DavConstants.PROP_GETETAG.equals(name)) {
                    davStartText(xml, name,
                            "\"" + generateETag(resource, attrs)
                                    + "\"");
                } else if (DavConstants.PROP_GETLASTMODIFIED
                        .equals(name)) {
                    davStartText(xml, name,
                            dateFormat.format(
                                    attrs.lastModifiedTime()
                                            .toMillis()));
                } else if (DavConstants.PROP_LOCKDISCOVERY
                        .equals(name)) {
                    davStart(xml, name);
                    writeLockDiscovery(xml, resource, isDir);
                    davEnd(xml, name);
                } else if (DavConstants.PROP_RESOURCETYPE
                        .equals(name)) {
                    davStart(xml, name);
                    if (isDir) {
                        davEmpty(xml,
                                DavConstants.ELEM_COLLECTION);
                    }
                    davEnd(xml, name);
                } else if (DavConstants.PROP_SUPPORTEDLOCK
                        .equals(name)) {
                    davStart(xml, name);
                    writeSupportedLock(xml);
                    davEnd(xml, name);
                } else if (aclEnabled && DavConstants.PROP_OWNER.equals(name)) {
                    davEmpty(xml, name);
                } else if (aclEnabled && DavConstants.PROP_GROUP.equals(name)) {
                    davEmpty(xml, name);
                } else if (aclEnabled && DavConstants.PROP_SUPPORTED_PRIVILEGE_SET.equals(name)) {
                    davStart(xml, name);
                    writeSupportedPrivilegeSet(xml);
                    davEnd(xml, name);
                } else if (aclEnabled && DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET.equals(name)) {
                    davStart(xml, name);
                    writeCurrentUserPrivilegeSet(xml);
                    davEnd(xml, name);
                } else if (aclEnabled && DavConstants.PROP_ACL.equals(name)) {
                    davStart(xml, name);
                    writeAcl(xml);
                    davEnd(xml, name);
                } else if (aclEnabled && DavConstants.PROP_PRINCIPAL_COLLECTION_SET.equals(name)) {
                    davEmpty(xml, name);
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
    private void writeLockDiscovery(XMLWriter xml, Path resource,
            boolean isDir) throws IOException {
        List<WebDAVLock> locks = lockManager.getCoveringLocks(resource);
        for (WebDAVLock lock : locks) {
            davStart(xml, DavConstants.ELEM_ACTIVELOCK);
            
            davStart(xml, DavConstants.ELEM_LOCKTYPE);
            davEmpty(xml, DavConstants.ELEM_WRITE);
            davEnd(xml, DavConstants.ELEM_LOCKTYPE);
            
            davStart(xml, DavConstants.ELEM_LOCKSCOPE);
            davEmpty(xml, lock.getScope() == WebDAVLock.Scope.EXCLUSIVE
                    ? DavConstants.ELEM_EXCLUSIVE : DavConstants.ELEM_SHARED);
            davEnd(xml, DavConstants.ELEM_LOCKSCOPE);
            
            davStartText(xml, DavConstants.ELEM_DEPTH,
                    lock.getDepth() == DavConstants.DEPTH_INFINITY
                            ? "infinity" : String.valueOf(lock.getDepth()));
            
            if (lock.getOwner() != null) {
                davStartText(xml, DavConstants.ELEM_OWNER, lock.getOwner());
            }
            
            long remaining = lock.getRemainingTimeoutSeconds();
            davStartText(xml, DavConstants.ELEM_TIMEOUT,
                    remaining < 0 ? "Infinite" : "Second-" + remaining);
            
            davStart(xml, DavConstants.ELEM_LOCKTOKEN);
            davStartText(xml, DavConstants.ELEM_HREF, lock.getToken());
            davEnd(xml, DavConstants.ELEM_LOCKTOKEN);
            
            // Ancestor covering locks are collections; same-path uses isDir.
            Path lockPath = lock.getPath();
            boolean lockIsDir = lockPath.equals(resource) ? isDir : true;
            davStart(xml, DavConstants.ELEM_LOCKROOT);
            davStartText(xml, DavConstants.ELEM_HREF,
                    getHref(lockPath, lockIsDir));
            davEnd(xml, DavConstants.ELEM_LOCKROOT);
            
            davEnd(xml, DavConstants.ELEM_ACTIVELOCK);
        }
    }

    /** RFC 4918 §15.10 — supportedlock property (exclusive + shared write). */
    private void writeSupportedLock(XMLWriter xml) throws IOException {
        // Exclusive write lock
        davStart(xml, DavConstants.ELEM_LOCKENTRY);
        davStart(xml, DavConstants.ELEM_LOCKSCOPE);
        davEmpty(xml, DavConstants.ELEM_EXCLUSIVE);
        davEnd(xml, DavConstants.ELEM_LOCKSCOPE);
        davStart(xml, DavConstants.ELEM_LOCKTYPE);
        davEmpty(xml, DavConstants.ELEM_WRITE);
        davEnd(xml, DavConstants.ELEM_LOCKTYPE);
        davEnd(xml, DavConstants.ELEM_LOCKENTRY);
        
        // Shared write lock
        davStart(xml, DavConstants.ELEM_LOCKENTRY);
        davStart(xml, DavConstants.ELEM_LOCKSCOPE);
        davEmpty(xml, DavConstants.ELEM_SHARED);
        davEnd(xml, DavConstants.ELEM_LOCKSCOPE);
        davStart(xml, DavConstants.ELEM_LOCKTYPE);
        davEmpty(xml, DavConstants.ELEM_WRITE);
        davEnd(xml, DavConstants.ELEM_LOCKTYPE);
        davEnd(xml, DavConstants.ELEM_LOCKENTRY);
    }

    private static void davStart(XMLWriter xml, String localName)
            throws IOException {
        xml.writeStartElement(DavConstants.PREFIX, localName, DavConstants.NAMESPACE);
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
    private void sendProppatchResponse(final HttpResponseState state,
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
            final HttpResponseState state,
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

        if (DavConstants.NAMESPACE.equals(ns)
                && getLivePropertyNames().contains(name)) {
            results.add(Boolean.FALSE);
            applyProppatchUpdate(state, proppatch, index + 1,
                    results);
            return;
        }

        if (update.operation
                == WebDAVRequestParser.PropPatchOp.REMOVE) {
            deadPropertyStore.removeProperty(path,
                    Boolean.valueOf(pathIsDirectory), ns, name,
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
            deadPropertyStore.setProperty(path,
                    Boolean.valueOf(pathIsDirectory), ns, name,
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

    private void sendProppatchResult(HttpResponseState state,
            WebDAVRequestParser.ProppatchRequest proppatch,
            List<Boolean> results) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            XMLWriter xml = new XMLWriter(baos);

            davStart(xml, DavConstants.ELEM_MULTISTATUS);
            xml.writeNamespace(DavConstants.PREFIX,
                    DavConstants.NAMESPACE);

            davStart(xml, DavConstants.ELEM_RESPONSE);
            davStartText(xml, DavConstants.ELEM_HREF,
                    getHref(path, pathIsDirectory));

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
                davStart(xml, DavConstants.ELEM_PROPSTAT);
                davStart(xml, DavConstants.ELEM_PROP);
                for (int i = 0; i < okIndices.size(); i++) {
                    writePropElement(xml,
                            proppatch.updates.get(
                                    okIndices.get(i).intValue()));
                }
                davEnd(xml, DavConstants.ELEM_PROP);
                davStartText(xml, DavConstants.ELEM_STATUS,
                        "HTTP/1.1 200 OK");
                davEnd(xml, DavConstants.ELEM_PROPSTAT);
            }

            if (!failIndices.isEmpty()) {
                davStart(xml, DavConstants.ELEM_PROPSTAT);
                davStart(xml, DavConstants.ELEM_PROP);
                for (int i = 0; i < failIndices.size(); i++) {
                    writePropElement(xml,
                            proppatch.updates.get(
                                    failIndices.get(i).intValue()));
                }
                davEnd(xml, DavConstants.ELEM_PROP);
                davStartText(xml, DavConstants.ELEM_STATUS,
                        "HTTP/1.1 403 Forbidden");
                davEnd(xml, DavConstants.ELEM_PROPSTAT);
            }

            davEnd(xml, DavConstants.ELEM_RESPONSE);
            davEnd(xml, DavConstants.ELEM_MULTISTATUS);
            xml.close();

            byte[] body = baos.toByteArray();
            Headers response = new Headers();
            response.status(HttpStatus.MULTI_STATUS);
            response.add("Content-Type",
                    DavConstants.CONTENT_TYPE_XML);
            response.add("Content-Length",
                    String.valueOf(body.length));
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(body));
            state.endResponseBody();
            state.complete();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, L10N.getString("severe.proppatch_response_error"), e);
        }
    }

    private void writePropElement(XMLWriter xml,
            WebDAVRequestParser.PropertyUpdate update)
            throws IOException {
        String ns = update.namespaceURI != null
                && !update.namespaceURI.isEmpty()
                ? update.namespaceURI : "";
        if (DavConstants.NAMESPACE.equals(ns)) {
            xml.writeStartElement(DavConstants.PREFIX,
                    update.localName, ns);
        } else if (!ns.isEmpty()) {
            xml.writeStartElement(ns, update.localName);
        } else {
            xml.writeStartElement(update.localName);
        }
        xml.writeEndElement();
    }

    /** Fallback when dead property store is not available. */
    private void sendProppatchForbidden(HttpResponseState state,
            WebDAVRequestParser.ProppatchRequest proppatch)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);

        davStart(xml, DavConstants.ELEM_MULTISTATUS);
        xml.writeNamespace(DavConstants.PREFIX,
                DavConstants.NAMESPACE);

        davStart(xml, DavConstants.ELEM_RESPONSE);
        davStartText(xml, DavConstants.ELEM_HREF,
                getHref(path, pathIsDirectory));

        davStart(xml, DavConstants.ELEM_PROPSTAT);
        davStart(xml, DavConstants.ELEM_PROP);
        for (WebDAVRequestParser.PropertyUpdate update
                : proppatch.updates) {
            writePropElement(xml, update);
        }
        davEnd(xml, DavConstants.ELEM_PROP);
        davStartText(xml, DavConstants.ELEM_STATUS,
                "HTTP/1.1 403 Forbidden");
        davEnd(xml, DavConstants.ELEM_PROPSTAT);
        davEnd(xml, DavConstants.ELEM_RESPONSE);
        davEnd(xml, DavConstants.ELEM_MULTISTATUS);
        xml.close();

        byte[] body = baos.toByteArray();
        Headers response = new Headers();
        response.status(HttpStatus.MULTI_STATUS);
        response.add("Content-Type", DavConstants.CONTENT_TYPE_XML);
        response.add("Content-Length", String.valueOf(body.length));
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(body));
        state.endResponseBody();
        state.complete();
    }

    /**
     * Outcome of LOCK createFile + lockManager.lock, computed off the loop.
     */
    private static final class LockPlan {
        HttpStatus error;
        WebDAVLock lock;
        boolean created;
        boolean isDirectory;
    }

    /** RFC 4918 §9.10 — create lock; §7.3 — lock-null resource creation. */
    private void createLock(HttpResponseState state, WebDAVLock.Scope scope,
            WebDAVLock.Type type, String owner) {
        final long timeout = parseTimeout(
                requestHeaders.getValue(DavConstants.HEADER_TIMEOUT));
        final WebDAVLock.Scope lockScope = scope;
        final WebDAVLock.Type lockType = type;
        final String lockOwner = owner;

        offload(state, new Callable<LockPlan>() {
            @Override
            public LockPlan call() throws IOException {
                return computeLockPlan(lockScope, lockType, lockOwner, timeout);
            }
        }, new StorageExecutor.Callback<LockPlan>() {
            @Override
            public void completed(LockPlan plan) {
                if (plan.error != null) {
                    sendError(state, plan.error);
                    return;
                }
                pathIsDirectory = plan.isDirectory;
                try {
                    sendLockResponse(state, plan.lock, plan.created,
                            plan.isDirectory);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, L10N.getString("severe.lock_response_error"), e);
                    sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.error_processing_lock"), error);
                sendError(state, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        });
    }

    private LockPlan computeLockPlan(WebDAVLock.Scope scope,
            WebDAVLock.Type type, String owner, long timeout)
            throws IOException {
        LockPlan plan = new LockPlan();
        if (path == null || !bindCanonicalPath()) {
            plan.error = HttpStatus.BAD_REQUEST;
            return plan;
        }
        // Create empty file if it doesn't exist (lock-null resource)
        if (!Files.exists(path)) {
            Files.createFile(path);
            plan.created = true;
            plan.isDirectory = false;
        } else {
            plan.isDirectory = Files.isDirectory(path);
        }

        WebDAVLock lock = lockManager.lock(path, scope, type, depth, owner,
                timeout);
        if (lock == null) {
            plan.error = HttpStatus.LOCKED;
            return plan;
        }
        plan.lock = lock;
        return plan;
    }

    private void sendLockResponse(HttpResponseState state, WebDAVLock lock,
            boolean created, boolean isDirectory) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLWriter xml = new XMLWriter(baos);
        
        davStart(xml, DavConstants.ELEM_PROP);
        xml.writeNamespace(DavConstants.PREFIX, DavConstants.NAMESPACE);
        
        davStart(xml, DavConstants.PROP_LOCKDISCOVERY);
        
        davStart(xml, DavConstants.ELEM_ACTIVELOCK);
        
        davStart(xml, DavConstants.ELEM_LOCKTYPE);
        davEmpty(xml, DavConstants.ELEM_WRITE);
        davEnd(xml, DavConstants.ELEM_LOCKTYPE);
        
        davStart(xml, DavConstants.ELEM_LOCKSCOPE);
        davEmpty(xml, lock.getScope() == WebDAVLock.Scope.EXCLUSIVE
                ? DavConstants.ELEM_EXCLUSIVE : DavConstants.ELEM_SHARED);
        davEnd(xml, DavConstants.ELEM_LOCKSCOPE);
        
        davStartText(xml, DavConstants.ELEM_DEPTH,
                lock.getDepth() == DavConstants.DEPTH_INFINITY
                        ? "infinity" : String.valueOf(lock.getDepth()));
        
        if (lock.getOwner() != null) {
            davStartText(xml, DavConstants.ELEM_OWNER, lock.getOwner());
        }
        
        long remaining = lock.getRemainingTimeoutSeconds();
        davStartText(xml, DavConstants.ELEM_TIMEOUT,
                remaining < 0 ? "Infinite" : "Second-" + remaining);
        
        davStart(xml, DavConstants.ELEM_LOCKTOKEN);
        davStartText(xml, DavConstants.ELEM_HREF, lock.getToken());
        davEnd(xml, DavConstants.ELEM_LOCKTOKEN);
        
        davStart(xml, DavConstants.ELEM_LOCKROOT);
        davStartText(xml, DavConstants.ELEM_HREF,
                getHref(lock.getPath(), isDirectory));
        davEnd(xml, DavConstants.ELEM_LOCKROOT);
        
        davEnd(xml, DavConstants.ELEM_ACTIVELOCK);
        davEnd(xml, DavConstants.PROP_LOCKDISCOVERY);
        davEnd(xml, DavConstants.ELEM_PROP);
        xml.close();
        
        byte[] body = baos.toByteArray();
        Headers response = new Headers();
        response.status(created ? HttpStatus.CREATED : HttpStatus.OK);
        response.add("Content-Type", DavConstants.CONTENT_TYPE_XML);
        response.add("Content-Length", String.valueOf(body.length));
        response.add(DavConstants.HEADER_LOCK_TOKEN, "<" + lock.getToken() + ">");
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(body));
        state.endResponseBody();
        state.complete();
        LOGGER.info(MessageFormat.format(L10N.getString("info.locked"), path, lock.getToken()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebDAV Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private Set<String> getLivePropertyNames() {
        Set<String> names = new HashSet<String>();
        names.add(DavConstants.PROP_CREATIONDATE);
        names.add(DavConstants.PROP_DISPLAYNAME);
        names.add(DavConstants.PROP_GETCONTENTLENGTH);
        names.add(DavConstants.PROP_GETCONTENTTYPE);
        names.add(DavConstants.PROP_GETETAG);
        names.add(DavConstants.PROP_GETLASTMODIFIED);
        names.add(DavConstants.PROP_LOCKDISCOVERY);
        names.add(DavConstants.PROP_RESOURCETYPE);
        names.add(DavConstants.PROP_SUPPORTEDLOCK);
        if (aclEnabled) {
            names.add(DavConstants.PROP_OWNER);
            names.add(DavConstants.PROP_GROUP);
            names.add(DavConstants.PROP_SUPPORTED_PRIVILEGE_SET);
            names.add(DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
            names.add(DavConstants.PROP_ACL);
            names.add(DavConstants.PROP_PRINCIPAL_COLLECTION_SET);
        }
        return names;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RFC 3744 (WebDAV ACL)
    // ─────────────────────────────────────────────────────────────────────────

    /** Every RFC 3744 §9 privilege this server evaluates (DAV:all and DAV:write are aggregates, handled separately by {@link #hasPrivilege}). */
    private static final String[] LEAF_PRIVILEGES = {
        DavConstants.PRIV_READ,
        DavConstants.PRIV_WRITE_PROPERTIES,
        DavConstants.PRIV_WRITE_CONTENT,
        DavConstants.PRIV_UNLOCK,
        DavConstants.PRIV_READ_ACL,
        DavConstants.PRIV_READ_CURRENT_USER_PRIVILEGE_SET,
        DavConstants.PRIV_WRITE_ACL,
        DavConstants.PRIV_BIND,
        DavConstants.PRIV_UNBIND,
    };

    /**
     * Checks a single (non-aggregate) privilege against {@link #realm},
     * as the role {@code "webdav:" + privilegeLocalName}
     * ({@link DavConstants#ROLE_PREFIX}) -- or, as a shortcut for an
     * administrator role, whether the principal holds {@code
     * DAV:all}'s own role ({@code "webdav:all"}) regardless of which
     * specific privilege was asked about.
     */
    private boolean hasPrivilege(String username, String privilegeLocalName) {
        if (username == null || realm == null) {
            return false;
        }
        if (realm.isUserInRole(username, DavConstants.ROLE_PREFIX + DavConstants.ELEM_ALL)) {
            return true;
        }
        return realm.isUserInRole(username, DavConstants.ROLE_PREFIX + privilegeLocalName);
    }

    /**
     * DAV:write (§9.2) aggregates write-properties/write-content/bind/
     * unbind -- granted either directly (the {@code webdav:write} role,
     * a shortcut so a deployer doesn't have to assign all four
     * sub-privilege roles individually) or by holding every one of the
     * four sub-privileges.
     */
    private boolean hasWritePrivilege(String username) {
        if (hasPrivilege(username, DavConstants.ELEM_WRITE)) {
            return true;
        }
        return hasPrivilege(username, DavConstants.PRIV_WRITE_PROPERTIES)
                && hasPrivilege(username, DavConstants.PRIV_WRITE_CONTENT)
                && hasPrivilege(username, DavConstants.PRIV_BIND)
                && hasPrivilege(username, DavConstants.PRIV_UNBIND);
    }

    /** Writes {@code <D:privilege><D:localName/></D:privilege>}. */
    private void writePrivilege(XMLWriter xml, String localName) throws IOException {
        davStart(xml, DavConstants.ELEM_PRIVILEGE);
        davEmpty(xml, localName);
        davEnd(xml, DavConstants.ELEM_PRIVILEGE);
    }

    /** RFC 3744 §5.4 -- the privileges {@link #principal} actually holds on this resource. */
    private void writeCurrentUserPrivilegeSet(XMLWriter xml) throws IOException {
        String username = (principal != null) ? principal.getName() : null;
        if (hasPrivilege(username, DavConstants.ELEM_ALL)) {
            writePrivilege(xml, DavConstants.ELEM_ALL);
            return;
        }
        if (hasWritePrivilege(username)) {
            writePrivilege(xml, DavConstants.ELEM_WRITE);
        }
        for (int i = 0; i < LEAF_PRIVILEGES.length; i++) {
            String privilege = LEAF_PRIVILEGES[i];
            if (hasPrivilege(username, privilege)) {
                writePrivilege(xml, privilege);
            }
        }
    }

    /**
     * RFC 3744 §5.5 -- this server can only meaningfully describe the
     * requesting principal's own access (it has no way to enumerate
     * every principal a {@link Realm} knows about to build a complete
     * ACL), so the response contains at most a single ACE for {@code
     * DAV:authenticated}, granting exactly the privileges
     * {@link #writeCurrentUserPrivilegeSet} reports for the current
     * request. An unauthenticated request gets an empty {@code
     * DAV:acl} -- RFC 3744 doesn't require every resource to have a
     * non-empty ACL.
     */
    private void writeAcl(XMLWriter xml) throws IOException {
        if (principal == null) {
            return;
        }
        String username = principal.getName();
        List<String> granted = new ArrayList<String>();
        if (hasPrivilege(username, DavConstants.ELEM_ALL)) {
            granted.add(DavConstants.ELEM_ALL);
        } else {
            if (hasWritePrivilege(username)) {
                granted.add(DavConstants.ELEM_WRITE);
            }
            for (int i = 0; i < LEAF_PRIVILEGES.length; i++) {
                String privilege = LEAF_PRIVILEGES[i];
                if (hasPrivilege(username, privilege)) {
                    granted.add(privilege);
                }
            }
        }
        if (granted.isEmpty()) {
            return;
        }
        davStart(xml, DavConstants.ELEM_ACE);
        davStart(xml, DavConstants.ELEM_PRINCIPAL);
        davEmpty(xml, DavConstants.ELEM_AUTHENTICATED);
        davEnd(xml, DavConstants.ELEM_PRINCIPAL);
        davStart(xml, DavConstants.ELEM_GRANT);
        for (int i = 0; i < granted.size(); i++) {
            writePrivilege(xml, granted.get(i));
        }
        davEnd(xml, DavConstants.ELEM_GRANT);
        davEnd(xml, DavConstants.ELEM_ACE);
    }

    /**
     * RFC 3744 §5.3 -- the static tree of privileges this server
     * evaluates at all, independent of any one principal's actual
     * grants (see {@link #writeCurrentUserPrivilegeSet}/{@link #writeAcl}
     * for those).
     */
    private void writeSupportedPrivilegeSet(XMLWriter xml) throws IOException {
        davStart(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
        writePrivilege(xml, DavConstants.ELEM_ALL);
        davEmpty(xml, DavConstants.ELEM_ABSTRACT);
        davStartText(xml, DavConstants.ELEM_DESCRIPTION, "All privileges");

        davStart(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
        writePrivilege(xml, DavConstants.PRIV_READ);
        davStartText(xml, DavConstants.ELEM_DESCRIPTION, "Read");
        writeAbstractSupportedPrivilege(xml, DavConstants.PRIV_READ_ACL, "Read ACL");
        writeAbstractSupportedPrivilege(xml, DavConstants.PRIV_READ_CURRENT_USER_PRIVILEGE_SET,
                "Read current user privilege set");
        davEnd(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);

        davStart(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
        writePrivilege(xml, DavConstants.ELEM_WRITE);
        davStartText(xml, DavConstants.ELEM_DESCRIPTION, "Write");
        writeSupportedPrivilege(xml, DavConstants.PRIV_WRITE_PROPERTIES, "Write properties");
        writeSupportedPrivilege(xml, DavConstants.PRIV_WRITE_CONTENT, "Write content");
        writeSupportedPrivilege(xml, DavConstants.PRIV_BIND, "Add member (bind)");
        writeSupportedPrivilege(xml, DavConstants.PRIV_UNBIND, "Remove member (unbind)");
        writeAbstractSupportedPrivilege(xml, DavConstants.PRIV_WRITE_ACL, "Write ACL");
        writeSupportedPrivilege(xml, DavConstants.PRIV_UNLOCK, "Unlock");
        davEnd(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);

        davEnd(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
    }

    private void writeSupportedPrivilege(XMLWriter xml, String localName, String description) throws IOException {
        davStart(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
        writePrivilege(xml, localName);
        davStartText(xml, DavConstants.ELEM_DESCRIPTION, description);
        davEnd(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
    }

    /** RFC 3744 §9: an "abstract" privilege can't be directly ACE-granted/denied -- only via one of its aggregates. */
    private void writeAbstractSupportedPrivilege(XMLWriter xml, String localName, String description)
            throws IOException {
        davStart(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
        writePrivilege(xml, localName);
        davEmpty(xml, DavConstants.ELEM_ABSTRACT);
        davStartText(xml, DavConstants.ELEM_DESCRIPTION, description);
        davEnd(xml, DavConstants.ELEM_SUPPORTED_PRIVILEGE);
    }

    /** RFC 3744 §5: writes the six ACL live properties into an already-open {@code <D:prop>}. */
    private void writeAclProperties(XMLWriter xml) throws IOException {
        // §5.1/§5.2: no per-resource ownership/group model exists in
        // this server distinct from filesystem permissions, which
        // aren't principal-addressable via a Realm -- empty is a
        // defined, valid value for "no owner/group known".
        davEmpty(xml, DavConstants.PROP_OWNER);
        davEmpty(xml, DavConstants.PROP_GROUP);

        davStart(xml, DavConstants.PROP_SUPPORTED_PRIVILEGE_SET);
        writeSupportedPrivilegeSet(xml);
        davEnd(xml, DavConstants.PROP_SUPPORTED_PRIVILEGE_SET);

        davStart(xml, DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
        writeCurrentUserPrivilegeSet(xml);
        davEnd(xml, DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);

        davStart(xml, DavConstants.PROP_ACL);
        writeAcl(xml);
        davEnd(xml, DavConstants.PROP_ACL);

        // §5.8: empty is valid -- this server exposes no principal
        // collection (there's no way to address a Realm's users as
        // WebDAV resources).
        davEmpty(xml, DavConstants.PROP_PRINCIPAL_COLLECTION_SET);
    }

    private List<Path> collectResources(Path root, int depth) throws IOException {
        List<Path> ancestors = new ArrayList<Path>();
        ancestors.add(root.toRealPath());
        return collectResources(root, depth, ancestors);
    }

    /**
     * Enumerates a collection to the given depth. {@code ancestors} holds
     * the canonical paths of the directories being walked, so that a
     * symbolic link back over the walk is not followed.
     */
    private List<Path> collectResources(Path root, int depth, List<Path> ancestors)
            throws IOException {
        List<Path> result = new ArrayList<Path>();
        result.add(root);
        
        if (depth > 0 && Files.isDirectory(root)) {
            for (Path child : listChildren(root)) {
                if (child.getFileName().toString().startsWith(".")) {
                    continue; // Skip hidden files
                }
                if (Files.isSymbolicLink(child)
                        && !isSafeToFollowLink(child, ancestors, null)) {
                    logLinkLeftOut(child);
                    continue;
                }
                result.add(child);
                if (depth > 1 && Files.isDirectory(child)) {
                    ancestors.add(child.toRealPath());
                    try {
                        result.addAll(collectResources(child, depth - 1, ancestors));
                    } finally {
                        ancestors.remove(ancestors.size() - 1);
                    }
                }
            }
        }
        
        return result;
    }

    private void logLinkLeftOut(Path link) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("fine.symlink_left_out"), link));
        }
    }

    /**
     * Builds a request-URI href for a resource. The directory flag must be
     * supplied by the caller (from a plan or pre-fetched attributes) so this
     * never performs a blocking {@code Files.isDirectory} stat.
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

    /**
     * What a recursive COPY must not re-enter: the directories being copied
     * and the destination they are copied to.
     */
    private static final class CopyGuard {
        private final Path destination;
        private final List<Path> sources = new ArrayList<Path>();

        CopyGuard(Path sourceReal, Path destinationReal) {
            this.destination = destinationReal;
            this.sources.add(sourceReal);
        }
    }

    private void copyDirectory(Path source, Path target, int depth, CopyGuard guard)
            throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectory(target);
        }

        if (depth <= 0) {
            return;
        }

        // An unreadable source directory fails the copy rather than
        // leaving a silently incomplete result.
        for (Path childSource : listChildren(source)) {
            String childName = childSource.getFileName().toString();
            if (DeadPropertyStore.isSidecarName(childName)) {
                continue;
            }
            if (Files.isSymbolicLink(childSource)
                    && !isSafeToFollowLink(childSource, guard.sources, guard.destination)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.copy_skipped_link"), childSource));
                continue;
            }
            Path childTarget = target.resolve(childName);

            if (Files.isDirectory(childSource)) {
                guard.sources.add(childSource.toRealPath());
                try {
                    copyDirectory(childSource, childTarget, depth - 1, guard);
                } finally {
                    guard.sources.remove(guard.sources.size() - 1);
                }
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

    /**
     * Decides whether a symbolic link met while walking a collection may be
     * followed. Its target must exist and be inside the web root, so that a
     * link planted in a collection cannot expose outside files, and a
     * directory target must not contain any of {@code ancestors} (the
     * directories being walked) or {@code destination} (where a copy is
     * going), since following it would walk the tree into itself without
     * end.
     *
     * <p>A link that fails these tests is treated as absent: not listed,
     * not reported, not copied. That is what GET already does for a
     * request for such a link.
     *
     * @param link the symbolic link
     * @param ancestors canonical paths of the directories being walked
     * @param destination canonical destination of a copy, or null
     */
    private boolean isSafeToFollowLink(Path link, List<Path> ancestors,
            Path destination) {
        Path real;
        try {
            real = link.toRealPath();
        } catch (IOException e) {
            return false;
        }
        if (!real.startsWith(canonicalRoot)) {
            return false;
        }
        if (Files.isDirectory(real)) {
            if (destination != null && destination.startsWith(real)) {
                return false;
            }
            for (int i = 0; i < ancestors.size(); i++) {
                if (ancestors.get(i).startsWith(real)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the canonical path of {@code path}, or, if it does not exist
     * yet, that of its nearest existing ancestor with the missing names
     * appended.
     */
    private static Path realPathAllowingMissing(Path path) throws IOException {
        Path existing = path;
        List<Path> missing = new ArrayList<Path>();
        while (existing != null && !Files.exists(existing)) {
            missing.add(existing.getFileName());
            existing = existing.getParent();
        }
        Path result = (existing != null) ? existing.toRealPath() : path;
        for (int i = missing.size() - 1; i >= 0; i--) {
            result = result.resolve(missing.get(i));
        }
        return result;
    }

    /**
     * Lists the entries of a directory. Unlike {@code File.listFiles}, which
     * answers null for any failure, an unreadable directory is reported.
     */
    private static List<Path> listChildren(Path dir) throws IOException {
        List<Path> children = new ArrayList<Path>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
        try {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        } finally {
            stream.close();
        }
        return children;
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
                boolean isDir = Files.isDirectory(targetPath);
                String href = getHref(targetPath, isDir);
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
            return DavConstants.DEFAULT_LOCK_TIMEOUT_SECONDS;
        }
        
        if (DavConstants.TIMEOUT_INFINITE.equalsIgnoreCase(timeout)) {
            return -1;
        }
        
        if (timeout.startsWith(DavConstants.TIMEOUT_SECOND_PREFIX)) {
            try {
                long seconds = Long.parseLong(timeout.substring(DavConstants.TIMEOUT_SECOND_PREFIX.length()));
                return Math.min(seconds, DavConstants.MAX_LOCK_TIMEOUT_SECONDS);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        return DavConstants.DEFAULT_LOCK_TIMEOUT_SECONDS;
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

    private void sendError(HttpResponseState state, HttpStatus status) {
        Headers response = new Headers();
        response.status(status);
        response.add("Content-Length", "0");
        state.headers(response);
        state.complete();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path validation and utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lexically resolves a request path against {@link #rootPath} with
     * dangerous-component checks only — no {@code Files.*} syscalls.
     * Disk containment / symlink resolve happen later via
     * {@link #bindCanonicalPath()} inside offloaded work.
     */
    private Path resolvePathLexical(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            return null;
        }

        if (requestPath.contains("\0")) {
            LOGGER.warning(L10N.getString("warn.rejected_null_byte_path"));
            return null;
        }

        if (requestPath.length() > 2048) {
            LOGGER.warning(L10N.getString("warn.rejected_overly_long_path"));
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
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.rejected_dangerous_path_component"), decodedComponent));
                    return null;
                }

                resolvedPath = resolvedPath.resolve(decodedComponent);
            }

            resolvedPath = resolvedPath.normalize();

            // Lexical containment only (no disk I/O).
            if (!resolvedPath.startsWith(rootPath.normalize())) {
                return null;
            }

            return resolvedPath;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Full path resolve including disk containment — for use only from
     * StorageExecutor work (e.g. Destination header on COPY/MOVE).
     */
    private Path validateAndResolvePath(String requestPath) {
        Path lexical = resolvePathLexical(requestPath);
        if (lexical == null) {
            return null;
        }
        return canonicalizePath(lexical);
    }

    /**
     * Disk containment / symlink resolve for the current request
     * {@link #path}. Call only from offloaded work.
     *
     * @return true if {@link #path} was updated to a safe canonical path
     */
    private boolean bindCanonicalPath() {
        if (path == null) {
            return false;
        }
        Path canonical = canonicalizePath(path);
        if (canonical == null) {
            return false;
        }
        path = canonical;
        return true;
    }

    /**
     * Verifies disk containment (and resolves symlinks for existing paths).
     * Blocking — call only from StorageExecutor work.
     */
    private Path canonicalizePath(Path resolvedPath) {
        if (!isWithinRoot(resolvedPath)) {
            return null;
        }
        if (Files.exists(resolvedPath)) {
            try {
                Path realPath = resolvedPath.toRealPath();
                if (!isWithinRoot(realPath)) {
                    return null;
                }
                return realPath;
            } catch (IOException e) {
                // Allow to proceed with the lexical path
            }
        }
        return resolvedPath;
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
        if (containsDangerousChars(component)) {
            return true;
        }
        for (int i = 0; i < component.length(); i++) {
            if (Character.isISOControl(component.charAt(i))) {
                return true;
            }
        }
        return isReservedWindowsName(component);
    }

    /** Returns true if {@code component} contains any of {@code <>:"|?*}. */
    private static boolean containsDangerousChars(String component) {
        for (int i = 0; i < component.length(); i++) {
            switch (component.charAt(i)) {
            case '<':
            case '>':
            case ':':
            case '"':
            case '|':
            case '?':
            case '*':
                return true;
            default:
                break;
            }
        }
        return false;
    }

    /**
     * Windows reserved device names (case-insensitive), optionally with an
     * extension: CON, PRN, AUX, NUL, COM1–COM9, LPT1–LPT9.
     */
    private static boolean isReservedWindowsName(String component) {
        String upper = component.toUpperCase();
        int dot = upper.indexOf('.');
        String base = (dot < 0) ? upper : upper.substring(0, dot);
        if ("CON".equals(base) || "PRN".equals(base)
                || "AUX".equals(base) || "NUL".equals(base)) {
            return true;
        }
        if (base.length() == 4) {
            char d = base.charAt(3);
            if (d >= '1' && d <= '9') {
                String prefix = base.substring(0, 3);
                return "COM".equals(prefix) || "LPT".equals(prefix);
            }
        }
        return false;
    }

    /**
     * Disk containment check using the cached {@link #canonicalRoot}.
     * Blocking — call only from StorageExecutor work.
     */
    private boolean isWithinRoot(Path path) {
        try {
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
                        && !Files.isDirectory(welcomeFilePath)
                        && isWithinRoot(welcomeFilePath)) {
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
            List<Path> entries = listChildren(directory);
            Collections.sort(entries, new DirectoryListingComparator());

            List<Path> none = new ArrayList<Path>();
            for (Path file : entries) {
                try {
                    String filename = file.getFileName().toString();
                    if (DeadPropertyStore.isSidecarName(filename)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(file)
                            && !isSafeToFollowLink(file, none, null)) {
                        logLinkLeftOut(file);
                        continue;
                    }
                    boolean isDirectory = Files.isDirectory(file);
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
                        html.append(" (").append(formatFileSize(Files.size(file))).append(")");
                    }

                    html.append("</li>\n");
                } catch (Exception e) {
                    // Skip
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

    private static class DirectoryListingComparator implements Comparator<Path> {
        @Override
        public int compare(Path f1, Path f2) {
            boolean f1IsDir = Files.isDirectory(f1);
            boolean f2IsDir = Files.isDirectory(f2);
            if (f1IsDir != f2IsDir) {
                return f1IsDir ? -1 : 1;
            }
            return f1.getFileName().toString().compareToIgnoreCase(
                    f2.getFileName().toString());
        }
    }
}
