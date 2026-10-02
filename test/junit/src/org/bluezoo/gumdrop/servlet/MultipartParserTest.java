/*
 * MultipartParserTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.http.Part;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link MultipartParser} and {@link MimePart}: in-memory and
 * file-backed part storage, header access and file-name sanitising.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MultipartParserTest {

    private static final String BOUNDARY = "bnd";

    private MultipartConfigDef config;
    private Path uploads;

    @Before
    public void setUp() throws Exception {
        MemoryFileSystem fs = MemoryFileSystem.create();
        uploads = fs.getPath("/uploads");
        Files.createDirectories(uploads);
        config = new MultipartConfigDef();
        config.location = uploads.toString();
        config.locationPath = uploads;
        config.maxFileSize = 1000L;
        config.maxRequestSize = 5000L;
        config.fileSizeThreshold = 10L;
    }

    private static int count(Path dir) throws IOException {
        int n = 0;
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        try {
            for (Path p : ds) {
                n++;
            }
        } finally {
            ds.close();
        }
        return n;
    }

    private static String part(String headers, String body) {
        return "--" + BOUNDARY + "\r\n" + headers + "\r\n\r\n" + body + "\r\n";
    }

    private static String close() {
        return "--" + BOUNDARY + "--\r\n";
    }

    private Collection<Part> parse(String body) throws IOException {
        MultipartParser parser = new MultipartParser(config, BOUNDARY);
        InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return parser.parse(in);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        for (int n = in.read(buf); n != -1; n = in.read(buf)) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void testSmallFieldStaysInMemory() throws Exception {
        String body = part("Content-Disposition: form-data; name=\"f\"\r\nContent-Type: text/plain", "val")
                + close();
        List<Part> parts = new ArrayList<Part>(parse(body));
        assertEquals(1, parts.size());
        Part p = parts.get(0);
        assertEquals("f", p.getName());
        assertEquals(3L, p.getSize());
        assertEquals("val", read(p.getInputStream()));
        assertNotNull(p.getContentType());
        assertTrue(p.getContentType().startsWith("text/plain"));
        assertNull(p.getSubmittedFileName());
        assertNotNull(p.getHeader("content-disposition"));
        assertNull(p.getHeader("x-missing"));
        assertTrue(p.getHeaders("x-missing").isEmpty());
        assertTrue(p.getHeaderNames().contains("content-disposition"));
        p.delete();
    }

    @Test
    public void testLargeFieldSpillsToFile() throws Exception {
        String payload = "0123456789abcdefghij";
        String body = part("Content-Disposition: form-data; name=\"file\"; filename=\"dir\\\\evil.txt\"",
                payload) + close();
        List<Part> parts = new ArrayList<Part>(parse(body));
        Part p = parts.get(0);
        assertEquals(payload.length(), p.getSize());
        assertEquals(payload, read(p.getInputStream()));
        assertEquals("evil.txt", p.getSubmittedFileName());
        assertEquals(1, count(uploads));
        p.write("saved.txt");
        assertTrue(Files.exists(uploads.resolve("saved.txt")));
        p.delete();
        assertEquals(1, count(uploads));
    }

    @Test
    public void testMultipleParts() throws Exception {
        String body = part("Content-Disposition: form-data; name=\"a\"", "1")
                + part("Content-Disposition: form-data; name=\"b\"; filename=\"b.bin\"\r\n"
                        + "Content-Type: application/octet-stream\r\nContent-Transfer-Encoding: binary\r\n"
                        + "Content-Description: desc", "22")
                + close();
        Collection<Part> parts = parse(body);
        assertEquals(2, parts.size());
        List<Part> list = new ArrayList<Part>(parts);
        assertEquals("a", list.get(0).getName());
        assertEquals("b", list.get(1).getName());
        assertEquals("b.bin", list.get(1).getSubmittedFileName());
        assertNotNull(list.get(1).getHeader("content-transfer-encoding"));
    }

    @Test
    public void testMissingFinalBoundaryFails() throws Exception {
        String body = part("Content-Disposition: form-data; name=\"a\"", "1");
        try {
            parse(body);
            fail("expected IOException");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testUnlimitedMaxFileSizeDefault() throws Exception {
        config.maxFileSize = -1L;
        String body = part("Content-Disposition: form-data; name=\"a\"", "0123456789") + close();
        List<Part> parts = new ArrayList<Part>(parse(body));
        assertEquals(1, parts.size());
        assertEquals(10L, parts.get(0).getSize());
    }

    @Test
    public void testOversizedPartFails() throws Exception {
        config.maxFileSize = 5L;
        String body = part("Content-Disposition: form-data; name=\"a\"", "0123456789") + close();
        try {
            parse(body);
            fail("expected IOException");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testMimePartWriteValidation() throws Exception {
        MultipartConfigDef bare = new MultipartConfigDef();
        bare.location = null;
        MimePart noLocation = new MimePart(bare);
        try {
            noLocation.write("x");
            fail("expected FileNotFoundException");
        } catch (FileNotFoundException e) {
            assertNotNull(e.getMessage());
        }
        MimePart p = new MimePart(config);
        p.finishWriting("data".getBytes(StandardCharsets.UTF_8), null, 4L);
        try {
            p.write("..");
            fail("expected IOException");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        try {
            p.write("");
            fail("expected IOException");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        p.write("../../escape.txt");
        assertTrue(Files.exists(uploads.resolve("escape.txt")));
        assertEquals(4L, p.getSize());
    }

    @Test
    public void testMimePartHeadersAndNames() throws Exception {
        MimePart p = new MimePart(config);
        assertNull(p.getName());
        assertNull(p.getContentType());
        assertNull(p.getSubmittedFileName());
        p.addHeader("Content-Type", "text/plain; name=\"fallback.txt\"");
        p.addHeader("X-Multi", "1");
        p.addHeader("x-multi", "2");
        assertEquals(2, p.getHeaders("X-MULTI").size());
        assertEquals("fallback.txt", p.getSubmittedFileName());
        assertEquals("", read(p.getInputStream()));
        MimePart star = new MimePart(config);
        star.addHeader("Content-Disposition", "form-data; name=\"n\"; filename*=UTF-8''a%20b.txt; filename=\"plain.txt\"");
        assertNotNull(star.getSubmittedFileName());
        assertFalse(star.getHeaderNames().isEmpty());
    }

    @Test
    public void testContentSinkByteWrites() throws Exception {
        MimePart p = new MimePart(config);
        java.io.OutputStream out = p.getOutputStream();
        for (int i = 0; i < 12; i++) {
            out.write('a');
        }
        out.flush();
        out.close();
        assertEquals(12L, p.getSize());
        assertEquals("aaaaaaaaaaaa", read(p.getInputStream()));
        MimePart q = new MimePart(config);
        java.io.OutputStream o2 = q.getOutputStream();
        o2.write(new byte[] {1, 2, 3}, 0, 3);
        o2.close();
        assertEquals(3L, q.getSize());
    }
}
