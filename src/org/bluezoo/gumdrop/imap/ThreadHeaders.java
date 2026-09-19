/*
 * ThreadHeaders.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentIDParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Message-ID and References extraction for RFC 5256 REFERENCES threading.
 */
public final class ThreadHeaders {

    private static final CharsetDecoder MSGID_DECODER =
            StandardCharsets.US_ASCII.newDecoder();

    private ThreadHeaders() {
    }

    public static String messageId(MessageContext ctx) throws IOException {
        return firstId(ctx.getHeader("Message-ID"));
    }

    public static List<String> references(MessageContext ctx)
            throws IOException {
        String refs = ctx.getHeader("References");
        if (refs != null && !refs.isEmpty()) {
            List<String> ids = parseIdList(refs);
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        String irt = ctx.getHeader("In-Reply-To");
        String single = firstId(irt);
        if (single != null) {
            return Collections.singletonList(single);
        }
        return Collections.emptyList();
    }

    static String firstId(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(
                headerValue.getBytes(StandardCharsets.US_ASCII));
        ContentID id = ContentIDParser.parse(buf, MSGID_DECODER);
        return id != null ? id.toString() : null;
    }

    static List<String> parseIdList(String headerValue) {
        ByteBuffer buf = ByteBuffer.wrap(
                headerValue.getBytes(StandardCharsets.US_ASCII));
        List<ContentID> parsed =
                ContentIDParser.parseList(buf, MSGID_DECODER);
        if (parsed == null || parsed.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(parsed.size());
        for (ContentID id : parsed) {
            out.add(id.toString());
        }
        return out;
    }

    public static String syntheticId(int sequenceNumber) {
        return "<gumdrop.thread." + sequenceNumber + "@local>";
    }
}
