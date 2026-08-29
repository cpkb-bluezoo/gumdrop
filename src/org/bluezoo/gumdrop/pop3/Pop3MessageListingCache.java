/*
 * Pop3MessageListingCache.java
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

package org.bluezoo.gumdrop.pop3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.pop3.handler.MessageListingCacheHost;

/**
 * Per-session cache of POP3 LIST/UIDL listing rows. Built once from
 * {@link Mailbox#getMessageList()} and the derived unique IDs, then
 * reused for subsequent bulk LIST/UIDL commands until the transaction
 * state changes (DELE removes one row; RSET drops the cache so the next
 * listing reflects restored messages).
 */
public final class Pop3MessageListingCache {

    /**
     * One non-deleted message as returned by bulk LIST/UIDL.
     */
    public static final class Entry {
        private final int messageNumber;
        private final long size;
        private final String uniqueId;

        public Entry(int messageNumber, long size, String uniqueId) {
            this.messageNumber = messageNumber;
            this.size = size;
            this.uniqueId = uniqueId;
        }

        public int getMessageNumber() {
            return messageNumber;
        }

        public long getSize() {
            return size;
        }

        public String getUniqueId() {
            return uniqueId;
        }
    }

    private List<Entry> entries;

    /**
     * Returns the cached listing, building it from {@code mailbox} on
     * first use.
     */
    public List<Entry> snapshot(Mailbox mailbox) throws IOException {
        if (entries == null) {
            build(mailbox);
        }
        return entries;
    }

    /** Drops the cache so the next listing rebuilds from the mailbox. */
    public void invalidate() {
        entries = null;
    }

    /**
     * Removes one message from a built cache after DELE; no-op if the
     * cache has not been built yet.
     */
    public void removeMessage(int messageNumber) {
        if (entries == null) {
            return;
        }
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().getMessageNumber() == messageNumber) {
                it.remove();
                return;
            }
        }
    }

    /**
     * Resolves the session cache from a {@link MessageListingCacheHost}
     * state object, or {@code null} when {@code state} is not hosted by
     * the protocol handler.
     */
    public static Pop3MessageListingCache forState(Object state) {
        if (state instanceof MessageListingCacheHost) {
            return ((MessageListingCacheHost) state).getMessageListingCache();
        }
        return null;
    }

    private void build(Mailbox mailbox) throws IOException {
        List<Entry> built = new ArrayList<Entry>();
        Iterator<MessageDescriptor> messages = mailbox.getMessageList();
        while (messages.hasNext()) {
            MessageDescriptor msg = messages.next();
            int msgNum = msg.getMessageNumber();
            if (!mailbox.isDeleted(msgNum)) {
                built.add(new Entry(msgNum, msg.getSize(),
                        mailbox.getUniqueId(msgNum)));
            }
        }
        entries = built;
    }
}
