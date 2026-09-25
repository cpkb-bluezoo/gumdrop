/*
 * Fields.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed access to the positional fields of a decoded composite list,
 * turning absent fields into defaults and wrongly-typed ones into
 * {@link Amqp1ProtocolException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Fields {

    private Fields() {
    }

    static Object get(List<Object> fields, int index) {
        return index < fields.size() ? fields.get(index) : null;
    }

    private static Amqp1ProtocolException bad(String name, String expected, Object v) {
        return new Amqp1ProtocolException("Field '" + name + "' should be "
                + expected + ", got " + v.getClass().getSimpleName());
    }

    static String string(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        throw bad(name, "a string", v);
    }

    static String symbol(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return null;
        }
        if (v instanceof Amqp1Symbol) {
            return ((Amqp1Symbol) v).getValue();
        }
        throw bad(name, "a symbol", v);
    }

    /** Reads an unsigned integer field of any width (ubyte, ushort, uint or ulong). */
    static Long unsigned(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return null;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short) {
            return Long.valueOf(((Number) v).longValue());
        }
        throw bad(name, "an unsigned integer", v);
    }

    static Boolean bool(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        throw bad(name, "a boolean", v);
    }

    static byte[] binary(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        throw bad(name, "binary", v);
    }

    /** Reads a multiple-valued symbol field: absent, a single symbol, or an array of them. */
    static List<String> symbols(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        if (v instanceof Amqp1Symbol) {
            result.add(((Amqp1Symbol) v).getValue());
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (!(o instanceof Amqp1Symbol)) {
                    throw bad(name, "an array of symbols", o == null ? "" : o);
                }
                result.add(((Amqp1Symbol) o).getValue());
            }
        } else {
            throw bad(name, "a symbol or array of symbols", v);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> map(List<Object> fields, int index, String name)
            throws Amqp1ProtocolException {
        Object v = get(fields, index);
        if (v == null) {
            return new LinkedHashMap<Object, Object>();
        }
        if (v instanceof Map) {
            return (Map<Object, Object>) v;
        }
        throw bad(name, "a map", v);
    }

    static <T> T required(T value, String type, String name)
            throws Amqp1ProtocolException {
        if (value == null) {
            throw new Amqp1ProtocolException(type + " is missing mandatory field '" + name + "'");
        }
        return value;
    }
}
