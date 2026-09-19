/*
 * ThreadHeaders.java
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
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
