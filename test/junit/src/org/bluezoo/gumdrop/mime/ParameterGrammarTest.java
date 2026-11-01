/*
 * ParameterGrammarTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * What the streaming grammar of Content-Type and Content-Disposition makes of
 * a wide set of inputs, taken from rmimeparser's test of the same grammar.
 * Each input is parsed at every chunking and the events are rendered with the
 * usual rule for repeated parameters: the first of a name wins, except that
 * an RFC 2231 extended value replaces a plain one. {@code None} is a header
 * whose head is not valid.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParameterGrammarTest {

    /** {input, expected rendering} */
    private static final String[][] CONTENT_TYPES = {
        { "text/plain",
          "text/plain" },
        { "text/plain; charset=utf-8",
          "text/plain | charset=\"utf-8\"" },
        { "text/plain;charset=utf-8;format=flowed",
          "text/plain | charset=\"utf-8\" | format=\"flowed\"" },
        { "  text/plain  ;  charset = utf-8  ",
          "text/plain | charset=\"utf-8\"" },
        { "TEXT/Plain; CHARSET=UTF-8",
          "TEXT/Plain | CHARSET=\"UTF-8\"" },
        { "text/plain; name=\"a b.txt\"",
          "text/plain | name=\"a b.txt\"" },
        { "text/plain; name=\"a\\\"b\\\\c\"",
          "text/plain | name=\"a\\\"b\\\\c\"" },
        { "text/plain; name= \"spaced quote\"",
          "text/plain | name=\"spaced quote\"" },
        { "text/plain; name=\"unterminated",
          "text/plain | name=\"unterminated\"" },
        { "text/plain; =noname; charset=x",
          "text/plain | charset=\"x\"" },
        { "text/plain; novalue; charset=x",
          "text/plain | charset=\"x\"" },
        { "text/plain; bad name=1; charset=x",
          "text/plain | charset=\"x\"" },
        { "text/plain; charset=x; charset=y",
          "text/plain | charset=\"x\"" },
        { "text/plain;;charset=x;",
          "text/plain | charset=\"x\"" },
        { "text/plain; name=\"=?UTF-8?B?dGVzdC50eHQ=?=\"",
          "text/plain | name=\"test.txt\"" },
        { "text/plain; name==?UTF-8?Q?a_b?=",
          "text/plain | name=\"a b\"" },
        { "text/plain; filename*=UTF-8''na%C3%AFve%20file.txt",
          "text/plain | filename=\"na\u00efve file.txt\"" },
        { "text/plain; filename*=iso-8859-1'en'caf%E9",
          "text/plain | filename=\"caf\u00e9\"" },
        { "text/plain; filename*0=\"abc\"; filename*1=\"def\"",
          "text/plain | filename=\"abcdef\"" },
        { "text/plain; filename*0*=UTF-8''a%20b; filename*1*=%20c; filename*2=d",
          "text/plain | filename=\"a b cd\"" },
        { "text/plain; filename*1=\"def\"; filename*0=\"abc\"",
          "text/plain | filename=\"abc\"" },
        { "text/plain; filename*0*=UTF-8''ab; filename=\"plain.txt\"",
          "text/plain | filename=\"ab\"" },
        { "text/plain; filename=\"plain.txt\"; filename*=UTF-8''ext.txt",
          "text/plain | filename=\"ext.txt\"" },
        { "text/plain; title*=bad",
          "text/plain | title=\"bad\"" },
        { "text/plain; title*=UTF-8''",
          "text/plain | title=\"\"" },
        { "multipart/mixed; boundary=\"b\"",
          "multipart/mixed | boundary=\"b\"" },
        { "multipart/mixed; boundary=b; charset=x",
          "multipart/mixed | boundary=\"b\" | charset=\"x\"" },
        { "text",
          "None" },
        { "text/",
          "None" },
        { "/plain",
          "None" },
        { "te xt/plain",
          "None" },
        { "text/pl ain",
          "None" },
        { "text/plain junk",
          "None" },
        { "text/plain (comment)",
          "None" },
        { "text/plain; name=\"caf\u00c3\u00a9\"",
          "text/plain | name=\"caf\u00e9\"" },
        { "text/plain; name=\"caf\u00e9\"",
          "text/plain | name=\"caf\u00e9\"" },
        { "text/plain; name=a b c",
          "text/plain | name=\"a b c\"" },
        { "text/plain; name=\"x\"junk; k=v",
          "text/plain | name=\"x\" | k=\"v\"" },
        { "text/plain; a=1;b=2;c=3;d=4",
          "text/plain | a=\"1\" | b=\"2\" | c=\"3\" | d=\"4\"" },
        { "text/plain; name=\"\"",
          "text/plain | name=\"\"" },
        { "text/plain; name=",
          "text/plain | name=\"\"" },
        { "text/plain\t;\tcharset=utf-8",
          "text/plain | charset=\"utf-8\"" },
        { "a/b;c=d",
          "a/b | c=\"d\"" },
        { "a/",
          "None" },
        { "",
          "None" },
        { "text/plain; name=\"  padded  \"",
          "text/plain | name=\"padded\"" },
        { "text/plain; name=  padded  ",
          "text/plain | name=\"padded\"" },
        { "text/plain; n*0*=utf-8''%C3; n*1*=%A9",
          "text/plain | n=\"\u00e9\"" },
        { "text/plain; n*0=\"a\"; n*2=\"c\"",
          "text/plain | n=\"a\"" }
    };

    /** {input, expected rendering} */
    private static final String[][] DISPOSITIONS = {
        { "attachment",
          "attachment" },
        { "inline",
          "inline" },
        { "form-data; name=\"f\"; filename=\"x.txt\"",
          "form-data | name=\"f\" | filename=\"x.txt\"" },
        { "attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E.txt",
          "attachment | filename=\"\u65e5\u672c\u8a9e.txt\"" },
        { "attachment;",
          "attachment" },
        { "Attachment ; FileName = \"T.TXT\"",
          "Attachment | FileName=\"T.TXT\"" },
        { "bad type; x=1",
          "None" },
        { "attachment; size=123; creation-date=\"Tue, 6 Oct 2026 10:00:00 +0000\"",
          "attachment | size=\"123\" | creation-date=\"Tue, 6 Oct 2026 10:00:00 +0000\"" },
        { "attachment; filename=\"=?UTF-8?B?dGVzdC50eHQ=?=\"",
          "attachment | filename=\"test.txt\"" },
        { "attachment; filename=C:\\dir\\x.pdf",
          "attachment | filename=\"C:\\\\dir\\\\x.pdf\"" },
        { "",
          "None" },
        { "; filename=x",
          "None" },
        { "attachment; filename*0=\"my \"; filename*1=\"file.txt\"",
          "attachment | filename=\"my file.txt\"" }
    };

    /** Renders the events of a Content-Type or Content-Disposition. */
    static final class Renderer implements MimeHandler {
        String head;
        boolean malformed;
        final List<String> names = new ArrayList<String>();
        final List<StringBuilder> values = new ArrayList<StringBuilder>();
        final List<Boolean> extended = new ArrayList<Boolean>();
        private String name;
        private StringBuilder value;
        private boolean isExtended;

        @Override
        public void setLocator(MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
        }

        @Override
        public void contentType(ContentType contentType) {
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
        }

        @Override
        public void contentTransferEncoding(String encoding) {
        }

        @Override
        public void contentID(ContentID contentID) {
        }

        @Override
        public void contentDescription(String description) {
        }

        @Override
        public void mimeVersion(MimeVersion version) {
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void endEntity(String boundary) {
        }

        @Override
        public void startContentType(String primary, String sub) {
            head = primary + "/" + sub;
        }

        @Override
        public void startContentDisposition(String kind) {
            head = kind;
        }

        @Override
        public void malformedHeader(String headerName, String reason) {
            malformed = true;
        }

        final StringBuilder text = new StringBuilder();
        int ends;

        @Override
        public void headerContent(String content) {
            text.append(content);
        }

        @Override
        public void endHeader() {
            ends++;
        }

        @Override
        public void startParameter(String parameterName, String language) {
            name = parameterName;
            value = new StringBuilder();
            isExtended = language != null;
        }

        @Override
        public void parameterContent(String text) {
            value.append(text);
        }

        @Override
        public void endParameter() {
            int at = -1;
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(name)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                names.add(name);
                values.add(value);
                extended.add(isExtended);
            } else if (isExtended && !extended.get(at)) {
                names.set(at, name);
                values.set(at, value);
                extended.set(at, true);
            }
        }

        String render() {
            if (malformed || head == null) {
                return "None";
            }
            StringBuilder sb = new StringBuilder(head);
            for (int i = 0; i < names.size(); i++) {
                sb.append(" | ").append(names.get(i)).append("=\"")
                    .append(escape(values.get(i).toString())).append('"');
            }
            return sb.toString();
        }
    }

    /** As Rust's Debug formatting of a string, without the surrounding quotes. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String render(String header, String value, int chunk) throws MimeParseException {
        Renderer renderer = new Renderer();
        MimeParser parser = new MimeParser();
        parser.setHandler(renderer);
        byte[] data = (header + ": " + value + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(4096);
        int size = chunk == 0 ? data.length : chunk;
        for (int off = 0; off < data.length; off += size) {
            buf.put(data, off, Math.min(size, data.length - off));
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.receive(buf);
        try {
            parser.close();
        } catch (MimeParseException e) {
            // a multipart header with no body is an unclosed multipart; the
            // header is what counts
        }
        return renderer.render();
    }

    private static void check(String header, String[][] table) throws MimeParseException {
        for (String[] row : table) {
            String input = row[0];
            assertEquals(input, row[1], render(header, input, 0));
            if (input.isEmpty()) {
                continue;
            }
            int total = (header + ": " + input).length() + 4;
            for (int chunk = 1; chunk <= total; chunk++) {
                assertEquals(input + " chunk " + chunk, row[1], render(header, input, chunk));
            }
        }
    }

    @Test
    public void contentTypesAreReadAsExpectedAtEveryChunking() throws Exception {
        check("Content-Type", CONTENT_TYPES);
    }

    @Test
    public void dispositionsAreReadAsExpectedAtEveryChunking() throws Exception {
        check("Content-Disposition", DISPOSITIONS);
    }

    private static Renderer run(String text, int chunk, int maxHeaderValueSize) throws MimeParseException {
        Renderer renderer = new Renderer();
        MimeParser parser = new MimeParser();
        if (maxHeaderValueSize > 0) {
            parser.setMaxHeaderValueSize(maxHeaderValueSize);
        }
        parser.setHandler(renderer);
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(8192);
        int size = chunk == 0 ? data.length : chunk;
        for (int off = 0; off < data.length; off += size) {
            buf.put(data, off, Math.min(size, data.length - off));
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.receive(buf);
        parser.close();
        return renderer;
    }

    @Test
    public void aHeadThatIsNotValidIsDeliveredAsUnstructuredText() throws Exception {
        String text = "Content-Type: not a type;\r\n charset=x\r\n\r\n";
        for (int chunk = 0; chunk <= text.length(); chunk++) {
            Renderer r = run(text, chunk, 0);
            assertEquals("chunk " + chunk, "None", r.render());
            assertEquals("chunk " + chunk, "not a type; charset=x", r.text.toString());
            assertEquals("chunk " + chunk, 1, r.ends);
        }
    }

    @Test
    public void aValidContentTypeIsNotDeliveredAsText() throws Exception {
        Renderer r = run("Content-Type: text/plain; charset=x\r\n\r\n", 0, 0);
        assertEquals("text/plain | charset=\"x\"", r.render());
        assertEquals("", r.text.toString());
        assertEquals(0, r.ends);
    }

    @Test(expected = HeaderValueTooLongException.class)
    public void collectedParametersAreLimitedInSize() throws Exception {
        StringBuilder value = new StringBuilder("Content-Type: text/plain; name=\"");
        for (int line = 0; line < 6; line++) {
            for (int i = 0; i < 500; i++) {
                value.append('a');
            }
            value.append("\r\n ");
        }
        value.append("\"\r\n\r\n");
        // the legacy contentType event carries the whole value
        run(value.toString(), 0, 1000);
    }
}
