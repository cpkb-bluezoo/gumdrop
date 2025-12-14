/*
 * GenerateTestData.java
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

package org.bluezoo.gumdrop.http.hpack;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/**
 * Generates HPACK test data by encoding raw-data headers using Gumdrop's
 * HPACK encoder. This produces story files with "wire" fields that can be
 * used to test the decoder.
 * 
 * <p>Following the hpack-test-case format:
 * <ul>
 *   <li>Each story file represents one HTTP/2 connection session</li>
 *   <li>Cases within a story share compression context (dynamic table)</li>
 *   <li>The "wire" field contains hex-encoded HPACK compressed bytes</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://github.com/http2jp/hpack-test-case">hpack-test-case</a>
 */
public class GenerateTestData {

    private static final String RAW_DATA_DIR = "test/hpack-test-case/raw-data";
    private static final String OUTPUT_DIR = "test/hpack-test-case/gumdrop";
    private static final int DEFAULT_TABLE_SIZE = 4096;

    public static void main(String[] args) throws Exception {
        // Create output directory
        Path outputPath = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputPath);

        // Process each story file
        File rawDataDir = new File(RAW_DATA_DIR);
        File[] storyFiles = rawDataDir.listFiles((dir, name) -> name.endsWith(".json"));
        
        if (storyFiles == null || storyFiles.length == 0) {
            System.err.println("No story files found in " + RAW_DATA_DIR);
            return;
        }

        Arrays.sort(storyFiles);

        for (File storyFile : storyFiles) {
            System.out.println("Processing: " + storyFile.getName());
            processStory(storyFile, outputPath);
        }

        System.out.println("Generated " + storyFiles.length + " story files in " + OUTPUT_DIR);
    }

    private static void processStory(File inputFile, Path outputDir) throws Exception {
        // Parse input story
        RawStoryHandler handler = new RawStoryHandler();
        try (InputStream in = new FileInputStream(inputFile)) {
            JSONParser parser = new JSONParser();
            parser.setContentHandler(handler);
            parser.parse(in);
        }

        // Create encoder with fresh state for this story
        Encoder encoder = new Encoder(DEFAULT_TABLE_SIZE, Integer.MAX_VALUE);

        // Build output JSON
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"description\": \"Encoded by Gumdrop HPACK encoder\",\n");
        json.append("  \"cases\": [\n");

        boolean first = true;
        int seqno = 0;
        for (List<Header> headers : handler.cases) {
            if (!first) {
                json.append(",\n");
            }
            first = false;

            // Encode headers
            ByteBuffer buf = ByteBuffer.allocate(16384);
            encoder.encode(buf, headers);
            buf.flip();

            // Convert to hex
            String wire = toHex(buf);

            // Write case
            json.append("    {\n");
            json.append("      \"seqno\": ").append(seqno++).append(",\n");
            if (seqno == 1) {
                json.append("      \"header_table_size\": ").append(DEFAULT_TABLE_SIZE).append(",\n");
            }
            json.append("      \"wire\": \"").append(wire).append("\",\n");
            json.append("      \"headers\": [\n");

            boolean firstHeader = true;
            for (Header header : headers) {
                if (!firstHeader) {
                    json.append(",\n");
                }
                firstHeader = false;
                json.append("        { \"").append(escapeJson(header.getName()))
                    .append("\": \"").append(escapeJson(header.getValue())).append("\" }");
            }
            json.append("\n      ]\n");
            json.append("    }");
        }

        json.append("\n  ]\n");
        json.append("}\n");

        // Write output file
        Path outputFile = outputDir.resolve(inputFile.getName());
        Files.write(outputFile, json.toString().getBytes("UTF-8"));
    }

    private static String toHex(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder();
        while (buf.hasRemaining()) {
            int b = buf.get() & 0xFF;
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Handler for parsing raw-data story files (headers only, no wire).
     */
    private static class RawStoryHandler extends JSONDefaultHandler {
        List<List<Header>> cases = new ArrayList<>();
        private List<Header> currentHeaders;
        private String currentKey;
        private Deque<String> stack = new ArrayDeque<>();

        public RawStoryHandler() {
            stack.addLast("");
        }

        @Override
        public void startArray() throws JSONException {
            String state = stack.getLast();
            if ("cases".equals(state)) {
                // Starting cases array
            } else if ("headers".equals(state)) {
                currentHeaders = new ArrayList<>();
            }
        }

        @Override
        public void endArray() throws JSONException {
            String state = stack.getLast();
            if ("headers".equals(state)) {
                if (currentHeaders != null) {
                    cases.add(currentHeaders);
                    currentHeaders = null;
                }
                stack.removeLast();
            } else if ("cases".equals(state)) {
                stack.removeLast();
            }
        }

        @Override
        public void startObject() throws JSONException {
            // Object within headers array is a single header
        }

        @Override
        public void endObject() throws JSONException {
            // End of header object or case object
        }

        @Override
        public void key(String key) throws JSONException {
            String state = stack.getLast();
            if ("headers".equals(state)) {
                // This key is a header name
                currentKey = key;
            } else {
                stack.addLast(key);
            }
        }

        @Override
        public void stringValue(String value) throws JSONException {
            String state = stack.getLast();
            if ("headers".equals(state)) {
                // Header value
                if (currentKey != null && currentHeaders != null) {
                    currentHeaders.add(new Header(currentKey, value));
                }
                currentKey = null;
            } else if (!"context".equals(state) && !"description".equals(state)) {
                stack.removeLast();
            } else {
                stack.removeLast();
            }
        }

        @Override
        public void numberValue(Number value) throws JSONException {
            stack.removeLast();
        }

        @Override
        public void nullValue() throws JSONException {
            stack.removeLast();
        }

        @Override
        public void booleanValue(boolean value) throws JSONException {
            stack.removeLast();
        }
    }
}

