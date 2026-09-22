/*
 * MailboxIdFileTest.java
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

package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxIdFileTest {

    @Test
    public void testLoadReturnsNullWhenFileMissing() throws IOException {
        Path path = Files.createTempFile("mailboxid", ".missing");
        Files.delete(path);
        assertNull(MailboxIdFile.load(path));
    }

    @Test
    public void testGenerateProducesValidObjectId() {
        String id = MailboxIdFile.generate();
        assertNotNull(id);
        assertFalse(id.isEmpty());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            assertTrue("Invalid RFC 8474 validid character: " + c, ok);
        }
    }

    @Test
    public void testGenerateProducesDistinctIds() {
        assertNotEquals(MailboxIdFile.generate(), MailboxIdFile.generate());
    }

    @Test
    public void testSaveThenLoadRoundTrips() throws IOException {
        Path path = Files.createTempFile("mailboxid", ".test");
        try {
            String id = MailboxIdFile.generate();
            MailboxIdFile.save(path, id);
            assertEquals(id, MailboxIdFile.load(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void testSaveIsAtomicReplace() throws IOException {
        Path path = Files.createTempFile("mailboxid", ".test");
        try {
            MailboxIdFile.save(path, "first-id");
            MailboxIdFile.save(path, "second-id");
            assertEquals("second-id", MailboxIdFile.load(path));
            // No leftover temp files in the same directory.
            String tempPrefix = path.getFileName().toString() + "-";
            int tmpCount = 0;
            try (java.nio.file.DirectoryStream<Path> siblings =
                    Files.newDirectoryStream(path.getParent())) {
                for (Path p : siblings) {
                    if (p.getFileName().toString().startsWith(tempPrefix)) {
                        tmpCount++;
                    }
                }
            }
            assertEquals(0, tmpCount);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void testLoadRejectsEmptyFile() throws IOException {
        Path path = Files.createTempFile("mailboxid", ".test");
        try {
            assertNull(MailboxIdFile.load(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void testLoadRejectsInvalidCharacters() throws IOException {
        Path path = Files.createTempFile("mailboxid", ".test");
        try {
            Files.write(path, "not a valid id!\n".getBytes(StandardCharsets.UTF_8));
            assertNull(MailboxIdFile.load(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void testLoadTrimsWhitespace() throws IOException {
        Path path = Files.createTempFile("mailboxid", ".test");
        try {
            Files.write(path, "  abc-123  \n".getBytes(StandardCharsets.UTF_8));
            assertEquals("abc-123", MailboxIdFile.load(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

}
