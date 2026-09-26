/*
 * ImapMetadataParserTest.java
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

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ImapMetadataParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapMetadataParserTest {

    @Test
    public void testParseGetServerEntry() throws Exception {
        ImapMetadataGetRequest req =
                new ImapMetadataParser("\"\" /shared/comment").parseGet();
        assertTrue(req.isServerMetadata());
        assertEquals(1, req.getEntryNames().size());
        assertEquals("/shared/comment", req.getEntryNames().get(0));
    }

    @Test
    public void testParseSetMailboxEntry() throws Exception {
        ImapMetadataSetRequest req = new ImapMetadataParser(
                "INBOX (/private/comment \"My note\")").parseSet();
        assertEquals("INBOX", req.getMailboxName());
        assertEquals(1, req.getEntries().size());
        assertEquals("/private/comment",
                req.getEntries().get(0).entryName);
        assertEquals("My note", req.getEntries().get(0).value);
    }

    @Test
    public void testEntryNameValidation() {
        assertTrue(ImapMetadataEntryNames.isValidEntryName("/shared/comment"));
        assertTrue(ImapMetadataEntryNames.isValidEntryName("/private/comment"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/bad"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/shared/foo*"));
    }
}
