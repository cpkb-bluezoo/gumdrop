/*
 * ProtoSmallTypesTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.grpc.proto;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FieldType} classification, the no-op
 * {@link ProtoDefaultHandler} and {@link ProtoParseException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoSmallTypesTest {

    @Test
    public void scalarClassification() {
        for (FieldType t : FieldType.values()) {
            boolean composite = t == FieldType.MESSAGE || t == FieldType.ENUM || t == FieldType.MAP;
            assertEquals(t.toString(), !composite, t.isScalar());
        }
    }

    @Test
    public void wireTypeClassification() {
        assertTrue(FieldType.INT32.isVarint());
        assertTrue(FieldType.SINT64.isVarint());
        assertTrue(FieldType.BOOL.isVarint());
        assertTrue(FieldType.ENUM.isVarint());
        assertFalse(FieldType.STRING.isVarint());

        assertTrue(FieldType.DOUBLE.isFixed64());
        assertTrue(FieldType.FIXED64.isFixed64());
        assertTrue(FieldType.SFIXED64.isFixed64());
        assertFalse(FieldType.FLOAT.isFixed64());

        assertTrue(FieldType.FLOAT.isFixed32());
        assertTrue(FieldType.FIXED32.isFixed32());
        assertTrue(FieldType.SFIXED32.isFixed32());
        assertFalse(FieldType.DOUBLE.isFixed32());

        assertTrue(FieldType.STRING.isLengthDelimited());
        assertTrue(FieldType.BYTES.isLengthDelimited());
        assertTrue(FieldType.MESSAGE.isLengthDelimited());
        assertTrue(FieldType.MAP.isLengthDelimited());
        assertFalse(FieldType.INT32.isLengthDelimited());
    }

    @Test
    public void everyTypeHasExactlyOneWireClass() {
        for (FieldType t : FieldType.values()) {
            int classes = 0;
            if (t.isVarint()) {
                classes++;
            }
            if (t.isFixed32()) {
                classes++;
            }
            if (t.isFixed64()) {
                classes++;
            }
            if (t.isLengthDelimited()) {
                classes++;
            }
            assertEquals(t.toString(), 1, classes);
        }
    }

    @Test
    public void defaultHandlerIgnoresEverything() throws Exception {
        ProtoDefaultHandler handler = new ProtoDefaultHandler();
        handler.setLocator(null);
        handler.startMessage("pkg.Msg");
        handler.startField("f", "pkg.Nested");
        handler.field("g", Integer.valueOf(1));
        handler.endField();
        handler.endMessage();
    }

    @Test
    public void parseExceptionConstructors() {
        RuntimeException cause = new RuntimeException("c");
        assertEquals("m", new ProtoParseException("m").getMessage());
        ProtoParseException withCause = new ProtoParseException("m", cause);
        assertSame(cause, withCause.getCause());
    }
}
