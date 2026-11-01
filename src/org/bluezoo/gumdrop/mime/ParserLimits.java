/*
 * ParserLimits.java
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

package org.bluezoo.gumdrop.mime;

/**
 * Limits guarding a {@link MimeParser} against hostile input. They bound the
 * amount of the message the parser has to hold at any moment, so that memory
 * use depends on the limits and not on the message.
 *
 * <p>A limit of zero or less means no limit.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ParserLimits {

	/** The RFC 5322 §2.1.1 maximum line length, excluding the line ending. */
	public static final int DEFAULT_MAX_HEADER_LINE_LENGTH = 998;

	/** The default longest header name. */
	public static final int DEFAULT_MAX_HEADER_NAME_LENGTH = 1024;

	/** The default longest token held to judge a structured header. */
	public static final int DEFAULT_MAX_TOKEN_LENGTH = 256;

	/** The default longest element of a structured RFC 5322 header. */
	public static final int DEFAULT_MAX_ELEMENT_LENGTH = 4096;

	private int maxHeaderLineLength = DEFAULT_MAX_HEADER_LINE_LENGTH;
	private int maxHeaderNameLength = DEFAULT_MAX_HEADER_NAME_LENGTH;
	private int maxTokenLength = DEFAULT_MAX_TOKEN_LENGTH;
	private int maxElementLength = DEFAULT_MAX_ELEMENT_LENGTH;

	/**
	 * Returns limits with no limit set at all.
	 * @return unlimited limits
	 */
	public static ParserLimits unlimited() {
		ParserLimits limits = new ParserLimits();
		limits.maxHeaderLineLength = 0;
		limits.maxHeaderNameLength = 0;
		limits.maxTokenLength = 0;
		limits.maxElementLength = 0;
		return limits;
	}

	/**
	 * Returns the longest header line, line ending not counted. The caller's
	 * buffer has to be able to hold a line of this length (plus its line
	 * ending), because an incomplete header line is left in it until the
	 * rest arrives.
	 * @return the limit, or zero or less for none (default 998)
	 */
	public int getMaxHeaderLineLength() {
		return maxHeaderLineLength;
	}

	/**
	 * Sets the longest header line, line ending not counted.
	 * @param maxHeaderLineLength the limit, or zero or less for none
	 */
	public void setMaxHeaderLineLength(int maxHeaderLineLength) {
		this.maxHeaderLineLength = maxHeaderLineLength;
	}

	/**
	 * Returns the longest header name. The name has to arrive in the first
	 * line of the header.
	 * @return the limit, or zero or less for none (default 1024)
	 */
	public int getMaxHeaderNameLength() {
		return maxHeaderNameLength;
	}

	/**
	 * Sets the longest header name.
	 * @param maxHeaderNameLength the limit, or zero or less for none
	 */
	public void setMaxHeaderNameLength(int maxHeaderNameLength) {
		this.maxHeaderNameLength = maxHeaderNameLength;
	}

	/**
	 * Returns the longest token the grammar of Content-Type and
	 * Content-Disposition holds to judge: the type, subtype and disposition
	 * type, parameter names, and the charset and language of an extended
	 * value. (Parameter values are not held.)
	 * @return the limit, or zero or less for none (default 256)
	 */
	public int getMaxTokenLength() {
		return maxTokenLength;
	}

	/**
	 * Sets the longest token held to judge a structured header.
	 * @param maxTokenLength the limit, or zero or less for none
	 */
	public void setMaxTokenLength(int maxTokenLength) {
		this.maxTokenLength = maxTokenLength;
	}

	/**
	 * Returns the longest element of an RFC 5322 address, message-id or date
	 * header that is held to be read: one mailbox, one group name, one
	 * message-id, or a date. A header may have any number of them. An
	 * element that is longer is not read; the rest of the header is delivered
	 * as text.
	 * @return the limit, or zero or less for none (default 4096)
	 */
	public int getMaxElementLength() {
		return maxElementLength;
	}

	/**
	 * Sets the longest element of a structured RFC 5322 header.
	 * @param maxElementLength the limit, or zero or less for none
	 */
	public void setMaxElementLength(int maxElementLength) {
		this.maxElementLength = maxElementLength;
	}

}
