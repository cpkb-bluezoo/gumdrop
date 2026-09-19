/*
 * MimeParameterAndExceptionTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Parameter#toHeaderValue()} encoding and
 * {@link MimeParseException} constructors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParameterAndExceptionTest {

    private static final class FixedLocator implements MimeLocator {
        @Override
        public long getOffset() {
            return 42L;
        }

        @Override
        public long getLineNumber() {
            return 3L;
        }

        @Override
        public long getColumnNumber() {
            return 7L;
        }
    }

    @Test
    public void tokenValueIsUnquoted() {
        Parameter p = new Parameter("charset", "utf-8");
        assertEquals("charset=utf-8", p.toHeaderValue());
    }

    @Test
    public void nonTokenAsciiValueIsQuotedAndEscaped() {
        Parameter p = new Parameter("filename", "my \"file\" \\ name.txt");
        assertEquals("filename=\"my \\\"file\\\" \\\\ name.txt\"", p.toHeaderValue());
    }

    @Test
    public void nonAsciiValueUsesRfc2231Encoding() {
        Parameter p = new Parameter("filename", "caf\u00e9 ~x");
        assertEquals("filename*=UTF-8''caf%C3%A9%20~x", p.toHeaderValue());
    }

    @Test
    public void equalityIgnoresNameCase() {
        Parameter a = new Parameter("Charset", "x");
        Parameter b = new Parameter("charset", "x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(new Parameter("charset", "y")));
        assertFalse(a.equals("charset=x"));
        assertEquals("Charset=x", a.toString());
    }

    @Test(expected = NullPointerException.class)
    public void nullNameRejected() {
        new Parameter(null, "x");
    }

    @Test(expected = NullPointerException.class)
    public void nullValueRejected() {
        new Parameter("x", null);
    }

    @Test
    public void exceptionWithoutLocator() {
        MimeParseException e = new MimeParseException("bad");
        assertEquals("bad", e.getMessage());
        assertEquals(-1L, e.getOffset());
        assertEquals(-1L, e.getLineNumber());
        assertEquals(-1L, e.getColumnNumber());
    }

    @Test
    public void exceptionWithLocatorFormatsPosition() {
        MimeParseException e = new MimeParseException("bad", new FixedLocator());
        assertEquals("bad (line 3, column 7, offset 42)", e.getMessage());
        assertEquals(42L, e.getOffset());
        assertEquals(3L, e.getLineNumber());
        assertEquals(7L, e.getColumnNumber());
    }

    @Test
    public void exceptionWithNullLocator() {
        MimeLocator none = null;
        MimeParseException e = new MimeParseException("bad", none);
        assertEquals("bad", e.getMessage());
        assertEquals(-1L, e.getOffset());
    }

    @Test
    public void exceptionWrappingCause() {
        RuntimeException cause = new RuntimeException("root");
        MimeParseException a = new MimeParseException(cause);
        assertSame(cause, a.getCause());
        MimeParseException b = new MimeParseException("msg", cause);
        assertSame(cause, b.getCause());
        assertEquals("msg", b.getMessage());
        assertEquals(-1L, b.getLineNumber());
    }
}
