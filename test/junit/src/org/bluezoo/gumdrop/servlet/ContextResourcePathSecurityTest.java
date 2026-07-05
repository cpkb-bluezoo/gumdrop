/*
 * ContextResourcePathSecurityTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Tests that {@link Context} resource lookup rejects path traversal.
 */
public class ContextResourcePathSecurityTest {

    private File webappRoot;
    private File outsideFile;
    private Context context;

    @Before
    public void setUp() throws Exception {
        webappRoot = Files.createTempDirectory("gumdrop-webapp").toFile();
        outsideFile = new File(webappRoot.getParentFile(), "outside-secret.txt");
        Files.writeString(outsideFile.toPath(), "secret");
        Files.writeString(new File(webappRoot, "index.html").toPath(), "hello");

        Container container = new Container();
        context = new Context(container, "/app", webappRoot);
    }

    @After
    public void tearDown() {
        outsideFile.delete();
        deleteRecursively(webappRoot);
    }

    @Test
    public void testGetResourceRejectsParentTraversal() throws Exception {
        assertNull(context.getResource("/../outside-secret.txt"));
        assertNull(context.getResource("/foo/../../outside-secret.txt"));
        assertNull(context.getResource("/foo/bar/../../../outside-secret.txt"));
    }

    @Test
    public void testGetResourceAsStreamRejectsParentTraversal() {
        assertNull(context.getResourceAsStream("/../outside-secret.txt"));
        assertNull(context.getResourceAsStream("/foo/../../outside-secret.txt"));
    }

    @Test
    public void testGetResourcePathsRejectsParentTraversal() {
        assertNull(context.getResourcePaths("/../"));
        assertNull(context.getResourcePaths("/foo/../../"));
    }

    @Test
    public void testGetResourcePathsAllowsInWebappDirectory() {
        assertNotNull(context.getResourcePaths("/"));
    }

    @Test
    public void testGetResourceAsStreamAllowsInWebappPath() {
        try (InputStream in = context.getResourceAsStream("/index.html")) {
            assertNotNull(in);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
