/*
 * FetchData.java
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
 * Structured data from an IMAP FETCH response (RFC 9051 section 7.4.2),
 * representing the non-literal data items for a single message.
 *
 * <p>Data items correspond to RFC 9051 section 6.4.5 fetch attributes:
 * FLAGS, UID, RFC822.SIZE, INTERNALDATE, ENVELOPE, BODY[section].
 * Also includes EMAILID (RFC 8474), when the server advertises
 * {@code OBJECTID}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FetchData {

    private String[] flags;
    private long uid;
    private long size;
    private String internalDate;
    private Envelope envelope;
    private String bodySection;
    private String emailId;

    public String[] getFlags() {
        return flags;
    }

    public void setFlags(String[] flags) {
        this.flags = flags;
    }

    public long getUid() {
        return uid;
    }

    public void setUid(long uid) {
        this.uid = uid;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getInternalDate() {
        return internalDate;
    }

    public void setInternalDate(String internalDate) {
        this.internalDate = internalDate;
    }

    public Envelope getEnvelope() {
        return envelope;
    }

    public void setEnvelope(Envelope envelope) {
        this.envelope = envelope;
    }

    public String getBodySection() {
        return bodySection;
    }

    public void setBodySection(String bodySection) {
        this.bodySection = bodySection;
    }

    /**
     * Returns the RFC 8474 EMAILID from an {@code EMAILID (...)} FETCH
     * data item, or null if the FETCH did not request/return one.
     */
    public String getEmailId() {
        return emailId;
    }

    public void setEmailId(String emailId) {
        this.emailId = emailId;
    }

    /**
     * IMAP ENVELOPE structure from a FETCH response.
     */
    public static class Envelope {

        private String date;
        private String subject;
        private String from;
        private String sender;
        private String replyTo;
        private String to;
        private String cc;
        private String bcc;
        private String inReplyTo;
        private String messageId;

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getSender() {
            return sender;
        }

        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getReplyTo() {
            return replyTo;
        }

        public void setReplyTo(String replyTo) {
            this.replyTo = replyTo;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getCc() {
            return cc;
        }

        public void setCc(String cc) {
            this.cc = cc;
        }

        public String getBcc() {
            return bcc;
        }

        public void setBcc(String bcc) {
            this.bcc = bcc;
        }

        public String getInReplyTo() {
            return inReplyTo;
        }

        public void setInReplyTo(String inReplyTo) {
            this.inReplyTo = inReplyTo;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }
    }
}
