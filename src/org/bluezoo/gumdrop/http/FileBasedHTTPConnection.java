/*
 * iFileBasedHTTPConnection.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Stream;
import gnu.inet.http.HTTPDateFormat;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for the HTTP protocol.
 * This serves files from a filesystem root.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileBasedHTTPConnection extends AbstractHTTPConnection {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    static final Logger LOGGER = Logger.getLogger(FileBasedHTTPConnection.class.getName());

    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    private final Path rootPath;
    private final boolean allowWrite;
    private final String allowedOptions;

    protected FileBasedHTTPConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Path rootPath,
            boolean allowWrite) {
        super(channel, engine, secure);
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        allowedOptions = allowWrite ? "OPTIONS, GET, HEAD, PUT, DELETE" : "OPTIONS, GET, HEAD";
    }

    protected Stream newStream(AbstractHTTPConnection connection, int streamId) {
        return this.new FileBasedStream(connection, streamId);
    }

    /**
     * Request/response pair for a given file.
     */
    class FileBasedStream extends Stream {

        private String method;
        private Path path;
        private FileChannel fileChannel;
        private Exception exception;
        private boolean created;
        private long ifModifiedSince = -1;

        protected FileBasedStream(AbstractHTTPConnection connection, int streamId) {
            super(connection, streamId);
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
                        // HTTP URI always uses slash as path separator.
                        // The underlying filesystem here may not.
                        String[] pathComps = value.split("/");
                        path = rootPath;
                        for (String comp : pathComps) {
                            try {
                                comp = URLDecoder.decode(comp, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                // This should never happen
                                RuntimeException e2 = new RuntimeException("UTF-8 is not supported");
                                e2.initCause(e);
                                throw e2;
                            }
                            path = path.resolve(comp);
                        }
                    }
                } else if ("If-Modified-Since".equalsIgnoreCase(name)) {
                    try {
                        ifModifiedSince = dateFormat.parse(value).getTime();
                    } catch (ParseException e) {
                        // NOOP
                    }
                }
            }
            if ("OPTIONS".equals(method) && path == null) {
                path = rootPath;
            }
            // Decide what to do
            try {
                if ("GET".equals(method)) {
                } else if (allowWrite && "PUT".equalsIgnoreCase(method)) {
                    created = !Files.exists(path);
                    fileChannel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                }
            } catch (Exception e) {
                exception = e;
            }
        }

        @Override protected void receiveRequestBody(byte[] buf) {
            if (allowWrite && "PUT".equalsIgnoreCase(method) && fileChannel != null && exception == null) {
                try {
                    fileChannel.write(ByteBuffer.wrap(buf));
                } catch (Exception e) {
                    exception = e;
                }
            }
        }

        @Override protected void endRequest() {
            try {
                List<Header> responseHeaders = new ArrayList<>();
                int sc = 405;
                if ("HEAD".equals(method)) {
                    if (!Files.exists(path)) {
                        sc = 404;
                    } else if (!Files.isReadable(path)) {
                        sc = 403;
                    } else {
                        try {
                            long size = Files.size(path);
                            responseHeaders.add(new Header("Content-Length", Long.toString(size)));
                            long lastModified = Files.getLastModifiedTime(path).toMillis();
                            responseHeaders.add(new Header("Last-Modified", dateFormat.format(lastModified)));
                            sc = 200;
                        } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, e.getMessage(), e);
                            sc = 403;
                        }
                    }
                    sendResponseHeaders(sc, responseHeaders, true);
                    return;
                } else if ("GET".equals(method)) {
                    boolean unmodified = false;
                    if (Files.isDirectory(path)) {
                        Path index = path.resolve("index.html");
                        if (Files.exists(index)) {
                            path = index;
                        }
                    }
                    long size = 0L;
                    if (!Files.exists(path)) {
                        sc = 404;
                    } else if (!Files.isReadable(path) || exception != null) {
                        sc = 403;
                    } else {
                        try {
                            size = Files.size(path);
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
                    try {
                        // TODO decide what to do about directory
                        fileChannel = FileChannel.open(path, StandardOpenOption.READ);
                        ByteBuffer buf = ByteBuffer.allocate(4096);
                        int len = fileChannel.read(buf);
                        int total = 0;
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
                    return;
                } else if ("OPTIONS".equals(method)) {
                    responseHeaders.add(new Header("Allow", allowedOptions));
                    sendResponseHeaders(200, responseHeaders, true);
                    return;
                } else if (allowWrite) {
                    if ("PUT".equals(method)) {
                        if (exception != null) {
                            sc = 403;
                        } else {
                            sc = created ? 201 : 200;
                        }
                        sendResponseHeaders(sc, responseHeaders, true);
                        close();
                        return;
                    } else if ("DELETE".equals(method)) {
                        if (!Files.exists(path)) {
                            sc = 404;
                        } else {
                            try {
                                Files.delete(path);
                                sc = 200;
                            } catch (IOException e) {
                                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                                sc = 403;
                            }
                        }
                        sendResponseHeaders(sc, responseHeaders, true);
                        return;
                    }
                }
                // method not allowed
                sendResponseHeaders(405, responseHeaders, true);
            } catch (ProtocolException e) {
                // we messed up with our headers :(
                String message = L10N.getString("err.send_headers");
                LOGGER.log(Level.SEVERE, message, e);
            }
        }

        @Override protected void close() {
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                    fileChannel = null;
                } catch (IOException e) {
                    // TODO log
                }
            }
        }

    }

}
