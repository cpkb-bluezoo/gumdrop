/*
 * QName.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

/**
 * Represents a qualified name in XML with namespace URI and local name.
 *
 * <p>This class is mutable and designed for object pooling to reduce allocations.
 * QName objects are reused across multiple elements/attributes by updating their values.
 *
 * <p>Two QNames are considered equal if both their namespace URIs and
 * local names are equal. The qualified name (prefix:localName) is not
 * considered in equality checks.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class QName {

    private String uri;
    private String localName;
    private String qName;

    // Cache hash code for performance (recomputed on update)
    private int hash;

    /**
     * Creates a new QName with default empty values (for pooling).
     */
    public QName() {
        this.uri = "";
        this.localName = "";
        this.qName = "";
        this.hash = 0;
    }

    /**
     * Creates a new QName with specified values.
     *
     * @param uri the namespace URI (never null, use "" for no namespace)
     * @param localName the local name (never null)
     * @param qName the qualified name (never null)
     */
    public QName(String uri, String localName, String qName) {
        update(uri, localName, qName);
    }

    /**
     * Updates this QName with new values (for object pooling).
     *
     * @param uri the namespace URI (never null, use "" for no namespace)
     * @param localName the local name (never null)
     * @param qName the qualified name (never null)
     */
    public void update(String uri, String localName, String qName) {
        if (uri == null || localName == null || qName == null) {
            throw new NullPointerException("QName components must not be null");
        }

        this.uri = uri;
        this.localName = localName;
        this.qName = qName;

        // Recompute hash code when values change
        this.hash = computeHash();
    }

    /**
     * Returns the namespace URI.
     *
     * @return the namespace URI (empty string if no namespace)
     */
    public String getURI() {
        return uri;
    }

    /**
     * Returns the local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the qualified name (prefix:localName or just localName).
     *
     * @return the qualified name
     */
    public String getQName() {
        return qName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof QName)) {
            return false;
        }

        QName other = (QName) obj;

        // Compare hash first for fast rejection
        if (this.hash != other.hash) {
            return false;
        }

        // Compare local name first (more likely to differ)
        if (!this.localName.equals(other.localName)) {
            return false;
        }

        // Then URI
        return this.uri.equals(other.uri);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Computes the hash code based on URI and local name.
     */
    private int computeHash() {
        int result = uri.hashCode();
        result = 31 * result + localName.hashCode();
        return result;
    }

    @Override
    public String toString() {
        if (uri.isEmpty()) {
            return localName;
        }
        return "{" + uri + "}" + localName;
    }

}
