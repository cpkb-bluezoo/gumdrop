/*
 * ASN1Element.java
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

package org.bluezoo.gumdrop.ldap.asn1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a decoded ASN.1 element.
 *
 * <p>An ASN.1 element consists of a tag, length, and value. For constructed
 * types, the value contains child elements. For primitive types, the value
 * contains raw bytes.</p>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ASN1Element {

    private final int tag;
    private final byte[] value;
    private final List<ASN1Element> children;

    /**
     * Creates a primitive element.
     *
     * @param tag the tag byte
     * @param value the raw value bytes
     */
    public ASN1Element(int tag, byte[] value) {
        this.tag = tag;
        this.value = value;
        this.children = null;
    }

    /**
     * Creates a constructed element.
     *
     * @param tag the tag byte
     * @param children the child elements
     */
    public ASN1Element(int tag, List<ASN1Element> children) {
        this.tag = tag;
        this.value = null;
        this.children = new ArrayList<ASN1Element>(children);
    }

    /**
     * Returns the tag byte.
     *
     * @return the tag
     */
    public int getTag() {
        return tag;
    }

    /**
     * Returns the tag class.
     *
     * @return CLASS_UNIVERSAL, CLASS_APPLICATION, CLASS_CONTEXT, or CLASS_PRIVATE
     */
    public int getTagClass() {
        return ASN1Type.getTagClass(tag);
    }

    /**
     * Returns the tag number.
     *
     * @return the tag number (0-30)
     */
    public int getTagNumber() {
        return ASN1Type.getTagNumber(tag);
    }

    /**
     * Returns whether this element is constructed.
     *
     * @return true if constructed, false if primitive
     */
    public boolean isConstructed() {
        return ASN1Type.isConstructed(tag);
    }

    /**
     * Returns the raw value bytes.
     *
     * @return the value bytes, or null for constructed elements
     */
    public byte[] getValue() {
        return value;
    }

    /**
     * Returns the child elements.
     *
     * @return unmodifiable list of children, or null for primitive elements
     */
    public List<ASN1Element> getChildren() {
        return children != null ? Collections.unmodifiableList(children) : null;
    }

    /**
     * Returns the number of children.
     *
     * @return the child count, or 0 for primitive elements
     */
    public int getChildCount() {
        return children != null ? children.size() : 0;
    }

    /**
     * Returns a specific child element.
     *
     * @param index the child index
     * @return the child element
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public ASN1Element getChild(int index) {
        if (children == null) {
            throw new IndexOutOfBoundsException("Primitive element has no children");
        }
        return children.get(index);
    }

    // Convenience value accessors

    /**
     * Returns the value as a boolean.
     *
     * @return the boolean value
     * @throws ASN1Exception if not a valid boolean
     */
    public boolean asBoolean() throws ASN1Exception {
        if (value == null || value.length != 1) {
            throw new ASN1Exception("Invalid BOOLEAN encoding");
        }
        return value[0] != 0;
    }

    /**
     * Returns the value as an integer.
     *
     * @return the integer value
     * @throws ASN1Exception if not a valid integer
     */
    public int asInt() throws ASN1Exception {
        if (value == null || value.length == 0 || value.length > 4) {
            throw new ASN1Exception("Invalid INTEGER encoding");
        }
        int result = 0;
        for (int i = 0; i < value.length; i++) {
            result = (result << 8) | (value[i] & 0xFF);
        }
        // Sign extension
        if ((value[0] & 0x80) != 0) {
            for (int i = value.length; i < 4; i++) {
                result |= (0xFF << (i * 8));
            }
        }
        return result;
    }

    /**
     * Returns the value as a long integer.
     *
     * @return the long value
     * @throws ASN1Exception if not a valid integer
     */
    public long asLong() throws ASN1Exception {
        if (value == null || value.length == 0 || value.length > 8) {
            throw new ASN1Exception("Invalid INTEGER encoding");
        }
        long result = 0;
        for (int i = 0; i < value.length; i++) {
            result = (result << 8) | (value[i] & 0xFF);
        }
        // Sign extension
        if ((value[0] & 0x80) != 0) {
            for (int i = value.length; i < 8; i++) {
                result |= (0xFFL << (i * 8));
            }
        }
        return result;
    }

    /**
     * Returns the value as a UTF-8 string.
     *
     * @return the string value
     */
    public String asString() {
        if (value == null) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    /**
     * Returns the value as an octet string (raw bytes).
     *
     * @return the byte array value
     */
    public byte[] asOctetString() {
        return value;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    private void toString(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        sb.append(ASN1Type.getTagName(tag));
        if (isConstructed() && children != null) {
            sb.append(" {\n");
            for (ASN1Element child : children) {
                child.toString(sb, indent + 1);
            }
            for (int i = 0; i < indent; i++) {
                sb.append("  ");
            }
            sb.append("}\n");
        } else if (value != null) {
            sb.append(" = ");
            if (value.length <= 32) {
                // Try to display as string if printable
                boolean printable = true;
                for (byte b : value) {
                    if (b < 0x20 || b > 0x7E) {
                        printable = false;
                        break;
                    }
                }
                if (printable) {
                    sb.append('"').append(asString()).append('"');
                } else {
                    sb.append(hexDump(value));
                }
            } else {
                sb.append("[").append(value.length).append(" bytes]");
            }
            sb.append("\n");
        } else {
            sb.append("\n");
        }
    }

    private static String hexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}
