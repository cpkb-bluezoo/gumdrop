/*
 * MessageBridge.java
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

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MimeHandler;
import org.bluezoo.gumdrop.mime.MimeLocator;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.MimeVersion;

/**
 * Stands between a {@link MessageParser} and its {@link MessageHandler}. It
 * asks the MIME layer for the headers the RFC 5322 layer reads itself
 * (addresses, message-ids and dates) raw, reads them with a
 * {@link HeaderStream} and delivers them to the handler as elements. Every
 * other event passes through.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MessageBridge implements MimeHandler {

	private final MessageParser parser;
	private final MessageHandler inner;
	private final HeaderStream stream;
	private HeaderStream.Kind claimed; // set by headerDelivery until the startHeader that follows
	private boolean legacy = true;

	MessageBridge(MessageParser parser, MessageHandler inner) {
		this.parser = parser;
		this.inner = inner;
		this.stream = new HeaderStream(inner);
	}

	/** Returns the kind of RFC 5322 header that is read by this layer, or null. */
	static HeaderStream.Kind classify(String name) {
		switch (name.toLowerCase()) {
			case "date":
			case "resent-date":
				return HeaderStream.Kind.DATE;
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
				return HeaderStream.Kind.ADDRESS;
			case "message-id":
			case "in-reply-to":
			case "references":
			case "resent-message-id":
				return HeaderStream.Kind.MESSAGE_ID;
			default:
				return null;
		}
	}

	/** Returns whether the handler wants the older events. */
	boolean legacy() {
		return legacy;
	}

	/** Readies the bridge for another message. */
	void reset() {
		claimed = null;
		stream.reset();
	}

	@Override
	public boolean wantsLegacyEvents() {
		legacy = inner.wantsLegacyEvents();
		return legacy;
	}

	@Override
	public HeaderDelivery headerDelivery(String name) {
		HeaderStream.Kind kind = classify(name);
		// The older events make the date themselves, so while they are
		// wanted the date is left to them
		if (kind == null || (kind == HeaderStream.Kind.DATE && legacy)) {
			claimed = null;
			return inner.headerDelivery(name);
		}
		claimed = kind;
		return HeaderDelivery.RAW;
	}

	@Override
	public void startHeader(String name) throws MimeParseException {
		if (claimed == null) {
			inner.startHeader(name);
			return;
		}
		HeaderStream.Kind kind = claimed;
		claimed = null;
		stream.start(kind, name, parser.isSmtputf8(),
			parser.getLimits().getMaxElementLength(), !legacy);
	}

	@Override
	public void headerContent(String text) throws MimeParseException {
		inner.headerContent(text);
	}

	@Override
	public void rawHeaderContent(ByteBuffer bytes) throws MimeParseException {
		if (stream.active()) {
			stream.feed(bytes);
		} else {
			inner.rawHeaderContent(bytes);
		}
	}

	@Override
	public void endHeader() throws MimeParseException {
		if (stream.active()) {
			stream.finish();
		} else {
			inner.endHeader();
		}
	}

	@Override
	public void setLocator(MimeLocator locator) {
		inner.setLocator(locator);
	}

	@Override
	public void startEntity(String boundary) throws MimeParseException {
		inner.startEntity(boundary);
	}

	@Override
	public void startContentType(String primary, String sub) throws MimeParseException {
		inner.startContentType(primary, sub);
	}

	@Override
	public void endContentType() throws MimeParseException {
		inner.endContentType();
	}

	@Override
	public void startContentDisposition(String kind) throws MimeParseException {
		inner.startContentDisposition(kind);
	}

	@Override
	public void endContentDisposition() throws MimeParseException {
		inner.endContentDisposition();
	}

	@Override
	public void startParameter(String name, String language) throws MimeParseException {
		inner.startParameter(name, language);
	}

	@Override
	public void parameterContent(String text) throws MimeParseException {
		inner.parameterContent(text);
	}

	@Override
	public void endParameter() throws MimeParseException {
		inner.endParameter();
	}

	@Override
	public void malformedHeader(String name, String reason) throws MimeParseException {
		inner.malformedHeader(name, reason);
	}

	@Override
	public void contentType(ContentType contentType) throws MimeParseException {
		inner.contentType(contentType);
	}

	@Override
	public void contentDisposition(ContentDisposition contentDisposition) throws MimeParseException {
		inner.contentDisposition(contentDisposition);
	}

	@Override
	public void contentTransferEncoding(String encoding) throws MimeParseException {
		inner.contentTransferEncoding(encoding);
	}

	@Override
	public void contentID(ContentID contentID) throws MimeParseException {
		inner.contentID(contentID);
	}

	@Override
	public void contentDescription(String description) throws MimeParseException {
		inner.contentDescription(description);
	}

	@Override
	public void mimeVersion(MimeVersion version) throws MimeParseException {
		inner.mimeVersion(version);
	}

	@Override
	public void endHeaders() throws MimeParseException {
		inner.endHeaders();
	}

	@Override
	public void bodyContent(ByteBuffer data) throws MimeParseException {
		inner.bodyContent(data);
	}

	@Override
	public void unexpectedContent(ByteBuffer data) throws MimeParseException {
		inner.unexpectedContent(data);
	}

	@Override
	public void endEntity(String boundary) throws MimeParseException {
		inner.endEntity(boundary);
	}

}
