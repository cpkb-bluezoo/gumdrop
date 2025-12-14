/*
 * ASN1Type.java
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

/**
 * ASN.1 type constants and tag manipulation utilities.
 *
 * <p>ASN.1 tags are encoded as follows:</p>
 * <pre>
 * Bits 7-6: Class
 *   00 = Universal
 *   01 = Application
 *   10 = Context-specific
 *   11 = Private
 *
 * Bit 5: Primitive/Constructed
 *   0 = Primitive
 *   1 = Constructed
 *
 * Bits 4-0: Tag number (0-30, or 31 for multi-byte tag)
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ASN1Type {

    // Tag classes (bits 7-6)
    /** Universal class (00). */
    public static final int CLASS_UNIVERSAL = 0x00;
    /** Application class (01). */
    public static final int CLASS_APPLICATION = 0x40;
    /** Context-specific class (10). */
    public static final int CLASS_CONTEXT = 0x80;
    /** Private class (11). */
    public static final int CLASS_PRIVATE = 0xC0;

    // Primitive/Constructed flag (bit 5)
    /** Primitive encoding. */
    public static final int PRIMITIVE = 0x00;
    /** Constructed encoding. */
    public static final int CONSTRUCTED = 0x20;

    // Universal type tags
    /** End-of-contents marker. */
    public static final int EOC = 0x00;
    /** Boolean type. */
    public static final int BOOLEAN = 0x01;
    /** Integer type. */
    public static final int INTEGER = 0x02;
    /** Bit string type. */
    public static final int BIT_STRING = 0x03;
    /** Octet string type. */
    public static final int OCTET_STRING = 0x04;
    /** Null type. */
    public static final int NULL = 0x05;
    /** Object identifier type. */
    public static final int OBJECT_IDENTIFIER = 0x06;
    /** Object descriptor type. */
    public static final int OBJECT_DESCRIPTOR = 0x07;
    /** External type. */
    public static final int EXTERNAL = 0x08;
    /** Real (floating point) type. */
    public static final int REAL = 0x09;
    /** Enumerated type. */
    public static final int ENUMERATED = 0x0A;
    /** Embedded PDV type. */
    public static final int EMBEDDED_PDV = 0x0B;
    /** UTF-8 string type. */
    public static final int UTF8_STRING = 0x0C;
    /** Relative OID type. */
    public static final int RELATIVE_OID = 0x0D;
    /** Sequence type (constructed). */
    public static final int SEQUENCE = 0x30;
    /** Set type (constructed). */
    public static final int SET = 0x31;
    /** Numeric string type. */
    public static final int NUMERIC_STRING = 0x12;
    /** Printable string type. */
    public static final int PRINTABLE_STRING = 0x13;
    /** T61 (Teletex) string type. */
    public static final int T61_STRING = 0x14;
    /** Videotex string type. */
    public static final int VIDEOTEX_STRING = 0x15;
    /** IA5 string type. */
    public static final int IA5_STRING = 0x16;
    /** UTC time type. */
    public static final int UTC_TIME = 0x17;
    /** Generalized time type. */
    public static final int GENERALIZED_TIME = 0x18;
    /** Graphic string type. */
    public static final int GRAPHIC_STRING = 0x19;
    /** Visible (ISO646) string type. */
    public static final int VISIBLE_STRING = 0x1A;
    /** General string type. */
    public static final int GENERAL_STRING = 0x1B;
    /** Universal string type. */
    public static final int UNIVERSAL_STRING = 0x1C;
    /** Character string type. */
    public static final int CHARACTER_STRING = 0x1D;
    /** BMP (Basic Multilingual Plane) string type. */
    public static final int BMP_STRING = 0x1E;

    // Mask constants
    private static final int CLASS_MASK = 0xC0;
    private static final int CONSTRUCTED_MASK = 0x20;
    private static final int TAG_MASK = 0x1F;

    private ASN1Type() {
    }

    /**
     * Returns the class of the given tag.
     *
     * @param tag the tag byte
     * @return the class (CLASS_UNIVERSAL, CLASS_APPLICATION, etc.)
     */
    public static int getTagClass(int tag) {
        return tag & CLASS_MASK;
    }

    /**
     * Returns whether the tag indicates a constructed type.
     *
     * @param tag the tag byte
     * @return true if constructed, false if primitive
     */
    public static boolean isConstructed(int tag) {
        return (tag & CONSTRUCTED_MASK) != 0;
    }

    /**
     * Returns the tag number portion of the tag.
     *
     * @param tag the tag byte
     * @return the tag number (0-30, or 31 for multi-byte)
     */
    public static int getTagNumber(int tag) {
        return tag & TAG_MASK;
    }

    /**
     * Creates a context-specific tag.
     *
     * @param tagNumber the tag number (0-30)
     * @param constructed whether this is a constructed type
     * @return the complete tag byte
     */
    public static int contextTag(int tagNumber, boolean constructed) {
        return CLASS_CONTEXT | (constructed ? CONSTRUCTED : PRIMITIVE) | tagNumber;
    }

    /**
     * Creates an application-specific tag.
     *
     * @param tagNumber the tag number (0-30)
     * @param constructed whether this is a constructed type
     * @return the complete tag byte
     */
    public static int applicationTag(int tagNumber, boolean constructed) {
        return CLASS_APPLICATION | (constructed ? CONSTRUCTED : PRIMITIVE) | tagNumber;
    }

    /**
     * Returns a human-readable name for the tag.
     *
     * @param tag the tag byte
     * @return a descriptive name
     */
    public static String getTagName(int tag) {
        int tagClass = getTagClass(tag);
        if (tagClass == CLASS_UNIVERSAL) {
            switch (tag & ~CONSTRUCTED_MASK) {
                case BOOLEAN:
                    return "BOOLEAN";
                case INTEGER:
                    return "INTEGER";
                case BIT_STRING:
                    return "BIT STRING";
                case OCTET_STRING:
                    return "OCTET STRING";
                case NULL:
                    return "NULL";
                case OBJECT_IDENTIFIER:
                    return "OBJECT IDENTIFIER";
                case ENUMERATED:
                    return "ENUMERATED";
                case UTF8_STRING:
                    return "UTF8String";
                case SEQUENCE & TAG_MASK:
                    return "SEQUENCE";
                case SET & TAG_MASK:
                    return "SET";
                case PRINTABLE_STRING:
                    return "PrintableString";
                case IA5_STRING:
                    return "IA5String";
                case UTC_TIME:
                    return "UTCTime";
                case GENERALIZED_TIME:
                    return "GeneralizedTime";
                default:
                    return "UNIVERSAL " + getTagNumber(tag);
            }
        } else {
            String className;
            switch (tagClass) {
                case CLASS_APPLICATION:
                    className = "APPLICATION";
                    break;
                case CLASS_CONTEXT:
                    className = "CONTEXT";
                    break;
                case CLASS_PRIVATE:
                    className = "PRIVATE";
                    break;
                default:
                    className = "UNKNOWN";
            }
            return className + " " + getTagNumber(tag) +
                   (isConstructed(tag) ? " (constructed)" : " (primitive)");
        }
    }
}
