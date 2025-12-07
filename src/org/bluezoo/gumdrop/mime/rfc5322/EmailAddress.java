/*
 * EmailAddress.java
 * Copyright (C) 2013, 2025 Chris Burdess
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

import java.util.Collections;
import java.util.List;

/**
 * This class represents an Internet email address using the syntax of RFC 5322.
 * Typical address syntax is of the form "user@host.domain" or
 * "Personal Name &lt;user@host.domain&gt;".
 * @see <a href='https://datatracker.ietf.org/doc/html/rfc5322#section-3.4'>RFC 5322</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EmailAddress {

	private final String displayName;
	private final String localPart;
	private final String domain;
	private final boolean simpleAddress;
	final List<String> comments; // package private for GroupEmailAddress

	/**
	 * Constructor.
	 * @param displayName the human-readable display name for the address. May be null
	 * @param localPart the local-part of the mailbox (before the '@'). Cannot be null
	 * @param domain the domain of the mailbox (after the '@'). Cannot be null
	 * @param comments any comments discovered while parsing. May be null
	 */
	public EmailAddress(String displayName, String localPart, String domain, List<String> comments) {
		if (localPart == null || domain == null) {
			throw new NullPointerException("localPart and domain must not be null");
		}
		this.displayName = displayName;
		this.localPart = localPart;
		this.domain = domain;
		this.comments = comments == null ? null : Collections.unmodifiableList(comments);
		this.simpleAddress = false;
	}

	/**
	 * Constructor.
	 * @param displayName the human-readable display name for the address. May be null
	 * @param localPart the local-part of the mailbox (before the '@'). Cannot be null
	 * @param domain the domain of the mailbox (after the '@'). Cannot be null
	 * @param simpleAddress if there was only an address with no angle brackets
	 */
	public EmailAddress(String displayName, String localPart, String domain, boolean simpleAddress) {
		if (localPart == null || domain == null) {
			throw new NullPointerException("localPart and domain must not be null");
		}
		this.displayName = displayName;
		this.localPart = localPart;
		this.domain = domain;
		this.comments = null;
		this.simpleAddress = simpleAddress;
	}

	/**
	 * Package-private constructor for GroupEmailAddress which has no real address.
	 */
	EmailAddress(String displayName, List<String> comments) {
		this.displayName = displayName;
		this.localPart = "";
		this.domain = "";
		this.comments = comments == null ? null : Collections.unmodifiableList(comments);
		this.simpleAddress = false;
	}

	/**
	 * Returns the display name for this address. This is an optional
	 * human-readable name for the address. It is not used for delivery.
	 * @return the display name, or null if not present
	 */
	public String getDisplayName() {
		return displayName;
	}

	/**
	 * Returns the local-part of the mailbox address (the part before the '@').
	 * @return the local-part
	 */
	public String getLocalPart() {
		return localPart;
	}

	/**
	 * Returns the domain of the mailbox address (the part after the '@').
	 * @return the domain
	 */
	public String getDomain() {
		return domain;
	}

	/**
	 * Returns the full mailbox address in the form local-part@domain.
	 * This is a convenience method that combines localPart and domain.
	 * @return the mailbox address for delivering the mail
	 */
	public String getAddress() {
		if (localPart.isEmpty() && domain.isEmpty()) {
			return "";
		}
		return localPart + "@" + domain;
	}

	/**
	 * Returns comments associated with this address, if any. These are
	 * generally ignorable.
	 * @return unmodifiable list of comments, or null if none
	 */
	public List<String> getComments() {
		return comments;
	}

	/**
	 * Indicates whether this is a simple address in the legacy format.
	 * @return true if the original address was in the legacy format without angle brackets
	 */
	public boolean isSimpleAddress() {
		return simpleAddress;
	}

	@Override
	public int hashCode() {
		return localPart.hashCode() + domain.toLowerCase().hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof EmailAddress)) {
			return false;
		}
		EmailAddress o = (EmailAddress) other;
		// Local-part is case-sensitive, domain is case-insensitive
		return localPart.equals(o.localPart) && domain.equalsIgnoreCase(o.domain);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		if (displayName != null && !displayName.isEmpty()) {
			sb.append(displayName).append(' ');
		}
		sb.append('<').append(getAddress()).append('>');
		if (comments != null) {
			for (String comment : comments) {
				sb.append(" (").append(comment).append(')');
			}
		}
		return sb.toString();
	}

}
