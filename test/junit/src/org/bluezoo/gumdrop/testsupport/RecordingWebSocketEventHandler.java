/*
 * RecordingWebSocketEventHandler.java
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

package org.bluezoo.gumdrop.testsupport;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

/**
 * {@link WebSocketEventHandler} that records every callback for later
 * assertion. Callbacks arrive inline on the test thread.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RecordingWebSocketEventHandler
        implements WebSocketEventHandler {

    public WebSocketSession session;
    public int openedCount;
    public final List<String> texts = new ArrayList<String>();
    public final List<byte[]> binaries = new ArrayList<byte[]>();
    public final List<Integer> closeCodes = new ArrayList<Integer>();
    public final List<String> closeReasons = new ArrayList<String>();
    public final List<Throwable> errors = new ArrayList<Throwable>();

    @Override
    public void opened(WebSocketSession session) {
        this.session = session;
        openedCount++;
    }

    @Override
    public void textMessageReceived(WebSocketSession session, String message) {
        texts.add(message);
    }

    @Override
    public void binaryMessageReceived(WebSocketSession session,
            ByteBuffer data) {
        byte[] b = new byte[data.remaining()];
        data.get(b);
        binaries.add(b);
    }

    @Override
    public void closed(int code, String reason) {
        closeCodes.add(code);
        closeReasons.add(reason);
    }

    @Override
    public void error(Throwable cause) {
        errors.add(cause);
    }
}
