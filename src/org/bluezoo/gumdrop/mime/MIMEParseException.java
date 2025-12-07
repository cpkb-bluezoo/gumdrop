/*
 * MIMEParseException.java
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

package org.bluezoo.gumdrop.mime;

/**
 * An exception indicating that a fatal error occurred during MIME parsing.
 * This can be thrown by either the parser itself or by the handler if it
 * wishes to abort parsing.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MIMEParseException extends Exception {

	private static final long serialVersionUID = 1L;

	private final long offset;
	private final long lineNumber;
	private final long columnNumber;

	/**
	 * Constructs a new exception with the specified message.
	 * @param message the detail message
	 */
	public MIMEParseException(String message) {
		super(message);
		this.offset = -1;
		this.lineNumber = -1;
		this.columnNumber = -1;
	}

	/**
	 * Constructs a new exception with the specified message and locator.
	 * @param message the detail message
	 * @param locator the locator providing position information
	 */
	public MIMEParseException(String message, MIMELocator locator) {
		super(formatMessage(message, locator));
		if (locator != null) {
			this.offset = locator.getOffset();
			this.lineNumber = locator.getLineNumber();
			this.columnNumber = locator.getColumnNumber();
		} else {
			this.offset = -1;
			this.lineNumber = -1;
			this.columnNumber = -1;
		}
	}

	/**
	 * Constructs a new exception with the specified cause.
	 * @param cause the cause of this exception
	 */
	public MIMEParseException(Throwable cause) {
		super(cause);
		this.offset = -1;
		this.lineNumber = -1;
		this.columnNumber = -1;
	}

	/**
	 * Constructs a new exception with the specified message and cause.
	 * @param message the detail message
	 * @param cause the cause of this exception
	 */
	public MIMEParseException(String message, Throwable cause) {
		super(message, cause);
		this.offset = -1;
		this.lineNumber = -1;
		this.columnNumber = -1;
	}

	/**
	 * Returns the byte offset where the error occurred, or -1 if unknown.
	 * @return the byte offset
	 */
	public long getOffset() {
		return offset;
	}

	/**
	 * Returns the line number where the error occurred, or -1 if unknown.
	 * @return the line number (1-indexed)
	 */
	public long getLineNumber() {
		return lineNumber;
	}

	/**
	 * Returns the column number where the error occurred, or -1 if unknown.
	 * @return the column number (0-indexed)
	 */
	public long getColumnNumber() {
		return columnNumber;
	}

	private static String formatMessage(String message, MIMELocator locator) {
		if (locator == null) {
			return message;
		}
		return String.format("%s (line %d, column %d, offset %d)",
			message, locator.getLineNumber(), locator.getColumnNumber(), locator.getOffset());
	}

}

