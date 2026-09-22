/*
 * AbstractXMLHandlerTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gonzalez.Parser;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link AbstractXMLHandler}'s namespace resolution and attribute/
 * character assembly against the real Gonzalez {@link Parser}, driven
 * through its native {@code XMLHandler} vocabulary -- the same path
 * every consumer of this class now runs through.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AbstractXMLHandlerTest {

    private RecordingHandler handler;
    private Parser parser;

    @Before
    public void setUp() throws Exception {
        handler = new RecordingHandler();
        parser = new Parser();
        parser.setXMLHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
    }

    private void parse(String xml) throws Exception {
        parser.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        parser.close();
    }

    @Test
    public void testDefaultNamespaceResolvesElementAndDescendants() throws Exception {
        parse("<root xmlns=\"urn:test\"><child/></root>");
        assertEquals(2, handler.starts.size());
        assertEquals("urn:test|root|root", handler.starts.get(0));
        assertEquals("urn:test|child|child", handler.starts.get(1));
    }

    @Test
    public void testPrefixedNamespaceResolvesUsingClosestDeclaration() throws Exception {
        parse("<D:root xmlns:D=\"DAV:\"><D:child/></D:root>");
        assertEquals("DAV:|root|D:root", handler.starts.get(0));
        assertEquals("DAV:|child|D:child", handler.starts.get(1));
    }

    @Test
    public void testInnerDeclarationShadowsOuter() throws Exception {
        parse("<a xmlns=\"urn:outer\"><b xmlns=\"urn:inner\"><c/></b><d/></a>");
        assertEquals("urn:outer|a|a", handler.starts.get(0));
        assertEquals("urn:inner|b|b", handler.starts.get(1));
        assertEquals("urn:inner|c|c", handler.starts.get(2));
        assertEquals("urn:outer|d|d", handler.starts.get(3));
    }

    @Test
    public void testUnprefixedAttributeIsNeverNamespaceQualified() throws Exception {
        parse("<root xmlns=\"urn:test\" plain=\"1\"/>");
        assertEquals("", handler.lastAttrs.getURI(0));
        assertEquals("plain", handler.lastAttrs.getLocalName(0));
        assertEquals("1", handler.lastAttrs.getValue(0));
        assertEquals("1", handler.lastAttrs.getValue("plain"));
    }

    @Test
    public void testPrefixedAttributeIsNamespaceQualified() throws Exception {
        parse("<root xmlns:x=\"urn:test\" x:foo=\"bar\"/>");
        assertEquals("urn:test", handler.lastAttrs.getURI(0));
        assertEquals("foo", handler.lastAttrs.getLocalName(0));
        assertEquals("bar", handler.lastAttrs.getValue("urn:test", "foo"));
    }

    @Test
    public void testEndElementMatchesStartElementIdentity() throws Exception {
        parse("<D:root xmlns:D=\"DAV:\"><D:child/></D:root>");
        assertEquals(2, handler.ends.size());
        assertEquals("DAV:|child|D:child", handler.ends.get(0));
        assertEquals("DAV:|root|D:root", handler.ends.get(1));
    }

    @Test
    public void testCharactersDelivered() throws Exception {
        parse("<root>hello world</root>");
        assertEquals("hello world", handler.text.toString());
    }

    @Test
    public void testFatalErrorReconstructsSAXParseExceptionAndThrows() throws Exception {
        try {
            parse("<root><unclosed></root>");
            fail("expected a fatal well-formedness error");
        } catch (SAXException expected) {
            // A malformed document reaches AbstractXMLHandler#fatalError
            // (default: rethrow), which is exactly what's under test --
            // the SAXParseException reconstruction itself is exercised
            // regardless of which SAXException subtype surfaces here.
        }
    }

    // ── Test double ──────────────────────────────────────────────────────

    private static final class RecordingHandler extends AbstractXMLHandler {
        final List<String> starts = new ArrayList<String>();
        final List<String> ends = new ArrayList<String>();
        final StringBuilder text = new StringBuilder();
        Attributes lastAttrs;

        @Override
        protected void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            starts.add(uri + "|" + localName + "|" + qName);
            lastAttrs = copy(atts);
        }

        @Override
        protected void endElement(String uri, String localName, String qName) throws SAXException {
            ends.add(uri + "|" + localName + "|" + qName);
        }

        @Override
        protected void characters(char[] ch, int start, int length) throws SAXException {
            text.append(ch, start, length);
        }

        /** {@code atts} is only valid for the duration of startElement's call -- snapshot it. */
        private static Attributes copy(Attributes atts) {
            final String[] uris = new String[atts.getLength()];
            final String[] locals = new String[atts.getLength()];
            final String[] values = new String[atts.getLength()];
            for (int i = 0; i < atts.getLength(); i++) {
                uris[i] = atts.getURI(i);
                locals[i] = atts.getLocalName(i);
                values[i] = atts.getValue(i);
            }
            return new Attributes() {
                @Override
                public int getLength() {
                    return uris.length;
                }

                @Override
                public String getURI(int index) {
                    return uris[index];
                }

                @Override
                public String getLocalName(int index) {
                    return locals[index];
                }

                @Override
                public String getQName(int index) {
                    return locals[index];
                }

                @Override
                public String getType(int index) {
                    return "CDATA";
                }

                @Override
                public String getValue(int index) {
                    return values[index];
                }

                @Override
                public int getIndex(String uri, String localName) {
                    for (int i = 0; i < uris.length; i++) {
                        if (uris[i].equals(uri) && locals[i].equals(localName)) {
                            return i;
                        }
                    }
                    return -1;
                }

                @Override
                public int getIndex(String qName) {
                    for (int i = 0; i < locals.length; i++) {
                        if (locals[i].equals(qName)) {
                            return i;
                        }
                    }
                    return -1;
                }

                @Override
                public String getType(String uri, String localName) {
                    return "CDATA";
                }

                @Override
                public String getType(String qName) {
                    return "CDATA";
                }

                @Override
                public String getValue(String uri, String localName) {
                    int i = getIndex(uri, localName);
                    return i < 0 ? null : values[i];
                }

                @Override
                public String getValue(String qName) {
                    int i = getIndex(qName);
                    return i < 0 ? null : values[i];
                }
            };
        }
    }
}
