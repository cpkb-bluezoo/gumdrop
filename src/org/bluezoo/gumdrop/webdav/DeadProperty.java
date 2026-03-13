/*
 * DeadProperty.java
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

/**
 * A WebDAV dead (custom) property.
 * RFC 4918 section 4: dead properties are stored and retrieved
 * verbatim by the server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DeadProperty {

    private final String namespaceURI;
    private final String localName;
    private final String value;
    private final boolean isXML;

    DeadProperty(String namespaceURI, String localName,
                 String value, boolean isXML) {
        this.namespaceURI = namespaceURI != null ? namespaceURI : "";
        this.localName = localName;
        this.value = value;
        this.isXML = isXML;
    }

    String getNamespaceURI() {
        return namespaceURI;
    }

    String getLocalName() {
        return localName;
    }

    String getValue() {
        return value;
    }

    boolean isXML() {
        return isXML;
    }

    /**
     * Returns a unique key for this property: {@code {ns}localName}.
     */
    String getKey() {
        return makeKey(namespaceURI, localName);
    }

    static String makeKey(String ns, String name) {
        if (ns == null || ns.isEmpty()) {
            return name;
        }
        return "{" + ns + "}" + name;
    }

}
