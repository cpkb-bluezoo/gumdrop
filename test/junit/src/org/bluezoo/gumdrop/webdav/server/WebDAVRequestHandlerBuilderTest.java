/*
 * WebDAVRequestHandlerBuilderTest.java
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

package org.bluezoo.gumdrop.webdav.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link WebDAVRequestHandler} and its builder: root validation,
 * dead-property storage modes and stream creation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVRequestHandlerBuilderTest {

    private final MemoryFileSystem fs = MemoryFileSystem.create();

    private Path dir(String name) throws IOException {
        Path p = fs.getPath("/" + name);
        Files.createDirectories(p);
        return p;
    }

    @Test
    public void buildsPlainFileServer() throws IOException {
        Path root = dir("root");
        WebDAVRequestHandler h = WebDAVRequestHandler.builder()
                .rootPath(root)
                .build();
        assertNotNull(h);
        assertNotNull(h.openStream(null));
    }

    @Test
    public void buildsWritableWebdavServerForEveryStorageMode()
            throws IOException {
        String[] modes = {"auto", "xattr", "sidecar", "none", null, "  ",
            "AUTO", "bogus"};
        for (int i = 0; i < modes.length; i++) {
            Path root = dir("root" + i);
            WebDAVRequestHandler h = WebDAVRequestHandler.builder()
                    .rootPath(root)
                    .allowWrite(true)
                    .webdavEnabled(true)
                    .deadPropertyStorage(modes[i])
                    .welcomeFile("home.html")
                    .realm(null)
                    .build();
            assertNotNull(h.openStream(null));
        }
    }

    @Test
    public void lockAndSidecarRootsAccepted() throws IOException {
        Path root = dir("data");
        Path locks = dir("locks");
        Path sidecar = dir("sidecar");
        WebDAVRequestHandler h = WebDAVRequestHandler.builder()
                .rootPath(root)
                .webdavEnabled(true)
                .deadPropertyStorage("sidecar")
                .lockRoot(locks)
                .sidecarRoot(sidecar)
                .build();
        assertNotNull(h);
    }

    @Test
    public void blankWelcomeFileFallsBackToDefault() throws IOException {
        Path root = dir("w");
        WebDAVRequestHandler.builder()
                .rootPath(root)
                .welcomeFile("   ")
                .build();
        WebDAVRequestHandler.builder()
                .rootPath(root)
                .welcomeFile(null)
                .build();
    }

    @Test(expected = NullPointerException.class)
    public void nullRootPathRejectedByBuilder() {
        WebDAVRequestHandler.builder().rootPath(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void buildFailsForMissingRoot() throws IOException {
        Path missing = fs.getPath("/does-not-exist");
        WebDAVRequestHandler.builder().rootPath(missing).build();
    }

    @Test
    public void validateRootPathAcceptsWritableDirectory() throws IOException {
        Path root = dir("v");
        WebDAVRequestHandler.validateRootPath(root, true);
        WebDAVRequestHandler.validateRootPath(root, false);
    }

    @Test
    public void validateRootPathRejectsRegularFile() throws IOException {
        Path f = fs.getPath("/plain.txt");
        Files.createFile(f);
        try {
            WebDAVRequestHandler.validateRootPath(f, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be a directory"));
        }
    }

    @Test
    public void validateRootPathRejectsNullAndMissing() throws IOException {
        try {
            WebDAVRequestHandler.validateRootPath(null, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("cannot be null"));
        }
        Path missing = fs.getPath("/nope");
        try {
            WebDAVRequestHandler.validateRootPath(missing, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Cannot access root path"));
        }
    }
}
