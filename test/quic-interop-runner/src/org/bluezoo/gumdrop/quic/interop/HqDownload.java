/*
 * HqDownload.java
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;

/**
 * One {@code hq-interop} download on the client: sends the HTTP/0.9
 * request line and finishes the send side as soon as the stream opens,
 * then writes whatever comes back to the target file until the peer's
 * FIN. A reset or connection failure before that FIN counts as a failed
 * download.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HqDownload implements ProtocolHandler {

    private static final Logger LOGGER = Logger.getLogger(HqDownload.class.getName());

    private final RequestUrl url;
    private final Path target;
    private final DownloadTracker tracker;
    private FileChannel channel;
    private long received;
    private boolean finished;

    HqDownload(RequestUrl url, Path target, DownloadTracker tracker) {
        this.url = url;
        this.target = target;
        this.tracker = tracker;
    }

    @Override
    public void connected(Endpoint endpoint) {
        try {
            channel = FileChannel.open(target, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "cannot create " + target, e);
            finish(false);
            endpoint.close();
            return;
        }
        LOGGER.fine("requesting " + url.path);
        endpoint.send(ByteBuffer.wrap(("GET " + url.path + "\r\n").getBytes(StandardCharsets.US_ASCII)));
        endpoint.close();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void receive(ByteBuffer data) {
        if (finished || channel == null) {
            return;
        }
        try {
            while (data.hasRemaining()) {
                int written = channel.write(data);
                received += written;
                tracker.received(written);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "cannot write " + target, e);
            finish(false);
        }
    }

    @Override
    public void readFinished() {
        LOGGER.fine("received " + url.fileName + ": " + received + " bytes");
        finish(true);
    }

    @Override
    public void disconnected() {
        if (!finished) {
            LOGGER.warning("stream for " + url.fileName + " closed before its FIN");
        }
        finish(false);
    }

    @Override
    public void error(Exception cause) {
        if (!finished) {
            LOGGER.log(Level.WARNING, "stream for " + url.fileName + " failed", cause);
        }
        finish(false);
    }

    private void finish(boolean ok) {
        if (finished) {
            return;
        }
        finished = true;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "cannot close " + target, e);
                ok = false;
            }
        }
        tracker.finished(ok);
    }

}
