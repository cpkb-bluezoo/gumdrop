/*
 * WebDAVRequestHandlerRootPermissionsIntegrationTest.java
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link WebDAVRequestHandler#validateRootPath} that
 * depend on real operating system file permissions (read and write bits on a
 * real directory), which an in-memory file system cannot model. The rest of
 * the root validation is covered by the unit-level
 * {@code WebDAVRequestHandlerBuilderTest}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVRequestHandlerRootPermissionsIntegrationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

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
