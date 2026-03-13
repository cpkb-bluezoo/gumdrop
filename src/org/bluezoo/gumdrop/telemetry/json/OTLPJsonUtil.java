/*
 * OTLPJsonUtil.java
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

package org.bluezoo.gumdrop.telemetry.json;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.json.JSONWriter;

import java.io.IOException;
import java.util.List;

/**
 * Shared utilities for OTLP JSON serialization.
 *
 * <p>Handles the common patterns used across trace, log, and metric
 * serializers: attribute encoding, hex encoding of byte arrays, and
 * OTLP KeyValue/AnyValue structures.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class OTLPJsonUtil {

    private OTLPJsonUtil() {
    }

    /**
     * Writes an OTLP attributes array.
     */
    static void writeAttributes(JSONWriter w, List<Attribute> attributes) throws IOException {
        w.writeStartArray();
        for (Attribute attr : attributes) {
            writeAttribute(w, attr);
        }
        w.writeEndArray();
    }

    /**
     * Writes a single OTLP KeyValue object.
     */
    static void writeAttribute(JSONWriter w, Attribute attr) throws IOException {
        w.writeStartObject();
        w.writeKey("key");
        w.writeString(attr.getKey());
        w.writeKey("value");
        writeAnyValue(w, attr);
        w.writeEndObject();
    }

    /**
     * Writes an OTLP AnyValue object for the given attribute.
     */
    static void writeAnyValue(JSONWriter w, Attribute attr) throws IOException {
        w.writeStartObject();
        switch (attr.getType()) {
            case Attribute.TYPE_STRING:
                w.writeKey("stringValue");
                w.writeString(attr.getStringValue());
                break;
            case Attribute.TYPE_BOOL:
                w.writeKey("boolValue");
                w.writeBoolean(attr.getBoolValue());
                break;
            case Attribute.TYPE_INT:
                w.writeKey("intValue");
                w.writeString(Long.toString(attr.getIntValue()));
                break;
            case Attribute.TYPE_DOUBLE:
                w.writeKey("doubleValue");
                w.writeNumber(Double.valueOf(attr.getDoubleValue()));
                break;
        }
        w.writeEndObject();
    }

    /**
     * Writes a KeyValue with a string value, used for resource attributes.
     */
    static void writeStringKeyValue(JSONWriter w, String key, String value) throws IOException {
        w.writeStartObject();
        w.writeKey("key");
        w.writeString(key);
        w.writeKey("value");
        w.writeStartObject();
        w.writeKey("stringValue");
        w.writeString(value);
        w.writeEndObject();
        w.writeEndObject();
    }

}
