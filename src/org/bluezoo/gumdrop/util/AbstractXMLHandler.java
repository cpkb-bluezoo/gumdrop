/*
 * AbstractXMLHandler.java
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

import java.nio.CharBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.XMLHandler;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Base {@link XMLHandler} that resolves namespace-qualified element/
 * attribute identity ({@code uri}, {@code localName}, {@code qName}) and
 * assembles streamed attribute values into complete {@link String}s --
 * the same shapes {@link org.xml.sax.ContentHandler}/
 * {@link org.xml.sax.ErrorHandler} give -- without paying for
 * {@code SAXAdapter}'s SAX-conformance machinery (its own attribute/
 * prefix-stack pooling, {@code Attributes2} wrapping, string interning,
 * configurable {@code xmlns}-URI reporting) that a consumer talking
 * directly to its own {@link org.bluezoo.gonzalez.Parser} never needed
 * in the first place -- see {@link XMLHandler}'s own class Javadoc for
 * why the native vocabulary is cheaper.
 *
 * <p>A subclass overrides {@link #startElement(String, String, String,
 * Attributes)}, {@link #endElement(String, String, String)}, and
 * {@link #characters(char[], int, int)} -- the same shapes and, for
 * {@code characters}, the same per-chunk-call contract (this class does
 * no coalescing of its own; a run split across multiple {@link
 * #characters(CharBuffer, boolean, boolean)} calls is delivered as that
 * many separate calls here too, exactly as SAX's own {@code
 * characters()} already never guarantees single-call delivery) an
 * {@code org.xml.sax.helpers.DefaultHandler} subclass already expects,
 * so porting one over is a matter of changing what it extends, not
 * rewriting its logic. {@link #error(SAXParseException)}/{@link
 * #fatalError(SAXParseException)} give the same for SAX's {@code
 * ErrorHandler}, using this class's {@link #setLocator(Locator)} to
 * reconstruct the {@link SAXParseException} {@link XMLHandler#error(
 * String)}/{@link XMLHandler#fatalError(String)} no longer carry one
 * for. The DTD-related methods ({@link #startDTD}, {@link #endDTD},
 * {@link #startEntity}, {@link #endEntity}, {@link #startCDATA}, {@link
 * #endCDATA}) already match {@code org.xml.sax.ext.LexicalHandler}'s
 * own shape one-for-one -- override them directly, no adaptation
 * needed.
 *
 * <p>Not thread-safe, and not reentrant across documents: one instance
 * parses one document at a time, exactly like the {@code DefaultHandler}
 * subclasses this replaces.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class AbstractXMLHandler implements XMLHandler {

    /** One open element's resolved identity plus the prefix bindings it declared (if any), popped on the matching {@link #endElement()}. */
    private static final class Frame {
        final String qName;
        final String uri;
        final String localName;
        final Map<String, String> bindings;

        Frame(String qName, String uri, String localName, Map<String, String> bindings) {
            this.qName = qName;
            this.uri = uri;
            this.localName = localName;
            this.bindings = bindings;
        }
    }

    private Locator locator;
    private final ArrayDeque<Frame> stack = new ArrayDeque<Frame>();

    // Namespace declarations reported for the element currently being
    // started, not yet pushed as a Frame (endAttributes() does that).
    private Map<String, String> pendingBindings;
    private String pendingQName;
    private final MutableAttributes pendingAttributes = new MutableAttributes();

    // Set for the duration of one attribute's value, between
    // startAttribute() and the attributeValueContent() call with end=true.
    private String pendingAttrName;
    private String pendingAttrType;
    private final StringBuilder attrValueBuilder = new StringBuilder();

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public void setXml11(boolean xml11) {
        // No XML-1.1-specific behaviour in any current consumer.
    }

    @Override
    public void startDocument() throws SAXException {
    }

    @Override
    public void endDocument() throws SAXException {
    }

    @Override
    public final void startElement(String qName) throws SAXException {
        pendingQName = qName;
        pendingBindings = null;
        pendingAttributes.clear();
    }

    @Override
    public final void namespace(String prefix, String uri) throws SAXException {
        if (pendingBindings == null) {
            pendingBindings = new HashMap<String, String>();
        }
        pendingBindings.put(prefix, uri);
    }

    @Override
    public void startAttribute(String name, String type, boolean declared, boolean specified) throws SAXException {
        pendingAttrName = name;
        pendingAttrType = type;
        attrValueBuilder.setLength(0);
    }

    @Override
    public final void attributeValueContent(CharBuffer value, boolean end) throws SAXException {
        attrValueBuilder.append(value);
        if (end) {
            String[] parts = splitQName(pendingAttrName);
            String uri = resolveUri(parts[0], false);
            pendingAttributes.add(uri, parts[1], pendingAttrName, pendingAttrType, attrValueBuilder.toString());
            pendingAttrName = null;
            pendingAttrType = null;
            attrValueBuilder.setLength(0);
        }
    }

    @Override
    public final void endAttributes() throws SAXException {
        String[] parts = splitQName(pendingQName);
        String uri = resolveUri(parts[0], true);
        stack.push(new Frame(pendingQName, uri, parts[1], pendingBindings));
        startElement(uri, parts[1], pendingQName, pendingAttributes);
        pendingQName = null;
        pendingBindings = null;
    }

    /**
     * Signals the start of an element, once its attributes and namespace
     * declarations are fully known -- the same moment {@link
     * org.xml.sax.ContentHandler#startElement} fires.
     *
     * @param uri the element's namespace URI, or the empty string if none
     * @param localName the element's local name
     * @param qName the element's raw qualified name (may contain a prefix)
     * @param atts the element's attributes; valid only for the duration
     *             of this call -- copy any value still needed afterwards
     */
    protected abstract void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException;

    /**
     * Routes both ordinary and DTD-ignorable ({@code ignorable=true})
     * runs to {@link #characters(char[], int, int)} -- unlike SAX, which
     * splits these into {@code ContentHandler.characters}/{@code
     * ignorableWhitespace}. {@code ignorable} can only ever be true when
     * an internal DTD subset has declared the current element's content
     * as element-only (see {@link XMLHandler#characters}); no consumer
     * of this class parses a document with an internal subset at all --
     * several explicitly reject any {@code DOCTYPE} outright as an XXE/
     * DoS precaution -- so this never actually diverges from SAX's own
     * behaviour for any real gumdrop-parsed document.
     */
    @Override
    public final void characters(CharBuffer text, boolean ignorable, boolean end) throws SAXException {
        if (text.hasArray()) {
            characters(text.array(), text.arrayOffset() + text.position(), text.remaining());
        } else {
            char[] chars = new char[text.remaining()];
            text.get(chars);
            characters(chars, 0, chars.length);
        }
    }

    /**
     * Reports a chunk of character data -- the same shape and per-chunk
     * delivery contract as {@link org.xml.sax.ContentHandler#characters}
     * (this class does no coalescing across chunks; see the class
     * Javadoc).
     *
     * @param ch the characters; valid only for the duration of this call
     * @param start the start offset within {@code ch}
     * @param length the number of characters
     */
    protected void characters(char[] ch, int start, int length) throws SAXException {
        // Most consumers only care about element/attribute structure.
    }

    @Override
    public final void endElement() throws SAXException {
        Frame frame = stack.pop();
        endElement(frame.uri, frame.localName, frame.qName);
    }

    /**
     * Signals the end of an element -- the same shape as {@link
     * org.xml.sax.ContentHandler#endElement}.
     *
     * @param uri the element's namespace URI, or the empty string if none
     * @param localName the element's local name
     * @param qName the element's raw qualified name (may contain a prefix)
     */
    protected void endElement(String uri, String localName, String qName) throws SAXException {
    }

    @Override
    public final SAXException fatalError(String message) throws SAXException {
        fatalError(new SAXParseException(message, locator));
        return new SAXException(message);
    }

    /**
     * Reports a fatal parse error -- the same shape as {@link
     * org.xml.sax.ErrorHandler#fatalError}. The default implementation
     * re-throws, matching {@code DefaultHandler}'s own default.
     *
     * @param e the error, reconstructed from the message {@link
     *          XMLHandler#fatalError(String)} gives plus this handler's
     *          current {@link #setLocator locator}
     */
    protected void fatalError(SAXParseException e) throws SAXException {
        throw e;
    }

    @Override
    public final void error(String message) throws SAXException {
        error(new SAXParseException(message, locator));
    }

    /**
     * Reports a recoverable parse error -- the same shape as {@link
     * org.xml.sax.ErrorHandler#error}. The default implementation does
     * nothing, matching {@code DefaultHandler}'s own default (most
     * consumers only ever see this for a validity constraint, since
     * validation is off unless explicitly enabled).
     *
     * @param e the error, reconstructed from the message {@link
     *          XMLHandler#error(String)} gives plus this handler's
     *          current {@link #setLocator locator}
     */
    protected void error(SAXParseException e) throws SAXException {
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
    }

    @Override
    public void endDTD() throws SAXException {
    }

    @Override
    public void startEntity(String name) throws SAXException {
    }

    @Override
    public void endEntity(String name) throws SAXException {
    }

    @Override
    public void startCDATA() throws SAXException {
    }

    @Override
    public void endCDATA() throws SAXException {
    }

    @Override
    public void startComment() throws SAXException {
    }

    @Override
    public void commentData(CharBuffer text, boolean end) throws SAXException {
    }

    @Override
    public void notationDecl(String name, String publicId, String systemId) throws SAXException {
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName)
            throws SAXException {
    }

    @Override
    public void piTarget(String target) throws SAXException {
    }

    @Override
    public void piData(CharBuffer data, boolean end) throws SAXException {
    }

    @Override
    public void saveBuffers() throws SAXException {
        // No consumer defers copying past this call.
    }

    /**
     * Resolves a prefix to its currently-bound namespace URI by walking
     * {@link #stack} outward, then falling back to the well-known {@code
     * xml}/{@code xmlns} bindings XML itself predeclares.
     *
     * @param prefix the prefix (empty string for the default namespace)
     * @param isElement true if resolving an element's prefix (the empty
     *                  prefix resolves against the default namespace);
     *                  false for an attribute's (an unprefixed attribute
     *                  is never namespace-qualified, XML Namespaces 1.0
     *                  §5.2 -- only {@code xml}, never the default one)
     * @return the resolved URI, or the empty string if unbound
     */
    private String resolveUri(String prefix, boolean isElement) {
        if (!isElement && prefix.isEmpty()) {
            return "";
        }
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        // pendingBindings (declared on the element currently being
        // started, not yet pushed onto stack) is the innermost scope --
        // checked first so a xmlns declaration on this element itself
        // takes priority over any ancestor's, exactly as it must for
        // this element's own name and attributes.
        if (pendingBindings != null) {
            String uri = pendingBindings.get(prefix);
            if (uri != null) {
                return uri;
            }
        }
        for (Frame frame : stack) {
            if (frame.bindings != null) {
                String uri = frame.bindings.get(prefix);
                if (uri != null) {
                    return uri;
                }
            }
        }
        return "";
    }

    private static String[] splitQName(String qName) {
        int colon = qName.indexOf(':');
        return colon < 0
                ? new String[] { "", qName }
                : new String[] { qName.substring(0, colon), qName.substring(colon + 1) };
    }

    /**
     * A reusable, mutable {@link Attributes} backing {@link
     * #startElement(String, String, String, Attributes)}'s {@code atts}
     * parameter -- built up once per element from {@link
     * #attributeValueContent} calls and cleared for reuse on the next
     * element, so a document with many elements doesn't allocate one
     * collection per element the way {@code SAXAdapter}'s own attribute
     * pooling still does per-use (it pools the entries, not the
     * enclosing collection).
     */
    private static final class MutableAttributes implements Attributes {
        private final List<String> uris = new ArrayList<String>();
        private final List<String> localNames = new ArrayList<String>();
        private final List<String> qNames = new ArrayList<String>();
        private final List<String> types = new ArrayList<String>();
        private final List<String> values = new ArrayList<String>();

        void clear() {
            uris.clear();
            localNames.clear();
            qNames.clear();
            types.clear();
            values.clear();
        }

        void add(String uri, String localName, String qName, String type, String value) {
            uris.add(uri);
            localNames.add(localName);
            qNames.add(qName);
            types.add(type);
            values.add(value);
        }

        @Override
        public int getLength() {
            return values.size();
        }

        @Override
        public String getURI(int index) {
            return valid(index) ? uris.get(index) : null;
        }

        @Override
        public String getLocalName(int index) {
            return valid(index) ? localNames.get(index) : null;
        }

        @Override
        public String getQName(int index) {
            return valid(index) ? qNames.get(index) : null;
        }

        @Override
        public String getType(int index) {
            return valid(index) ? types.get(index) : null;
        }

        @Override
        public String getValue(int index) {
            return valid(index) ? values.get(index) : null;
        }

        @Override
        public int getIndex(String uri, String localName) {
            for (int i = 0; i < uris.size(); i++) {
                if (uris.get(i).equals(uri) && localNames.get(i).equals(localName)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getIndex(String qName) {
            return qNames.indexOf(qName);
        }

        @Override
        public String getType(String uri, String localName) {
            int i = getIndex(uri, localName);
            return i < 0 ? null : types.get(i);
        }

        @Override
        public String getType(String qName) {
            int i = getIndex(qName);
            return i < 0 ? null : types.get(i);
        }

        @Override
        public String getValue(String uri, String localName) {
            int i = getIndex(uri, localName);
            return i < 0 ? null : values.get(i);
        }

        @Override
        public String getValue(String qName) {
            int i = getIndex(qName);
            return i < 0 ? null : values.get(i);
        }

        private boolean valid(int index) {
            return index >= 0 && index < values.size();
        }
    }
}
