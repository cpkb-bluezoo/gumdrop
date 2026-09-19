/*
 * ZoneJournal.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory journal of zone changes keyed by post-update SOA serial (RFC 1995 IXFR).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneJournal {

    private static final int DEFAULT_MAX_ENTRIES = 512;

    static final class Entry {
        final int serial;
        final List<DnsResourceRecord> deletions;
        final List<DnsResourceRecord> additions;

        Entry(int serial, ZoneChangeBatch batch) {
            this.serial = serial;
            this.deletions = new ArrayList<DnsResourceRecord>(batch.deletions());
            this.additions = new ArrayList<DnsResourceRecord>(batch.additions());
        }
    }

    private final LinkedList<Entry> entries = new LinkedList<Entry>();
    private final int maxEntries;

    ZoneJournal() {
        this(DEFAULT_MAX_ENTRIES);
    }

    ZoneJournal(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    void clear() {
        entries.clear();
    }

    void record(int newSerial, ZoneChangeBatch batch) {
        if (batch.isEmpty()) {
            return;
        }
        entries.addLast(new Entry(newSerial, batch));
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Lowest serial still present in the journal, or -1 if empty.
     */
    int oldestSerial() {
        if (entries.isEmpty()) {
            return -1;
        }
        return entries.getFirst().serial;
    }

    /**
     * Returns journal entries with serial {@code (clientSerial, currentSerial]},
     * in ascending serial order.
     */
    List<Entry> entriesAfter(int clientSerial, int currentSerial) {
        if (clientSerial >= currentSerial) {
            return Collections.emptyList();
        }
        List<Entry> result = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.serial > clientSerial && entry.serial <= currentSerial) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * True when every serial step up to {@code currentSerial} is journaled from
     * {@code clientSerial}.
     */
    boolean coversRange(int clientSerial, int currentSerial) {
        if (clientSerial >= currentSerial) {
            return true;
        }
        if (entries.isEmpty()) {
            return false;
        }
        int expected = clientSerial + 1;
        for (int s = expected; s <= currentSerial; s++) {
            if (!containsSerial(s)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsSerial(int serial) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).serial == serial) {
                return true;
            }
        }
        return false;
    }
}
