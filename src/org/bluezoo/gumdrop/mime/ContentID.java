/*
 * ContentID.java
 * Copyright (C) 2005 Chris Burdess
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
 * A MIME content-id or RFC 5322 msg-id value.
 * This corresponds to the msg-id production in RFC 5322 section 3.6.4 and
 * the id production in RFC 2045 section 7 (Content-ID).
 * The format is {@code <id-left@id-right>}.
 * @see <a href='https://www.rfc-editor.org/rfc/rfc5322#section-3.6.4'>RFC 5322</a>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-7'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentID {

	private final String localPart;
	private final String domain;

	/**
	 * Constructor.
	 * @param localPart the local-part of the id (the part before '@'). May not be null
	 * @param domain the domain of the id (the part after '@'). May not be null
	 * @exception NullPointerException if either argument is null
	 */
	public ContentID(String localPart, String domain) {
		if (localPart == null || domain == null) {
			throw new NullPointerException("localPart and domain must not be null");
		}
		this.localPart = localPart;
		this.domain = domain;
	}

	/**
	 * Returns the local-part of this content-id. This is the part of the id
	 * preceding the '@'.
	 * @return the local-part
	 */
	public String getLocalPart() {
		return localPart;
	}

	/**
	 * Returns the domain of this content-id. This is the part of the id
	 * following the '@'.
	 * @return the domain
	 */
	public String getDomain() {
		return domain;
	}

	@Override
	public int hashCode() {
		return toString().hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof ContentID)) {
			return false;
		}
		ContentID o = (ContentID) other;
		return localPart.equals(o.localPart) && domain.equals(o.domain);
	}

	@Override
	public String toString() {
		return "<" + localPart + "@" + domain + ">";
	}

}

