/*
 * QpackTestSupport.java
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


package org.bluezoo.gumdrop.http.qpack;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HeaderCollector;

/**
 * Lets tests that just want the decoded fields of a section get them as a
 * list, now that {@link Decoder#decode(long, ByteBuffer,
 * org.bluezoo.gumdrop.http.HeaderFieldHandler)} pushes them to a handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QpackTestSupport {

    private QpackTestSupport() {
    }

    /** Decodes a section and returns its valid fields in wire order. */
    static List<Header> decode(Decoder decoder, long streamId, ByteBuffer block)
            throws ProtocolException {
        HeaderCollector collector = new HeaderCollector();
        decoder.decode(streamId, block, collector);
        return collector.headers();
    }
}
