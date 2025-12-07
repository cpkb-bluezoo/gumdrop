/*
 * RFC5322DateTimeFormatter.java
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

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * RFC 5322 compliant date-time formatter for email headers.
 *
 * Produces dates in the canonical RFC 5322 format:
 * - "Fri, 21 Nov 1997 09:55:06 -0600"
 * - "Tue, 1 Jul 2003 10:52:37 +0200" (single digit day, no padding)
 *
 * This formatter preserves timezone information and follows RFC 5322 section 3.3
 * specifications exactly.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RFC5322DateTimeFormatter {

	/**
	 * RFC 5322 compliant formatter.
	 * Format: "EEE, d MMM yyyy HH:mm:ss xx"
	 *
	 * Key formatting rules per RFC 5322:
	 * - Day of week: 3-letter abbreviation (Mon, Tue, etc.)
	 * - Day of month: 1-2 digits, NO zero padding (1, 2, ..., 31)
	 * - Month: 3-letter abbreviation (Jan, Feb, etc.)
	 * - Year: 4 digits (1997, 2023, etc.)
	 * - Time: HH:mm:ss with zero padding (09:55:06)
	 * - Timezone: +/-HHMM format (RFC 5322 section 4.3)
	 */
	public static final DateTimeFormatter RFC5322_FORMATTER =
		new DateTimeFormatterBuilder()
			.appendText(ChronoField.DAY_OF_WEEK, TextStyle.SHORT)
			.appendLiteral(", ")
			.appendValue(ChronoField.DAY_OF_MONTH)  // No padding - canonical format
			.appendLiteral(' ')
			.appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
			.appendLiteral(' ')
			.appendValue(ChronoField.YEAR, 4)
			.appendLiteral(' ')
			.appendValue(ChronoField.HOUR_OF_DAY, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.MINUTE_OF_HOUR, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.SECOND_OF_MINUTE, 2)
			.appendLiteral(' ')
			.appendOffset("+HHMM", "+0000")
			.toFormatter(Locale.US);

	/**
	 * Parses RFC 5322 date strings, handling both canonical format and common variations.
	 * Accepts:
	 * - "Fri, 21 Nov 1997 09:55:06 -0600" (canonical)
	 * - "21 Nov 1997 09:55:06 -0600" (without day of week)
	 * - "Fri, 05 Jul 2019 13:22:36 +0100" (zero-padded day)
	 * - "Mon,  4 Jul 2022 13:09:53 +0900" (space-padded day)
	 */
	public static final DateTimeFormatter RFC5322_PARSER =
		new DateTimeFormatterBuilder()
			.optionalStart()
				.appendText(ChronoField.DAY_OF_WEEK, TextStyle.SHORT)
				.appendLiteral(", ")
			.optionalEnd()
			.optionalStart()
				.appendLiteral(' ')  // Handle extra space padding
			.optionalEnd()
			.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)  // 1-2 digits
			.appendLiteral(' ')
			.appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
			.appendLiteral(' ')
			.appendValue(ChronoField.YEAR, 4)
			.appendLiteral(' ')
			.appendValue(ChronoField.HOUR_OF_DAY, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.MINUTE_OF_HOUR, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.SECOND_OF_MINUTE, 2)
			.appendLiteral(' ')
			.appendOffset("+HHMM", "+0000")
			.toFormatter(Locale.US);

	/**
	 * Format a OffsetDateTime in RFC 5322 canonical format.
	 * @param dateTime the date/time to format
	 * @return RFC 5322 formatted string
	 */
	public static String format(OffsetDateTime dateTime) {
		return RFC5322_FORMATTER.format(dateTime);
	}

	/**
	 * Obsolete date-time parser for RFC 5322 section 4.5 legacy formats.
	 * Handles legacy date-time formats that were valid in older RFCs:
	 * - Two-digit years (e.g., "99" interpreted as "1999")
	 * - Missing seconds (e.g., "21 Nov 97 09:55 -0600")
	 * - Obsolete timezone names (handled via preprocessing)
	 */
	public static final DateTimeFormatter OBSOLETE_RFC5322_PARSER =
		new DateTimeFormatterBuilder()
			.optionalStart()
				.appendText(ChronoField.DAY_OF_WEEK, TextStyle.SHORT)
				.appendLiteral(", ")
			.optionalEnd()
			.optionalStart()
				.appendLiteral(' ')  // Handle extra space padding
			.optionalEnd()
			.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
			.appendLiteral(' ')
			.appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
			.appendLiteral(' ')
			.appendValue(ChronoField.YEAR, 2, 4, java.time.format.SignStyle.NOT_NEGATIVE)  // 2-4 digit years
			.appendLiteral(' ')
			.appendValue(ChronoField.HOUR_OF_DAY, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.MINUTE_OF_HOUR, 2)
			.optionalStart()
				.appendLiteral(':')
				.appendValue(ChronoField.SECOND_OF_MINUTE, 2)
			.optionalEnd()
			.optionalStart()
				.appendLiteral(' ')
				.appendOffset("+HHMM", "+0000")
			.optionalEnd()
			.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)  // Default seconds to 0 if missing
			.parseDefaulting(ChronoField.OFFSET_SECONDS, 0)    // Default to UTC if no timezone
			.toFormatter(Locale.US);

	/**
	 * Parse an RFC 5322 date string.
	 * @param dateString the date string to parse
	 * @return parsed OffsetDateTime
	 */
	public static OffsetDateTime parse(String dateString) {
		return OffsetDateTime.parse(dateString.trim(), RFC5322_PARSER);
	}

	/**
	 * Parse obsolete RFC 5322 section 4.5 date-time formats.
	 * This method handles legacy date-time syntax that was valid in older RFCs
	 * but is obsolete in RFC 5322.
	 *
	 * @param dateString the obsolete date string to parse
	 * @return parsed OffsetDateTime, or null if parsing fails
	 */
	public static OffsetDateTime parseObsolete(String dateString) {
		try {
			dateString = dateString.trim();

			// Handle two-digit year conversion (RFC 5322 section 4.5)
			// Years 00-49 are interpreted as 2000-2049
			// Years 50-99 are interpreted as 1950-1999
			dateString = convertTwoDigitYear(dateString);

			// Convert obsolete timezone names to numeric offsets
			dateString = convertObsoleteTimezones(dateString);

			return OffsetDateTime.parse(dateString, OBSOLETE_RFC5322_PARSER);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Convert two-digit years to four-digit years according to RFC 5322 section 4.5.
	 */
	private static String convertTwoDigitYear(String dateString) {
		// Look for pattern like "21 Nov 99" or "21-Nov-99"
		// where year appears to be 2 digits at word boundary
		return dateString.replaceAll("\\b([0-4][0-9])\\b(?=\\s+\\d{1,2}:\\d{2})", "20$1")
						.replaceAll("\\b([5-9][0-9])\\b(?=\\s+\\d{1,2}:\\d{2})", "19$1");
	}

	/**
	 * Convert obsolete timezone names to numeric offsets.
	 * This handles legacy timezone abbreviations from RFC 5322 section 4.5.
	 */
	private static String convertObsoleteTimezones(String dateString) {
		// Replace obsolete timezone names with numeric offsets
		// Using word boundaries to ensure we only match complete timezone names
		dateString = dateString.replaceAll("\\bGMT\\b", "+0000");
		dateString = dateString.replaceAll("\\bUT\\b", "+0000");
		dateString = dateString.replaceAll("\\bUTC\\b", "+0000");
		dateString = dateString.replaceAll("\\bEST\\b", "-0500");
		dateString = dateString.replaceAll("\\bEDT\\b", "-0400");
		dateString = dateString.replaceAll("\\bCST\\b", "-0600");
		dateString = dateString.replaceAll("\\bCDT\\b", "-0500");
		dateString = dateString.replaceAll("\\bMST\\b", "-0700");
		dateString = dateString.replaceAll("\\bMDT\\b", "-0600");
		dateString = dateString.replaceAll("\\bPST\\b", "-0800");
		dateString = dateString.replaceAll("\\bPDT\\b", "-0700");

		return dateString;
	}

}

