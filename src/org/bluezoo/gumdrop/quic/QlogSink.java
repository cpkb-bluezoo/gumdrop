/*
 * QlogSink.java
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

package org.bluezoo.gumdrop.quic;

/**
 * Where a protocol running on a QUIC connection reports qlog events. A
 * connection that does not log answers false to {@link #isQlogEnabled()}
 * and the caller builds nothing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface QlogSink {

    /**
     * Returns whether events are being logged. Decided when the connection
     * was created.
     *
     * @return true if {@link #emitQlog} will report the event
     */
    boolean isQlogEnabled();

    /**
     * Reports an event, timestamped now.
     *
     * @param schema the URI of the event schema, such as
     *               {@code urn:ietf:params:qlog:events:http3}
     * @param eventName the namespaced event name
     * @param dataJson the event's {@code data} object as JSON text
     */
    void emitQlog(String schema, String eventName, String dataJson);
}
