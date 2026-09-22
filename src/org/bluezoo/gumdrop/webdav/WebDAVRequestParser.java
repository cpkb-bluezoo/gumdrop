/*
 * WebDAVRequestParser.java
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

package org.bluezoo.gumdrop.webdav;

import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gumdrop.util.XMLParseUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses WebDAV request XML bodies using the Gonzalez streaming parser.
 *
 * <p>Implements RFC 4918 §14 (XML element definitions).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
 */
class WebDAVRequestParser extends DefaultHandler {

    enum PropfindType { ALLPROP, PROPNAME, PROP }
    enum PropPatchOp { SET, REMOVE }

    /** PROPFIND request body (§14.20 propfind) */
    static class PropfindRequest {
        PropfindType type = PropfindType.ALLPROP;
        final List<PropertyRef> properties = new ArrayList<PropertyRef>();
        final List<PropertyRef> include = new ArrayList<PropertyRef>();
    }

    /** PROPPATCH request body (§14.19 propertyupdate) */
    static class ProppatchRequest {
        final List<PropertyUpdate> updates = new ArrayList<PropertyUpdate>();
    }

    static class PropertyUpdate {
        PropPatchOp operation;
        String namespaceURI;
        String localName;
        String value;
        boolean isXML;
    }

    /** LOCK request body (§14.11 lockinfo) */
    static class LockRequest {
        WebDAVLock.Scope scope = WebDAVLock.Scope.EXCLUSIVE;
        WebDAVLock.Type type = WebDAVLock.Type.WRITE;
        String owner;
    }

    static class PropertyRef {
        String namespaceURI;
        String localName;

        PropertyRef(String namespaceURI, String localName) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
        }
    }

    private final Parser parser;
    private final StringBuilder textContent = new StringBuilder();

    private PropfindRequest propfindRequest;
    private ProppatchRequest proppatchRequest;
    private LockRequest lockRequest;

    private boolean inProp = false;
    private boolean inInclude = false;
    private boolean inSet = false;
    private boolean inRemove = false;
    private boolean inOwner = false;
    private String currentPropNS;
    private String currentPropName;
    private StringBuilder currentPropValue = new StringBuilder();
    private int propValueDepth = 0;

    private String parseError;

    WebDAVRequestParser() {
        this.parser = new Parser();
        this.parser.setContentHandler(this);
        // Block external entity resolution (XXE) for untrusted request bodies.
        this.parser.setEntityResolver(XMLParseUtils.DENY_EXTERNAL_ENTITIES);
        try {
            this.parser.setFeature("http://xml.org/sax/features/namespaces", true);
        } catch (Exception e) {
            // Namespaces enabled by default
        }
    }

    void receive(ByteBuffer data) throws IOException {
        try {
            parser.receive(data);
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (parseError != null) {
            throw new IOException(parseError);
        }
    }

    void close() throws IOException {
        try {
            parser.close();
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (parseError != null) {
            throw new IOException(parseError);
        }
    }

    void reset() {
        try {
            parser.reset();
        } catch (SAXException e) {
            // Ignore reset errors
        }
        textContent.setLength(0);
        propfindRequest = null;
        proppatchRequest = null;
        lockRequest = null;
        inProp = false;
        inInclude = false;
        inSet = false;
        inRemove = false;
        inOwner = false;
        currentPropNS = null;
        currentPropName = null;
        currentPropValue.setLength(0);
        propValueDepth = 0;
        parseError = null;
    }

    PropfindRequest getPropfindRequest() {
        return propfindRequest;
    }

    ProppatchRequest getProppatchRequest() {
        return proppatchRequest;
    }

    LockRequest getLockRequest() {
        return lockRequest;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
        textContent.setLength(0);

        // Check for capturing property value content
        if (currentPropName != null && propValueDepth > 0) {
            propValueDepth++;
            currentPropValue.append('<').append(qName).append('>');
            return;
        }

        if (DavConstants.NAMESPACE.equals(uri)) {
            handleDAVStartElement(localName);
        } else if (inProp || inInclude) {
            handlePropertyElement(uri, localName);
        }
    }

    private void handleDAVStartElement(String local) {
        if (DavConstants.ELEM_PROPFIND.equals(local)) {
            propfindRequest = new PropfindRequest();
        } else if (DavConstants.ELEM_ALLPROP.equals(local)) {
            if (propfindRequest != null) {
                propfindRequest.type = PropfindType.ALLPROP;
            }
        } else if (DavConstants.ELEM_PROPNAME.equals(local)) {
            if (propfindRequest != null) {
                propfindRequest.type = PropfindType.PROPNAME;
            }
        } else if (DavConstants.ELEM_PROP.equals(local)) {
            if (propfindRequest != null) {
                propfindRequest.type = PropfindType.PROP;
            }
            inProp = true;
        } else if (DavConstants.ELEM_INCLUDE.equals(local)) {
            inInclude = true;
        } else if (DavConstants.ELEM_PROPERTYUPDATE.equals(local)) {
            proppatchRequest = new ProppatchRequest();
        } else if (DavConstants.ELEM_SET.equals(local)) {
            inSet = true;
        } else if (DavConstants.ELEM_REMOVE.equals(local)) {
            inRemove = true;
        } else if (DavConstants.ELEM_LOCKINFO.equals(local)) {
            lockRequest = new LockRequest();
        } else if (DavConstants.ELEM_EXCLUSIVE.equals(local)) {
            if (lockRequest != null) {
                lockRequest.scope = WebDAVLock.Scope.EXCLUSIVE;
            }
        } else if (DavConstants.ELEM_SHARED.equals(local)) {
            if (lockRequest != null) {
                lockRequest.scope = WebDAVLock.Scope.SHARED;
            }
        } else if (DavConstants.ELEM_WRITE.equals(local)) {
            if (lockRequest != null) {
                lockRequest.type = WebDAVLock.Type.WRITE;
            }
        } else if (DavConstants.ELEM_OWNER.equals(local) && lockRequest != null) {
            // DAV:owner is overloaded: RFC 4918's lock owner (§14.17,
            // inside LOCK's <D:lockinfo>) and RFC 3744's DAV:owner live
            // property (§5.1, requestable by name in a PROPFIND <D:prop>)
            // share this local name. Only treat it as the lock-owner
            // text container when actually parsing a LOCK request body;
            // otherwise fall through so a PROPFIND request for it is
            // recorded as a requested property like any other.
            inOwner = true;
        } else if (inProp || inInclude) {
            handlePropertyElement(DavConstants.NAMESPACE, local);
        }
    }

    private void handlePropertyElement(String ns, String local) {
        if (inProp && propfindRequest != null) {
            propfindRequest.properties.add(new PropertyRef(ns, local));
        } else if (inInclude && propfindRequest != null) {
            propfindRequest.include.add(new PropertyRef(ns, local));
        } else if ((inSet || inRemove) && proppatchRequest != null) {
            currentPropNS = ns;
            currentPropName = local;
            currentPropValue.setLength(0);
            propValueDepth = 1;
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        // Check for property value content end
        if (currentPropName != null && propValueDepth > 0) {
            propValueDepth--;
            if (propValueDepth > 0) {
                currentPropValue.append("</").append(qName).append('>');
                return;
            }
            finishPropertyUpdate();
            return;
        }

        if (DavConstants.NAMESPACE.equals(uri)) {
            handleDAVEndElement(localName);
        }
    }

    private void handleDAVEndElement(String local) {
        if (DavConstants.ELEM_PROP.equals(local)) {
            inProp = false;
        } else if (DavConstants.ELEM_INCLUDE.equals(local)) {
            inInclude = false;
        } else if (DavConstants.ELEM_SET.equals(local)) {
            inSet = false;
        } else if (DavConstants.ELEM_REMOVE.equals(local)) {
            inRemove = false;
        } else if (DavConstants.ELEM_OWNER.equals(local)) {
            if (lockRequest != null && textContent.length() > 0) {
                lockRequest.owner = textContent.toString().trim();
            }
            inOwner = false;
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (currentPropName != null && propValueDepth > 0) {
            currentPropValue.append(ch, start, length);
        } else if (inOwner) {
            textContent.append(ch, start, length);
        }
    }

    private void finishPropertyUpdate() {
        if (proppatchRequest != null && currentPropName != null) {
            PropertyUpdate update = new PropertyUpdate();
            update.operation = inSet ? PropPatchOp.SET : PropPatchOp.REMOVE;
            update.namespaceURI = currentPropNS;
            update.localName = currentPropName;
            update.value = currentPropValue.toString();
            update.isXML = update.value.contains("<");
            proppatchRequest.updates.add(update);
        }
        currentPropNS = null;
        currentPropName = null;
        currentPropValue.setLength(0);
        propValueDepth = 0;
    }

    @Override
    public void error(SAXParseException e) {
        parseError = "XML parse error: " + e.getMessage();
    }

    @Override
    public void fatalError(SAXParseException e) {
        parseError = "XML fatal error: " + e.getMessage();
    }
}
