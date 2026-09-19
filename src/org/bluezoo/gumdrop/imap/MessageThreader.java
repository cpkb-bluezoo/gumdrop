/*
 * MessageThreader.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 5256 THREAD over a mailbox search result set.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageThreader {

    private MessageThreader() {
    }

    public static String thread(Mailbox mailbox, List<Integer> matches,
            ThreadAlgorithm algorithm, boolean uidMode) throws IOException {
        List<ThreadBranch> branches;
        switch (algorithm) {
            case ORDEREDSUBJECT:
                branches = OrderedSubjectThreader.thread(mailbox, matches);
                break;
            case REFERENCES:
                branches = ReferencesThreader.thread(mailbox, matches);
                break;
            default:
                branches = new ArrayList<>();
        }
        if (uidMode) {
            replaceWithUids(mailbox, branches);
        }
        return ThreadFormatter.format(branches);
    }

    private static void replaceWithUids(Mailbox mailbox,
            List<ThreadBranch> branches) throws IOException {
        for (ThreadBranch branch : branches) {
            replaceBranchUids(mailbox, branch);
        }
    }

    private static void replaceBranchUids(Mailbox mailbox, ThreadBranch branch)
            throws IOException {
        List<Long> members = branch.membersMutable();
        for (int i = 0; i < members.size(); i++) {
            int seq = members.get(i).intValue();
            members.set(i, parseUid(mailbox.getUniqueId(seq)));
        }
        for (ThreadBranch nested : branch.getNested()) {
            replaceBranchUids(mailbox, nested);
        }
    }

    private static long parseUid(String uid) {
        try {
            return Long.parseLong(uid);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
