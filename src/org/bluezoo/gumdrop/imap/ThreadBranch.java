/*
 * ThreadBranch.java
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

package org.bluezoo.gumdrop.imap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RFC 5256 THREAD response branch (parent/child chain plus nested splits).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ThreadBranch {

    private final List<Long> members = new ArrayList<>();
    private final List<ThreadBranch> nested = new ArrayList<>();

    public void addMember(long sequenceOrUid) {
        members.add(sequenceOrUid);
    }

    public void addNested(ThreadBranch branch) {
        nested.add(branch);
    }

    List<Long> membersMutable() {
        return members;
    }

    public List<Long> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public List<ThreadBranch> getNested() {
        return Collections.unmodifiableList(nested);
    }
}
