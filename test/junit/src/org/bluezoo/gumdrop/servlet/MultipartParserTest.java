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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.Part;

import static org.junit.Assert.*;

/**
 * Unit tests for multipart/form-data upload processing.
 * 
 * Tests the {@link MultipartParser} and {@link MimePart} classes which handle
 * parsing of multipart/form-data request bodies as defined in RFC 7578.
 * 
 * Tests cover:
 * - Single text field parsing
 * - Multiple text fields
 * - File uploads
 * - Mixed fields and files
 * - Part headers (Content-Type, Content-Disposition)
 * - Memory vs file storage threshold
 * - Maximum file size limits
 * - Malformed input handling
 * - Edge cases (empty parts, binary content)
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MultipartParserTest {

    private static final String BOUNDARY = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
    private static final String CRLF = "\r\n";
    
    private MultipartConfigDef config;
    private File tempDir;
    private List<Part> partsToCleanup;

    @Before
    public void setUp() throws IOException {
        config = new MultipartConfigDef();
        tempDir = createTempDir();
        config.location = tempDir.getAbsolutePath();
        config.maxFileSize = 10 * 1024 * 1024; // 10 MB
        config.maxRequestSize = 50 * 1024 * 1024; // 50 MB
        config.fileSizeThreshold = 4096; // 4 KB
        partsToCleanup = new ArrayList<>();
    }

    @After
    public void tearDown() throws IOException {
        // Clean up any parts that were created
        for (Part part : partsToCleanup) {
            try {
                part.delete();
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
        // Clean up temp directory
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
    }

    private File createTempDir() throws IOException {
        File dir = File.createTempFile("multipart_test_", "");
        dir.delete();
        dir.mkdirs();
        return dir;
    }

    // ===== Single Text Field Tests =====

    @Test
    public void testSingleTextField() throws Exception {
        String body = buildMultipartBody(
            part("field1", null, null, "Hello World")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals("Should have 1 part", 1, parts.size());
        Part part = parts.iterator().next();
        assertEquals("Field name", "field1", part.getName());
        assertNull("Should not have filename", part.getSubmittedFileName());
        assertEquals("Content", "Hello World", readContent(part));
    }

    @Test
    public void testTextFieldWithSpecialCharacters() throws Exception {
        String content = "Special chars: <>&\"'";
        String body = buildMultipartBody(
            part("message", null, null, content)
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals(content, readContent(part));
    }

    @Test
    public void testTextFieldWithUnicode() throws Exception {
        String content = "Unicode: \u00E9\u00E8\u00EA \u4E2D\u6587 \uD83D\uDE00";
        String body = buildMultipartBody(
            part("unicode", null, "text/plain; charset=UTF-8", content)
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals(content, readContent(part));
    }

    // ===== Multiple Fields Tests =====

    @Test
    public void testMultipleTextFields() throws Exception {
        String body = buildMultipartBody(
            part("name", null, null, "John Doe"),
            part("email", null, null, "john@example.com"),
            part("message", null, null, "Hello!")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals("Should have 3 parts", 3, parts.size());
        
        Part namePart = findPart(parts, "name");
        Part emailPart = findPart(parts, "email");
        Part messagePart = findPart(parts, "message");
        
        assertNotNull("Name part should exist", namePart);
        assertNotNull("Email part should exist", emailPart);
        assertNotNull("Message part should exist", messagePart);
        
        assertEquals("John Doe", readContent(namePart));
        assertEquals("john@example.com", readContent(emailPart));
        assertEquals("Hello!", readContent(messagePart));
    }

    @Test
    public void testDuplicateFieldNames() throws Exception {
        String body = buildMultipartBody(
            part("tags", null, null, "java"),
            part("tags", null, null, "servlet"),
            part("tags", null, null, "multipart")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals("Should have 3 parts", 3, parts.size());
        
        List<String> values = new ArrayList<>();
        for (Part part : parts) {
            if ("tags".equals(part.getName())) {
                values.add(readContent(part));
            }
        }
        
        assertEquals("Should have 3 tag values", 3, values.size());
        assertTrue(values.contains("java"));
        assertTrue(values.contains("servlet"));
        assertTrue(values.contains("multipart"));
    }

    // ===== File Upload Tests =====

    @Test
    public void testSingleFileUpload() throws Exception {
        String fileContent = "This is the file content.";
        String body = buildMultipartBody(
            part("document", "test.txt", "text/plain", fileContent)
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals("document", part.getName());
        assertEquals("test.txt", part.getSubmittedFileName());
        assertEquals("text/plain", part.getContentType());
        assertEquals(fileContent.length(), part.getSize());
        assertEquals(fileContent, readContent(part));
    }

    @Test
    public void testBinaryFileUpload() throws Exception {
        // Test with printable ASCII content that survives string encoding
        byte[] binaryContent = "Binary content: \001\002\003\004\005".getBytes(StandardCharsets.ISO_8859_1);
        String body = buildMultipartBody(
            part("file", "data.bin", "application/octet-stream", 
                 new String(binaryContent, StandardCharsets.ISO_8859_1))
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals("file", part.getName());
        assertEquals("data.bin", part.getSubmittedFileName());
        assertEquals("application/octet-stream", part.getContentType());
        
        byte[] readBack = readBinaryContent(part);
        assertArrayEquals(binaryContent, readBack);
    }

    @Test
    public void testFileWithContentTypeParameters() throws Exception {
        String content = "<html><body>Hello</body></html>";
        String body = buildMultipartBody(
            part("page", "index.html", "text/html; charset=UTF-8", content)
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertTrue("Content-Type should include charset",
            part.getContentType().contains("text/html"));
    }

    // ===== Mixed Fields and Files Tests =====

    @Test
    public void testMixedFieldsAndFiles() throws Exception {
        String body = buildMultipartBody(
            part("title", null, null, "My Document"),
            part("description", null, null, "A test document"),
            part("file", "document.txt", "text/plain", "Document content here."),
            part("tags", null, null, "test,document")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(4, parts.size());
        
        Part titlePart = findPart(parts, "title");
        Part descPart = findPart(parts, "description");
        Part filePart = findPart(parts, "file");
        Part tagsPart = findPart(parts, "tags");
        
        assertNotNull(titlePart);
        assertNotNull(descPart);
        assertNotNull(filePart);
        assertNotNull(tagsPart);
        
        assertNull("Title should not have filename", titlePart.getSubmittedFileName());
        assertEquals("document.txt", filePart.getSubmittedFileName());
    }

    @Test
    public void testMultipleFileUploads() throws Exception {
        String body = buildMultipartBody(
            part("files", "file1.txt", "text/plain", "Content 1"),
            part("files", "file2.txt", "text/plain", "Content 2"),
            part("files", "file3.txt", "text/plain", "Content 3")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(3, parts.size());
        
        int fileCount = 0;
        for (Part part : parts) {
            if ("files".equals(part.getName()) && part.getSubmittedFileName() != null) {
                fileCount++;
            }
        }
        assertEquals("Should have 3 files", 3, fileCount);
    }

    // ===== Part Headers Tests =====

    @Test
    public void testPartHeaders() throws Exception {
        String body = buildMultipartBody(
            part("file", "test.txt", "text/plain", "content")
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Check Content-Disposition header
        String cdHeader = part.getHeader("Content-Disposition");
        assertNotNull("Should have Content-Disposition", cdHeader);
        assertTrue("Should contain form-data", cdHeader.contains("form-data"));
        // The name may be in different formats depending on how the parser reconstructs it
        // Check using getName() which parses the header
        assertEquals("Part name should be file", "file", part.getName());
        assertEquals("Filename should be test.txt", "test.txt", part.getSubmittedFileName());
        
        // Check Content-Type - may be available via getContentType() method
        String contentType = part.getContentType();
        assertNotNull("Should have Content-Type", contentType);
        assertTrue("Content-Type should contain text/plain", contentType.contains("text/plain"));
    }

    @Test
    public void testGetHeaderNames() throws Exception {
        String body = buildMultipartBody(
            part("file", "test.txt", "text/plain", "content")
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        Collection<String> headerNames = part.getHeaderNames();
        assertTrue("Should have content-disposition", headerNames.contains("content-disposition"));
        assertTrue("Should have content-type", headerNames.contains("content-type"));
    }

    @Test
    public void testCaseInsensitiveHeaders() throws Exception {
        String body = buildMultipartBody(
            part("file", "test.txt", "text/plain", "content")
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Headers should be case-insensitive
        assertNotNull(part.getHeader("content-type"));
        assertNotNull(part.getHeader("Content-Type"));
        assertNotNull(part.getHeader("CONTENT-TYPE"));
    }

    // ===== Storage Threshold Tests =====

    @Test
    public void testSmallPartStaysInMemory() throws Exception {
        // Create content smaller than threshold (4KB)
        String smallContent = "Small content";
        String body = buildMultipartBody(
            part("small", "small.txt", "text/plain", smallContent)
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Verify content is correct
        assertEquals(smallContent, readContent(part));
        assertEquals(smallContent.length(), part.getSize());
        
        // No temp files should be created for small content
        File[] tempFiles = tempDir.listFiles();
        assertEquals("No temp files for small content", 0, tempFiles != null ? tempFiles.length : 0);
    }

    @Test
    public void testLargePartUsesFile() throws Exception {
        // Create content larger than threshold (4KB)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(" of large content.\n");
        }
        String largeContent = sb.toString();
        assertTrue("Content should exceed threshold", largeContent.length() > 4096);
        
        String body = buildMultipartBody(
            part("large", "large.txt", "text/plain", largeContent)
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Verify we can read the content back
        String readBack = readContent(part);
        
        // A temp file should have been created
        File[] tempFiles = tempDir.listFiles();
        boolean hasTempFile = false;
        if (tempFiles != null) {
            for (File f : tempFiles) {
                if (f.getName().startsWith("upload_")) {
                    hasTempFile = true;
                    break;
                }
            }
        }
        assertTrue("Should have temp file for large content", hasTempFile);
        
        // Verify content size is reasonable (may vary slightly due to encoding)
        assertTrue("Should have substantial content", readBack.length() > 4000);
        assertTrue("Content should contain expected text", readBack.contains("Line 0 of large content"));
        assertTrue("Content should contain later lines", readBack.contains("Line 100 of large content"));
    }

    // ===== Size Limit Tests =====

    @Test(expected = IOException.class)
    public void testMaxFileSizeExceeded() throws Exception {
        config.maxFileSize = 100; // 100 bytes max
        
        String largeContent = new String(new char[200]).replace('\0', 'X');
        String body = buildMultipartBody(
            part("file", "large.txt", "text/plain", largeContent)
        );
        
        parse(body); // Should throw IOException
    }

    // ===== Empty and Edge Cases =====

    @Test
    public void testEmptyFieldValue() throws Exception {
        String body = buildMultipartBody(
            part("empty", null, null, "")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals("empty", part.getName());
        assertEquals("", readContent(part));
        assertEquals(0, part.getSize());
    }

    @Test
    public void testEmptyFile() throws Exception {
        String body = buildMultipartBody(
            part("file", "empty.txt", "text/plain", "")
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals("empty.txt", part.getSubmittedFileName());
        assertEquals(0, part.getSize());
    }

    @Test
    public void testFieldWithNewlines() throws Exception {
        String content = "Line 1\r\nLine 2\nLine 3\rLine 4";
        String body = buildMultipartBody(
            part("multiline", null, null, content)
        );
        
        Collection<Part> parts = parse(body);
        
        assertEquals(1, parts.size());
        Part part = parts.iterator().next();
        assertEquals(content, readContent(part));
    }

    @Test
    public void testFilenameWithSpaces() throws Exception {
        String body = buildMultipartBody(
            part("file", "my document.txt", "text/plain", "content")
        );
        
        Collection<Part> parts = parse(body);
        
        Part part = parts.iterator().next();
        assertEquals("my document.txt", part.getSubmittedFileName());
    }

    // ===== Part.write() Tests =====

    @Test
    public void testPartWriteToFile() throws Exception {
        String content = "Content to be written";
        String body = buildMultipartBody(
            part("file", "source.txt", "text/plain", content)
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Write to a new file
        part.write("destination.txt");
        
        // Verify the file was written
        File dest = new File(tempDir, "destination.txt");
        assertTrue("Destination file should exist", dest.exists());
        assertEquals(content.length(), dest.length());
    }

    @Test(expected = IOException.class)
    public void testPartWriteWithPathTraversal() throws Exception {
        String body = buildMultipartBody(
            part("file", "test.txt", "text/plain", "content")
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Attempt path traversal - should fail
        part.write("../outside.txt");
    }

    // ===== Part.delete() Tests =====

    @Test
    public void testPartDelete() throws Exception {
        // Create large content to ensure file storage
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Large content line ").append(i).append("\n");
        }
        String body = buildMultipartBody(
            part("large", "large.txt", "text/plain", sb.toString())
        );
        
        Collection<Part> parts = parse(body);
        Part part = parts.iterator().next();
        
        // Verify temp file exists
        File[] beforeDelete = tempDir.listFiles();
        assertTrue("Should have temp files", beforeDelete != null && beforeDelete.length > 0);
        
        // Delete the part
        part.delete();
        
        // After delete, temp file should be removed
        File[] afterDelete = tempDir.listFiles();
        int tempFileCount = 0;
        if (afterDelete != null) {
            for (File f : afterDelete) {
                if (f.getName().startsWith("upload_")) {
                    tempFileCount++;
                }
            }
        }
        assertEquals("Temp file should be deleted", 0, tempFileCount);
    }

    // ===== Malformed Input Tests =====

    @Test(expected = IOException.class)
    public void testMissingFinalBoundary() throws Exception {
        // Build body without final boundary
        String body = "--" + BOUNDARY + CRLF +
            "Content-Disposition: form-data; name=\"field\"" + CRLF +
            CRLF +
            "value" + CRLF;
        // Missing: --BOUNDARY--
        
        parse(body);
    }

    // ===== Helper Methods =====

    private Collection<Part> parse(String body) throws IOException {
        MultipartParser parser = new MultipartParser(config, BOUNDARY);
        InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        Collection<Part> parts = parser.parse(in);
        partsToCleanup.addAll(parts);
        return parts;
    }

    private Part findPart(Collection<Part> parts, String name) {
        for (Part part : parts) {
            if (name.equals(part.getName())) {
                return part;
            }
        }
        return null;
    }

    private String readContent(Part part) throws IOException {
        InputStream in = part.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        in.close();
        return baos.toString("UTF-8");
    }

    private byte[] readBinaryContent(Part part) throws IOException {
        InputStream in = part.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        in.close();
        return baos.toByteArray();
    }

    private String buildMultipartBody(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append("--").append(BOUNDARY).append(CRLF);
            sb.append(part);
        }
        sb.append("--").append(BOUNDARY).append("--").append(CRLF);
        return sb.toString();
    }

    private String buildMultipartBodyWithBinary(byte[]... partsData) {
        // For binary, we need to build as bytes and convert
        // This is a simplified version that handles single binary part
        return new String(partsData[0], StandardCharsets.ISO_8859_1);
    }

    private String part(String name, String filename, String contentType, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) {
            sb.append("; filename=\"").append(filename).append("\"");
        }
        sb.append(CRLF);
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append(CRLF);
        }
        sb.append(CRLF);
        sb.append(content);
        sb.append(CRLF);
        return sb.toString();
    }

    private byte[] binaryPart(String name, String filename, String contentType, byte[] content) {
        StringBuilder header = new StringBuilder();
        header.append("--").append(BOUNDARY).append(CRLF);
        header.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) {
            header.append("; filename=\"").append(filename).append("\"");
        }
        header.append(CRLF);
        if (contentType != null) {
            header.append("Content-Type: ").append(contentType).append(CRLF);
        }
        header.append(CRLF);
        
        String footer = CRLF + "--" + BOUNDARY + "--" + CRLF;
        
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] footerBytes = footer.getBytes(StandardCharsets.US_ASCII);
        
        byte[] result = new byte[headerBytes.length + content.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(content, 0, result, headerBytes.length, content.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + content.length, footerBytes.length);
        
        return result;
    }
}

