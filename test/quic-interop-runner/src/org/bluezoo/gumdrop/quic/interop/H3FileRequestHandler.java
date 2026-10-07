/*
 * H3FileRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.quic.interop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;

/**
 * Serves the runner's {@code /www} files over HTTP/3 for the {@code http3}
 * test case: a 200 with the file as the body, or a bare 404. Responses are
 * never content-coded, since the runner compares the downloaded bytes
 * with the originals.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class H3FileRequestHandler extends DefaultHttpRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(H3FileRequestHandler.class.getName());

    /**
     * Binds a handler per request stream.
     */
    static final class StreamHandler implements HttpStreamHandler {

        private final Path www;

        StreamHandler(Path www) {
            this.www = www;
        }

        @Override
        public HttpRequestHandler openStream(HttpResponse response) {
            return new H3FileRequestHandler(www, response);
        }

    }

    private final Path www;
    private final HttpResponse response;
    private String target;

    H3FileRequestHandler(Path www, HttpResponse response) {
        this.www = www;
        this.response = response;
    }

    @Override
    public void target(ByteBuffer target) {
        byte[] bytes = new byte[target.remaining()];
        target.duplicate().get(bytes);
        this.target = new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public boolean encodeResponseContentCoding() {
        return false;
    }

    @Override
    public void endHeaders() {
        Path file = InteropFiles.resolve(www, target);
        if (file == null) {
            LOGGER.info("no such file: " + target);
            response.status(404);
            response.endHeaders();
            response.endMessage();
            return;
        }
        FileChannel channel;
        long size;
        try {
            size = Files.size(file);
            channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "cannot open " + file, e);
            response.status(500);
            response.endHeaders();
            response.endMessage();
            return;
        }
        LOGGER.fine("serving " + target);
        response.status(200);
        response.header("Content-Type", "application/octet-stream");
        response.longHeader("Content-Length", size);
        response.endHeaders();
        new ResponsePump(channel).start();
    }

    /**
     * Pushes the file into the response body.
     */
    private final class ResponsePump extends FilePump {

        ResponsePump(FileChannel channel) {
            super(channel);
        }

        @Override
        void write(ByteBuffer data) {
            response.bodyContent(data);
        }

        @Override
        void finish() {
            response.endMessage();
        }

        @Override
        void abort() {
            response.cancel();
        }

        @Override
        void onWritable(Runnable callback) {
            response.onWritable(callback);
        }

        @Override
        void execute(Runnable task) {
            response.execute(task);
        }

    }

}
