/*
 * QlogAttributes.java
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

package org.bluezoo.gumdrop.telemetry;

/**
 * The attributes a qlog event carries as a {@link LogRecord}: the record
 * body is the event's {@code data} object as JSON, and these name the rest
 * (draft-ietf-quic-qlog-main-schema-14 section 5.4).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QlogAttributes {

    private QlogAttributes() {
    }

    /** The channel qlog records are tagged with. */
    public static final String CHANNEL = "qlog";

    /** The namespaced event name, such as {@code quic:packet_lost}. */
    public static final String NAME = "qlog.name";

    /** The URI of the event schema the event belongs to. */
    public static final String SCHEMA = "qlog.schema";

    /** The group id: the connection's original destination connection ID in hex. */
    public static final String GROUP_ID = "qlog.group_id";

    /** The vantage point: {@code client} or {@code server}. */
    public static final String VANTAGE_POINT = "qlog.vantage_point";

    /** draft-ietf-quic-qlog-quic-events: the QUIC event schema. */
    public static final String SCHEMA_QUIC = "urn:ietf:params:qlog:events:quic";

    /** draft-ietf-quic-qlog-h3-events: the HTTP/3 event schema. */
    public static final String SCHEMA_HTTP3 = "urn:ietf:params:qlog:events:http3";

    /** draft-ietf-quic-qlog-h3-events: the HTTP event schema (capsules). */
    public static final String SCHEMA_HTTP = "urn:ietf:params:qlog:events:http";
}
