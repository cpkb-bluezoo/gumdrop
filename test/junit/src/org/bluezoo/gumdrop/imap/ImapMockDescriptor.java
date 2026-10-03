/*
 * ImapMockDescriptor.java
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.mailbox.ImapMessageDescriptor;

/**
 * Hand-written mock {@link ImapMessageDescriptor} (with its envelope, address
 * and body-structure collaborators) whose fields a test sets directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ImapMockDescriptor implements ImapMessageDescriptor {

    int number = 1;
    long size = 100;
    String uniqueId = "1";
    final Set<String> flags = new HashSet<String>();
    OffsetDateTime internalDate = OffsetDateTime.of(2025, 5, 5, 10, 0, 0, 0,
            ZoneOffset.UTC);
    Env envelope;
    Bs bodyStructure;

    @Override
    public int getMessageNumber() {
        return number;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public String getUniqueId() {
        return uniqueId;
    }

    @Override
    public Set<String> getFlags() {
        return flags;
    }

    @Override
    public OffsetDateTime getInternalDate() {
        return internalDate;
    }

    @Override
    public Envelope getEnvelope() {
        return envelope;
    }

    @Override
    public BodyStructure getBodyStructure() {
        return bodyStructure;
    }

    /** Mock envelope. */
    static final class Env implements Envelope {
        OffsetDateTime date = OffsetDateTime.of(2025, 5, 5, 10, 0, 0, 0,
                ZoneOffset.UTC);
        String subject = "subj";
        Address[] from = new Address[] { new Addr("Alice \"A\"", null, "alice", "example.com") };
        Address[] sender;
        Address[] replyTo;
        Address[] to = new Address[] {
            new Addr(null, "route", "bob", "example.com"),
            new Addr("Carol", null, "carol", null)
        };
        Address[] cc = new Address[0];
        Address[] bcc;
        String inReplyTo = "<irt@x>";
        String messageId = "<mid@x>";

        @Override
        public OffsetDateTime getDate() {
            return date;
        }

        @Override
        public String getSubject() {
            return subject;
        }

        @Override
        public Address[] getFrom() {
            return from;
        }

        @Override
        public Address[] getSender() {
            return sender;
        }

        @Override
        public Address[] getReplyTo() {
            return replyTo;
        }

        @Override
        public Address[] getTo() {
            return to;
        }

        @Override
        public Address[] getCc() {
            return cc;
        }

        @Override
        public Address[] getBcc() {
            return bcc;
        }

        @Override
        public String getInReplyTo() {
            return inReplyTo;
        }

        @Override
        public String getMessageId() {
            return messageId;
        }
    }

    /** Mock address. */
    static final class Addr implements Address {
        final String name;
        final String route;
        final String mailbox;
        final String host;

        Addr(String name, String route, String mailbox, String host) {
            this.name = name;
            this.route = route;
            this.mailbox = mailbox;
            this.host = host;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getRoute() {
            return route;
        }

        @Override
        public String getMailbox() {
            return mailbox;
        }

        @Override
        public String getHost() {
            return host;
        }
    }

    /** Mock body structure (leaf or multipart). */
    static final class Bs implements BodyStructure {
        String type = "text";
        String subtype = "plain";
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        String contentId;
        String description;
        String encoding;
        long size = 10;
        long lines = 1;
        Envelope envelope;
        BodyStructure body;
        BodyStructure[] parts;
        String md5;
        String disposition;
        Map<String, String> dispositionParameters;
        String[] language;
        String location;

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getSubtype() {
            return subtype;
        }

        @Override
        public Map<String, String> getParameters() {
            return parameters;
        }

        @Override
        public String getContentId() {
            return contentId;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getEncoding() {
            return encoding;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public long getLines() {
            return lines;
        }

        @Override
        public Envelope getEnvelope() {
            return envelope;
        }

        @Override
        public BodyStructure getBody() {
            return body;
        }

        @Override
        public BodyStructure[] getParts() {
            return parts;
        }

        @Override
        public String getMd5() {
            return md5;
        }

        @Override
        public String getDisposition() {
            return disposition;
        }

        @Override
        public Map<String, String> getDispositionParameters() {
            return dispositionParameters;
        }

        @Override
        public String[] getLanguage() {
            return language;
        }

        @Override
        public String getLocation() {
            return location;
        }
    }
}
