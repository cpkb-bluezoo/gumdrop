/*
 * MessageParser.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import org.bluezoo.gumdrop.mime.MimeHandler;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.MimeParser;
import org.bluezoo.gumdrop.mime.rfc2047.Rfc2047Decoder;
import java.nio.ByteBuffer;

/**
 * A parser for RFC 5322 email messages.
 * This parser extends MimeParser to add support for email-specific
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
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5322">RFC 5322: Internet Message Format</a>
 */
// The older events are deprecated for removal but still delivered here
@SuppressWarnings("removal")
public class MessageParser extends MimeParser {

	private MessageHandler messageHandler;
	private MessageBridge bridge; // reads the address, message-id and date headers as elements
	private boolean smtputf8 = false; // RFC 6531/6532 internationalized email mode

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
		setUtf8Headers(smtputf8);
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
		this.bridge = new MessageBridge(this, handler, handler);
		super.setHandler(bridge);
	}

	@Override
	public void setHandler(MimeHandler handler) {
		if (handler instanceof MessageHandler) {
			setMessageHandler((MessageHandler) handler);
		} else {
			// a plain MIME handler takes the MIME events; a message handler
			// set before it goes on getting the structured header events
			this.bridge = messageHandler == null ? null : new MessageBridge(this, messageHandler, handler);
			super.setHandler(bridge != null ? bridge : handler);
		}
	}

	/**
	 * We have received a complete header (name and value as bytes).
	 * Decode to string only for headers we handle; apply RFC 2047 for unstructured and address headers.
	 */
	@Override
	protected void header(String name, ByteBuffer value) throws MimeParseException {
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

	/**
	 * The headers this parser gives to {@link #header(String, ByteBuffer)}
	 * with their whole value: those it turns into events of its own, as well
	 * as the ones the MIME layer collects.
	 */
	@Override
	protected boolean collectsHeader(String name) {
		if (super.collectsHeader(name)) {
			return true;
		}
		if (bridge != null && !bridge.legacy()) {
			// the handler works from the streaming events: nothing is
			// held whole for the older ones
			return false;
		}
		return isUnstructuredHeader(name.toLowerCase());
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
		String s = Rfc2047Decoder.decodeHeaderValue(bytes, smtputf8);
		return stripHeaderWhitespace ? s.trim() : s;
	}

	protected void handleReceivedHeader(String name, ByteBuffer value) throws MimeParseException {
		if (messageHandler != null) {
			String valueStr = decodeHeaderValueWithRFC2047(value, smtputf8);
			messageHandler.header(name, valueStr);
		}
	}

	@Override
	public void reset() {
		super.reset();
		if (bridge != null) {
			bridge.reset();
		}
		smtputf8 = false;
	}

}
