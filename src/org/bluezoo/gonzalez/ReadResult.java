/*
 * ReadResult.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

/**
 * Result of a read operation in DeclParser implementations.
 * 
 * <p>Used to indicate the outcome of parsing attempts:
 * <ul>
 * <li>{@link #OK} - Successfully parsed the expected content</li>
 * <li>{@link #FAILURE} - The expected content was not found (parsing failed)</li>
 * <li>{@link #UNDERFLOW} - More data is needed to determine the result</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum ReadResult {
    /** Successfully parsed the expected content */
    OK,
    /** The expected content was not found (parsing failed) */
    FAILURE,
    /** More data is needed to determine the result */
    UNDERFLOW
}
