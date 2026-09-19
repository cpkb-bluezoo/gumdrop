/*
 * ThreadMessageRecord.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageContext;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-message data used by RFC 5256 threading.
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
        copy.sort((a, b) -> {
            int c = ImapUnicodeCasemap.compare(a.baseSubject, b.baseSubject);
            if (c != 0) {
                return c;
            }
            c = a.sentDate.compareTo(b.sentDate);
            if (c != 0) {
                return c;
            }
            return Integer.compare(a.sequenceNumber, b.sequenceNumber);
        });
        return copy;
    }
}
