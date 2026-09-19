/*
 * MessageSorter.java
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
import org.bluezoo.gumdrop.mailbox.MessageContext;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 5256 SORT ordering over a list of matching message sequence numbers.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageSorter {

    private MessageSorter() {
    }

    /**
     * Sorts message numbers in place according to the sort program.
     *
     * @param mailbox mailbox for message context access
     * @param messageNumbers modifiable list of sequence numbers to sort
     * @param sortProgram sort criteria in priority order
     */
    public static void sort(Mailbox mailbox, List<Integer> messageNumbers,
            List<SortCriterion> sortProgram) throws IOException {
        if (messageNumbers.size() < 2 || sortProgram.isEmpty()) {
            return;
        }
        Map<Integer, MessageContext> contexts = new HashMap<>();
        for (Integer num : messageNumbers) {
            MessageContext ctx = mailbox.getMessageContext(num);
            if (ctx != null) {
                contexts.put(num, ctx);
            }
        }
        Comparator<Integer> cmp = buildComparator(sortProgram, contexts);
        Collections.sort(messageNumbers, cmp);
    }

    private static Comparator<Integer> buildComparator(
            List<SortCriterion> sortProgram,
            Map<Integer, MessageContext> contexts) {
        List<Comparator<Integer>> parts = new ArrayList<>();
        for (SortCriterion criterion : sortProgram) {
            Comparator<Integer> keyCmp = keyComparator(criterion.getKey(), contexts);
            if (criterion.isReverse()) {
                keyCmp = keyCmp.reversed();
            }
            parts.add(keyCmp);
        }
        parts.add(Comparator.naturalOrder());
        return new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                for (Comparator<Integer> part : parts) {
                    int c = part.compare(a, b);
                    if (c != 0) {
                        return c;
                    }
                }
                return 0;
            }
        };
    }

    private static Comparator<Integer> keyComparator(final SortKey key,
            final Map<Integer, MessageContext> contexts) {
        return new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                try {
                    return compareKey(key, contexts.get(a), contexts.get(b),
                            a, b);
                } catch (IOException e) {
                    return Integer.compare(a, b);
                }
            }
        };
    }

    private static int compareKey(SortKey key, MessageContext ca,
            MessageContext cb, int seqA, int seqB) throws IOException {
        switch (key) {
            case ARRIVAL:
                return compareInstant(SortKeyAccess.arrival(ca),
                        SortKeyAccess.arrival(cb));
            case DATE:
                return compareInstant(SortKeyAccess.date(ca),
                        SortKeyAccess.date(cb));
            case SIZE:
                return Long.compare(
                        ca != null ? SortKeyAccess.size(ca) : 0L,
                        cb != null ? SortKeyAccess.size(cb) : 0L);
            case SUBJECT:
                return ImapUnicodeCasemap.compare(
                        ca != null ? SortKeyAccess.subject(ca) : "",
                        cb != null ? SortKeyAccess.subject(cb) : "");
            case FROM:
                return ImapUnicodeCasemap.compare(
                        ca != null ? SortKeyAccess.from(ca) : "",
                        cb != null ? SortKeyAccess.from(cb) : "");
            case TO:
                return ImapUnicodeCasemap.compare(
                        ca != null ? SortKeyAccess.to(ca) : "",
                        cb != null ? SortKeyAccess.to(cb) : "");
            case CC:
                return ImapUnicodeCasemap.compare(
                        ca != null ? SortKeyAccess.cc(ca) : "",
                        cb != null ? SortKeyAccess.cc(cb) : "");
            default:
                return Integer.compare(seqA, seqB);
        }
    }

    private static int compareInstant(Instant a, Instant b) {
        Instant ia = a != null ? a : Instant.EPOCH;
        Instant ib = b != null ? b : Instant.EPOCH;
        return ia.compareTo(ib);
    }
}
