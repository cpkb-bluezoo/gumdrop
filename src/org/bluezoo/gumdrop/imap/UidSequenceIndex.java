/*
 * UidSequenceIndex.java
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
import org.bluezoo.gumdrop.mailbox.MessageSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Sorted UID-to-sequence mapping for a mailbox snapshot, used to resolve
 * UID {@link MessageSet}s without scanning every message.
 */
final class UidSequenceIndex {

    private final int[] seqNums;
    private final long[] uids;

    private UidSequenceIndex(int[] seqNums, long[] uids) {
        this.seqNums = seqNums;
        this.uids = uids;
    }

    static UidSequenceIndex build(Mailbox mailbox) throws IOException {
        int msgCount = mailbox.getMessageCount();
        int[] seqScratch = new int[msgCount];
        long[] uidScratch = new long[msgCount];
        int size = 0;
        for (int msgNum = 1; msgNum <= msgCount; msgNum++) {
            if (mailbox.isDeleted(msgNum)) {
                continue;
            }
            seqScratch[size] = msgNum;
            uidScratch[size] = parseUid(mailbox.getUniqueId(msgNum));
            size++;
        }
        int[] seqNums = new int[size];
        long[] uids = new long[size];
        System.arraycopy(seqScratch, 0, seqNums, 0, size);
        System.arraycopy(uidScratch, 0, uids, 0, size);
        return new UidSequenceIndex(seqNums, uids);
    }

    List<Integer> resolve(MessageSet seqSet, long lastUid) {
        LinkedHashSet<Integer> matching = new LinkedHashSet<Integer>();
        for (MessageSet.Range range : seqSet.getRanges()) {
            long rangeStart = range.getStart() == MessageSet.WILDCARD
                    ? lastUid : range.getStart();
            long rangeEnd = range.getEnd() == MessageSet.WILDCARD
                    ? lastUid : range.getEnd();
            if (rangeStart > rangeEnd) {
                long tmp = rangeStart;
                rangeStart = rangeEnd;
                rangeEnd = tmp;
            }
            int lo = lowerBound(uids, rangeStart);
            int hi = upperBound(uids, rangeEnd);
            for (int i = lo; i < hi; i++) {
                matching.add(Integer.valueOf(seqNums[i]));
            }
        }
        return new ArrayList<Integer>(matching);
    }

    private static long parseUid(String uniqueId) {
        try {
            return Long.parseLong(uniqueId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int lowerBound(long[] uids, long target) {
        int lo = 0;
        int hi = uids.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (uids[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int upperBound(long[] uids, long target) {
        int lo = 0;
        int hi = uids.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (uids[mid] <= target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
