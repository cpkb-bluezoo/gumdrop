/*
 * DecoderTest.java
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
 * Test data from https://github.com/http2jp/hpack-test-case
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@RunWith(Parameterized.class)
public class DecoderTest implements StoryTestInterface {

    // Path to test files (gumdrop-encoded from hpack-test-case)
    private static final String TEST_DIRECTORY = "test/hpack-test-case/gumdrop";

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
        File directory = new File(TEST_DIRECTORY);

        assertTrue(TEST_DIRECTORY + " does not exist or is not a directory",
                   directory.exists() && directory.isDirectory());

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    parameters.add(new Object[] { file });
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
            assertEquals("seqno " + seqno + " wire " + wire, headers, testHeaders);
        } catch (IOException e) {
            fail("seqno " + seqno + " wire " + wire + ": " + e.getMessage());
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
