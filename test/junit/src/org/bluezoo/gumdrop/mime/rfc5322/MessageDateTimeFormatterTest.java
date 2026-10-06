/*
 * MessageDateTimeFormatterTest.java
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

package org.bluezoo.gumdrop.mime.rfc5322;

import org.junit.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageDateTimeFormatter}.
 * Tests RFC 5322 date-time formatting and parsing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageDateTimeFormatterTest {

    // ========== format() tests ==========

    @Test
    public void testFormatCanonical() {
        // Fri, 21 Nov 1997 09:55:06 -0600
        OffsetDateTime dt = OffsetDateTime.of(1997, 11, 21, 9, 55, 6, 0, ZoneOffset.ofHours(-6));
        String result = MessageDateTimeFormatter.format(dt);
        
        assertEquals("Fri, 21 Nov 1997 09:55:06 -0600", result);
    }

    @Test
    public void testFormatSingleDigitDay() {
        // Single digit day should NOT be zero-padded per RFC 5322
        OffsetDateTime dt = OffsetDateTime.of(2003, 7, 1, 10, 52, 37, 0, ZoneOffset.ofHours(2));
        String result = MessageDateTimeFormatter.format(dt);
        
        assertEquals("Tue, 1 Jul 2003 10:52:37 +0200", result);
    }

    @Test
    public void testFormatUTC() {
        OffsetDateTime dt = OffsetDateTime.of(2023, 12, 25, 0, 0, 0, 0, ZoneOffset.UTC);
        String result = MessageDateTimeFormatter.format(dt);
        
        assertEquals("Mon, 25 Dec 2023 00:00:00 +0000", result);
    }

    @Test
    public void testFormatNegativeOffset() {
        OffsetDateTime dt = OffsetDateTime.of(2020, 1, 15, 14, 30, 45, 0, ZoneOffset.ofHours(-8));
        String result = MessageDateTimeFormatter.format(dt);
        
        assertEquals("Wed, 15 Jan 2020 14:30:45 -0800", result);
    }

    @Test
    public void testFormatPositiveOffset() {
        OffsetDateTime dt = OffsetDateTime.of(2022, 6, 10, 8, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30));
        String result = MessageDateTimeFormatter.format(dt);
        
        assertEquals("Fri, 10 Jun 2022 08:15:30 +0530", result);
    }

    @Test
    public void testFormatAllMonths() {
        // Test all month abbreviations
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", 
                          "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        
        for (int m = 1; m <= 12; m++) {
            OffsetDateTime dt = OffsetDateTime.of(2023, m, 15, 12, 0, 0, 0, ZoneOffset.UTC);
            String result = MessageDateTimeFormatter.format(dt);
            assertTrue("Month " + m + " should contain " + months[m-1],
                      result.contains(months[m-1]));
        }
    }

    @Test
    public void testFormatAllDaysOfWeek() {
        // Week starting from a known Monday
        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        
        for (int d = 0; d < 7; d++) {
            // Jan 2, 2023 is a Monday
            OffsetDateTime dt = OffsetDateTime.of(2023, 1, 2 + d, 12, 0, 0, 0, ZoneOffset.UTC);
            String result = MessageDateTimeFormatter.format(dt);
            assertTrue("Day " + d + " should start with " + days[d],
                      result.startsWith(days[d]));
        }
    }

    // ========== parse() tests ==========

    @Test
    public void testParseCanonical() {
        OffsetDateTime dt = MessageDateTimeFormatter.parse("Fri, 21 Nov 1997 09:55:06 -0600");
        
        assertEquals(1997, dt.getYear());
        assertEquals(11, dt.getMonthValue());
        assertEquals(21, dt.getDayOfMonth());
        assertEquals(9, dt.getHour());
        assertEquals(55, dt.getMinute());
        assertEquals(6, dt.getSecond());
        assertEquals(ZoneOffset.ofHours(-6), dt.getOffset());
    }

    @Test
    public void testParseSingleDigitDay() {
        OffsetDateTime dt = MessageDateTimeFormatter.parse("Tue, 1 Jul 2003 10:52:37 +0200");
        
        assertEquals(2003, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(1, dt.getDayOfMonth());
    }

    @Test
    public void testParseZeroPaddedDay() {
        OffsetDateTime dt = MessageDateTimeFormatter.parse("Fri, 05 Jul 2019 13:22:36 +0100");
        
        assertEquals(2019, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(5, dt.getDayOfMonth());
    }

    @Test
    public void testParseWithoutDayOfWeek() {
        OffsetDateTime dt = MessageDateTimeFormatter.parse("21 Nov 1997 09:55:06 -0600");
        
        assertEquals(1997, dt.getYear());
        assertEquals(11, dt.getMonthValue());
        assertEquals(21, dt.getDayOfMonth());
    }

    @Test
    public void testParseWithSpacePaddedDay() {
        // Some mailers use space padding
        OffsetDateTime dt = MessageDateTimeFormatter.parse("Mon,  4 Jul 2022 13:09:53 +0900");
        
        assertEquals(2022, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(4, dt.getDayOfMonth());
    }

    @Test
    public void testParseWithWhitespace() {
        // Leading/trailing whitespace should be handled
        OffsetDateTime dt = MessageDateTimeFormatter.parse("  Fri, 21 Nov 1997 09:55:06 -0600  ");
        
        assertEquals(1997, dt.getYear());
        assertEquals(11, dt.getMonthValue());
        assertEquals(21, dt.getDayOfMonth());
    }

    @Test
    public void testParseUTC() {
        OffsetDateTime dt = MessageDateTimeFormatter.parse("Mon, 25 Dec 2023 00:00:00 +0000");
        
        assertEquals(ZoneOffset.UTC, dt.getOffset());
    }

    // ========== parseObsolete() tests ==========

    @Test
    public void testParseObsoleteGMT() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 GMT");
        
        assertNotNull(dt);
        assertEquals(1997, dt.getYear());
        assertEquals(ZoneOffset.UTC, dt.getOffset());
    }

    @Test
    public void testParseObsoleteUT() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 UT");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.UTC, dt.getOffset());
    }

    @Test
    public void testParseObsoleteUTC() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 UTC");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.UTC, dt.getOffset());
    }

    @Test
    public void testParseObsoleteEST() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 EST");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-5), dt.getOffset());
    }

    @Test
    public void testParseObsoleteEDT() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 EDT");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-4), dt.getOffset());
    }

    @Test
    public void testParseObsoleteCST() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 CST");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-6), dt.getOffset());
    }

    @Test
    public void testParseObsoleteCDT() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 CDT");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-5), dt.getOffset());
    }

    @Test
    public void testParseObsoleteMST() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 MST");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-7), dt.getOffset());
    }

    @Test
    public void testParseObsoleteMDT() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 MDT");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-6), dt.getOffset());
    }

    @Test
    public void testParseObsoletePST() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 PST");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-8), dt.getOffset());
    }

    @Test
    public void testParseObsoletePDT() {
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 1997 09:55:06 PDT");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.ofHours(-7), dt.getOffset());
    }

    @Test
    public void testParseObsoleteMissingSeconds() {
        // Obsolete format without seconds
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("21 Nov 1997 09:55 -0600");
        
        assertNotNull(dt);
        assertEquals(1997, dt.getYear());
        assertEquals(9, dt.getHour());
        assertEquals(55, dt.getMinute());
        assertEquals(0, dt.getSecond()); // Defaults to 0
    }

    @Test
    public void testParseObsoleteMissingTimezone() {
        // Obsolete format without timezone defaults to UTC
        OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete("21 Nov 1997 09:55:06");
        
        assertNotNull(dt);
        assertEquals(ZoneOffset.UTC, dt.getOffset());
    }

    @Test
    public void testParseObsoleteInvalid() {
        // Invalid date should return null
        assertNull(MessageDateTimeFormatter.parseObsolete("not a date"));
        assertNull(MessageDateTimeFormatter.parseObsolete(""));
    }

    // ========== Round-trip tests ==========

    @Test
    public void testRoundTrip() {
        // Format and then parse should give equivalent result
        OffsetDateTime original = OffsetDateTime.of(2023, 6, 15, 14, 30, 45, 0, ZoneOffset.ofHours(-5));
        String formatted = MessageDateTimeFormatter.format(original);
        OffsetDateTime parsed = MessageDateTimeFormatter.parse(formatted);
        
        assertEquals(original.getYear(), parsed.getYear());
        assertEquals(original.getMonthValue(), parsed.getMonthValue());
        assertEquals(original.getDayOfMonth(), parsed.getDayOfMonth());
        assertEquals(original.getHour(), parsed.getHour());
        assertEquals(original.getMinute(), parsed.getMinute());
        assertEquals(original.getSecond(), parsed.getSecond());
        assertEquals(original.getOffset(), parsed.getOffset());
    }

    @Test
    public void testRoundTripEdgeCases() {
        // Test with various edge cases
        OffsetDateTime[] testDates = {
            OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),      // Y2K
            OffsetDateTime.of(1999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC), // Pre-Y2K
            OffsetDateTime.of(2038, 1, 19, 3, 14, 7, 0, ZoneOffset.UTC),    // Unix epoch limit
            OffsetDateTime.of(2023, 2, 28, 12, 0, 0, 0, ZoneOffset.ofHours(12)), // Max positive offset
            OffsetDateTime.of(2023, 2, 28, 12, 0, 0, 0, ZoneOffset.ofHours(-12)) // Max negative offset
        };
        
        for (OffsetDateTime original : testDates) {
            String formatted = MessageDateTimeFormatter.format(original);
            OffsetDateTime parsed = MessageDateTimeFormatter.parse(formatted);
            
            assertEquals("Year mismatch for " + formatted, original.getYear(), parsed.getYear());
            assertEquals("Month mismatch for " + formatted, original.getMonthValue(), parsed.getMonthValue());
            assertEquals("Day mismatch for " + formatted, original.getDayOfMonth(), parsed.getDayOfMonth());
            assertEquals("Hour mismatch for " + formatted, original.getHour(), parsed.getHour());
            assertEquals("Minute mismatch for " + formatted, original.getMinute(), parsed.getMinute());
            assertEquals("Second mismatch for " + formatted, original.getSecond(), parsed.getSecond());
            assertEquals("Offset mismatch for " + formatted, original.getOffset(), parsed.getOffset());
        }
    }

    // ========== Real-world examples ==========

    @Test
    public void testParseRealWorldExamples() {
        // Examples from real email headers
        String[] examples = {
            "Thu, 13 Feb 2020 14:30:00 +0000",
            "Mon, 1 Jan 2024 00:00:00 +0000",
            "Sat, 31 Dec 2022 23:59:59 -0800",
            "Wed, 15 May 2019 09:30:00 +0530"
        };
        
        for (String example : examples) {
            OffsetDateTime dt = MessageDateTimeFormatter.parse(example);
            assertNotNull("Failed to parse: " + example, dt);
        }
    }

    @Test
    public void testParseObsoleteRealWorldExamples() {
        // Examples of obsolete formats found in old emails
        String[] examples = {
            "Thu, 13 Feb 2020 14:30:00 GMT",
            "Mon, 1 Jan 2024 00:00:00 EST",
            "Sat, 31 Dec 2022 23:59 PST"
        };
        
        for (String example : examples) {
            OffsetDateTime dt = MessageDateTimeFormatter.parseObsolete(example);
            assertNotNull("Failed to parse obsolete: " + example, dt);
        }
    }

    // ========== CFWS / comment tests (RFC 5322 section 3.3) ==========

    private static final String BASE = "Fri, 21 Nov 1997 09:55:06 -0600";

    private static void assertBase(OffsetDateTime dt) {
        assertEquals(OffsetDateTime.of(1997, 11, 21, 9, 55, 6, 0, ZoneOffset.ofHours(-6)), dt);
    }

    @Test
    public void testParseTrailingComment() {
        assertBase(MessageDateTimeFormatter.parse(BASE + " (via relay)"));
    }

    @Test
    public void testParseTrailingCommentNoSpace() {
        assertBase(MessageDateTimeFormatter.parse(BASE + "(note)"));
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseLeadingCommentRejected() {
        // leading comments are only valid in the obsolete syntax
        MessageDateTimeFormatter.parse("(sent) " + BASE);
    }

    @Test
    public void testParseLeadingWhitespaceAndTrailingComments() {
        assertBase(MessageDateTimeFormatter.parse(" \t" + BASE + " (c) \t(d) "));
    }

    @Test
    public void testParseNestedAndEscapedComment() {
        assertBase(MessageDateTimeFormatter.parse(BASE + " (a (nested) \\) still) (b\\))"));
    }

    @Test
    public void testParseFoldedWhitespaceAroundComment() {
        assertBase(MessageDateTimeFormatter.parse(BASE + "\r\n (note)\r\n "));
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseUnterminatedCommentRejected() {
        MessageDateTimeFormatter.parse(BASE + " (unterminated");
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseUnterminatedNestedCommentRejected() {
        MessageDateTimeFormatter.parse(BASE + " (a (b)");
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseEscapedCloseParenUnterminatedRejected() {
        MessageDateTimeFormatter.parse(BASE + " (a\\)");
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseTrailingJunkAfterCommentRejected() {
        MessageDateTimeFormatter.parse(BASE + " (ok) junk");
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseStrayCloseParenRejected() {
        MessageDateTimeFormatter.parse(BASE + " )");
    }

    @Test(expected = java.time.format.DateTimeParseException.class)
    public void testParseUnterminatedLeadingCommentRejected() {
        MessageDateTimeFormatter.parse("(oops " + BASE);
    }

    @Test
    public void testParseObsoleteTrailingComment() {
        assertBase(MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 97 09:55:06 -0600 (note)"));
    }

    @Test
    public void testParseObsoleteLeadingComment() {
        assertBase(MessageDateTimeFormatter.parseObsolete("(x) Fri, 21 Nov 97 09:55:06 -0600"));
    }

    @Test
    public void testParseObsoleteUnterminatedCommentReturnsNull() {
        assertNull(MessageDateTimeFormatter.parseObsolete("Fri, 21 Nov 97 09:55:06 -0600 (oops"));
    }

    @Test
    public void testParseObsoleteUnterminatedLeadingCommentReturnsNull() {
        assertNull(MessageDateTimeFormatter.parseObsolete("(oops Fri, 21 Nov 97 09:55:06 -0600"));
    }

    @Test
    public void testParseObsoleteNullInputReturnsNull() {
        assertNull(MessageDateTimeFormatter.parseObsolete(null));
    }

    // ========== single-pass parser grammar tests ==========

    private static void assertRejected(String value) {
        assertNull("strict should reject: " + value, MessageDateTimeFormatter.parseStrict(value));
        try {
            MessageDateTimeFormatter.parse(value);
            fail("parse should throw: " + value);
        } catch (java.time.format.DateTimeParseException expected) {
            // expected
        }
    }

    @Test
    public void testEveryProperPrefixRejected() {
        for (int i = 0; i < BASE.length(); i++) {
            assertRejected(BASE.substring(0, i));
        }
    }

    @Test
    public void testEveryProperPrefixRejectedObsoleteWithoutMinutes() {
        String full = "Fri, 21 Nov 1997 09:55";
        for (int i = 0; i < full.length(); i++) {
            assertNull("obsolete should reject: " + full.substring(0, i),
                MessageDateTimeFormatter.parseObsolete(full.substring(0, i)));
        }
        assertNotNull(MessageDateTimeFormatter.parseObsolete(full));
    }

    @Test
    public void testAllMonthNames() {
        String[] months = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        for (int m = 0; m < 12; m++) {
            OffsetDateTime dt = MessageDateTimeFormatter.parse("1 " + months[m] + " 2001 00:00:00 +0000");
            assertEquals(m + 1, dt.getMonthValue());
        }
    }

    @Test
    public void testAllDayNames() {
        // 2024-12-01 is a Sunday
        String[] days = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
        for (int d = 0; d < 7; d++) {
            OffsetDateTime dt = MessageDateTimeFormatter.parse(
                days[d] + ", " + (d + 1) + " Dec 2024 00:00:00 +0000");
            assertEquals(d + 1, dt.getDayOfMonth());
        }
    }

    @Test
    public void testNamesAreCaseInsensitive() {
        assertBase(MessageDateTimeFormatter.parse("FRI, 21 NOV 1997 09:55:06 -0600"));
        assertBase(MessageDateTimeFormatter.parse("fri, 21 nov 1997 09:55:06 -0600"));
    }

    @Test
    public void testFoldingWhitespaceBetweenComponents() {
        assertBase(MessageDateTimeFormatter.parse("Fri ,\r\n 21\t Nov  1997 \r\n 09:55:06\t-0600"));
    }

    @Test
    public void testRejectedStructure() {
        assertRejected("");
        assertRejected("   ");
        assertRejected("(c) " + BASE);
        assertRejected("Xyz, 21 Nov 1997 09:55:06 -0600");
        assertRejected("Fri 21 Nov 1997 09:55:06 -0600");
        assertRejected("Fri, Nov 1997 09:55:06 -0600");
        assertRejected("Fri, 21Nov 1997 09:55:06 -0600");
        assertRejected("Fri, 21 Xxx 1997 09:55:06 -0600");
        assertRejected("Fri, 21 Nov1997 09:55:06 -0600");
        assertRejected("Fri, 21 Nov 97 09:55:06 -0600");
        assertRejected("Fri, 21 Nov 19970 09:55:06 -0600");
        assertRejected("Fri, 21 Nov 1997x09:55:06 -0600");
        assertRejected("Fri, 21 Nov 1997 9:55:06 -0600");
        assertRejected("Fri, 21 Nov 1997 09-55:06 -0600");
        assertRejected("Fri, 21 Nov 1997 09:5x:06 -0600");
        assertRejected("Fri, 21 Nov 1997 09:55 -0600");
        assertRejected("Fri, 21 Nov 1997 09:55:0x -0600");
        assertRejected("Fri, 21 Nov 1997 09:55:06");
        assertRejected("Fri, 21 Nov 1997 09:55:06 ");
        assertRejected("Fri, 21 Nov 1997 09:55:06-0600");
        assertRejected("Fri, 21 Nov 1997 09:55:06 GMT");
        assertRejected("Fri, 21 Nov 1997 09:55:06 -06");
        assertRejected("Fri, 21 Nov 1997 09:55:06 -06x0");
        assertRejected("Fri, 21 Nov 1997 09:55:06 -0600x");
    }

    @Test
    public void testRejectedRanges() {
        assertRejected("Fri, 21 Nov 1997 24:00:00 -0600");
        assertRejected("Fri, 21 Nov 1997 09:60:00 -0600");
        assertRejected("Fri, 21 Nov 1997 09:55:60 -0600");
        assertRejected("Fri, 21 Nov 1997 09:55:06 -0660");
        assertRejected("Fri, 21 Nov 1997 09:55:06 +1801");
        assertRejected("Fri, 0 Nov 1997 09:55:06 -0600");
        assertRejected("Fri, 31 Nov 1997 09:55:06 -0600");
        assertRejected("Sat, 29 Feb 1997 09:55:06 -0600");
        assertRejected("Sat, 21 Nov 1997 09:55:06 -0600");
    }

    @Test
    public void testLeapDays() {
        assertEquals(29, MessageDateTimeFormatter.parse("Thu, 29 Feb 2024 00:00:00 +0000").getDayOfMonth());
        assertEquals(29, MessageDateTimeFormatter.parse("29 Feb 2000 00:00:00 +0000").getDayOfMonth());
        assertRejected("29 Feb 1900 00:00:00 +0000");
    }

    @Test
    public void testDayOfWeekAcrossMonths() {
        assertNotNull(MessageDateTimeFormatter.parse("Wed, 1 Jan 2020 00:00:00 +0000"));
        assertNotNull(MessageDateTimeFormatter.parse("Sat, 1 Feb 2020 00:00:00 +0000"));
        assertNotNull(MessageDateTimeFormatter.parse("Sun, 1 Mar 2020 00:00:00 +0000"));
    }

    @Test
    public void testZoneExtremes() {
        assertEquals(ZoneOffset.ofHours(18),
            MessageDateTimeFormatter.parse("1 Jan 2020 00:00:00 +1800").getOffset());
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30),
            MessageDateTimeFormatter.parse("1 Jan 2020 00:00:00 -0530").getOffset());
    }

    @Test
    public void testObsoleteYears() {
        assertEquals(2005, MessageDateTimeFormatter.parseObsolete("1 Jan 05 00:00 +0000").getYear());
        assertEquals(2049, MessageDateTimeFormatter.parseObsolete("1 Jan 49 00:00 +0000").getYear());
        assertEquals(1950, MessageDateTimeFormatter.parseObsolete("1 Jan 50 00:00 +0000").getYear());
        assertEquals(1999, MessageDateTimeFormatter.parseObsolete("1 Jan 99 00:00 +0000").getYear());
        assertEquals(1999, MessageDateTimeFormatter.parseObsolete("1 Jan 099 00:00 +0000").getYear());
        assertEquals(2020, MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 +0000").getYear());
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 5 00:00 +0000"));
    }

    @Test
    public void testObsoleteZones() {
        assertEquals(ZoneOffset.UTC, MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00:00 utc").getOffset());
        assertEquals(ZoneOffset.UTC, MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 UT").getOffset());
        assertEquals(ZoneOffset.UTC, MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 GMT (x)").getOffset());
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 ESTX"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 ES"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 U"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 +06"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 +1900"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 +0060"));
    }

    @Test
    public void testObsoleteZoneAbsent() {
        assertEquals(ZoneOffset.UTC, MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00:00 ").getOffset());
        assertEquals(ZoneOffset.UTC, MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00:00(c)").getOffset());
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00:00-0600"));
    }

    @Test
    public void testObsoleteSecondsMalformed() {
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00:6 +0000"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00:60 +0000"));
    }

    @Test
    public void testObsoleteZoneWithNonLetterCharacters() {
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 ~"));
        assertNull(MessageDateTimeFormatter.parseObsolete("1 Jan 2020 00:00 {"));
    }

    @Test
    public void testInstantiable() {
        assertNotNull(new MessageDateTimeFormatter());
    }
}

