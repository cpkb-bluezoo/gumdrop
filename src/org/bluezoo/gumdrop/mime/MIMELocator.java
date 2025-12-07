/*
 * MIMELocator.java
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
 * A locator enables the recipient of parsing events to be informed of where
 * within the MIME entity a parsing event has occurred.
 * The parser will notify a MIMEHandler with an instance of this interface
 * before parsing begins. The instance's state will change during the parsing
 * process so it is not suitable for permanent association with any resulting
 * artifacts.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MIMELocator {

	/**
	 * Returns the current byte offset within the overall entity.
	 * @return the position of the parser relative to the start of the
	 * MIME entity byte data
	 */
	long getOffset();

	/**
	 * Returns the current line number. 1-indexed.
	 * @return the logical line number within the MIME entity, i.e. the
	 * number of CRLF tokens seen by the parser
	 */
	long getLineNumber();

	/**
	 * Returns the current column number. Zero indexed.
	 * @return the logical column number within the MIME entity, i.e. the
	 * byte offset since the last CRLF was seen by the parser
	 */
	long getColumnNumber();

}

