/*
 * OrderedSubjectThreader.java
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
import java.util.Comparator;
import java.util.List;

/**
 * RFC 5256 ORDEREDSUBJECT threading.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class OrderedSubjectThreader {

    private OrderedSubjectThreader() {
    }

    public static List<ThreadBranch> thread(Mailbox mailbox,
            List<Integer> matches) throws IOException {
        if (matches.isEmpty()) {
            return new ArrayList<>();
        }
        List<ThreadMessageRecord> records =
                ThreadMessageRecord.load(mailbox, matches);
        List<ThreadMessageRecord> sorted =
                ThreadMessageRecord.sortedBySubjectDateSeq(records);

        List<List<ThreadMessageRecord>> groups = new ArrayList<>();
        List<ThreadMessageRecord> current = new ArrayList<>();
        String prevSubject = null;
        for (ThreadMessageRecord rec : sorted) {
            if (prevSubject == null
                    || ImapUnicodeCasemap.compare(prevSubject,
                            rec.baseSubject) != 0) {
                if (!current.isEmpty()) {
                    groups.add(current);
                }
                current = new ArrayList<>();
                prevSubject = rec.baseSubject;
            }
            current.add(rec);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        groups.sort(new Comparator<List<ThreadMessageRecord>>() {
            @Override
            public int compare(List<ThreadMessageRecord> a,
                    List<ThreadMessageRecord> b) {
                int c = a.get(0).sentDate.compareTo(b.get(0).sentDate);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(a.get(0).sequenceNumber,
                        b.get(0).sequenceNumber);
            }
        });

        List<ThreadBranch> threads = new ArrayList<>(groups.size());
        for (List<ThreadMessageRecord> group : groups) {
            threads.add(toBranch(group));
        }
        return threads;
    }

    private static ThreadBranch toBranch(List<ThreadMessageRecord> group) {
        ThreadBranch root = new ThreadBranch();
        if (group.size() == 1) {
            root.addMember((long) group.get(0).sequenceNumber);
            return root;
        }
        root.addMember((long) group.get(0).sequenceNumber);
        root.addMember((long) group.get(1).sequenceNumber);
        for (int i = 2; i < group.size(); i++) {
            ThreadBranch sibling = new ThreadBranch();
            sibling.addMember((long) group.get(i).sequenceNumber);
            root.addNested(sibling);
        }
        return root;
    }
}
