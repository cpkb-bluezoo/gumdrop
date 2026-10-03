/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Table-driven tests for the ByteBuffer {@link ContentTypeParser}: each input
 * is rendered as {@code type/subtype|name=value;...} and compared, covering
 * malformed type and parameter syntax, quoting, and RFC 2231 continuation and
 * extended-value handling. The same inputs are also fed split into two
 * chunks at every position to prove the result does not depend on how the
 * header bytes were delivered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentTypeParserTablesTest {

    private static String render(ContentType t) {
        if (t == null) {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getPrimaryType());
        sb.append('/');
        sb.append(t.getSubType());
        sb.append('|');
        List<Parameter> params = t.getParameters();
        if (params != null) {
            for (Parameter p : params) {
                sb.append(p.getName());
                sb.append('=');
                sb.append(p.getValue());
                sb.append(';');
            }
        }
        return sb.toString();
    }

    private static final String[][] CASES = {
        {"text/plain; a*0=ab; a*1=cd", "text/plain|a=abcd;"},
        {"text/plain; name*=UTF-8''x%20y", "text/plain|name=x y;"},
        {"text/plain;", "text/plain|"},
        {"text/plain; ", "text/plain|"},
        {"text/plain;;;", "text/plain|"},
        {"text/plain; =x", "text/plain|"},
        {"text/plain; a=\"\"", "text/plain|a=;"},
        {"text/plain; a= b ", "text/plain|a=b;"},
        {"text/plain; a = b", "text/plain|a=b;"},
        {"text/plain; a=b; a=c", "text/plain|a=b;"},
        {"text/plain; a*1=x; a*0=y", "text/plain|a=yx;"},
        {"text/plain; a*0*=UTF-8''%41; a*1*=%42", "text/plain|a=AB;"},
        {"text/plain; a*0=\"x\"; a*1=y", "text/plain|a=xy;"},
        {"t/p", "t/p|"},
        {"ab", "NULL"},
        {"text/", "NULL"},
        {"/plain", "NULL"},
        {"text/pl@in", "NULL"},
        {"a/b;c=d", "a/b|c=d;"},
        {"text;a/b", "NULL"},
        {" text/plain", "text/plain|"},
        {"text/plain; a=\"x\\\"y\"", "text/plain|a=x\"y;"},
        {"text/plain; a=\"unterminated", "text/plain|"},
        {"text/plain; a=\"q\"; b=r", "text/plain|a=q;b=r;"},
        {"text/plain; a*=", "text/plain|a=;"},
        {"text/plain; a*0=%41", "text/plain|a=%41;"},
        {"text/plain; a*=us-ascii''abc; a*0=zzz", "text/plain|a=abc;"},
        {"text/plain; a*2=c; a*0=a", "text/plain|a=ac;"},
        {"text/plain; *=x", "text/plain|*=x;"},
        {"text/plain; a*b=c", "text/plain|a*b=c;"},
        {"text/plain; a=\"=?UTF-8?Q?x=C3=A9?=\"", "text/plain|a=x\u00e9;"},
        {"text/plain; a=\"\\\\\"", "text/plain|a=\\;"},
        {"text/plain; a=\"\"\"", "text/plain|a=;"},
        {"text/plain; a*0=\"\"; a*1=\"\"", "text/plain|a=;"},
        {"text/plain; a*0=\"b\"; a*1=\"c", "text/plain|a=b;"},
        {"text/plain; a*0=\"\"", "text/plain|a=;"},
        {"text/plain; a*0*=ISO-8859-1''%E9; a*1=x", "text/plain|a=\u00e9x;"},
        {"text/plain; a*0*=bogus''%41", "text/plain|a=A;"},
        {"text/plain; a*=''x", "text/plain|a=''x;"},
    };

    @Test
    public void table() {
        for (int i = 0; i < CASES.length; i++) {
            String input = CASES[i][0];
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
            ContentType parsed = ContentTypeParser.parse(ByteBuffer.wrap(bytes), decoder);
            assertEquals("input: " + input, CASES[i][1], render(parsed));
        }
    }

    @Test
    public void resultIsIndependentOfBufferWindow() {
        for (int i = 0; i < CASES.length; i++) {
            String input = CASES[i][0];
            byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
            byte[] padded = new byte[bytes.length + 6];
            System.arraycopy(bytes, 0, padded, 3, bytes.length);
            ByteBuffer window = ByteBuffer.wrap(padded, 3, bytes.length);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            ContentType parsed = ContentTypeParser.parse(window, decoder);
            assertEquals("windowed input: " + input, CASES[i][1], render(parsed));
        }
    }

    @Test
    public void nullAndEmptyBuffersAreRejected() {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        assertNull(ContentTypeParser.parse((ByteBuffer) null, decoder));
        assertNull(ContentTypeParser.parse(ByteBuffer.allocate(0), decoder));
    }
}
