package org.bluezoo.gumdrop.http.hpack;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * JUnit 4 test class for Huffman encoder and decoder.
 */
public class HuffmanTest {

    private static final String EXAMPLE_1_STRING = "www.example.com";
    private static final byte[] EXAMPLE_1_ENCODED = new byte[] {
            (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5, (byte) 0xf2, ':', 'k',
            (byte) 0xa0, (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
    };

    private static final String EXAMPLE_2_STRING = "no-cache";
    private static final byte[] EXAMPLE_2_ENCODED = new byte[] {
            (byte) 0xa8, (byte) 0xeb, (byte) 0x10, 'd', (byte) 0x9c, (byte) 0xbf
    };

    private static final String EXAMPLE_3_STRING = "custom-key";
    private static final byte[] EXAMPLE_3_ENCODED = new byte[] {
            '%', (byte) 0xa8, 'I', (byte) 0xe9, '[', (byte) 0xa9, '}', (byte) 0x7f
    };

    private static final String EXAMPLE_4_STRING = "custom-value";
    private static final byte[] EXAMPLE_4_ENCODED = new byte[] {
            '%', (byte) 0xa8, 'I', (byte) 0xe9, '[', (byte) 0xb8, (byte) 0xe8,
            (byte) 0xb4, (byte) 0xbf
    };

    private static final byte[] INVALID_1 = new byte[] {
            (byte) 0xff
    };
    private static final byte[] INVALID_2 = new byte[] {
            (byte) 0x5f, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
    };
    private static final byte[] INVALID_3 = new byte[] {
            (byte) 0x00, (byte) 0x3f, (byte) 0xff, (byte) 0xff, (byte) 0xff
    };
    
    /**
     * Test case for encoding a simple, common string and verifying the output against the RFC.
     */
    @Test
    public void testEncodeSimpleString() throws IOException {
        byte[] encoded = Huffman.encode(EXAMPLE_1_STRING.getBytes());
        assertArrayEquals("Encoding of 'www.example.com' should match RFC 7541", EXAMPLE_1_ENCODED, encoded);
    }

    /**
     * Test case for decoding a simple, common encoded string and verifying the output.
     */
    @Test
    public void testDecodeSimpleString() throws IOException {
        byte[] decoded = Huffman.decode(EXAMPLE_1_ENCODED);
        assertEquals("Decoding of RFC 7541 example 1 should be 'www.example.com'", EXAMPLE_1_STRING, new String(decoded));
    }

    /**
     * Test a round-trip: encode a string and then decode the result.
     * This ensures that the encode and decode methods are inverses of each other.
     */
    @Test
    public void testEncodeAndDecodeRoundTrip() throws IOException {
        String original = "custom-key: custom-value";
        byte[] encoded = Huffman.encode(original.getBytes());
        byte[] decoded = Huffman.decode(encoded);
        assertEquals("Encode-decode round trip should yield the original string", original, new String(decoded));
    }
    
    /**
     * Test decoding for RFC 7541, Appendix C.1, Example 2.
     */
    @Test
    public void testDecodeExample2() throws IOException {
        byte[] decoded = Huffman.decode(EXAMPLE_2_ENCODED);
        assertEquals("Decoding of RFC 7541 example 2 should be 'no-cache'", EXAMPLE_2_STRING, new String(decoded));
    }

    /**
     * Test decoding for RFC 7541, Appendix C.1, Example 3.
     */
    @Test
    public void testDecodeExample3() throws IOException {
        byte[] decoded = Huffman.decode(EXAMPLE_3_ENCODED);
        assertEquals("Decoding of RFC 7541 example 3 should be 'public, max-age=31536000'", EXAMPLE_3_STRING, new String(decoded));
    }

    /**
     * Test decoding for RFC 7541, Appendix C.1, Example 4.
     */
    @Test
    public void testDecodeExample4() throws IOException {
        byte[] decoded = Huffman.decode(EXAMPLE_4_ENCODED);
        assertEquals("Decoding of RFC 7541 example 4 should be 'Mon, 21 Oct 2013 20:13:21 GMT'", EXAMPLE_4_STRING, new String(decoded));
    }

    /**
     * Test decoding a string with multiple special characters.
     */
    @Test
    public void testDecodeSpecialCharacters() throws IOException {
        String original = "key_with-special@#$chars!%";
        byte[] encoded = Huffman.encode(original.getBytes());
        byte[] decoded = Huffman.decode(encoded);
        assertEquals("Decoding special characters should work correctly", original, new String(decoded));
    }
    
    /**
     * Test encoding and decoding an empty string.
     */
    @Test
    public void testEncodeAndDecodeEmptyString() throws IOException {
        String original = "";
        byte[] encoded = Huffman.encode(original.getBytes());
        assertEquals("Encoded empty string should be empty", 0, encoded.length);
        
        byte[] decoded = Huffman.decode(encoded);
        assertEquals("Decoded empty byte array should be an empty string", original, new String(decoded));
    }
    
    /**
     * Test a large string to ensure the Huffman encoding handles long inputs correctly without overflow issues.
     */
    @Test
    public void testEncodeAndDecodeLargeString() throws IOException {
        String largeString = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.";
        byte[] encoded = Huffman.encode(largeString.getBytes());
        byte[] decoded = Huffman.decode(encoded);
        assertEquals("Encoding and decoding of a large string should match", largeString, new String(decoded));
    }
    
    /**
     * Test decoding with an invalid Huffman code at the end, which should throw an IOException.
     * The last bits of the encoded data do not form a complete code, which is a violation
     * of the HPACK spec and should be handled by an exception.
     */
    @Test(expected = IOException.class)
    public void testDecodeWithInvalidEndOfCode() throws IOException {
        // This byte array corresponds to a valid prefix but ends abruptly without a full code.
        byte[] invalidData = new byte[] { (byte) 0xf1, (byte) 0xe3, (byte) 0x6e };
        Huffman.decode(invalidData);
    }
    
    /**
     * Test decoding with an invalid, non-terminal Huffman code at the end.
     * This tests for a sequence that is a prefix of a valid code but not a complete code itself.
     * The decoder should fail because it expects a complete code or an EOS symbol.
     */
    @Test(expected = IOException.class)
    public void testDecodeWithNonTerminalEnd() throws IOException {
        byte[] invalidData = new byte[] { (byte) 0xfe };
        // This is a prefix of '!' and '"' but not in itself a valid code
        Huffman.decode(invalidData);
    }
    
    /**
     * Test invalid random bytestring 1
     */
    @Test(expected = IOException.class)
    public void testInvalid1() throws IOException {
        Huffman.decode(INVALID_1);
    }

    /**
     * Test invalid random bytestring 2
     */
    @Test(expected = IOException.class)
    public void testInvalid2() throws IOException {
        Huffman.decode(INVALID_2);
    }

    /**
     * Test invalid random bytestring 3
     */
    @Test(expected = IOException.class)
    public void testInvalid3() throws IOException {
        Huffman.decode(INVALID_3);
    }

    public static String toString(byte[] bytes) {
        StringBuilder buf = new StringBuilder();
        buf.append('"');
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b < 32 || b > 126) {
                buf.append(String.format("\\u%02x", b));
            } else {
                buf.append((char) b);
            }
        }
        buf.append('"');
        return buf.toString();
    }

}

