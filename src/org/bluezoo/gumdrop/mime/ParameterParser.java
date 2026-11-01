/*
 * ParameterParser.java
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

package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.mime.rfc2047.HeaderTextDecoder;

/**
 * The grammar of Content-Type and Content-Disposition, read as the bytes
 * arrive.
 *
 * <pre>
 * content-type        = type "/" subtype *( ";" parameter )
 * content-disposition = disposition-type *( ";" parameter )
 * parameter           = name [ "*" [ index ] [ "*" ] ] "=" value
 * </pre>
 *
 * <p>The head (the media type or disposition type) is short, so it is held
 * until it is complete and judged whole. Parameter values can be long and
 * are not held: each is decoded as it arrives and handed on in pieces.
 *
 * <p>A value is decoded one of three ways:
 * <ul>
 * <li>as text, for an ordinary parameter: quoted-pairs resolved, RFC 2047
 * encoded words decoded, raw 8-bit text read as UTF-8 where valid;</li>
 * <li>as an RFC 2231 extended value ({@code name*=charset'language'%xx...}),
 * which is percent-decoded and read in its charset;</li>
 * <li>as an RFC 2231 continuation, which is either of those over several
 * parameters ({@code name*0}, {@code name*1*}, ...), joined into one value.
 * Segments have to arrive in order, starting at 0; a segment out of order
 * is skipped.</li>
 * </ul>
 *
 * <p>Anything the grammar cannot make sense of in a parameter is skipped,
 * and parsing goes on with the next. Only a head that is not valid is
 * reported, and then nothing has been delivered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2045#section-5.1">RFC 2045 §5.1</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2183">RFC 2183</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2231">RFC 2231</a>
 */
final class ParameterParser {

	/** Which header is being read. */
	enum Kind {
		CONTENT_TYPE,
		CONTENT_DISPOSITION;
	}

	/** Where the events go. */
	interface Sink {

		/**
		 * The head: the type and subtype of a media type, or the
		 * disposition type and an empty second string.
		 */
		void head(String first, String second) throws MimeParseException;

		/** A parameter starts. The language is non-null for an RFC 2231 extended value, possibly empty. */
		void startParameter(String name, String language) throws MimeParseException;

		/** Some of the decoded value of the current parameter. */
		void content(String text) throws MimeParseException;

		/** The current parameter is complete. */
		void endParameter() throws MimeParseException;

	}

	private enum State {
		HEAD,
		/** Between parameters: white space and semicolons. */
		BETWEEN,
		NAME,
		AFTER_EQUALS,
		QUOTED,
		UNQUOTED,
		/** After a closing quote: whatever is there is ignored. */
		AFTER_QUOTED,
		/** Skipping a parameter that cannot be used. */
		SKIP;
	}

	/** How the bytes of the current segment's value are decoded. */
	private enum Route {
		NONE,
		TEXT,
		/** Reading charset'language' at the start of an extended value. */
		PREFIX,
		/** Percent-decoded and read in the charset. */
		EXT,
		/** Read in the charset without percent-decoding: a plain segment of a continuation whose first segment was extended. */
		RAW;
	}

	/** The longest charset'language' prefix. */
	private static final int MAX_PREFIX = 160;

	/** Trailing white space of an unquoted value held to see whether more follows. */
	private static final int MAX_PENDING_WHITESPACE = 64;

	private static final byte[] HEX = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
	};

	/** A growable run of bytes. */
	private static final class Bytes {

		byte[] data = new byte[32];
		int length;

		void append(byte b) {
			if (length == data.length) {
				data = java.util.Arrays.copyOf(data, length * 2);
			}
			data[length++] = b;
		}

		void append(byte[] bytes, int from, int to) {
			int n = to - from;
			if (length + n > data.length) {
				data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, length + n));
			}
			System.arraycopy(bytes, from, data, length, n);
			length += n;
		}

		void clear() {
			length = 0;
		}

		String ascii() {
			return new String(data, 0, length, StandardCharsets.ISO_8859_1);
		}

	}

	private Kind kind = Kind.CONTENT_TYPE;
	private int maxToken = 256;
	private State state = State.HEAD;
	private boolean escaped;
	private final Bytes head = new Bytes();
	private final Bytes name = new Bytes();
	private Route route = Route.NONE;
	/** Whether the current segment's parameter ends with it. */
	private boolean segmentEndsParameter = true;
	/** The name of an extended parameter, until its prefix is read. */
	private String pendingName = "";
	/** A parameter that can be continued by the next segment: its name, the next index and its route. */
	private boolean open;
	private String openBase = "";
	private int openNextIndex;
	private Route openRoute = Route.NONE;
	/** A parameter has been started and not ended. */
	private boolean inParameter;
	private HeaderTextDecoder text;
	private ByteDecoder bytes = new ByteDecoder("UTF-8");
	private final Bytes prefix = new Bytes();
	/** Percent-decoding: 0 outside, 1 after '%', 2 after '%' and a digit. */
	private int percent;
	private int percentHigh;
	private final Bytes decoded = new Bytes();
	private final StringBuilder scratch = new StringBuilder();
	/** White space at the end of what has been read of an unquoted value. */
	private final Bytes pendingWhitespace = new Bytes();

	private String malformedReason;
	private Sink current;
	private final HeaderTextDecoder.Sink textSink = new HeaderTextDecoder.Sink() {
		@Override
		public void text(String t) throws MimeParseException {
			current.content(t);
		}
	};

	ParameterParser(boolean utf8Headers) {
		text = new HeaderTextDecoder(utf8Headers);
	}

	/**
	 * Readies the grammar for another header.
	 * @param kind which header
	 * @param maxToken bounds the head's tokens, parameter names and the parts
	 * of a language prefix (zero or less for no bound)
	 */
	void start(Kind kind, int maxToken) {
		this.kind = kind;
		this.maxToken = maxToken > 0 ? maxToken : Integer.MAX_VALUE / 4;
		state = State.HEAD;
		escaped = false;
		head.clear();
		name.clear();
		route = Route.NONE;
		segmentEndsParameter = true;
		pendingName = "";
		open = false;
		inParameter = false;
		text.reset();
		percent = 0;
		decoded.clear();
		scratch.setLength(0);
		pendingWhitespace.clear();
		malformedReason = null;
	}

	/**
	 * Returns the bytes of the value so far, while the head is unfinished or
	 * after the value was found to be malformed.
	 */
	byte[] headBytes() {
		return java.util.Arrays.copyOf(head.data, head.length);
	}

	/** Returns why the last value was reported as malformed. */
	String getMalformedReason() {
		return malformedReason;
	}

	/**
	 * Reads some of the value. The buffer's position is moved to its limit.
	 * @param buffer the bytes, unfolded
	 * @param sink the receiver of the events
	 * @return -1, or, if the head is not valid, the index in the bytes
	 * just fed of the first byte that was not read. The head's bytes up to
	 * and including the one that showed it are in {@link #headBytes()}, and
	 * nothing has been delivered.
	 */
	int feed(ByteBuffer buffer, Sink sink) throws MimeParseException {
		byte[] b = new byte[buffer.remaining()];
		buffer.get(b);
		current = sink;
		int i = 0;
		int n = b.length;
		while (i < n) {
			switch (state) {
				case HEAD: {
					int semicolon = indexOf(b, i, n, (byte) ';');
					int end = semicolon < 0 ? n : semicolon + 1;
					head.append(b, i, end);
					i = end;
					if (semicolon >= 0) {
						head.length--; // the semicolon
						String reason = completeHead(sink);
						if (reason == null) {
							state = State.BETWEEN;
						} else {
							head.append((byte) ';');
							malformedReason = reason;
							return i;
						}
					} else if (head.length > 2L * maxToken + 32) {
						malformedReason = "the media type or disposition type is too long";
						return i;
					}
					break;
				}
				case BETWEEN:
					while (i < n && (b[i] == ' ' || b[i] == '\t' || b[i] == ';')) {
						i++;
					}
					if (i < n) {
						state = State.NAME;
						name.clear();
					}
					break;
				case NAME: {
					byte c = b[i++];
					if (c == '=') {
						beginValue(sink);
					} else if (c == ';') {
						state = State.BETWEEN;
					} else if (name.length >= maxToken) {
						state = State.SKIP;
					} else {
						name.append(c);
					}
					break;
				}
				case AFTER_EQUALS:
					if (b[i] == ' ' || b[i] == '\t') {
						i++;
					} else if (b[i] == '"') {
						i++;
						state = State.QUOTED;
						escaped = false;
					} else if (b[i] == ';') {
						i++;
						endSegment(sink);
						state = State.BETWEEN;
					} else {
						state = State.UNQUOTED;
						pendingWhitespace.clear();
					}
					break;
				case QUOTED:
					i = quoted(b, i, n, sink);
					break;
				case UNQUOTED:
					i = unquoted(b, i, n, sink);
					break;
				default: { // AFTER_QUOTED, SKIP
					int semicolon = indexOf(b, i, n, (byte) ';');
					if (semicolon < 0) {
						i = n;
					} else {
						i = semicolon + 1;
						state = State.BETWEEN;
					}
					break;
				}
			}
		}
		return -1;
	}

	/**
	 * The value has ended. If the head was still unfinished, it is judged now.
	 * @param sink the receiver of the events
	 * @return true if the value is complete, false if the head is not valid
	 * (see {@link #getMalformedReason()}, and nothing has been delivered)
	 */
	boolean finish(Sink sink) throws MimeParseException {
		current = sink;
		switch (state) {
			case HEAD: {
				String reason = completeHead(sink);
				if (reason != null) {
					malformedReason = reason;
					return false;
				}
				break;
			}
			case AFTER_EQUALS:
			case QUOTED:
			case UNQUOTED:
				endSegment(sink);
				break;
			default:
				break;
		}
		state = State.BETWEEN;
		closeOpen(sink);
		return true;
	}

	/**
	 * Judges the head and, if it is valid, delivers it.
	 * @return the reason it is not valid, or null
	 */
	private String completeHead(Sink sink) throws MimeParseException {
		int[] h = trim(head.data, 0, head.length);
		if (kind == Kind.CONTENT_TYPE) {
			int slash = indexOf(head.data, h[0], h[1], (byte) '/');
			if (slash < 0) {
				return "a media type is type/subtype";
			}
			int[] primary = trim(head.data, h[0], slash);
			int[] sub = trim(head.data, slash + 1, h[1]);
			if (!isTokenRun(primary) || !isTokenRun(sub)) {
				return "the type or subtype of the media type is not a token";
			}
			sink.head(ascii(head.data, primary), ascii(head.data, sub));
		} else {
			if (!isTokenRun(h)) {
				return "the disposition type is not a token";
			}
			sink.head(ascii(head.data, h), "");
		}
		return null;
	}

	private boolean isTokenRun(int[] run) {
		if (run[1] <= run[0] || run[1] - run[0] > maxToken) {
			return false;
		}
		for (int k = run[0]; k < run[1]; k++) {
			if (!isTokenByte(head.data[k])) {
				return false;
			}
		}
		return true;
	}

	/** name is complete and an '=' followed it: starts the parameter's value, or skips it. */
	private void beginValue(Sink sink) throws MimeParseException {
		int[] t = trim(name.data, 0, name.length);
		boolean ok = t[1] > t[0];
		for (int k = t[0]; ok && k < t[1]; k++) {
			ok = isTokenByte(name.data[k]);
		}
		if (ok) {
			startSegment(ascii(name.data, t), sink);
		} else {
			state = State.SKIP;
		}
	}

	private void startSegment(String parameterName, Sink sink) throws MimeParseException {
		state = State.AFTER_EQUALS;
		pendingWhitespace.clear();
		int star = parameterName.indexOf('*');
		String base = star > 0 ? parameterName.substring(0, star) : null;
		String rest = star > 0 ? parameterName.substring(star + 1) : null;
		if (base == null) {
			// plain
			closeOpen(sink);
			startTextParameter(parameterName, sink);
			segmentEndsParameter = true;
			return;
		}
		if (rest.isEmpty()) {
			// name*
			closeOpen(sink);
			startPrefix(base);
			segmentEndsParameter = true;
			return;
		}
		boolean extended = rest.endsWith("*");
		String digits = extended ? rest.substring(0, rest.length() - 1) : rest;
		if (!isIndex(digits)) {
			closeOpen(sink);
			startTextParameter(parameterName, sink);
			segmentEndsParameter = true;
			return;
		}
		int index = Integer.parseInt(digits);
		if (open && openBase.equals(base) && openNextIndex == index) {
			openNextIndex++;
			if (openRoute == Route.EXT && extended) {
				route = Route.EXT;
			} else if (openRoute == Route.EXT) {
				percent = 0;
				route = Route.RAW;
			} else {
				route = Route.TEXT;
			}
			segmentEndsParameter = false;
		} else if (index == 0) {
			closeOpen(sink);
			open = true;
			openBase = base;
			openNextIndex = 1;
			openRoute = extended ? Route.EXT : Route.TEXT;
			if (extended) {
				startPrefix(base);
			} else {
				startTextParameter(base, sink);
			}
			segmentEndsParameter = false;
		} else {
			// out of order, or a continuation that never began
			route = Route.NONE;
			state = State.SKIP;
		}
	}

	private static boolean isIndex(String digits) {
		if (digits.isEmpty() || digits.length() > 9) {
			return false;
		}
		for (int k = 0; k < digits.length(); k++) {
			char c = digits.charAt(k);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	private void startTextParameter(String parameterName, Sink sink) throws MimeParseException {
		sink.startParameter(parameterName, null);
		inParameter = true;
		route = Route.TEXT;
		text.reset();
	}

	private void startPrefix(String base) {
		pendingName = base;
		prefix.clear();
		route = Route.PREFIX;
	}

	/** The text of a quoted value, from i; returns where it ended. */
	private int quoted(byte[] b, int i, int n, Sink sink) throws MimeParseException {
		while (i < n) {
			if (escaped) {
				escaped = false;
				value(b, i, i + 1, sink);
				i++;
				continue;
			}
			int end = i;
			while (end < n && b[end] != '"' && b[end] != '\\') {
				end++;
			}
			if (end > i) {
				value(b, i, end, sink);
				i = end;
				continue;
			}
			i++;
			if (b[i - 1] == '\\') {
				escaped = true;
			} else {
				endSegment(sink);
				state = State.AFTER_QUOTED;
				break;
			}
		}
		return i;
	}

	/**
	 * The text of an unquoted value, from i; returns where it ended. White
	 * space at its end is not part of it.
	 */
	private int unquoted(byte[] b, int i, int n, Sink sink) throws MimeParseException {
		while (i < n) {
			int end = i;
			while (end < n && b[end] != ';' && b[end] != ' ' && b[end] != '\t') {
				end++;
			}
			if (end > i) {
				flushPendingWhitespace(sink);
				value(b, i, end, sink);
				i = end;
				continue;
			}
			byte c = b[i++];
			if (c == ';') {
				pendingWhitespace.clear();
				endSegment(sink);
				state = State.BETWEEN;
				break;
			}
			if (pendingWhitespace.length >= MAX_PENDING_WHITESPACE) {
				flushPendingWhitespace(sink);
			}
			pendingWhitespace.append(c);
		}
		return i;
	}

	private void flushPendingWhitespace(Sink sink) throws MimeParseException {
		if (pendingWhitespace.length > 0) {
			byte[] w = java.util.Arrays.copyOf(pendingWhitespace.data, pendingWhitespace.length);
			pendingWhitespace.clear();
			value(w, 0, w.length, sink);
		}
	}

	/** Value bytes, quotes and escapes already resolved, decoded by the current route. */
	private void value(byte[] b, int from, int to, Sink sink) throws MimeParseException {
		switch (route) {
			case NONE:
				break;
			case TEXT:
				text.feed(ByteBuffer.wrap(b, from, to - from), textSink);
				break;
			case PREFIX:
				prefixBytes(b, from, to, sink);
				break;
			case EXT:
				extendedBytes(b, from, to, sink);
				break;
			default: // RAW
				scratch.setLength(0);
				bytes.feed(b, from, to, scratch);
				emitScratch(sink);
				break;
		}
	}

	private void emitScratch(Sink sink) throws MimeParseException {
		if (scratch.length() > 0) {
			String s = scratch.toString();
			scratch.setLength(0);
			sink.content(s);
		}
	}

	/** Bytes at the start of an extended value, up to its second apostrophe. */
	private void prefixBytes(byte[] b, int from, int to, Sink sink) throws MimeParseException {
		for (int k = from; k < to; k++) {
			byte c = b[k];
			if (prefix.length >= MAX_PREFIX || c < 0x21 || c > 0x7e) {
				// not charset'language'
				prefixFallbackAfter(b, k, to, sink);
				return;
			}
			prefix.append(c);
			if (c == '\'' && countApostrophes() == 2) {
				if (prefixComplete(sink)) {
					extendedBytes(b, k + 1, to, sink);
				} else {
					prefixFallbackAfter(b, k + 1, to, sink);
				}
				return;
			}
		}
	}

	private int countApostrophes() {
		int count = 0;
		for (int k = 0; k < prefix.length; k++) {
			if (prefix.data[k] == '\'') {
				count++;
			}
		}
		return count;
	}

	/** prefix is charset'language': starts the parameter. False if it is not a usable one. */
	private boolean prefixComplete(Sink sink) throws MimeParseException {
		int first = 0;
		while (prefix.data[first] != '\'') {
			first++;
		}
		String charset = new String(prefix.data, 0, first, StandardCharsets.ISO_8859_1);
		String language = new String(prefix.data, first + 1, prefix.length - first - 2, StandardCharsets.ISO_8859_1);
		if (charset.isEmpty() || charset.length() > maxToken || language.length() > maxToken) {
			return false;
		}
		bytes = new ByteDecoder(charset);
		percent = 0;
		sink.startParameter(pendingName, language);
		inParameter = true;
		route = Route.EXT;
		return true;
	}

	/**
	 * What was read is not a prefix: the value is plain text, with the bytes
	 * so far and the rest as its start.
	 */
	private void prefixFallbackAfter(byte[] rest, int from, int to, Sink sink) throws MimeParseException {
		startTextParameter(pendingName, sink);
		if (open) {
			openRoute = Route.TEXT;
		}
		byte[] p = java.util.Arrays.copyOf(prefix.data, prefix.length);
		prefix.clear();
		text.feed(ByteBuffer.wrap(p), textSink);
		value(rest, from, to, sink);
	}

	/** Percent-encoded bytes of an extended value. */
	private void extendedBytes(byte[] b, int from, int to, Sink sink) throws MimeParseException {
		for (int k = from; k < to; k++) {
			byte c = b[k];
			switch (percent) {
				case 0:
					if (c == '%') {
						percent = 1;
					} else {
						decoded.append(c);
					}
					break;
				case 1: {
					int h = hex(c);
					if (h >= 0) {
						percentHigh = h;
						percent = 2;
					} else {
						percent = 0;
						decoded.append((byte) '%');
						decoded.append(c);
					}
					break;
				}
				default: {
					percent = 0;
					int l = hex(c);
					if (l >= 0) {
						decoded.append((byte) (percentHigh << 4 | l));
					} else {
						decoded.append((byte) '%');
						decoded.append(HEX[percentHigh]);
						decoded.append(c);
					}
					break;
				}
			}
		}
		feedDecoded(sink);
	}

	private void feedDecoded(Sink sink) throws MimeParseException {
		scratch.setLength(0);
		bytes.feed(decoded.data, 0, decoded.length, scratch);
		decoded.clear();
		emitScratch(sink);
	}

	/** The current segment's value is complete. */
	private void endSegment(Sink sink) throws MimeParseException {
		if (route == Route.PREFIX) {
			prefixFallbackAfter(new byte[0], 0, 0, sink);
		}
		pendingWhitespace.clear();
		if (segmentEndsParameter) {
			endValue(sink);
		} else if (percent != 0) {
			// a percent sign cut short by the end of a segment is text
			flushPercent();
			feedDecoded(sink);
		}
		route = Route.NONE;
	}

	/** Ends the parameter being read, finishing its decoders. */
	private void endValue(Sink sink) throws MimeParseException {
		switch (route) {
			case TEXT:
				text.finish(textSink);
				break;
			case EXT:
			case RAW:
				flushPercent();
				feedDecoded(sink);
				scratch.setLength(0);
				bytes.finish(scratch);
				emitScratch(sink);
				break;
			default:
				break;
		}
		if (inParameter) {
			inParameter = false;
			sink.endParameter();
		}
	}

	/** Ends the parameter a continuation has kept open. */
	private void closeOpen(Sink sink) throws MimeParseException {
		if (!open) {
			return;
		}
		open = false;
		if (inParameter) {
			// the route of its last segment is not necessarily the parameter's
			route = openRoute;
			endValue(sink);
		}
	}

	/** A percent sign (and digit) cut short is text. */
	private void flushPercent() {
		if (percent == 1) {
			decoded.append((byte) '%');
		} else if (percent == 2) {
			decoded.append((byte) '%');
			decoded.append(HEX[percentHigh]);
		}
		percent = 0;
	}

	private static int hex(byte b) {
		if (b >= '0' && b <= '9') {
			return b - '0';
		}
		if (b >= 'a' && b <= 'f') {
			return b - 'a' + 10;
		}
		if (b >= 'A' && b <= 'F') {
			return b - 'A' + 10;
		}
		return -1;
	}

	private static int indexOf(byte[] b, int from, int to, byte target) {
		for (int k = from; k < to; k++) {
			if (b[k] == target) {
				return k;
			}
		}
		return -1;
	}

	/** Bytes that are known to be ASCII tokens (checked by the caller). */
	private static String ascii(byte[] b, int[] run) {
		return new String(b, run[0], run[1] - run[0], StandardCharsets.ISO_8859_1);
	}

	/** The bounds of the bytes between from and to without ASCII white space at either end. */
	private static int[] trim(byte[] b, int from, int to) {
		int start = from;
		while (start < to && isSpace(b[start])) {
			start++;
		}
		int end = to;
		while (end > start && isSpace(b[end - 1])) {
			end--;
		}
		return new int[] { start, end };
	}

	private static boolean isSpace(byte b) {
		return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == '\f' || b == 0x0b;
	}

	private static boolean isTokenByte(byte b) {
		return b >= 0 && MimeUtils.isTokenChar((char) b);
	}

}
