/*
 * FileSecurityTest.java
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

package org.bluezoo.gumdrop.http.file;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for HTTP file server security and sandboxing.
 * 
 * <p>This test verifies that the file server cannot access paths outside
 * of its configured root directory, preventing directory traversal attacks
 * and other security vulnerabilities.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileSecurityTest {

    private Path tempRootDir;
    private Path tempSubDir;
    private Path tempFile;
    private Path outsideDir;
    private Path outsideFile;
    private TestFileHandler testHandler;
    
    /**
     * Test implementation of FileHandler that exposes the validateAndResolvePath method
     * for direct testing via reflection.
     */
    private static class TestFileHandler extends FileHandler {
        
        public TestFileHandler(Path rootPath) {
            super(rootPath, true, "GET, HEAD, PUT, DELETE, OPTIONS", new String[]{"index.html"});
        }
        
        public Path testValidateAndResolvePath(String requestPath) throws Exception {
            Method method = FileHandler.class.getDeclaredMethod("validateAndResolvePath", String.class);
            method.setAccessible(true);
            return (Path) method.invoke(this, requestPath);
        }
    }

    @Before
    public void setup() throws Exception {
        // Create temporary directory structure for testing
        tempRootDir = Files.createTempDirectory("gumdrop-http-security-test");
        tempSubDir = Files.createDirectory(tempRootDir.resolve("subdir"));
        tempFile = Files.createFile(tempSubDir.resolve("testfile.txt"));
        Files.write(tempFile, "test content".getBytes());
        
        // Create directory outside the root for security testing
        outsideDir = Files.createTempDirectory("gumdrop-http-outside-test");
        outsideFile = Files.createFile(outsideDir.resolve("sensitive.txt"));
        Files.write(outsideFile, "sensitive data".getBytes());
        
        // Create test handler
        testHandler = new TestFileHandler(tempRootDir);
        
        // Reduce logging noise during tests
        Logger.getLogger(FileHandler.class.getName()).setLevel(Level.SEVERE);
    }

    @After
    public void cleanup() throws Exception {
        // Clean up temporary files and directories
        // Handle potential symlinks by deleting everything in temp directories first
        if (Files.exists(tempRootDir)) {
            deleteRecursively(tempRootDir);
        }
        if (Files.exists(outsideFile)) Files.delete(outsideFile);
        if (Files.exists(outsideDir)) Files.delete(outsideDir);
    }
    
    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(child -> {
                try {
                    deleteRecursively(child);
                } catch (IOException e) {
                    // Ignore errors during cleanup
                }
            });
        }
        
        try {
            Files.delete(path);
        } catch (IOException e) {
            // Ignore errors during cleanup - some symlinks might cause issues
        }
    }

    @Test
    public void testValidPaths() throws Exception {
        // Test valid paths within the root
        assertNotNull("Root path should be valid", 
                     testHandler.testValidateAndResolvePath("/"));
        
        assertNotNull("Subdir path should be valid", 
                     testHandler.testValidateAndResolvePath("/subdir"));
        
        assertNotNull("File path should be valid", 
                     testHandler.testValidateAndResolvePath("/subdir/testfile.txt"));
        
        assertNotNull("URL encoded path should be valid", 
                     testHandler.testValidateAndResolvePath("/subdir/testfile%2Etxt"));
    }

    @Test
    public void testDirectoryTraversalAttacks() throws Exception {
        // Classic directory traversal attempts
        assertNull("../ should be rejected", 
                  testHandler.testValidateAndResolvePath("/../"));
        
        assertNull("Multiple ../ should be rejected", 
                  testHandler.testValidateAndResolvePath("/../../etc/passwd"));
        
        assertNull("Nested ../ should be rejected", 
                  testHandler.testValidateAndResolvePath("/subdir/../../etc/passwd"));
        
        // URL encoded traversal attempts
        assertNull("URL encoded ../ should be rejected", 
                  testHandler.testValidateAndResolvePath("/%2e%2e/etc/passwd"));
        
        assertNull("Mixed encoding should be rejected", 
                  testHandler.testValidateAndResolvePath("/subdir/%2e%2e/../etc/passwd"));
    }

    @Test
    public void testDoubleEncodingAttacks() throws Exception {
        // Double URL encoded directory traversal
        assertNull("Double encoded ../ should be rejected", 
                  testHandler.testValidateAndResolvePath("/%252e%252e/etc/passwd"));
        
        assertNull("Double encoded with single should be rejected", 
                  testHandler.testValidateAndResolvePath("/%252e./etc/passwd"));
    }

    @Test
    public void testNullByteAttacks() throws Exception {
        // Null byte injection attempts
        assertNull("Null byte should be rejected", 
                  testHandler.testValidateAndResolvePath("/subdir\0/../../etc/passwd"));
        
        assertNull("URL encoded null byte should be rejected", 
                  testHandler.testValidateAndResolvePath("/subdir%00/../../etc/passwd"));
    }

    @Test
    public void testWindowsSpecificAttacks() throws Exception {
        // Windows device names
        assertNull("CON device name should be rejected", 
                  testHandler.testValidateAndResolvePath("/CON"));
        
        assertNull("PRN device name should be rejected", 
                  testHandler.testValidateAndResolvePath("/PRN"));
        
        assertNull("AUX device name should be rejected", 
                  testHandler.testValidateAndResolvePath("/AUX"));
        
        assertNull("COM1 device name should be rejected", 
                  testHandler.testValidateAndResolvePath("/COM1"));
        
        assertNull("LPT1 device name should be rejected", 
                  testHandler.testValidateAndResolvePath("/LPT1"));
        
        // Windows invalid characters
        assertNull("< character should be rejected", 
                  testHandler.testValidateAndResolvePath("/test<file.txt"));
        
        assertNull("> character should be rejected", 
                  testHandler.testValidateAndResolvePath("/test>file.txt"));
        
        assertNull("| character should be rejected", 
                  testHandler.testValidateAndResolvePath("/test|file.txt"));
    }

    @Test
    public void testControlCharacterAttacks() throws Exception {
        // Control characters
        assertNull("Tab character should be rejected", 
                  testHandler.testValidateAndResolvePath("/test\tfile.txt"));
        
        assertNull("Newline character should be rejected", 
                  testHandler.testValidateAndResolvePath("/test\nfile.txt"));
        
        assertNull("Carriage return should be rejected", 
                  testHandler.testValidateAndResolvePath("/test\rfile.txt"));
    }

    @Test
    public void testLongPathAttacks() throws Exception {
        // Generate a very long path
        StringBuilder longPath = new StringBuilder("/");
        for (int i = 0; i < 300; i++) {
            longPath.append("verylongdirectoryname/");
        }
        
        assertNull("Overly long path should be rejected", 
                  testHandler.testValidateAndResolvePath(longPath.toString()));
    }

    @Test
    public void testEdgeCases() throws Exception {
        // Empty and null paths
        assertNull("Empty path should be rejected", 
                  testHandler.testValidateAndResolvePath(""));
        
        assertNull("Null path should be rejected", 
                  testHandler.testValidateAndResolvePath(null));
        
        // Just dots
        assertNull("Single dot should be rejected", 
                  testHandler.testValidateAndResolvePath("/."));
        
        assertNull("Double dot should be rejected", 
                  testHandler.testValidateAndResolvePath("/.."));
        
        // Multiple slashes
        assertNotNull("Multiple slashes should be normalized", 
                     testHandler.testValidateAndResolvePath("//subdir///testfile.txt"));
    }

    @Test
    public void testSymbolicLinkSecurity() throws Exception {
        // Create a symbolic link pointing outside the root
        Path symlinkPath = tempRootDir.resolve("evil_symlink");
        try {
            Files.createSymbolicLink(symlinkPath, outsideFile);
            
            // The symlink should be detected and rejected
            assertNull("Symbolic link outside root should be rejected", 
                      testHandler.testValidateAndResolvePath("/evil_symlink"));
                      
        } catch (UnsupportedOperationException e) {
            // Symbolic links not supported on this platform - skip test
            System.out.println("Skipping symbolic link test - not supported on this platform");
        }
    }

    @Test
    public void testAbsolutePathAttempts() throws Exception {
        // Valid path within root should work and be within root
        Path result = testHandler.testValidateAndResolvePath("/subdir/testfile.txt");
        assertNotNull("Valid path should work", result);
        
        // Use real path resolution to handle symlinks properly
        Path realTempRoot = tempRootDir.toRealPath();
        Path realResult = result.toRealPath();
        assertTrue("Result should be within root", realResult.startsWith(realTempRoot));
    }

    @Test
    public void testPathNormalization() throws Exception {
        // Simple valid path should work
        Path result = testHandler.testValidateAndResolvePath("/subdir/testfile.txt");
        assertNotNull("Simple valid path should work", result);
        
        // But paths that normalize outside root should be rejected
        assertNull("Path normalizing outside root should be rejected", 
                  testHandler.testValidateAndResolvePath("/subdir/../.."));
    }

    @Test
    public void testConnectorRootValidation() throws Exception {
        FileHTTPServer server = new FileHTTPServer();
        
        // Valid directory should work
        server.setRootPath(tempRootDir);
        assertEquals("Root path should be set", tempRootDir, server.getRootPath());
        
        // Non-existent path should fail
        try {
            server.setRootPath("/non/existent/path");
            fail("Should have thrown IllegalArgumentException for non-existent path");
        } catch (IllegalArgumentException e) {
            assertTrue("Should mention path access", e.getMessage().contains("Cannot access root path"));
        }
        
        // File instead of directory should fail
        try {
            server.setRootPath(tempFile);
            fail("Should have thrown IllegalArgumentException for file path");
        } catch (IllegalArgumentException e) {
            assertTrue("Should mention directory requirement", e.getMessage().contains("must be a directory"));
        }
        
        // Null path should fail
        try {
            server.setRootPath((Path) null);
            fail("Should have thrown IllegalArgumentException for null path");
        } catch (IllegalArgumentException e) {
            assertTrue("Should mention null path", e.getMessage().contains("cannot be null"));
        }
    }

    @Test
    public void testSecurityLogging() throws Exception {
        // Capture log messages for security events
        List<String> logMessages = new ArrayList<>();
        Handler logHandler = new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logMessages.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        
        Logger fileHandlerLogger = Logger.getLogger(FileHandler.class.getName());
        fileHandlerLogger.setLevel(Level.WARNING);
        fileHandlerLogger.addHandler(logHandler);
        
        try {
            // Trigger security violations
            testHandler.testValidateAndResolvePath("/../etc/passwd");
            testHandler.testValidateAndResolvePath("/CON");
            testHandler.testValidateAndResolvePath("/test\0file");
            
            // Verify security events are logged
            boolean hasTraversalLog = logMessages.stream().anyMatch(msg -> 
                msg.contains("dangerous path component") && msg.contains(".."));
            assertTrue("Should log directory traversal attempt: " + logMessages, hasTraversalLog);
            
            boolean hasDangerousLog = logMessages.stream().anyMatch(msg -> 
                msg.contains("dangerous path component") && msg.contains("CON"));
            assertTrue("Should log dangerous path component: " + logMessages, hasDangerousLog);
            
            boolean hasNullByteLog = logMessages.stream().anyMatch(msg -> 
                msg.contains("null bytes"));
            assertTrue("Should log null byte attack: " + logMessages, hasNullByteLog);
                      
        } finally {
            fileHandlerLogger.removeHandler(logHandler);
        }
    }
}
