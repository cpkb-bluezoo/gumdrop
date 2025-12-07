/*
 * ObsoleteStructureType.java
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

/**
 * Types of obsolete but recoverable message structures that may be detected
 * during parsing.
 * @see <a href='https://datatracker.ietf.org/doc/html/rfc5322#section-4'>RFC 5322 Section 4</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum ObsoleteStructureType {

	/**
	 * Obsolete folding whitespace patterns in headers.
	 * RFC 5322 section 4.5 describes obs-FWS patterns that were valid in
	 * older RFCs but are obsolete in RFC 5322.
	 */
	OBSOLETE_FOLDING_WHITESPACE,

	/**
	 * Obsolete header syntax such as whitespace before the colon.
	 */
	OBSOLETE_HEADER_SYNTAX,

	/**
	 * Obsolete date-time syntax.
	 * RFC 5322 section 4.5.1 describes obs-date-time patterns.
	 */
	OBSOLETE_DATE_TIME_SYNTAX,

	/**
	 * Obsolete address syntax.
	 * RFC 5322 section 4.5.4 describes obs-addr patterns.
	 */
	OBSOLETE_ADDRESS_SYNTAX,

	/**
	 * Obsolete message-id syntax.
	 * RFC 5322 section 4.5.4 describes obs-id patterns.
	 */
	OBSOLETE_MESSAGE_ID_SYNTAX,

	/**
	 * Obsolete parameter syntax in structured headers.
	 * This covers cases like unquoted special characters in parameter values.
	 */
	OBSOLETE_STRUCTURED_PARAMETERS;

}

