/*
 * ClientSelectedState.java
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

package org.bluezoo.gumdrop.imap.client;

/**
 * Operations available in SELECTED state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientSelectedState extends ClientAuthenticatedState {

    void close(CloseReplyHandler callback);

    void unselect(CloseReplyHandler callback);

    void expunge(ExpungeReplyHandler callback);

    void search(String criteria, SearchReplyHandler callback);

    void uidSearch(String criteria, SearchReplyHandler callback);

    void sort(String arguments, SearchReplyHandler callback);

    void uidSort(String arguments, SearchReplyHandler callback);

    void fetch(String sequenceSet, String dataItems, FetchReplyHandler callback);

    void uidFetch(String sequenceSet, String dataItems, FetchReplyHandler callback);

    void store(String sequenceSet, String action, String[] flags, StoreReplyHandler callback);

    void uidStore(String sequenceSet, String action, String[] flags, StoreReplyHandler callback);

    void copy(String sequenceSet, String mailbox, CopyReplyHandler callback);

    void uidCopy(String sequenceSet, String mailbox, CopyReplyHandler callback);

    void move(String sequenceSet, String mailbox, CopyReplyHandler callback);

    void uidMove(String sequenceSet, String mailbox, CopyReplyHandler callback);
}
