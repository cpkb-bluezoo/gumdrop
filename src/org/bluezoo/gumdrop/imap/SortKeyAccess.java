/*
 * SortKeyAccess.java
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
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Reads RFC 5256 sort key values from a {@link MessageContext}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SortKeyAccess {

    private SortKeyAccess() {
    }

    public static Instant arrival(MessageContext ctx) {
        return SortSentDate.toSortInstant(null, ctx.getInternalDate());
    }

    public static Instant date(MessageContext ctx) throws IOException {
        return SortSentDate.toSortInstant(ctx.getSentDate(),
                ctx.getInternalDate());
    }

    public static long size(MessageContext ctx) {
        return ctx.getSize();
    }

    public static String subject(MessageContext ctx) throws IOException {
        String raw = ctx.getHeader("Subject");
        if (raw == null) {
            return "";
        }
        return BaseSubject.extract(raw);
    }

    public static String from(MessageContext ctx) throws IOException {
        return firstAddrMailbox(ctx.getHeader("From"));
    }

    public static String to(MessageContext ctx) throws IOException {
        return firstAddrMailbox(ctx.getHeader("To"));
    }

    public static String cc(MessageContext ctx) throws IOException {
        return firstAddrMailbox(ctx.getHeader("Cc"));
    }

    private static String firstAddrMailbox(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return "";
        }
        List<EmailAddress> list =
                EmailAddressParser.parseEmailAddressList(headerValue);
        if (list == null || list.isEmpty()) {
            return "";
        }
        EmailAddress first = list.get(0);
        String addr = first.getAddress();
        return addr != null ? addr : "";
    }
}
