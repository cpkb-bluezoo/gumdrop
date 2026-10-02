/*
 * XMLParseUtilsChunkedReadTest.java
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

package org.bluezoo.gumdrop.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link XMLParseUtils#parseStream} reads its source in arbitrary-sized
 * pieces; the push parser leaves bytes it cannot yet decode in the buffer
 * and the reader must retain them across reads (and must not spin forever
 * when the input ends with such bytes).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLParseUtilsChunkedReadTest {

    /** Returns at most {@code max} bytes per read call. */
    private static class DribbleStream extends InputStream {
        private final byte[] data;
        private final int max;
        private int pos;

        DribbleStream(byte[] data, int max) {
            this.data = data;
            this.max = max;
        }

        @Override
        public int read() {
            if (pos >= data.length) {
                return -1;
            }
            int b = data[pos] & 0xFF;
            pos++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, max), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static class CollectingHandler extends AbstractXMLHandler {
        final StringBuilder text = new StringBuilder();
        String root;

        @Override
        protected void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            if (root == null) {
                root = qName;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }
    }

    private static final String DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><r>café €</r>";

    private static void parseDribbled(byte[] doc, int max) throws IOException, SAXException {
        CollectingHandler h = new CollectingHandler();
        XMLParseUtils.parseStream(new DribbleStream(doc, max), h, "test:doc", null);
        assertEquals("chunk " + max, "r", h.root);
        assertEquals("chunk " + max, "café €", h.text.toString());
    }

    @Test(timeout = 20000)
    public void everyReadSizeWithDeclaration() throws Exception {
        byte[] doc = DOC.getBytes(StandardCharsets.UTF_8);
        for (int max = 1; max <= doc.length; max++) {
            parseDribbled(doc, max);
        }
    }

    @Test(timeout = 20000)
    public void everyReadSizeWithBomAndWithoutDeclaration() throws Exception {
        byte[] body = "<r>café €</r>".getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[body.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        for (int max = 1; max <= bom.length; max++) {
            parseDribbled(bom, max);
            parseDribbled(body, max);
        }
    }

    @Test(timeout = 20000)
    public void truncatedInputEndingInUnconsumedBytesFailsInsteadOfHanging() throws Exception {
        byte[] one = new byte[] { '<' };
        CollectingHandler h = new CollectingHandler();
        try {
            XMLParseUtils.parseStream(new ByteArrayInputStream(one), h, "test:doc", null);
            fail("a one byte document is not well formed");
        } catch (SAXException e) {
            // expected
        } catch (RuntimeException e) {
            // the parser may also reject an empty document this way
        }
    }
}
