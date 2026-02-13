/*
 * MessageParser.java
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

import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.MIMEHandler;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEParser;
import org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * A parser for RFC 5322 email messages.
 * This parser extends MIMEParser to add support for email-specific
 * structured headers such as Date, From, To, Cc, Message-ID, etc.
 *
 * This parser uses a completely asynchronous, non-blocking,
 * push design pattern (also known as EDA or event driven architecture).
 * First a MessageHandler must be supplied to the parser for receiving
 * parsing events.
 * Then byte data can be supplied to the parser via the receive method as
 * and when it arrives, obviously in order. The MessageHandler (event sink)
 * will be notified of events that it can use to construct an in-memory
 * representation of the message (AST) or store its component parts via some
 * kind of message part storage service which allows for not storing
 * potentially large message representations entirely in memory.
 * The parser itself will not block the process receiving message byte data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageParser extends MIMEParser {

	private MessageHandler messageHandler;
	private boolean usedObsoleteSyntax = false; // track obsolete syntax in structured headers
	private boolean smtputf8 = false; // RFC 6531/6532 internationalized email mode
	private CharsetDecoder headerDecoder;
	private CharsetDecoder utf8HeaderDecoder;

	/**
	 * Constructor.
	 */
	public MessageParser() {
		super();
	}

	/**
	 * Sets whether SMTPUTF8 mode is enabled for this message.
	 * 
	 * <p>When true, UTF-8 characters are permitted in email addresses
	 * per RFC 6531 (SMTP Extension for Internationalized Email) and
	 * RFC 6532 (Internationalized Email Headers).
	 * 
	 * <p>This affects parsing of address headers (From, To, Cc, etc.)
	 * where the local-part, domain, and display-name may contain
	 * UTF-8 characters.
	 * 
	 * @param smtputf8 true to enable internationalized email parsing
	 */
	public void setSmtputf8(boolean smtputf8) {
		this.smtputf8 = smtputf8;
	}

	/**
	 * Returns whether SMTPUTF8 mode is enabled.
	 * 
	 * @return true if internationalized email parsing is enabled
	 */
	public boolean isSmtputf8() {
		return smtputf8;
	}

	/**
	 * Sets the message handler instance (aka event sink) that will be used
	 * to receive parsing events.
	 * @param handler the message handler
	 */
	public void setMessageHandler(MessageHandler handler) {
		this.messageHandler = handler;
		super.setHandler(handler);
	}

	@Override
	public void setHandler(MIMEHandler handler) {
		if (handler instanceof MessageHandler) {
			this.messageHandler = (MessageHandler) handler;
		}
		super.setHandler(handler);
	}

	/**
	 * We have received a complete header (name and value as bytes).
	 * Decode to string only for headers we handle; apply RFC 2047 for unstructured and address headers.
	 */
	@Override
	protected void header(String name, ByteBuffer value) throws MIMEParseException {
		String lower = name.toLowerCase().intern();
		switch (lower) {
			case "content-type":
			case "content-disposition":
			case "content-transfer-encoding":
			case "content-id":
			case "content-description":
			case "mime-version":
				super.header(name, value);
				break;
			case "date":
			case "resent-date":
				handleDateHeader(name, value);
				break;
			case "from":
			case "sender":
			case "to":
			case "cc":
			case "bcc":
			case "reply-to":
			case "resent-from":
			case "return-path":
			case "resent-sender":
			case "resent-to":
			case "resent-cc":
			case "resent-bcc":
			case "resent-reply-to":
			case "envelope-to":
			case "delivered-to":
			case "x-original-to":
			case "errors-to":
			case "apparently-to":
				handleAddressHeader(name, value);
				break;
			case "message-id":
			case "in-reply-to":
			case "references":
			case "resent-message-id":
				handleMessageIDHeader(name, value);
				break;
			case "received":
				handleReceivedHeader(name, value);
				break;
			default:
				if (isUnstructuredHeader(lower)) {
					String valueStr = decodeHeaderValueWithRFC2047(value, smtputf8);
					if (messageHandler != null) {
						messageHandler.header(name, valueStr);
					}
				}
		}
	}

	private static boolean isUnstructuredHeader(String lowerName) {
		return "subject".equals(lowerName)
			|| "comments".equals(lowerName)
			|| "keywords".equals(lowerName)
			|| "received".equals(lowerName)
			|| lowerName.startsWith("x-");
	}

	/**
	 * Decodes header value bytes with RFC 2047 and optional trim.
	 * Used for unstructured and address headers when SMTPUTF8 may apply.
	 */
	private String decodeHeaderValueWithRFC2047(ByteBuffer value, boolean smtputf8) {
		int len = value.remaining();
		if (len == 0) {
			return "";
		}
		byte[] bytes = new byte[len];
		value.duplicate().get(bytes);
		String s = RFC2047Decoder.decodeHeaderValue(bytes, smtputf8);
		return stripHeaderWhitespace ? s.trim() : s;
	}

	private static boolean isAddressHeader(String lowerName) {
		switch (lowerName) {
			case "from": case "sender": case "to": case "cc": case "bcc":
			case "reply-to": case "resent-from": case "return-path": case "resent-sender":
			case "resent-to": case "resent-cc": case "resent-bcc": case "resent-reply-to":
			case "envelope-to": case "delivered-to": case "x-original-to":
			case "errors-to": case "apparently-to":
				return true;
			default:
				return false;
		}
	}

	protected void handleDateHeader(String name, ByteBuffer value) throws MIMEParseException {
		if (messageHandler == null) {
			return;
		}
		String valueStr = decodeTokenHeaderValue(value.duplicate(), getHeaderDecoder());
		usedObsoleteSyntax = false;
		OffsetDateTime dateTime = parseRFC5322DateTime(valueStr);
		if (dateTime != null) {
			if (usedObsoleteSyntax) {
				messageHandler.obsoleteStructure(ObsoleteStructureType.OBSOLETE_DATE_TIME_SYNTAX);
				usedObsoleteSyntax = false;
			}
			messageHandler.dateHeader(name, dateTime);
		} else {
			messageHandler.unexpectedHeader(name, valueStr);
		}
	}

	protected void handleAddressHeader(String name, ByteBuffer value) throws MIMEParseException {
		if (messageHandler == null) {
			return;
		}
		List<EmailAddress> addresses = EmailAddressParser.parseEmailAddressList(value.duplicate(), getHeaderDecoder());
		if (addresses != null && !addresses.isEmpty()) {
			messageHandler.addressHeader(name, addresses);
		} else {
			List<EmailAddress> obsoleteAddresses = ObsoleteParserUtils.parseObsoleteAddressList(value.duplicate(), getHeaderDecoder());
			if (obsoleteAddresses != null && !obsoleteAddresses.isEmpty()) {
				messageHandler.obsoleteStructure(ObsoleteStructureType.OBSOLETE_ADDRESS_SYNTAX);
				messageHandler.addressHeader(name, obsoleteAddresses);
			} else {
				messageHandler.unexpectedHeader(name, decodeHeaderValueWithRFC2047(value, smtputf8));
			}
		}
	}

	protected void handleMessageIDHeader(String name, ByteBuffer value) throws MIMEParseException {
		if (messageHandler == null) {
			return;
		}
		List<ContentID> messageIDs = MessageIDParser.parseMessageIDList(value.duplicate(), getHeaderDecoder());
		if (messageIDs != null && !messageIDs.isEmpty()) {
			messageHandler.messageIDHeader(name, messageIDs);
		} else {
			List<ContentID> obsoleteMessageIDs = ObsoleteParserUtils.parseObsoleteMessageIDList(value.duplicate(), getHeaderDecoder());
			if (obsoleteMessageIDs != null && !obsoleteMessageIDs.isEmpty()) {
				messageHandler.obsoleteStructure(ObsoleteStructureType.OBSOLETE_MESSAGE_ID_SYNTAX);
				messageHandler.messageIDHeader(name, obsoleteMessageIDs);
			} else {
				messageHandler.unexpectedHeader(name, decodeHeaderBytes(value.duplicate(), StandardCharsets.ISO_8859_1, true));
			}
		}
	}

	protected void handleReceivedHeader(String name, ByteBuffer value) throws MIMEParseException {
		if (messageHandler != null) {
			String valueStr = decodeHeaderValueWithRFC2047(value, smtputf8);
			messageHandler.header(name, valueStr);
		}
	}

	/**
	 * Parses an RFC5322 date/time string into a OffsetDateTime.
	 * Returns null if the parse failed.
	 */
	private OffsetDateTime parseRFC5322DateTime(String value) {
		try {
			return MessageDateTimeFormatter.parse(value);
		} catch (DateTimeParseException e) {
			try {
				OffsetDateTime ret = MessageDateTimeFormatter.parseObsolete(value);
				if (ret != null) {
					this.usedObsoleteSyntax = true;
				}
				return ret;
			} catch (Exception e2) {
				return null;
			}
		}
	}

	private CharsetDecoder getHeaderDecoder() {
		if (smtputf8) {
			if (utf8HeaderDecoder == null) {
				utf8HeaderDecoder = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPLACE)
					.onUnmappableCharacter(CodingErrorAction.REPLACE);
			}
			return utf8HeaderDecoder;
		}
		if (headerDecoder == null) {
			headerDecoder = StandardCharsets.ISO_8859_1.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		}
		return headerDecoder;
	}

	@Override
	public void reset() {
		super.reset();
		usedObsoleteSyntax = false;
		smtputf8 = false;
	}

}
