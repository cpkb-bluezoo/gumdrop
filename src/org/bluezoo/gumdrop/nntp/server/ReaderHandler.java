/*
 * ReaderHandler.java
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

package org.bluezoo.gumdrop.nntp.server;

import java.time.Instant;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

/**
 * Application policy for the reader commands (RFC 3977 section 6 onwards).
 * The protocol calls these off the event loop, since opening mailboxes and
 * reading articles block; the handler must answer each call through the
 * state it is given.
 *
 * <p>CAPABILITIES, MODE, DATE, HELP and QUIT have only one possible
 * answer and are handled by the protocol itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ReaderHandler {

    /**
     * GROUP and LISTGROUP: selects a group.
     *
     * @param state the response
     * @param store the store of groups
     * @param group the group name
     */
    void selectGroup(GroupState state, MailboxStore store, String group);

    /**
     * LIST ACTIVE, LIST NEWSGROUPS and NEWGROUPS.
     *
     * @param state the response
     * @param store the store of groups
     * @param type which listing
     * @param wildmat a wildmat of group names (RFC 3977 section 4.2), or
     * null for all
     * @param since for {@link ListType#NEW_GROUPS}, the time after which
     * a group must have been created; otherwise null
     */
    void listGroups(ListState state, MailboxStore store, ListType type,
                    String wildmat, Instant since);

    /**
     * LISTGROUP: the article numbers in a range of the current group.
     *
     * @param state the response
     * @param mailbox the current group
     * @param range a {@link ArticleRef.Kind#RANGE range}
     */
    void listArticles(ListState state, Mailbox mailbox, ArticleRef range);

    /**
     * ARTICLE, HEAD, BODY, STAT, LAST and NEXT.
     *
     * @param state the response
     * @param mailbox the current group, null if none is selected and the
     * article is identified by message-id
     * @param ref which article
     * @param part which part the client asked for
     */
    void retrieve(ArticleState state, Mailbox mailbox, ArticleRef ref,
                  ArticlePart part);

    /**
     * OVER.
     *
     * @param state the response
     * @param mailbox the current group, null if none is selected and the
     * articles are identified by message-id
     * @param ref a range, or a message-id
     */
    void overview(OverState state, Mailbox mailbox, ArticleRef ref);

    /**
     * HDR.
     *
     * @param state the response
     * @param mailbox the current group, null if none is selected and the
     * articles are identified by message-id
     * @param ref a range, or a message-id
     * @param field the header field name
     */
    void headers(OverState state, Mailbox mailbox, ArticleRef ref, String field);

    /**
     * POST: the client wishes to post an article (RFC 3977 section 6.3.1).
     * Answer with {@link PostState#accept} or {@link PostState#refuse}.
     *
     * @param state the state to answer through
     * @param store the open store
     */
    void post(PostState state, MailboxStore store);

}
