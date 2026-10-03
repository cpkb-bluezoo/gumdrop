/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mailbox.maildir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link MaildirUidList}: first-time initialisation, UID assignment
 * and removal, persistence through save and load, and recovery from a
 * missing, malformed or partially malformed uidlist file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirUidListTest {

    private Path maildir;

    @Before
    public void setUp() throws IOException {
        maildir = MemoryFileSystem.create().getPath("/md");
        Files.createDirectories(maildir);
    }

    private void writeList(String text) throws IOException {
        Files.write(maildir.resolve(".uidlist"), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void freshListStartsAtOneAndIsDirty() throws IOException {
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        assertEquals(1L, list.getUidNext());
        assertTrue(list.getUidValidity() > 0);
        assertTrue(list.isDirty());
        assertEquals(0, list.size());
    }

    @Test
    public void assignsSequentialUidsAndIsIdempotent() throws IOException {
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        assertEquals(1L, list.assignUid("a.1"));
        assertEquals(2L, list.assignUid("b.2"));
        assertEquals(1L, list.assignUid("a.1"));
        assertEquals(3L, list.getUidNext());
        assertEquals(2, list.size());
        assertEquals(2L, list.getUid("b.2"));
        assertEquals(-1L, list.getUid("missing"));
        assertEquals("a.1", list.getFilename(1L));
        assertNull(list.getFilename(9L));
    }

    @Test
    public void removeUidForgetsTheMapping() throws IOException {
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        list.assignUid("a.1");
        list.save();
        assertFalse(list.isDirty());
        list.removeUid("missing");
        assertFalse(list.isDirty());
        list.removeUid("a.1");
        assertTrue(list.isDirty());
        assertEquals(-1L, list.getUid("a.1"));
        assertNull(list.getFilename(1L));
        assertEquals(0, list.size());
    }

    @Test
    public void saveAndLoadRoundTripKeepsUidsValidityAndNext() throws IOException {
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        list.assignUid("a.1");
        list.assignUid("b.2");
        list.assignUid("c.3");
        list.removeUid("b.2");
        list.save();
        MaildirUidList again = new MaildirUidList(maildir);
        again.load();
        assertFalse(again.isDirty());
        assertEquals(list.getUidValidity(), again.getUidValidity());
        assertEquals(4L, again.getUidNext());
        assertEquals(1L, again.getUid("a.1"));
        assertEquals(3L, again.getUid("c.3"));
        assertEquals(2, again.size());
        assertEquals(4L, again.assignUid("d.4"));
    }

    @Test
    public void saveWithoutChangesWritesNothing() throws IOException {
        writeList("# gumdrop-uidlist v1\nuidvalidity 5\nuidnext 2\n1 a.1\n");
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        assertFalse(list.isDirty());
        list.save();
        List<String> lines = Files.readAllLines(maildir.resolve(".uidlist"), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
    }

    @Test
    public void wrongHeaderResetsTheList() throws IOException {
        writeList("garbage\nuidvalidity 5\nuidnext 9\n1 a.1\n");
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        assertEquals(1L, list.getUidNext());
        assertTrue(list.getUidValidity() != 5L);
        assertTrue(list.isDirty());
        assertEquals(0, list.size());
        writeList("");
        list.load();
        assertEquals(1L, list.getUidNext());
        assertTrue(list.isDirty());
    }

    @Test
    public void malformedLinesAreSkipped() throws IOException {
        writeList("# gumdrop-uidlist v1\n"
            + "\n"
            + "# comment\n"
            + "uidvalidity abc\n"
            + "uidnext xyz\n"
            + "uidvalidity 77\n"
            + "uidnext 10\n"
            + "x bad-uid\n"
            + "nospace\n"
            + " lead\n"
            + "4 good.4\n");
        MaildirUidList list = new MaildirUidList(maildir);
        list.load();
        assertEquals(77L, list.getUidValidity());
        assertEquals(10L, list.getUidNext());
        assertEquals(1, list.size());
        assertEquals(4L, list.getUid("good.4"));
        assertFalse(list.isDirty());
    }
}
