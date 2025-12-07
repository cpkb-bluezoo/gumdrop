/*
 * GroupEmailAddress.java
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

import java.util.Collections;
import java.util.List;

/**
 * This class represents an Internet group email address using the syntax of RFC 5322.
 * Group email addresses are rarely used, this is only included for completeness.
 * @see <a href='https://datatracker.ietf.org/doc/html/rfc5322#section-3.4'>RFC 5322</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GroupEmailAddress extends EmailAddress {

	private final String groupName;
	private final List<EmailAddress> members;

	/**
	 * Constructor.
	 * @param groupName the group name
	 * @param members the list of members of the group
	 * @param comments comments encountered while parsing
	 */
	public GroupEmailAddress(String groupName, List<EmailAddress> members, List<String> comments) {
		super(null, comments);
		this.groupName = groupName;
		this.members = members == null ? Collections.emptyList() : Collections.unmodifiableList(members);
	}

	/**
	 * Returns the group name. This is a description of the group that the
	 * members belong to.
	 * @return the group name
	 */
	public String getGroupName() {
		return groupName;
	}

	/**
	 * Returns the list of members of the group. This may be an empty list
	 * if the group name is symbolic or mail delivery to the group is
	 * handled out of bounds.
	 * @return the unmodifiable list of deliverable email addresses of members of the group
	 */
	public List<EmailAddress> getMembers() {
		return members;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(groupName).append(": ");
		for (int i = 0; i < members.size(); i++) {
			sb.append(members.get(i).toString());
			if (i < members.size() - 1) {
				sb.append(", ");
			}
		}
		sb.append(";");
		if (comments != null) {
			for (String comment : getComments()) {
				sb.append(" (").append(comment).append(")");
			}
		}
		return sb.toString();
	}

}
