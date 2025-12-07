/*
 * DecodeResult.java
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
 * Result class for decoding operations.
 * Contains information about the decoding process including how many bytes were decoded
 * and how many bytes were consumed from the input.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DecodeResult {

	/** Number of bytes that were successfully decoded and written to the output buffer */
	final int decodedBytes;

	/** Number of bytes that were consumed from the input buffer during decoding */
	final int consumedBytes;

	/**
	 * Constructs a DecodeResult with the specified decoded and consumed byte counts.
	 *
	 * @param decodedBytes the number of bytes successfully decoded
	 * @param consumedBytes the number of bytes consumed from input
	 */
	DecodeResult(int decodedBytes, int consumedBytes) {
		this.decodedBytes = decodedBytes;
		this.consumedBytes = consumedBytes;
	}

}
