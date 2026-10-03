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
import java.util.HashSet;
import java.util.Set;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link MaildirKeywords}: index allocation, the 26 keyword limit,
 * conversion between names and indices, and the on-disk keywords file
 * (save, load, malformed header and malformed lines).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirKeywordsTest {

    private Path maildir;

    @Before
    public void setUp() throws IOException {
        maildir = MemoryFileSystem.create().getPath("/md");
        Files.createDirectories(maildir);
    }

    @Test
    public void allocatesIndicesInOrderAndReusesExistingOnes() {
        MaildirKeywords k = new MaildirKeywords(maildir);
        assertFalse(k.isDirty());
        assertEquals(-1, k.getIndex("$Junk"));
        assertEquals(0, k.getOrCreateIndex("$Junk"));
        assertEquals(1, k.getOrCreateIndex("Label"));
        assertEquals(0, k.getOrCreateIndex("$Junk"));
        assertEquals(1, k.getIndex("Label"));
        assertEquals("Label", k.getKeyword(1));
        assertNull(k.getKeyword(2));
        assertNull(k.getKeyword(-1));
        assertNull(k.getKeyword(26));
        assertTrue(k.isDirty());
        Set<String> all = k.getAllKeywords();
        assertEquals(2, all.size());
        assertTrue(all.contains("$Junk"));
    }

    @Test
    public void refusesMoreThanTwentySixKeywords() {
        MaildirKeywords k = new MaildirKeywords(maildir);
        for (int i = 0; i < 26; i++) {
            assertEquals(i, k.getOrCreateIndex("k" + i));
        }
        assertEquals(-1, k.getOrCreateIndex("overflow"));
        assertEquals(-1, k.getIndex("overflow"));
        assertEquals(26, k.getAllKeywords().size());
    }

    @Test
    public void convertsBetweenNamesAndIndices() {
        MaildirKeywords k = new MaildirKeywords(maildir);
        Set<String> names = new HashSet<String>();
        names.add("a");
        names.add("b");
        Set<Integer> indices = k.keywordsToIndices(names);
        assertEquals(2, indices.size());
        assertEquals(names, k.indicesToKeywords(indices));
        Set<Integer> withUnknown = new HashSet<Integer>(indices);
        withUnknown.add(Integer.valueOf(20));
        assertEquals(names, k.indicesToKeywords(withUnknown));
    }

    @Test
    public void keywordsToIndicesSkipsKeywordsThatDoNotFit() {
        MaildirKeywords k = new MaildirKeywords(maildir);
        for (int i = 0; i < 26; i++) {
            k.getOrCreateIndex("k" + i);
        }
        Set<String> names = new HashSet<String>();
        names.add("k3");
        names.add("extra");
        Set<Integer> indices = k.keywordsToIndices(names);
        assertEquals(1, indices.size());
        assertTrue(indices.contains(Integer.valueOf(3)));
    }

    @Test
    public void saveAndLoadRoundTrip() throws IOException {
        MaildirKeywords k = new MaildirKeywords(maildir);
        k.getOrCreateIndex("$Junk");
        k.getOrCreateIndex("Label");
        k.save();
        assertFalse(k.isDirty());
        MaildirKeywords again = new MaildirKeywords(maildir);
        again.load();
        assertFalse(again.isDirty());
        assertEquals(0, again.getIndex("$Junk"));
        assertEquals("Label", again.getKeyword(1));
        assertEquals(2, again.getOrCreateIndex("third"));
    }

    @Test
    public void saveWithoutChangesWritesNothing() throws IOException {
        MaildirKeywords k = new MaildirKeywords(maildir);
        k.save();
        assertFalse(Files.exists(maildir.resolve(".keywords")));
    }

    @Test
    public void loadWithoutFileLeavesEmptyState() throws IOException {
        MaildirKeywords k = new MaildirKeywords(maildir);
        k.getOrCreateIndex("stale");
        k.load();
        assertEquals(-1, k.getIndex("stale"));
        assertTrue(k.getAllKeywords().isEmpty());
    }

    @Test
    public void loadIgnoresFileWithWrongHeader() throws IOException {
        Files.write(maildir.resolve(".keywords"), "not a header\n0 x\n".getBytes(StandardCharsets.UTF_8));
        MaildirKeywords k = new MaildirKeywords(maildir);
        k.load();
        assertTrue(k.getAllKeywords().isEmpty());
        Files.write(maildir.resolve(".keywords"), new byte[0]);
        k.load();
        assertTrue(k.getAllKeywords().isEmpty());
    }

    @Test
    public void loadSkipsBlankCommentAndMalformedLines() throws IOException {
        String text = "# gumdrop-keywords v1\n"
            + "\n"
            + "# a comment\n"
            + "0 first\n"
            + "x bad-index\n"
            + "noSpace\n"
            + " leading-space\n"
            + "99 out-of-range\n"
            + "-1 negative\n"
            + "5 sixth\n";
        Files.write(maildir.resolve(".keywords"), text.getBytes(StandardCharsets.UTF_8));
        MaildirKeywords k = new MaildirKeywords(maildir);
        k.load();
        assertEquals(2, k.getAllKeywords().size());
        assertEquals("first", k.getKeyword(0));
        assertEquals("sixth", k.getKeyword(5));
        assertEquals(6, k.getOrCreateIndex("next"));
    }
}
