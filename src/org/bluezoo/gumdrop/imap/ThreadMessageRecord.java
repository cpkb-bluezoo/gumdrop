/*
 * ThreadMessageRecord.java
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
import java.util.List;

/**
 * Per-message data used by RFC 5256 threading.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ThreadMessageRecord {

    final int sequenceNumber;
    final String messageId;
    final List<String> references;
    final String baseSubject;
    final Instant sentDate;
    final boolean replyOrForward;

    ThreadMessageRecord(int sequenceNumber, String messageId,
            List<String> references, String baseSubject,
            Instant sentDate, boolean replyOrForward) {
        this.sequenceNumber = sequenceNumber;
        this.messageId = messageId;
        this.references = references;
        this.baseSubject = baseSubject;
        this.sentDate = sentDate;
        this.replyOrForward = replyOrForward;
    }

    static List<ThreadMessageRecord> load(Mailbox mailbox,
            List<Integer> sequenceNumbers) throws IOException {
        List<ThreadMessageRecord> list =
                new ArrayList<>(sequenceNumbers.size());
        for (Integer seq : sequenceNumbers) {
            MessageContext ctx = mailbox.getMessageContext(seq);
            if (ctx == null) {
                continue;
            }
            String mid = ThreadHeaders.messageId(ctx);
            if (mid == null) {
                mid = ThreadHeaders.syntheticId(seq);
            }
            String subjectRaw = ctx.getHeader("Subject");
            list.add(new ThreadMessageRecord(
                    seq,
                    mid,
                    ThreadHeaders.references(ctx),
                    SortKeyAccess.subject(ctx),
                    SortKeyAccess.date(ctx),
                    BaseSubject.isReplyOrForward(subjectRaw)));
        }
        return list;
    }

    static List<ThreadMessageRecord> sortedBySubjectDateSeq(
            List<ThreadMessageRecord> records) {
        List<ThreadMessageRecord> copy = new ArrayList<>(records);
        copy.sort(new Comparator<ThreadMessageRecord>() {
            @Override
            public int compare(ThreadMessageRecord a, ThreadMessageRecord b) {
                int c = ImapUnicodeCasemap.compare(a.baseSubject, b.baseSubject);
                if (c != 0) {
                    return c;
                }
                c = a.sentDate.compareTo(b.sentDate);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(a.sequenceNumber, b.sequenceNumber);
            }
        });
        return copy;
    }
}
