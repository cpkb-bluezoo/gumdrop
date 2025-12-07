package org.bluezoo.gumdrop.http.hpack;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

import org.bluezoo.json.*;
import org.bluezoo.gumdrop.http.Header;

/**
 * Test HPACK header decoder.
 * To run this test:
 * git clone https://github.com/http2jp/hpack-test-case.git in the parent
 * directory
 */
@RunWith(Parameterized.class)
public class DecoderTest implements StoryTestInterface {

    // Path to test files.
    // Testing with https://github.com/http2jp/hpack-test-case
    private static final String TEST_DIRECTORY_ROOT = "../hpack-test-case/";
    private static final String[] SUBDIRECTORIES = new String[] {
        "go-hpack",
        "haskell-http2-linear-huffman",
        "haskell-http2-linear",
        "haskell-http2-naive-huffman",
        "haskell-http2-naive",
        "haskell-http2-static-huffman",
        "haskell-http2-static",
        "nghttp2",
        "nghttp2-16384-4096",
        "nghttp2-change-table-size",
        "node-http2-hpack",
        "python-hpack",
        "swift-nio-hpack-plain-text",
        "swift-nio-hpack-huffman"
        // Determined incorrect encodings: nghttp2, python-hpack
    };

    /*
     * Each file is a "story" which means a sequence of requests on a
     * connection.
     * Since the decoder is associated with a connection, we
     * reuse the same decoder for the whole file.
     */
    private Decoder decoder;

    private File file;

    public DecoderTest(File file) {
        this.file = file;
        decoder = new Decoder(4096);
    }

    @Parameters(name = "Test for file: {0}")
    public static Collection<Object[]> data() {
        List<Object[]> parameters = new ArrayList<>();
        File root = new File(TEST_DIRECTORY_ROOT);
        for (String filename : SUBDIRECTORIES) {
            File directory = new File(root, filename);

            // Check if the directory exists and is a directory
            if (!directory.exists() || !directory.isDirectory()) {
                fail(directory + " does not exist or is not a directory");
            }

            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".json")) {
                        parameters.add(new Object[] { file });
                    }
                }
            }
        }
        return parameters;
    }

    /**
     * Test decoding an HPACK header block
     */
    @Test
    public void testDecode() {
        System.out.println("Processing file: " + file);

        try (InputStream in = new FileInputStream(file)) {
            JSONParser parser = new JSONParser();
            parser.setContentHandler(new StoryHandler(this));
            parser.parse(in);
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        } catch (JSONException e) {
            fail("Unexpected JSONException: " + e.getMessage());
        }
    }

    /**
     * Test an individual request within the story file
     */
    @Override public void testCase(int seqno, String wire, List<Header> headers) {
        byte[] encodedSequence = toByteArray(wire);

        System.out.println("Test decode for "+seqno+", wire value: " + wire);
        ByteBuffer buf = ByteBuffer.wrap(encodedSequence);
        final List<Header> testHeaders = new ArrayList<>();
        HeaderHandler handler = new HeaderHandler() {
            public void header(Header header) {
                testHeaders.add(header);
            }
        };
        // decode
        try {
            decoder.decode(buf, handler);
            assertEquals("Decode failed: ", headers, testHeaders);
        } catch (IOException e) {
            System.out.println("  decode failed at seqno "+seqno+", testHeaders="+testHeaders+": "+e.getMessage());
            fail("Decode failed: " + e.getMessage());
        }
    }

    public static byte[] toByteArray(String hexString) {
        if (hexString == null || hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must not be null and must have an even length.");
        }
        byte[] byteArray = new byte[hexString.length() / 2];
        for (int i = 0; i < hexString.length(); i += 2) {
            String hexPair = hexString.substring(i, i + 2);
            byteArray[i / 2] = (byte) Integer.parseInt(hexPair, 16);
        }
        return byteArray;
    }

}
