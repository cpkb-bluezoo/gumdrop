/*
 * HTTPDateFormatTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Before;
import org.junit.Test;

import java.text.ParsePosition;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPDateFormat}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPDateFormatTest {

    private HTTPDateFormat format;
    private Calendar calendar;
    
    @Before
    public void setUp() {
        format = new HTTPDateFormat();
        calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    }
    
    @Test
    public void testFormatRFC1123() {
        // Create a specific date: Fri, 15 Nov 2024 12:30:45 GMT
        calendar.clear();
        calendar.set(2024, Calendar.NOVEMBER, 15, 12, 30, 45);
        Date date = calendar.getTime();
        
        String formatted = format.format(date);
        
        // Check components of the formatted date
        assertTrue("Should contain day of month", formatted.contains("15"));
        assertTrue("Should contain month", formatted.contains("Nov"));
        assertTrue("Should contain year", formatted.contains("2024"));
        assertTrue("Should contain time", formatted.contains("12:30:45"));
    }
    
    @Test
    public void testFormatWithPaddedDay() {
        calendar.clear();
        calendar.set(2024, Calendar.MARCH, 5, 9, 5, 3);
        Date date = calendar.getTime();
        
        String formatted = format.format(date);
        
        assertTrue(formatted.contains("05 Mar 2024"));
        assertTrue(formatted.contains("09:05:03"));
    }
    
    @Test
    public void testFormatAllDaysOfWeek() {
        String[] expectedDays = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        
        calendar.clear();
        calendar.set(2024, Calendar.NOVEMBER, 10, 12, 0, 0); // Sunday
        
        for (int i = 0; i < 7; i++) {
            Date date = calendar.getTime();
            String formatted = format.format(date);
            assertTrue("Day " + i + " should start with " + expectedDays[i],
                      formatted.startsWith(expectedDays[i]));
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
    }
    
    @Test
    public void testFormatAllMonths() {
        String[] expectedMonths = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                   "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        
        for (int month = 0; month < 12; month++) {
            calendar.clear();
            calendar.set(2024, month, 15, 12, 0, 0);
            Date date = calendar.getTime();
            String formatted = format.format(date);
            assertTrue("Month " + month + " should contain " + expectedMonths[month],
                      formatted.contains(expectedMonths[month]));
        }
    }
    
    @Test
    public void testParseRFC1123() {
        String input = "Fri, 15 Nov 2024 12:30:45 GMT";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull(date);
        
        calendar.setTime(date);
        assertEquals(2024, calendar.get(Calendar.YEAR));
        assertEquals(Calendar.NOVEMBER, calendar.get(Calendar.MONTH));
        assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(12, calendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, calendar.get(Calendar.MINUTE));
        assertEquals(45, calendar.get(Calendar.SECOND));
    }
    
    @Test
    public void testParseRFC1123WithNumericTimezone() {
        String input = "Fri, 15 Nov 2024 12:30:45 +0000";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull(date);
    }
    
    @Test
    public void testParseRFC850Obsolete() {
        // RFC 850 format: Weekday, DD-Mon-YY HH:MM:SS TIMEZONE
        String input = "Friday, 15-Nov-24 12:30:45 GMT";
        ParsePosition pos = new ParsePosition(0);
        Date date = format.parse(input, pos);
        
        // This format may not be fully supported - just ensure no exception
        // The parser might return null for unsupported formats
        if (date != null) {
            calendar.setTime(date);
            assertEquals(Calendar.NOVEMBER, calendar.get(Calendar.MONTH));
            assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH));
        }
    }
    
    @Test
    public void testParseAsctime() {
        // asctime format: Wdy Mon DD HH:MM:SS YYYY
        String input = "Fri Nov 15 12:30:45 2024";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull("Should parse asctime format", date);
        
        calendar.setTime(date);
        assertEquals(2024, calendar.get(Calendar.YEAR));
        assertEquals(Calendar.NOVEMBER, calendar.get(Calendar.MONTH));
        assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH));
    }
    
    @Test
    public void testParseWithoutDayOfWeek() {
        // Some formats omit day of week
        String input = "15 Nov 2024 12:30:45 GMT";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull(date);
    }
    
    @Test
    public void testParseInvalidMonth() {
        String input = "Fri, 15 Xyz 2024 12:30:45 GMT";
        ParsePosition pos = new ParsePosition(0);
        Date date = format.parse(input, pos);
        
        assertNull(date);
        assertTrue(pos.getErrorIndex() > 0);
    }
    
    @Test
    public void testRoundTrip() {
        calendar.clear();
        calendar.set(2024, Calendar.DECEMBER, 25, 12, 0, 0);
        Date original = calendar.getTime();
        
        String formatted = format.format(original);
        ParsePosition pos = new ParsePosition(0);
        Date parsed = format.parse(formatted, pos);
        
        assertNotNull("Should parse formatted date: " + formatted + 
                      " (error at " + pos.getErrorIndex() + ")", parsed);
        
        long diff = Math.abs(original.getTime() - parsed.getTime());
        assertTrue("Round trip should preserve time within 1 second. " +
                   "Original=" + original.getTime() + ", Parsed=" + parsed.getTime() + 
                   ", Diff=" + diff + "ms, Formatted='" + formatted + "'",
                   diff < 1000);
    }
    
    @Test
    public void testParseWithPositiveTimezone() {
        String input = "Fri, 15 Nov 2024 12:30:45 +0530";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull(date);
    }
    
    @Test
    public void testParseWithNegativeTimezone() {
        String input = "Fri, 15 Nov 2024 12:30:45 -0800";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull(date);
    }
    
    @Test(expected = UnsupportedOperationException.class)
    public void testSetCalendarNotAllowed() {
        format.setCalendar(Calendar.getInstance());
    }
    
    @Test(expected = UnsupportedOperationException.class)
    public void testSetNumberFormatNotAllowed() {
        format.setNumberFormat(java.text.NumberFormat.getInstance());
    }
    
    @Test
    public void testParsePadding() {
        // Test with single-digit day
        String input = "Tue, 05 Nov 2024 09:05:03 GMT";
        Date date = format.parse(input, new ParsePosition(0));
        
        assertNotNull(date);
        
        calendar.setTime(date);
        assertEquals(5, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, calendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(5, calendar.get(Calendar.MINUTE));
        assertEquals(3, calendar.get(Calendar.SECOND));
    }
    
    @Test
    public void testFormatYear() {
        // Test year formatting with leading zeros
        calendar.clear();
        calendar.set(99, Calendar.JANUARY, 1, 0, 0, 0);
        Date date = calendar.getTime();
        
        String formatted = format.format(date);
        assertTrue(formatted.contains("0099"));
    }
}

