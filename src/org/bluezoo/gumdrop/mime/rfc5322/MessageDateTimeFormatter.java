/*
 * MessageDateTimeFormatter.java
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
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * RFC 5322 compliant date-time formatter and parser for email headers.
 *
 * Produces dates in the canonical RFC 5322 format:
 * - "Fri, 21 Nov 1997 09:55:06 -0600"
 * - "Tue, 1 Jul 2003 10:52:37 +0200" (single digit day, no padding)
 *
 * Parsing is a single forward pass over the string driven by the known
 * date-time grammar: no regular expressions, intermediate strings,
 * formatter machinery or exceptions on the hot path.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5322#section-3.3">RFC 5322 §3.3</a>
 */
public class MessageDateTimeFormatter {

	/** Largest zone offset java.time can represent, in seconds. */
	private static final int MAX_OFFSET_SECONDS = 18 * 3600;

	/** Day-of-week lookup for the Sakamoto algorithm (0 = Sunday). */
	private static final int[] DOW_MONTH_OFFSET = { 0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4 };

	/** Days in each month of a non-leap year. */
	private static final int[] DAYS_IN_MONTH = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };

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
	 * Format a OffsetDateTime in RFC 5322 canonical format.
	 * @param dateTime the date/time to format
	 * @return RFC 5322 formatted string
	 */
	public static String format(OffsetDateTime dateTime) {
		return RFC5322_FORMATTER.format(dateTime);
	}

	/**
	 * Parse an RFC 5322 date-time (section 3.3):
	 * <pre>[ day-of-week "," ] day month year time zone [CFWS]</pre>
	 * Day-of-week and month names are case-insensitive, the day may be
	 * one or two digits, and runs of folding whitespace may separate the
	 * components. Comment content after the zone is discarded; a
	 * malformed comment, a day that does not exist in its month or a
	 * day-of-week that does not match the date is rejected.
	 *
	 * @param dateString the date string to parse
	 * @return parsed OffsetDateTime
	 * @throws DateTimeParseException if the string is not a valid date-time
	 */
	public static OffsetDateTime parse(String dateString) {
		OffsetDateTime ret = parseStrict(dateString);
		if (ret == null) {
			throw new DateTimeParseException("Text is not an RFC 5322 date-time",
				dateString, 0);
		}
		return ret;
	}

	/**
	 * As {@link #parse(String)} but returns null rather than throwing, so
	 * callers that fall back to the obsolete syntax pay no exception cost.
	 */
	static OffsetDateTime parseStrict(String dateString) {
		return parseImpl(dateString, false);
	}

	/**
	 * Parse obsolete RFC 5322 section 4.3 date-time syntax. In addition to
	 * the current syntax this accepts:
	 * <ul>
	 * <li>two and three digit years (00-49 are 20xx, 50-99 and three
	 * digit years are 19xx / 1900 + year)</li>
	 * <li>missing seconds</li>
	 * <li>a missing zone (taken as UTC) or an alphabetic zone: UT, GMT,
	 * UTC, EST, EDT, CST, CDT, MST, MDT, PST, PDT</li>
	 * <li>a comment before the date-time</li>
	 * </ul>
	 *
	 * @param dateString the obsolete date string to parse
	 * @return parsed OffsetDateTime, or null if parsing fails
	 */
	public static OffsetDateTime parseObsolete(String dateString) {
		if (dateString == null) {
			return null;
		}
		return parseImpl(dateString, true);
	}

	/**
	 * Single-pass parser shared by the current and obsolete syntaxes.
	 * Returns null on any syntax or range error.
	 */
	private static OffsetDateTime parseImpl(String s, boolean obsolete) {
		int len = s.length();
		int i = obsolete ? skipCfws(s, 0) : skipWsp(s, 0);
		if (i < 0) {
			return null;
		}

		// [ day-of-week "," ]
		int dow = -1;
		if (i < len && !isDigit(s.charAt(i))) {
			if (i + 3 > len) {
				return null;
			}
			dow = dayOfWeek(s.charAt(i), s.charAt(i + 1), s.charAt(i + 2));
			if (dow < 0) {
				return null;
			}
			i = skipWsp(s, i + 3);
			if (i >= len || s.charAt(i) != ',') {
				return null;
			}
			i = skipWsp(s, i + 1);
		}

		// day: 1*2DIGIT FWS
		int day = digit(s, i);
		if (day < 0) {
			return null;
		}
		i++;
		int d = digit(s, i);
		if (d >= 0) {
			day = day * 10 + d;
			i++;
		}
		int j = skipWsp(s, i);
		if (j == i) {
			return null;
		}
		i = j;

		// month FWS
		if (i + 3 > len) {
			return null;
		}
		int month = month(s.charAt(i), s.charAt(i + 1), s.charAt(i + 2));
		if (month < 0) {
			return null;
		}
		i += 3;
		j = skipWsp(s, i);
		if (j == i) {
			return null;
		}
		i = j;

		// year: 4DIGIT (2*4DIGIT obsolete) FWS
		int year = 0;
		int digits = 0;
		while (digits < 4 && (d = digit(s, i)) >= 0) {
			year = year * 10 + d;
			digits++;
			i++;
		}
		if (obsolete) {
			if (digits < 2) {
				return null;
			}
			if (digits == 2) {
				year += year < 50 ? 2000 : 1900;
			} else if (digits == 3) {
				year += 1900;
			}
		} else if (digits != 4) {
			return null;
		}
		j = skipWsp(s, i);
		if (j == i) {
			return null;
		}
		i = j;

		// time: HH ":" MM [ ":" SS ] (seconds required unless obsolete)
		int hour = twoDigits(s, i);
		if (hour < 0 || hour > 23 || i + 2 >= len || s.charAt(i + 2) != ':') {
			return null;
		}
		int minute = twoDigits(s, i + 3);
		if (minute < 0 || minute > 59) {
			return null;
		}
		i += 5;
		int second = 0;
		if (i < len && s.charAt(i) == ':') {
			second = twoDigits(s, i + 1);
			if (second < 0 || second > 59) {
				return null;
			}
			i += 3;
		} else if (!obsolete) {
			return null;
		}

		// zone: FWS ( "+" / "-" ) 4DIGIT; obsolete: or a name, or absent
		int offsetSeconds = 0;
		j = skipWsp(s, i);
		if (j > i && j < len) {
			char c = s.charAt(j);
			if (c == '+' || c == '-') {
				int hh = twoDigits(s, j + 1);
				int mm = twoDigits(s, j + 3);
				if (hh < 0 || mm < 0 || mm > 59) {
					return null;
				}
				offsetSeconds = hh * 3600 + mm * 60;
				if (offsetSeconds > MAX_OFFSET_SECONDS) {
					return null;
				}
				if (c == '-') {
					offsetSeconds = -offsetSeconds;
				}
				i = j + 5;
			} else if (obsolete) {
				int end = j;
				while (end < len && isLetter(s.charAt(end))) {
					end++;
				}
				if (end - j > 3) {
					return null;
				}
				offsetSeconds = zoneName(s, j, end);
				if (offsetSeconds == Integer.MIN_VALUE) {
					return null;
				}
				i = end;
			} else {
				return null;
			}
		} else if (!obsolete) {
			return null;
		}

		// [CFWS]
		if (skipCfws(s, i) != len) {
			return null;
		}

		// semantic checks
		if (day < 1 || day > daysInMonth(year, month)) {
			return null;
		}
		if (dow >= 0 && dayOfWeekOf(year, month, day) != dow) {
			return null;
		}
		return OffsetDateTime.of(year, month, day, hour, minute, second, 0,
			ZoneOffset.ofTotalSeconds(offsetSeconds));
	}

	/** Skips folding whitespace; returns the index of the next other character. */
	private static int skipWsp(String s, int pos) {
		int len = s.length();
		while (pos < len && isWsp(s.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	/**
	 * Skips CFWS (RFC 5322 section 3.2.2), discarding comment content.
	 * Returns the index of the next other character, or -1 if a comment
	 * is unterminated.
	 */
	private static int skipCfws(String s, int pos) {
		int len = s.length();
		while (pos < len) {
			char c = s.charAt(pos);
			if (isWsp(c)) {
				pos++;
			} else if (c == '(') {
				int depth = 1;
				pos++;
				while (pos < len && depth > 0) {
					c = s.charAt(pos);
					if (c == '\\') {
						pos++;
					} else if (c == '(') {
						depth++;
					} else if (c == ')') {
						depth--;
					}
					pos++;
				}
				if (depth > 0) {
					return -1;
				}
			} else {
				break;
			}
		}
		return pos;
	}

	private static boolean isWsp(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isLetter(char c) {
		c |= 0x20;
		return c >= 'a' && c <= 'z';
	}

	/** Returns the digit at pos, or -1 if out of range or not a digit. */
	private static int digit(String s, int pos) {
		if (pos >= s.length()) {
			return -1;
		}
		char c = s.charAt(pos);
		return isDigit(c) ? c - '0' : -1;
	}

	/** Returns the two-digit number at pos, or -1. */
	private static int twoDigits(String s, int pos) {
		int a = digit(s, pos);
		int b = digit(s, pos + 1);
		return (a < 0 || b < 0) ? -1 : a * 10 + b;
	}

	/** Packs three letters, lower-cased, into one int. */
	private static int pack(char a, char b, char c) {
		return ((a | 0x20) << 16) | ((b | 0x20) << 8) | (c | 0x20);
	}

	/** Returns 0 (Sunday) to 6 (Saturday) for a day name, or -1. */
	private static int dayOfWeek(char a, char b, char c) {
		switch (pack(a, b, c)) {
		case 's' << 16 | 'u' << 8 | 'n': return 0;
		case 'm' << 16 | 'o' << 8 | 'n': return 1;
		case 't' << 16 | 'u' << 8 | 'e': return 2;
		case 'w' << 16 | 'e' << 8 | 'd': return 3;
		case 't' << 16 | 'h' << 8 | 'u': return 4;
		case 'f' << 16 | 'r' << 8 | 'i': return 5;
		case 's' << 16 | 'a' << 8 | 't': return 6;
		default: return -1;
		}
	}

	/** Returns 1 (January) to 12 (December) for a month name, or -1. */
	private static int month(char a, char b, char c) {
		switch (pack(a, b, c)) {
		case 'j' << 16 | 'a' << 8 | 'n': return 1;
		case 'f' << 16 | 'e' << 8 | 'b': return 2;
		case 'm' << 16 | 'a' << 8 | 'r': return 3;
		case 'a' << 16 | 'p' << 8 | 'r': return 4;
		case 'm' << 16 | 'a' << 8 | 'y': return 5;
		case 'j' << 16 | 'u' << 8 | 'n': return 6;
		case 'j' << 16 | 'u' << 8 | 'l': return 7;
		case 'a' << 16 | 'u' << 8 | 'g': return 8;
		case 's' << 16 | 'e' << 8 | 'p': return 9;
		case 'o' << 16 | 'c' << 8 | 't': return 10;
		case 'n' << 16 | 'o' << 8 | 'v': return 11;
		case 'd' << 16 | 'e' << 8 | 'c': return 12;
		default: return -1;
		}
	}

	/**
	 * Returns the offset in seconds of the obsolete zone name in
	 * s[from, to), or Integer.MIN_VALUE if it is not recognised.
	 */
	private static int zoneName(String s, int from, int to) {
		char a = s.charAt(from);
		char b = from + 1 < to ? s.charAt(from + 1) : 0;
		char c = from + 2 < to ? s.charAt(from + 2) : 0;
		switch (((a | 0x20) << 16) | ((b == 0 ? 0 : b | 0x20) << 8) | (c == 0 ? 0 : c | 0x20)) {
		case 'u' << 16 | 't' << 8:
		case 'g' << 16 | 'm' << 8 | 't':
		case 'u' << 16 | 't' << 8 | 'c':
			return 0;
		case 'e' << 16 | 's' << 8 | 't': return -5 * 3600;
		case 'e' << 16 | 'd' << 8 | 't': return -4 * 3600;
		case 'c' << 16 | 's' << 8 | 't': return -6 * 3600;
		case 'c' << 16 | 'd' << 8 | 't': return -5 * 3600;
		case 'm' << 16 | 's' << 8 | 't': return -7 * 3600;
		case 'm' << 16 | 'd' << 8 | 't': return -6 * 3600;
		case 'p' << 16 | 's' << 8 | 't': return -8 * 3600;
		case 'p' << 16 | 'd' << 8 | 't': return -7 * 3600;
		default: return Integer.MIN_VALUE;
		}
	}

	private static int daysInMonth(int year, int month) {
		if (month == 2 && Year.isLeap(year)) {
			return 29;
		}
		return DAYS_IN_MONTH[month - 1];
	}

	/** Day of week, 0 (Sunday) to 6, by Sakamoto's algorithm. */
	private static int dayOfWeekOf(int year, int month, int day) {
		int y = month < 3 ? year - 1 : year;
		return (y + y / 4 - y / 100 + y / 400 + DOW_MONTH_OFFSET[month - 1] + day) % 7;
	}

}
