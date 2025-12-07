/*
 * MessageHandler.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mime.rfc5322;

import java.time.OffsetDateTime;
import java.util.List;

import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.MIMEHandler;
import org.bluezoo.gumdrop.mime.MIMEParseException;

/**
 * Receive notification of the logical content of an RFC 5322 email message.
 * This interface extends MIMEHandler to add email-specific callbacks for
 * structured headers defined in RFC 5322.
 * <p>
 * In addition to the MIME-level events from MIMEHandler, this interface
 * provides callbacks for:
 * <ul>
 * <li>Unstructured headers (Subject, Comments, etc.)</li>
 * <li>Date headers (Date, Resent-Date)</li>
 * <li>Address headers (From, To, Cc, Bcc, etc.)</li>
 * <li>Message-ID headers (Message-ID, References, In-Reply-To)</li>
 * <li>Obsolete syntax detection</li>
 * </ul>
 * </p>
 * @see <a href='https://datatracker.ietf.org/doc/html/rfc5322'>RFC 5322</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MessageHandler extends MIMEHandler {

	/**
	 * Receive notification of an unstructured header in the message.
	 * Headers are fields that occur at the beginning of a message or body part.
	 * They contain a name and an optional value.
	 * <p>
	 * Header values may be continued over multiple lines in the underlying
	 * message data. The value supplied here is the logical value of the
	 * header after any line unfolding and decoding of non-ASCII characters
	 * performed according to RFC 2047.
	 * </p>
	 * @param name the name of the header (field-name, cannot be null)
	 * @param value the header value (field-body, may be null)
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void header(String name, String value) throws MIMEParseException;

	/**
	 * Receive notification of a structured header field for which the value
	 * was unparseable. This indicates a malformed message, but handlers may
	 * choose to log or ignore these events instead of throwing an exception.
	 * @param name the name of the header (field-name, cannot be null)
	 * @param value the header value (field-body, may be null)
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void unexpectedHeader(String name, String value) throws MIMEParseException;

	/**
	 * Receive notification of a date header in the message.
	 * @param name the name of the header field, e.g. "Date"
	 * @param date the parsed date value of the header with timezone preserved
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void dateHeader(String name, OffsetDateTime date) throws MIMEParseException;

	/**
	 * Receive notification of an address header in the message.
	 * Some header fields (e.g. From, Sender) will only have one address.
	 * Others (To, Cc, Bcc) may have multiple addresses.
	 * The addresses list will never be empty. If the value in the underlying
	 * message did not contain valid addresses it will be reported via
	 * unexpectedHeader.
	 * @param name the name of the header field, e.g. "From", "To"
	 * @param addresses a non-empty list of parsed email addresses
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException;

	/**
	 * Receive notification of a message-id header in the message.
	 * Some header fields (e.g. Message-ID) will only have one ID.
	 * Others (References, In-Reply-To) may have multiple IDs.
	 * The contentIDs list will never be empty. If the value in the underlying
	 * message did not contain valid message-ids it will be reported via
	 * unexpectedHeader.
	 * @param name the name of the header field, e.g. "Message-ID", "References"
	 * @param contentIDs the parsed message-id values (same structure as Content-ID)
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void messageIDHeader(String name, List<ContentID> contentIDs) throws MIMEParseException;

	/**
	 * Receive notification of an obsolete but recoverable message structure.
	 * This method will be called before the corresponding normal parsing event
	 * to allow handlers to gather statistics on obsolete structures while
	 * still processing the message content normally.
	 * @param type the type of obsolete structure detected
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException;

}

