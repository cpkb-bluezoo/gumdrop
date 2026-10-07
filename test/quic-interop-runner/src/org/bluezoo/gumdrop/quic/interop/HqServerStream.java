/*
 * HqServerStream.java
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;

/**
 * One {@code hq-interop} request stream on the server: parses the
 * HTTP/0.9 request line as it arrives, then streams the named file back
 * and finishes the stream. A missing file or malformed request gets an
 * empty response (FIN with no data), which is what other interop
 * endpoints do for HTTP/0.9, where there is no status line to send.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HqServerStream implements ProtocolHandler, HqRequestParser.Listener {

    private static final Logger LOGGER = Logger.getLogger(HqServerStream.class.getName());

    private final Path www;
    private final Runnable onComplete;
    private final HqRequestParser parser = new HqRequestParser(this);
    private Endpoint endpoint;
    private boolean completed;

    /**
     * @param www the directory to serve
     * @param onComplete run once, when this stream's response is finished
     *        or abandoned, so the connection can grant the peer another
     *        stream in its place
     */
    HqServerStream(Path www, Runnable onComplete) {
        this.www = www;
        this.onComplete = onComplete;
    }

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void readFinished() {
        parser.readFinished();
    }

    @Override
    public void disconnected() {
        complete();
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "request stream failed", cause);
        complete();
    }

    @Override
    public void requestTarget(String target) {
        Path file = InteropFiles.resolve(www, target);
        if (file == null) {
            LOGGER.info("no such file: " + target);
            endpoint.close();
            complete();
            return;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "cannot open " + file, e);
            endpoint.close();
            complete();
            return;
        }
        LOGGER.fine("serving " + target);
        new StreamPump(channel).start();
    }

    @Override
    public void malformed(String reason) {
        LOGGER.warning("malformed request: " + reason);
        endpoint.close();
        complete();
    }

    private void complete() {
        if (completed) {
            return;
        }
        completed = true;
        onComplete.run();
    }

    /**
     * Pushes the file into the stream and finishes it.
     */
    private final class StreamPump extends FilePump {

        StreamPump(FileChannel channel) {
            super(channel);
        }

        @Override
        void write(ByteBuffer data) {
            endpoint.send(data);
        }

        @Override
        void finish() {
            endpoint.close();
            complete();
        }

        @Override
        void abort() {
            endpoint.close();
            complete();
        }

        @Override
        void onWritable(Runnable callback) {
            endpoint.onWriteReady(callback);
        }

        @Override
        void execute(Runnable task) {
            endpoint.execute(task);
        }

    }

}
