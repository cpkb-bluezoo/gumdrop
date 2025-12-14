/*
 * BEREncoder.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * BER (Basic Encoding Rules) encoder for ASN.1 data.
 *
 * <p>This encoder produces BER-encoded data suitable for LDAP protocol
 * messages. It uses definite-length encoding for all elements.</p>
 *
 * <h4>Usage Example</h4>
 * <pre>{@code
 * BEREncoder encoder = new BEREncoder();
 *
 * // Encode an LDAP message sequence
 * encoder.beginSequence();
 * encoder.writeInteger(messageId);
 * encoder.beginContext(0, true);  // BindRequest
 * encoder.writeInteger(3);        // LDAP version
 * encoder.writeOctetString(dn);   // DN
 * encoder.beginContext(0, false); // Simple auth
 * encoder.writeOctetString(password);
 * encoder.endContext();
 * encoder.endContext();
 * encoder.endSequence();
 *
 * ByteBuffer data = encoder.toByteBuffer();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BEREncoder {

    private final ByteArrayOutputStream output;
    
    // Stack for nested constructs (sequence, set, context)
    private static final int MAX_DEPTH = 32;
    private final ByteArrayOutputStream[] stack;
    private int stackDepth;

    /**
     * Creates a new BER encoder.
     */
    public BEREncoder() {
        output = new ByteArrayOutputStream();
        stack = new ByteArrayOutputStream[MAX_DEPTH];
        stackDepth = 0;
    }

    /**
     * Resets the encoder for reuse.
     */
    public void reset() {
        output.reset();
        stackDepth = 0;
    }

    /**
     * Returns the encoded data as a byte array.
     *
     * @return the encoded bytes
     */
    public byte[] toByteArray() {
        return output.toByteArray();
    }

    /**
     * Returns the encoded data as a ByteBuffer.
     *
     * @return the encoded data wrapped in a ByteBuffer
     */
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(toByteArray());
    }

    /**
     * Writes a complete ASN1Element to the output.
     *
     * @param element the element to encode
     */
    public void write(ASN1Element element) {
        if (element.isConstructed()) {
            List<ASN1Element> children = element.getChildren();
            beginConstruct(element.getTag());
            if (children != null) {
                for (ASN1Element child : children) {
                    write(child);
                }
            }
            endConstruct();
        } else {
            byte[] value = element.getValue();
            writeRaw(element.getTag(), value != null ? value : new byte[0]);
        }
    }

    // Primitive type encoders

    /**
     * Writes a boolean value.
     *
     * @param value the boolean value
     */
    public void writeBoolean(boolean value) {
        writeRaw(ASN1Type.BOOLEAN, new byte[] { (byte) (value ? 0xFF : 0x00) });
    }

    /**
     * Writes an integer value.
     *
     * @param value the integer value
     */
    public void writeInteger(int value) {
        byte[] bytes = encodeInteger(value);
        writeRaw(ASN1Type.INTEGER, bytes);
    }

    /**
     * Writes a long integer value.
     *
     * @param value the long value
     */
    public void writeInteger(long value) {
        byte[] bytes = encodeLong(value);
        writeRaw(ASN1Type.INTEGER, bytes);
    }

    /**
     * Writes an enumerated value.
     *
     * @param value the enumerated value
     */
    public void writeEnumerated(int value) {
        byte[] bytes = encodeInteger(value);
        writeRaw(ASN1Type.ENUMERATED, bytes);
    }

    /**
     * Writes an octet string from a byte array.
     *
     * @param value the byte array
     */
    public void writeOctetString(byte[] value) {
        writeRaw(ASN1Type.OCTET_STRING, value);
    }

    /**
     * Writes an octet string from a string (UTF-8 encoded).
     *
     * @param value the string value
     */
    public void writeOctetString(String value) {
        writeOctetString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a null value.
     */
    public void writeNull() {
        writeRaw(ASN1Type.NULL, new byte[0]);
    }

    // Constructed type support

    /**
     * Begins a SEQUENCE.
     */
    public void beginSequence() {
        beginConstruct(ASN1Type.SEQUENCE);
    }

    /**
     * Ends a SEQUENCE.
     */
    public void endSequence() {
        endConstruct();
    }

    /**
     * Begins a SET.
     */
    public void beginSet() {
        beginConstruct(ASN1Type.SET);
    }

    /**
     * Ends a SET.
     */
    public void endSet() {
        endConstruct();
    }

    /**
     * Begins a context-specific tagged element.
     *
     * @param tagNumber the context tag number (0-30)
     * @param constructed whether this is constructed (contains other elements)
     */
    public void beginContext(int tagNumber, boolean constructed) {
        int tag = ASN1Type.contextTag(tagNumber, constructed);
        beginConstruct(tag);
    }

    /**
     * Ends a context-specific tagged element.
     */
    public void endContext() {
        endConstruct();
    }

    /**
     * Begins an application-specific tagged element.
     *
     * @param tagNumber the application tag number (0-30)
     * @param constructed whether this is constructed
     */
    public void beginApplication(int tagNumber, boolean constructed) {
        int tag = ASN1Type.applicationTag(tagNumber, constructed);
        beginConstruct(tag);
    }

    /**
     * Ends an application-specific tagged element.
     */
    public void endApplication() {
        endConstruct();
    }

    /**
     * Writes an application-specific primitive value.
     *
     * @param tagNumber the application tag number
     * @param value the value bytes
     */
    public void writeApplication(int tagNumber, byte[] value) {
        int tag = ASN1Type.applicationTag(tagNumber, false);
        writeRaw(tag, value);
    }

    /**
     * Writes a context-specific primitive value.
     *
     * @param tagNumber the context tag number
     * @param value the value bytes
     */
    public void writeContext(int tagNumber, byte[] value) {
        int tag = ASN1Type.contextTag(tagNumber, false);
        writeRaw(tag, value);
    }

    /**
     * Writes a context-specific primitive string value.
     *
     * @param tagNumber the context tag number
     * @param value the string value (UTF-8 encoded)
     */
    public void writeContext(int tagNumber, String value) {
        writeContext(tagNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    // Low-level encoding

    private void beginConstruct(int tag) {
        if (stackDepth >= MAX_DEPTH) {
            throw new IllegalStateException("Nesting too deep");
        }
        // Push current output to stack
        stack[stackDepth] = new ByteArrayOutputStream();
        stackDepth++;
        // Write tag to new buffer (we'll prepend length later)
        currentOutput().write(tag);
    }

    private void endConstruct() {
        if (stackDepth == 0) {
            throw new IllegalStateException("No construct to end");
        }
        
        // Pop the current construct
        stackDepth--;
        ByteArrayOutputStream construct = stack[stackDepth];
        stack[stackDepth] = null;
        
        byte[] data = construct.toByteArray();
        // data[0] is the tag, data[1:] is the content
        int tag = data[0] & 0xFF;
        byte[] content = new byte[data.length - 1];
        System.arraycopy(data, 1, content, 0, content.length);
        
        // Write to parent
        writeRaw(tag, content);
    }

    private void writeRaw(int tag, byte[] value) {
        ByteArrayOutputStream out = currentOutput();
        
        // Write tag
        if (tag <= 0xFF) {
            out.write(tag);
        } else {
            // Multi-byte tag (rare in LDAP)
            out.write((tag >> 24) & 0xFF);
            out.write((tag >> 16) & 0xFF);
            out.write((tag >> 8) & 0xFF);
            out.write(tag & 0xFF);
        }
        
        // Write length
        writeLength(out, value.length);
        
        // Write value
        out.write(value, 0, value.length);
    }

    private void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            // Short form
            out.write(length);
        } else if (length < 256) {
            // Long form, 1 byte
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            // Long form, 2 bytes
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else if (length < 16777216) {
            // Long form, 3 bytes
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            // Long form, 4 bytes
            out.write(0x84);
            out.write((length >> 24) & 0xFF);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    private ByteArrayOutputStream currentOutput() {
        if (stackDepth > 0) {
            return stack[stackDepth - 1];
        }
        return output;
    }

    private byte[] encodeInteger(int value) {
        // Determine minimum bytes needed
        if (value >= -128 && value <= 127) {
            return new byte[] { (byte) value };
        } else if (value >= -32768 && value <= 32767) {
            return new byte[] { (byte) (value >> 8), (byte) value };
        } else if (value >= -8388608 && value <= 8388607) {
            return new byte[] { (byte) (value >> 16), (byte) (value >> 8), (byte) value };
        } else {
            return new byte[] { (byte) (value >> 24), (byte) (value >> 16),
                               (byte) (value >> 8), (byte) value };
        }
    }

    private byte[] encodeLong(long value) {
        // Find minimum bytes needed
        int bytes = 8;
        long test = value;
        for (int i = 0; i < 7; i++) {
            long high = test >> 8;
            if ((test >= -128 && test <= 127) && 
                ((value >= 0 && (test & 0x80) == 0) || (value < 0 && (test & 0x80) != 0))) {
                bytes = i + 1;
                break;
            }
            test = high;
        }
        
        byte[] result = new byte[bytes];
        for (int i = bytes - 1; i >= 0; i--) {
            result[i] = (byte) value;
            value >>= 8;
        }
        return result;
    }
}

