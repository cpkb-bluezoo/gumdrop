/*
 * IndexedMessageContextTest.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IndexedMessageContext}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IndexedMessageContextTest {

    private MessageIndexEntry entry;
    private IndexedMessageContext context;

    @Before
    public void setUp() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.ANSWERED);
        entry = new MessageIndexEntry(
            12345L,                        // uid
            42,                            // messageNumber
            8192L,                         // size
            1704110400000L,                // internalDate (Jan 1, 2024 12:00:00 UTC)
            1704106800000L,                // sentDate (Jan 1, 2024 11:00:00 UTC)
            flags,
            "1704110400.12345.host:2,S",   // location
            "alice@example.com",           // from
            "bob@example.com, charlie@example.com", // to
            "cc@example.com",              // cc
            "",                            // bcc
            "test subject line",           // subject
            "<msg123@example.com>",        // messageId
            "important,urgent"             // keywords
        );
        context = new IndexedMessageContext(entry);
    }

    // ========================================================================
    // Basic Property Access Tests
    // ========================================================================

    @Test
    public void testGetMessageNumber() {
        assertEquals(42, context.getMessageNumber());
    }

    @Test
    public void testGetUID() {
        assertEquals(12345L, context.getUID());
    }

    @Test
    public void testGetSize() {
        assertEquals(8192L, context.getSize());
    }

    // ========================================================================
    // Flag Tests
    // ========================================================================

    @Test
    public void testGetFlags() {
        Set<Flag> flags = context.getFlags();
        
        assertNotNull(flags);
        assertEquals(2, flags.size());
        assertTrue(flags.contains(Flag.SEEN));
        assertTrue(flags.contains(Flag.ANSWERED));
        assertFalse(flags.contains(Flag.FLAGGED));
        assertFalse(flags.contains(Flag.DELETED));
    }

    @Test
    public void testGetFlagsEmpty() {
        MessageIndexEntry emptyFlags = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );
        IndexedMessageContext ctx = new IndexedMessageContext(emptyFlags);
        
        assertTrue(ctx.getFlags().isEmpty());
    }

    // ========================================================================
    // Keyword Tests
    // ========================================================================

    @Test
    public void testGetKeywords() {
        Set<String> keywords = context.getKeywords();
        
        assertNotNull(keywords);
        assertEquals(2, keywords.size());
        assertTrue(keywords.contains("important"));
        assertTrue(keywords.contains("urgent"));
    }

    @Test
    public void testGetKeywordsEmpty() {
        MessageIndexEntry noKeywords = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );
        IndexedMessageContext ctx = new IndexedMessageContext(noKeywords);
        
        assertTrue(ctx.getKeywords().isEmpty());
    }

    @Test
    public void testGetKeywordsSingleValue() {
        MessageIndexEntry singleKeyword = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", "solo"
        );
        IndexedMessageContext ctx = new IndexedMessageContext(singleKeyword);
        
        Set<String> keywords = ctx.getKeywords();
        assertEquals(1, keywords.size());
        assertTrue(keywords.contains("solo"));
    }

    // ========================================================================
    // Date Tests
    // ========================================================================

    @Test
    public void testGetInternalDate() {
        OffsetDateTime internalDate = context.getInternalDate();
        
        assertNotNull(internalDate);
        assertEquals(2024, internalDate.getYear());
        assertEquals(1, internalDate.getMonthValue());
        assertEquals(1, internalDate.getDayOfMonth());
        assertEquals(12, internalDate.getHour());
        assertEquals(0, internalDate.getMinute());
    }

    @Test
    public void testGetInternalDateNull() {
        MessageIndexEntry noDate = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );
        IndexedMessageContext ctx = new IndexedMessageContext(noDate);
        
        assertNull(ctx.getInternalDate());
    }

    @Test
    public void testGetSentDate() throws IOException {
        OffsetDateTime sentDate = context.getSentDate();
        
        assertNotNull(sentDate);
        assertEquals(2024, sentDate.getYear());
        assertEquals(1, sentDate.getMonthValue());
        assertEquals(1, sentDate.getDayOfMonth());
        assertEquals(11, sentDate.getHour());
    }

    @Test
    public void testGetSentDateNull() throws IOException {
        MessageIndexEntry noDate = new MessageIndexEntry(
            1L, 1, 100L, 1000L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );
        IndexedMessageContext ctx = new IndexedMessageContext(noDate);
        
        assertNull(ctx.getSentDate());
    }

    // ========================================================================
    // Header Tests
    // ========================================================================

    @Test
    public void testGetHeaderFrom() throws IOException {
        String from = context.getHeader("From");
        assertEquals("alice@example.com", from);
    }

    @Test
    public void testGetHeaderTo() throws IOException {
        String to = context.getHeader("To");
        assertEquals("bob@example.com, charlie@example.com", to);
    }

    @Test
    public void testGetHeaderCc() throws IOException {
        String cc = context.getHeader("Cc");
        assertEquals("cc@example.com", cc);
    }

    @Test
    public void testGetHeaderSubject() throws IOException {
        String subject = context.getHeader("Subject");
        assertEquals("test subject line", subject);
    }

    @Test
    public void testGetHeaderMessageId() throws IOException {
        String messageId = context.getHeader("Message-ID");
        assertEquals("<msg123@example.com>", messageId);
    }

    @Test
    public void testGetHeaderCaseInsensitive() throws IOException {
        // Headers should be matched case-insensitively
        assertEquals("alice@example.com", context.getHeader("from"));
        assertEquals("alice@example.com", context.getHeader("FROM"));
        assertEquals("alice@example.com", context.getHeader("From"));
        
        assertEquals("test subject line", context.getHeader("subject"));
        assertEquals("test subject line", context.getHeader("SUBJECT"));
    }

    @Test
    public void testGetHeaderUnknown() throws IOException {
        String unknown = context.getHeader("X-Custom-Header");
        // Unknown headers return empty string since they're not indexed
        assertEquals("", unknown);
    }

    @Test
    public void testGetHeaderEmpty() throws IOException {
        String bcc = context.getHeader("Bcc");
        // Empty string fields should return empty, not null
        assertEquals("", bcc);
    }

    // ========================================================================
    // Headers List Tests
    // ========================================================================

    @Test
    public void testGetHeaders() throws IOException {
        List<String> fromList = context.getHeaders("From");
        
        assertNotNull(fromList);
        assertEquals(1, fromList.size());
        assertEquals("alice@example.com", fromList.get(0));
    }

    @Test
    public void testGetHeadersMultiple() throws IOException {
        // To field has multiple addresses
        List<String> toList = context.getHeaders("To");
        
        assertNotNull(toList);
        // With our implementation, they come as a single concatenated value
        assertEquals(1, toList.size());
        assertTrue(toList.get(0).contains("bob@example.com"));
        assertTrue(toList.get(0).contains("charlie@example.com"));
    }

    @Test
    public void testGetHeadersUnknown() throws IOException {
        List<String> unknown = context.getHeaders("X-Unknown");
        
        assertNotNull(unknown);
        assertTrue(unknown.isEmpty());
    }

    // ========================================================================
    // Interface Contract Tests
    // ========================================================================

    @Test
    public void testImplementsMessageContext() {
        assertTrue(context instanceof MessageContext);
    }

    @Test
    public void testImmutabilityOfFlags() {
        Set<Flag> flags = context.getFlags();
        
        // Try to modify - should throw or have no effect
        try {
            flags.add(Flag.DRAFT);
            // If it didn't throw, verify original is unchanged
            Set<Flag> flags2 = context.getFlags();
            assertFalse(flags2.contains(Flag.DRAFT));
        } catch (UnsupportedOperationException e) {
            // Expected for immutable set
        }
    }

    @Test
    public void testImmutabilityOfKeywords() {
        Set<String> keywords = context.getKeywords();
        
        try {
            keywords.add("modified");
            Set<String> keywords2 = context.getKeywords();
            assertFalse(keywords2.contains("modified"));
        } catch (UnsupportedOperationException e) {
            // Expected for immutable set
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testNullEntry() {
        try {
            new IndexedMessageContext(null);
            fail("Should throw NullPointerException or IllegalArgumentException");
        } catch (NullPointerException e) {
            // Expected
        } catch (IllegalArgumentException e) {
            // Also acceptable
        }
    }

    @Test
    public void testMinimalEntry() {
        MessageIndexEntry minimal = new MessageIndexEntry();
        IndexedMessageContext ctx = new IndexedMessageContext(minimal);
        
        assertEquals(0, ctx.getMessageNumber());
        assertEquals(0L, ctx.getUID());
        assertEquals(0L, ctx.getSize());
        assertTrue(ctx.getFlags().isEmpty());
        assertTrue(ctx.getKeywords().isEmpty());
        assertNull(ctx.getInternalDate());
    }

    @Test
    public void testSpecialCharactersInHeaders() throws IOException {
        MessageIndexEntry special = new MessageIndexEntry(
            1L, 1, 100L, 1000L, 1000L, EnumSet.noneOf(Flag.class),
            "location",
            "user+tag@example.com",
            "\"Quoted, Name\" <name@example.com>",
            "",
            "",
            "Subject with <angle> & \"quotes\"",
            "<id@example.com>",
            ""
        );
        IndexedMessageContext ctx = new IndexedMessageContext(special);
        
        assertEquals("user+tag@example.com", ctx.getHeader("From"));
        assertEquals("Subject with <angle> & \"quotes\"", ctx.getHeader("Subject"));
    }

    // ========================================================================
    // Performance Test (Sanity Check)
    // ========================================================================

    @Test
    public void testRepeatedAccess() throws IOException {
        // Ensure repeated access is consistent and reasonably fast
        for (int i = 0; i < 1000; i++) {
            assertEquals(42, context.getMessageNumber());
            assertEquals(12345L, context.getUID());
            assertEquals("test subject line", context.getHeader("Subject"));
            assertNotNull(context.getFlags());
            assertNotNull(context.getKeywords());
        }
    }

}

