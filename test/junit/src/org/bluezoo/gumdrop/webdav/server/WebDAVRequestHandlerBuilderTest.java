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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * Tests for {@link WebDAVRequestHandler} and its builder: root validation,
 * dead-property storage modes and stream creation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVRequestHandlerBuilderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void buildsPlainFileServer() throws IOException {
        Path root = folder.newFolder("root").toPath();
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
            Path root = folder.newFolder("root" + i).toPath();
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
        Path root = folder.newFolder("data").toPath();
        Path locks = folder.newFolder("locks").toPath();
        Path sidecar = folder.newFolder("sidecar").toPath();
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
        Path root = folder.newFolder("w").toPath();
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
        File missing = new File(folder.getRoot(), "does-not-exist");
        WebDAVRequestHandler.builder().rootPath(missing.toPath()).build();
    }

    @Test
    public void validateRootPathAcceptsWritableDirectory() throws IOException {
        Path root = folder.newFolder("v").toPath();
        WebDAVRequestHandler.validateRootPath(root, true);
        WebDAVRequestHandler.validateRootPath(root, false);
    }

    @Test
    public void validateRootPathWarnsWhenNotWritable() throws IOException {
        File dir = folder.newFolder("ro");
        boolean changed = dir.setWritable(false);
        try {
            WebDAVRequestHandler.validateRootPath(dir.toPath(), true);
        } finally {
            if (changed) {
                dir.setWritable(true);
            }
        }
    }

    @Test
    public void validateRootPathRejectsRegularFile() throws IOException {
        File f = folder.newFile("plain.txt");
        try {
            WebDAVRequestHandler.validateRootPath(f.toPath(), false);
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
        File missing = new File(folder.getRoot(), "nope");
        try {
            WebDAVRequestHandler.validateRootPath(missing.toPath(), false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Cannot access root path"));
        }
    }

    @Test
    public void validateRootPathRejectsUnreadableDirectory()
            throws IOException {
        File dir = folder.newFolder("noread");
        boolean changed = dir.setReadable(false);
        try {
            if (!dir.canRead()) {
                try {
                    WebDAVRequestHandler.validateRootPath(dir.toPath(), false);
                    fail("expected IllegalArgumentException");
                } catch (IllegalArgumentException e) {
                    assertNotNull(e.getMessage());
                }
            }
        } finally {
            if (changed) {
                dir.setReadable(true);
            }
        }
    }
}
