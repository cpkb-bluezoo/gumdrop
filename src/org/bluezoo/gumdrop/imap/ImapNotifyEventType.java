/*
 * ImapNotifyEventType.java
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

import java.util.Locale;

/**
 * NOTIFY event types from RFC 5465 section 5.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum ImapNotifyEventType {

    MESSAGE_NEW("MessageNew", true),
    MESSAGE_EXPUNGE("MessageExpunge", true),
    FLAG_CHANGE("FlagChange", true),
    ANNOTATION_CHANGE("AnnotationChange", true),
    MAILBOX_NAME("MailboxName", false),
    SUBSCRIPTION_CHANGE("SubscriptionChange", false),
    MAILBOX_METADATA_CHANGE("MailboxMetadataChange", false),
    SERVER_METADATA_CHANGE("ServerMetadataChange", false);

    private final String imapName;
    private final boolean messageEvent;

    ImapNotifyEventType(String imapName, boolean messageEvent) {
        this.imapName = imapName;
        this.messageEvent = messageEvent;
    }

    public String getImapName() {
        return imapName;
    }

    public boolean isMessageEvent() {
        return messageEvent;
    }

    public static ImapNotifyEventType fromImapName(String name) {
        if (name == null) {
            return null;
        }
        String upper = name.toUpperCase(Locale.ENGLISH);
        for (ImapNotifyEventType type : values()) {
            if (type.imapName.equalsIgnoreCase(upper)) {
                return type;
            }
        }
        if ("MESSAGENEW".equalsIgnoreCase(upper)) {
            return MESSAGE_NEW;
        }
        if ("MESSAGEEXPUNGE".equalsIgnoreCase(upper)) {
            return MESSAGE_EXPUNGE;
        }
        if ("FLAGCHANGE".equalsIgnoreCase(upper)) {
            return FLAG_CHANGE;
        }
        if ("ANNOTATIONCHANGE".equalsIgnoreCase(upper)) {
            return ANNOTATION_CHANGE;
        }
        if ("MAILBOXNAME".equalsIgnoreCase(upper)) {
            return MAILBOX_NAME;
        }
        if ("SUBSCRIPTIONCHANGE".equalsIgnoreCase(upper)) {
            return SUBSCRIPTION_CHANGE;
        }
        if ("MAILBOXMETADATACHANGE".equalsIgnoreCase(upper)) {
            return MAILBOX_METADATA_CHANGE;
        }
        if ("SERVERMETADATACHANGE".equalsIgnoreCase(upper)) {
            return SERVER_METADATA_CHANGE;
        }
        return null;
    }
}
