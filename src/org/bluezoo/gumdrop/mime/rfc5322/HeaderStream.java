/*
 * HeaderStream.java
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

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;

import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.rfc2047.HeaderTextDecoder;

/**
 * Reads the headers of the RFC 5322 layer (address lists, message-id lists
 * and dates) as the bytes arrive.
 *
 * <p>A header is never held whole. It is cut into elements as it passes (one
 * mailbox, one group member, one message-id), each element is held while it
 * is read and judged, and an event is delivered for it. A To with a thousand
 * addresses is a thousand events and one address in memory. An element is
 * limited by {@link org.bluezoo.gumdrop.mime.ParserLimits#getMaxElementLength()};
 * a date is one element.
 *
 * <p>The bytes arrive raw, unfolded but undecoded. Elements are cut out of
 * them by their quotes, comments and angle brackets before anything is
 * decoded, so that a decoded RFC 2047 word can never change the structure.
 *
 * <p>If an element cannot be read, malformedHeader is delivered and the rest
 * of the header, from that element on, follows as unstructured text.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HeaderStream {

	/** Which sort of header is being read. */
	enum Kind {
		DATE,
		ADDRESS,
		MESSAGE_ID;
	}

	private enum IdState {
		/** Between ids: white space, comments and commas. */
		OUTSIDE,
		/** In a comment between ids. */
		COMMENT,
		/** In angle brackets. */
		ANGLE,
		/** A word that is not in angle brackets. */
		BARE;
	}

	/** The bytes the address tokenizer has to look at: quotes, comments, angle brackets, separators and the escape. */
	private static final boolean[] ADDRESS_SPECIAL = new boolean[256];

	static {
		byte[] specials = { '"', '(', '<', '>', ',', ':', ';', '\\' };
		for (int i = 0; i < specials.length; i++) {
			ADDRESS_SPECIAL[specials[i]] = true;
		}
	}

	private final MessageHandler inner;
	private final HeaderTextDecoder.Sink textSink;
	private Kind kind; // null when no header is being read
	private String name;
	private boolean utf8;
	private int maxElement; // 0 or less for no limit
	private boolean emitShared; // whether obsoleteStructure is delivered
	private byte[] buf = new byte[128]; // the element being read
	private int len;
	private boolean quote;
	private boolean escaped;
	private int comment;
	private boolean angle;
	private boolean inGroup;
	private boolean afterGroup; // a group has just ended: a comma after it separates nothing
	private IdState ids = IdState.OUTSIDE;
	private boolean obsoleteFlagged;
	private boolean failed; // an element could not be read: the rest is text
	private HeaderTextDecoder text = new HeaderTextDecoder(false);
	private boolean textUtf8;

	HeaderStream(MessageHandler handler) {
		this.inner = handler;
		this.textSink = new HeaderTextDecoder.Sink() {
			@Override
			public void text(String s) throws MimeParseException {
				inner.headerContent(s);
			}
		};
	}

	/** Returns whether a header is being read. */
	boolean active() {
		return kind != null;
	}

	/**
	 * Starts reading a header.
	 * @param kind what sort of header it is
	 * @param name the header name, as written
	 * @param utf8 true in an SMTPUTF8 message
	 * @param maxElement the longest element held, or zero or less for no limit
	 * @param emitShared whether obsoleteStructure is delivered; the older
	 * events also deliver it
	 */
	void start(Kind kind, String name, boolean utf8, int maxElement, boolean emitShared)
			throws MimeParseException {
		this.kind = kind;
		this.name = name;
		if (textUtf8 != utf8) {
			text = new HeaderTextDecoder(utf8);
			textUtf8 = utf8;
		}
		this.utf8 = utf8;
		this.maxElement = maxElement;
		this.emitShared = emitShared;
		len = 0;
		quote = false;
		escaped = false;
		comment = 0;
		angle = false;
		inGroup = false;
		afterGroup = false;
		ids = IdState.OUTSIDE;
		obsoleteFlagged = false;
		failed = false;
		switch (kind) {
			case ADDRESS:
				inner.startAddressHeader(name);
				break;
			case MESSAGE_ID:
				inner.startMessageIDHeader(name);
				break;
			default:
				break;
		}
	}

	/**
	 * More of the header's raw, unfolded value. The bytes are consumed.
	 */
	void feed(ByteBuffer bytes) throws MimeParseException {
		while (bytes.hasRemaining()) {
			if (failed) {
				text.feed(bytes, textSink);
				return;
			}
			switch (kind) {
				case ADDRESS:
					// the bytes that mean nothing to the tokenizer go in together
					if (!quote && !escaped && comment == 0) {
						int p = bytes.position();
						int end = bytes.limit();
						int q = p;
						while (q < end && !ADDRESS_SPECIAL[bytes.get(q) & 0xff]) {
							q++;
						}
						if (q > p) {
							pushRun(bytes, p, q);
							bytes.position(q);
							continue;
						}
					}
					addressByte(bytes.get());
					break;
				case MESSAGE_ID:
					idByte(bytes.get());
					break;
				default:
					int n = bytes.remaining();
					ensure(n);
					bytes.get(buf, len, n);
					len += n;
					if (maxElement > 0 && len > maxElement) {
						fail("a date is not that long", null, null);
					}
					break;
			}
		}
	}

	/** The header's value is complete. */
	void finish() throws MimeParseException {
		if (kind == null) {
			return;
		}
		Kind k = kind;
		kind = null;
		if (!failed) {
			switch (k) {
				case ADDRESS:
					finishAddresses();
					break;
				case MESSAGE_ID:
					finishIds();
					break;
				default:
					finishDate();
					break;
			}
		}
		if (failed) {
			text.finish(textSink);
			inner.endHeader();
			return;
		}
		switch (k) {
			case ADDRESS:
				inner.endAddressHeader();
				break;
			case MESSAGE_ID:
				inner.endMessageIDHeader();
				break;
			default:
				break;
		}
	}

	/** Forgets any header being read. */
	void reset() {
		kind = null;
		failed = false;
		len = 0;
	}

	// ---- addresses ----

	private void addressByte(byte b) throws MimeParseException {
		if (escaped) {
			escaped = false;
			push(b);
			return;
		}
		if (quote) {
			if (b == '\\') {
				escaped = true;
			} else if (b == '"') {
				quote = false;
			}
			push(b);
			return;
		}
		if (comment > 0) {
			if (b == '\\') {
				escaped = true;
			} else if (b == '(') {
				comment++;
			} else if (b == ')') {
				comment--;
			}
			push(b);
			return;
		}
		switch (b) {
			case '"':
				quote = true;
				push(b);
				return;
			case '(':
				comment = 1;
				push(b);
				return;
			case '<':
				angle = true;
				push(b);
				return;
			case '>':
				angle = false;
				push(b);
				return;
			case ',':
				if (!angle) {
					endMember((byte) ',');
					return;
				}
				break;
			case ':':
				if (!angle && !inGroup) {
					startGroup();
					return;
				}
				break;
			case ';':
				if (!angle && inGroup) {
					endMember((byte) ';');
					if (!failed) {
						inGroup = false;
						afterGroup = true;
						inner.endGroup();
					}
					return;
				}
				break;
			default:
				break;
		}
		push(b);
	}

	/** Adds bytes that need no deciding to the element. */
	private void pushRun(ByteBuffer bytes, int from, int to) throws MimeParseException {
		int n = to - from;
		ensure(n);
		for (int i = from; i < to; i++) {
			buf[len++] = bytes.get(i);
		}
		if (maxElement > 0 && len > maxElement) {
			fail("an address is too long", null, null);
		}
	}

	/** Adds a byte to the element. */
	private void push(byte b) throws MimeParseException {
		ensure(1);
		buf[len++] = b;
		if (maxElement > 0 && len > maxElement) {
			fail("an address is too long", null, null);
		}
	}

	/**
	 * A comma, or the semicolon that ends a group, has ended an element: a
	 * mailbox, or a member of a group. The delimiter is 0 at the end of the
	 * header.
	 */
	private void endMember(byte delimiter) throws MimeParseException {
		String element = ElementParser.decode(buf, len, utf8).trim();
		if (element.isEmpty()) {
			// an empty element is obsolete syntax, except in an empty group
			// and after the end of one
			if (delimiter == ',' && !afterGroup && !inGroup) {
				flagObsolete(ObsoleteStructureType.OBSOLETE_ADDRESS_SYNTAX);
			}
			len = 0;
			afterGroup = false;
			return;
		}
		ElementParser.Mailbox mailbox = ElementParser.mailbox(element, utf8);
		afterGroup = false;
		if (mailbox == null) {
			fail("not a mailbox", null, delimiter == 0 ? null : new byte[] { delimiter });
			return;
		}
		if (mailbox.obsolete) {
			flagObsolete(ObsoleteStructureType.OBSOLETE_ADDRESS_SYNTAX);
		}
		inner.mailbox(mailbox.displayName, mailbox.localPart, mailbox.domain);
		len = 0;
	}

	private void startGroup() throws MimeParseException {
		String groupName = ElementParser.groupName(ElementParser.decode(buf, len, utf8), utf8);
		if (groupName == null) {
			fail("not the name of a group", null, new byte[] { ':' });
			return;
		}
		afterGroup = false;
		inGroup = true;
		len = 0;
		inner.startGroup(groupName);
	}

	private void finishAddresses() throws MimeParseException {
		if (quote || comment > 0 || angle) {
			fail("a quoted string, comment or angle bracket is not closed", null, null);
			return;
		}
		endMember((byte) 0);
		if (!failed && inGroup) {
			// a group that is never closed
			flagObsolete(ObsoleteStructureType.OBSOLETE_ADDRESS_SYNTAX);
			inGroup = false;
			inner.endGroup();
		}
	}

	// ---- message-ids ----

	private void idByte(byte b) throws MimeParseException {
		switch (ids) {
			case OUTSIDE:
				if (b == ' ' || b == '\t' || b == ',') {
					return;
				}
				if (b == '(') {
					comment = 1;
					ids = IdState.COMMENT;
				} else if (b == '<') {
					len = 0;
					quote = false;
					ids = IdState.ANGLE;
				} else {
					len = 0;
					ids = IdState.BARE;
					push(b);
				}
				return;
			case COMMENT:
				if (escaped) {
					escaped = false;
				} else if (b == '\\') {
					escaped = true;
				} else if (b == '(') {
					comment++;
				} else if (b == ')') {
					comment--;
					if (comment == 0) {
						ids = IdState.OUTSIDE;
					}
				}
				return;
			case ANGLE:
				if (escaped) {
					escaped = false;
				} else if (quote) {
					if (b == '\\') {
						escaped = true;
					} else if (b == '"') {
						quote = false;
					}
				} else if (b == '"') {
					quote = true;
				} else if (b == '>') {
					endAngle();
					return;
				}
				push(b);
				return;
			default:
				if (b == ' ' || b == '\t' || b == ',' || b == '(' || b == '<') {
					// the byte that ends the word is part of the text if it fails
					endBare(new byte[] { b });
					if (!failed) {
						ids = IdState.OUTSIDE;
						idByte(b);
					}
					return;
				}
				push(b);
				return;
		}
	}

	private void endAngle() throws MimeParseException {
		ids = IdState.OUTSIDE;
		String element = ElementParser.decode(buf, len, utf8);
		String[] id = ElementParser.messageID(element, utf8);
		if (id != null) {
			inner.messageID(id[0], id[1]);
			len = 0;
			return;
		}
		id = ElementParser.obsoleteMessageID("<" + element + ">");
		if (id == null) {
			fail("not a message-id", new byte[] { '<' }, new byte[] { '>' });
			return;
		}
		flagObsolete(ObsoleteStructureType.OBSOLETE_MESSAGE_ID_SYNTAX);
		inner.messageID(id[0], id[1]);
		len = 0;
	}

	/** A word that is not in angle brackets: obsolete syntax, if it is an id. */
	private void endBare(byte[] suffix) throws MimeParseException {
		String[] id = ElementParser.obsoleteMessageID(ElementParser.decode(buf, len, utf8));
		if (id == null) {
			fail("not a message-id", null, suffix);
			return;
		}
		flagObsolete(ObsoleteStructureType.OBSOLETE_MESSAGE_ID_SYNTAX);
		inner.messageID(id[0], id[1]);
		len = 0;
	}

	private void finishIds() throws MimeParseException {
		switch (ids) {
			case BARE:
				endBare(null);
				break;
			case ANGLE:
				fail("a message-id is not closed", new byte[] { '<' }, null);
				break;
			case COMMENT:
				fail("a comment is not closed", null, null);
				break;
			default:
				break;
		}
	}

	// ---- dates ----

	private void finishDate() throws MimeParseException {
		String value = ElementParser.decode(buf, len, utf8).trim();
		OffsetDateTime date = MessageDateTimeFormatter.parseStrict(value);
		if (date == null) {
			date = MessageDateTimeFormatter.parseObsolete(value);
			if (date == null) {
				fail("not a date and time", null, null);
				return;
			}
			flagObsolete(ObsoleteStructureType.OBSOLETE_DATE_TIME_SYNTAX);
		}
		inner.dateHeader(name, date);
	}

	// ---- failure ----

	private void flagObsolete(ObsoleteStructureType type) throws MimeParseException {
		if (!obsoleteFlagged) {
			obsoleteFlagged = true;
			if (emitShared) {
				inner.obsoleteStructure(type);
			}
		}
	}

	/**
	 * The element cannot be read. The handler is told, and the rest of the
	 * header (the prefix, the element, the suffix, then whatever follows) is
	 * delivered as text.
	 */
	private void fail(String reason, byte[] prefix, byte[] suffix) throws MimeParseException {
		inner.malformedHeader(name, reason);
		failed = true;
		inGroup = false;
		text.reset();
		if (prefix != null) {
			text.feed(ByteBuffer.wrap(prefix), textSink);
		}
		if (len > 0) {
			ByteBuffer element = ByteBuffer.wrap(buf, 0, len);
			len = 0;
			text.feed(element, textSink);
		}
		if (suffix != null) {
			text.feed(ByteBuffer.wrap(suffix), textSink);
		}
	}

	private void ensure(int more) {
		int needed = len + more;
		if (needed > buf.length) {
			int size = buf.length * 2;
			while (size < needed) {
				size *= 2;
			}
			byte[] grown = new byte[size];
			System.arraycopy(buf, 0, grown, 0, len);
			buf = grown;
		}
	}

}
