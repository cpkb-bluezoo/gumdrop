package org.bluezoo.gumdrop.http.hpack;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * JUnit 4 test class for HPACKHuffman encoder and decoder.
 * This class tests the implementation against the examples provided in RFC 7541,
 * Appendix C.1, as well as a variety of edge cases.
 */
public class HPACKHuffmanTest {

    // Test data from RFC 7541, Appendix C.1
    private static final String EXAMPLE_1_STRING = "www.example.com";
    private static final byte[] EXAMPLE_1_ENCODED = new byte[] {
            (byte) 0xf1, (byte) 0xe3, (byte) 0x6e, (byte) 0x1d, (byte) 0xe8, (byte) 0x61, (byte) 0x8b,
            (byte) 0x64, (byte) 0x93, (byte) 0xbe, (byte) 0x3b
    };

    private static final String EXAMPLE_2_STRING = "no-cache";
    private static final byte[] EXAMPLE_2_ENCODED = new byte[] {
            (byte) 0xc6, (byte) 0x33, (byte) 0xc1, (byte) 0xb5, (byte) 0x05, (byte) 0x1e
    };

    private static final String EXAMPLE_3_STRING = "public, max-age=31536000";
    private static final byte[] EXAMPLE_3_ENCODED = new byte[] {
            (byte) 0x4f, (byte) 0xb2, (byte) 0x3d, (byte) 0xb3, (byte) 0xd5, (byte) 0x1a, (byte) 0xb2,
            (byte) 0x54, (byte) 0xce, (byte) 0xb2, (byte) 0xee, (byte) 0x6e, (byte) 0x75, (byte) 0x5a,
            (byte) 0x89, (byte) 0x18, (byte) 0x66, (byte) 0x12, (byte) 0x58, (byte) 0x22, (byte) 0x53,
            (byte) 0xf2
    };

    private static final String EXAMPLE_4_STRING = "Mon, 21 Oct 2013 20:13:21 GMT";
    private static final byte[] EXAMPLE_4_ENCODED = new byte[] {
            (byte) 0x64, (byte) 0x02, (byte) 0x59, (byte) 0xa8, (byte) 0x01, (byte) 0x5d, (byte) 0x44,
            (byte) 0xc9, (byte) 0x07, (byte) 0x94, (byte) 0x0b, (byte) 0xe9, (byte) 0x41, (byte) 0xa8,
            (byte) 0x16, (byte) 0x22, (byte) 0x4d, (byte) 0xd7, (byte) 0xbb, (byte) 0x76, (byte) 0x60,
            (byte) 0x6f, (byte) 0x67, (byte) 0xd8, (byte) 0x1d, (byte) 0x4d, (byte) 0x39, (byte) 0x7a,
            (byte) 0xb7, (byte) 0xd6
    };
    
    /**
     * Test case for encoding a simple, common string and verifying the output against the RFC.
     */
    @Test
    public void testEncodeSimpleString() throws IOException {
        byte[] encoded = HPACKHuffman.encode(EXAMPLE_1_STRING.getBytes());
        assertArrayEquals("Encoding of 'www.example.com' should match RFC 7541", EXAMPLE_1_ENCODED, encoded);
    }

    /**
     * Test case for decoding a simple, common encoded string and verifying the output.
     */
    @Test
    public void testDecodeSimpleString() throws IOException {
        byte[] decoded = HPACKHuffman.decode(EXAMPLE_1_ENCODED);
        assertEquals("Decoding of RFC 7541 example 1 should be 'www.example.com'", EXAMPLE_1_STRING, new String(decoded));
    }

    /**
     * Test a round-trip: encode a string and then decode the result.
     * This ensures that the encode and decode methods are inverses of each other.
     */
    @Test
    public void testEncodeAndDecodeRoundTrip() throws IOException {
        String original = "custom-key: custom-value";
        byte[] encoded = HPACKHuffman.encode(original.getBytes());
        byte[] decoded = HPACKHuffman.decode(encoded);
        assertEquals("Encode-decode round trip should yield the original string", original, new String(decoded));
    }
    
    /**
     * Test decoding for RFC 7541, Appendix C.1, Example 2.
     */
    @Test
    public void testDecodeExample2() throws IOException {
        byte[] decoded = HPACKHuffman.decode(EXAMPLE_2_ENCODED);
        assertEquals("Decoding of RFC 7541 example 2 should be 'no-cache'", EXAMPLE_2_STRING, new String(decoded));
    }

    /**
     * Test decoding for RFC 7541, Appendix C.1, Example 3.
     */
    @Test
    public void testDecodeExample3() throws IOException {
        byte[] decoded = HPACKHuffman.decode(EXAMPLE_3_ENCODED);
        assertEquals("Decoding of RFC 7541 example 3 should be 'public, max-age=31536000'", EXAMPLE_3_STRING, new String(decoded));
    }

    /**
     * Test decoding for RFC 7541, Appendix C.1, Example 4.
     */
    @Test
    public void testDecodeExample4() throws IOException {
        byte[] decoded = HPACKHuffman.decode(EXAMPLE_4_ENCODED);
        assertEquals("Decoding of RFC 7541 example 4 should be 'Mon, 21 Oct 2013 20:13:21 GMT'", EXAMPLE_4_STRING, new String(decoded));
    }

    /**
     * Test decoding a string with multiple special characters.
     */
    @Test
    public void testDecodeSpecialCharacters() throws IOException {
        String original = "key_with-special@#$chars!%";
        byte[] encoded = HPACKHuffman.encode(original.getBytes());
        byte[] decoded = HPACKHuffman.decode(encoded);
        assertEquals("Decoding special characters should work correctly", original, new String(decoded));
    }
    
    /**
     * Test encoding and decoding an empty string.
     */
    @Test
    public void testEncodeAndDecodeEmptyString() throws IOException {
        String original = "";
        byte[] encoded = HPACKHuffman.encode(original.getBytes());
        assertEquals("Encoded empty string should be empty", 0, encoded.length);
        
        byte[] decoded = HPACKHuffman.decode(encoded);
        assertEquals("Decoded empty byte array should be an empty string", original, new String(decoded));
    }
    
    /**
     * Test a large string to ensure the Huffman encoding handles long inputs correctly without overflow issues.
     */
    @Test
    public void testEncodeAndDecodeLargeString() throws IOException {
        String largeString = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.";
        byte[] encoded = HPACKHuffman.encode(largeString.getBytes());
        byte[] decoded = HPACKHuffman.decode(encoded);
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
        HPACKHuffman.decode(invalidData);
    }
    
    /**
     * Test decoding with an invalid, non-terminal Huffman code at the end.
     * This tests for a sequence that is a prefix of a valid code but not a complete code itself.
     * The decoder should fail because it expects a complete code or an EOS symbol.
     */
    @Test(expected = IOException.class)
    public void testDecodeWithNonTerminalEnd() throws IOException {
        // This is a valid prefix for "www.example.com", but not a complete encoded string.
        byte[] invalidData = new byte[] { (byte) 0xf1 };
        HPACKHuffman.decode(invalidData);
    }
    
    /**
     * Test decoding with a valid Huffman code followed by trailing garbage bits.
     * The last byte contains extraneous bits that are not part of a valid code.
     * This should also cause an IOException.
     */
    @Test(expected = IOException.class)
    public void testDecodeWithTrailingGarbage() throws IOException {
        // This is a correct encoded string for "www.example.com" but with trailing bits (0b111) added.
        byte[] dataWithTrailingBits = new byte[] {
            (byte) 0xf1, (byte) 0xe3, (byte) 0x6e, (byte) 0x1d, (byte) 0xe8, (byte) 0x61, (byte) 0x8b,
            (byte) 0x64, (byte) 0x93, (byte) 0xbe, (byte) 0x3b, (byte) 0xff // adding a byte with trailing bits
        };
        HPACKHuffman.decode(dataWithTrailingBits);
    }
}

