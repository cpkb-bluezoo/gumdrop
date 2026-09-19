/*
 * ZoneJournal.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory journal of zone changes keyed by post-update SOA serial (RFC 1995 IXFR).
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
