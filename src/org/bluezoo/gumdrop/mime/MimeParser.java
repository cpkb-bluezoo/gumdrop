/*
 * MimeParser.java
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

package org.bluezoo.gumdrop.mime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.bluezoo.gumdrop.mime.rfc2047.HeaderTextDecoder;
import org.bluezoo.gumdrop.util.ByteBufferPool;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ResourceBundle;

/**
 * A parser for MIME entities.
 *
 * <p>This parser uses a completely asynchronous, non-blocking,
 * push design pattern (also known as EDA or event driven architecture).
 * First a MimeHandler must be supplied to the parser for receiving
 * parsing events.
 * Then byte data can be supplied to the parser via the receive method as
 * and when it arrives, obviously in order. The MimeHandler (event sink)
 * will be notified of events that it can use to construct an in-memory
 * representation of the entity (AST) or store its component parts via some
 * kind of storage service which allows for not storing potentially large
 * representations entirely in memory.
 * The parser itself will not block the process receiving byte data.
 *
 * <h3>Buffer Management Contract</h3>
 * <p>This parser operates on standard {@link ByteBuffer} with a non-blocking
 * stream contract:
 * <ul>
 *   <li>The caller provides a buffer in read mode (ready for get operations)</li>
 *   <li>Header lines are consumed whole: the parser consumes as many complete
 *       header lines as possible, and an incomplete one stays in the buffer</li>
 *   <li>A body is consumed as it arrives, whether or not its lines are
 *       complete, so a body line of any length needs no buffer space</li>
 *   <li>After {@link #receive(ByteBuffer)} returns, the buffer's position
 *       indicates where unconsumed data begins</li>
 *   <li>If there is unconsumed data (a partial header line), the caller MUST call
 *       {@link ByteBuffer#compact()} before reading more data into the buffer</li>
 *   <li>The next {@code receive()} call will continue from where parsing left off</li>
 * </ul>
 *
 * <h3>Typical Usage</h3>
 * <pre>
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * MimeParser parser = new MimeParser();
 * parser.setHandler(myHandler);
 *
 * while (channel.read(buffer) &gt; 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 * parser.close();
 * </pre>
 *
 * <p>This base class handles core MIME parsing including:
 * <ul>
 *   <li>Content-Type header parsing and multipart boundary detection</li>
 *   <li>Content-Disposition header parsing</li>
 *   <li>Content-Transfer-Encoding handling (base64, quoted-printable)</li>
 *   <li>Content-ID header parsing</li>
 *   <li>MIME-Version header parsing</li>
 *   <li>Multipart boundary detection and entity lifecycle</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2045">RFC 2045: MIME Part One</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2046">RFC 2046: MIME Part Two — Media Types</a>
 */
// The older events are deprecated for removal but still delivered here
@SuppressWarnings("removal")
public class MimeParser {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.mime.L10N");

	/**
	 * Locator implementation for tracking parse position.
	 */
	static class MimeParserLocator implements MimeLocator {

		long offset;
		long lineNumber;
		long columnNumber;

		@Override
		public long getOffset() {
			return offset;
		}

		@Override
		public long getLineNumber() {
			return lineNumber;
		}

		@Override
		public long getColumnNumber() {
			return columnNumber;
		}

		/** The last byte counted, to recognise a CR that ended a line by itself. */
		private byte last;

		void reset() {
			offset = 0L;
			lineNumber = 1L;
			columnNumber = 0L;
			last = (byte) 0;
		}

		/**
		 * Counts the bytes in [from, to). A line feed ends a line. If
		 * crLineEnd is set a CR that is not followed by a line feed does
		 * too, and the byte that shows it is the first of the next line.
		 */
		void advance(ByteBuffer data, int from, int to, boolean crLineEnd) {
			for (int p = from; p < to; p++) {
				byte c = data.get(p);
				offset++;
				columnNumber++;
				if (c == '\n') {
					lineNumber++;
					columnNumber = 0L;
				} else if (crLineEnd && last == '\r') {
					lineNumber++;
					columnNumber = 1L;
				}
				last = c;
			}
		}

		/** Counts a line feed without ending its line yet. */
		void countLineFeed() {
			offset++;
			columnNumber++;
			last = (byte) '\n';
		}

		/** Ends the line whose line feed was counted with countLineFeed. */
		void endLine() {
			lineNumber++;
			columnNumber = 0L;
		}

	}

	enum State {
		INIT, // Start state
		HEADER, // Expecting header line or empty line up to and including CRLF
		BODY, // Non-multipart entity: expecting body content
		FIRST_BOUNDARY, // Multipart: expecting first boundary
		BOUNDARY_OR_CONTENT, // Multipart body: boundary can interrupt content
		BOUNDARY_ONLY; // After nested multipart end, only parent boundary
	}

	enum TransferEncoding {
		BINARY,
		BASE64,
		QUOTED_PRINTABLE;
	}

	/** Where the body scanner is within a line (multipart states only). */
	enum LineState {
		/** At the start of a line; held may hold the previous line's ending. */
		START,
		/** Assembling a line that starts with '-': it may be a boundary. */
		CANDIDATE,
		/** Inside an ordinary content line. */
		MID;
	}

	/**
	 * RFC 2046 defines the maximum boundary length of 70 characters. So a
	 * maximum boundary line length including the terminating CRLF will be
	 * 76 characters.
	 */
	private static final int MAX_BOUNDARY_LINE_LENGTH = 76;

	/**
	 * RFC 5322 defines the maximum header line length as 998 characters. So
	 * including the terminating CRLF this will be 1000.
	 */
	private static final int MAX_HEADER_LINE_LENGTH = 1000;

	/**
	 * The most transport padding (spaces and tabs, RFC 2046 section 5.1.1)
	 * that is looked for after a boundary delimiter. A line with more is
	 * ordinary content.
	 */
	private static final int MAX_BOUNDARY_PADDING = 64;

	/**
	 * The longest line that is assembled to see whether it is a boundary:
	 * two hyphens, a boundary of at most 70 characters, two more hyphens,
	 * padding and a line ending, with room to complete a CRLF.
	 */
	private static final int MAX_CANDIDATE_LINE = 70 + 6 + MAX_BOUNDARY_PADDING;

	private static final int INITIAL_HEADER_VALUE_CAPACITY = 1024;

	/** Default max unfolded header value size (32 KB), per Gmail/Exchange practice. */
	private static final int DEFAULT_MAX_HEADER_VALUE_SIZE = 32 * 1024;

	protected MimeHandler handler; // event sink
	protected MimeParserLocator locator;
	private State state = State.INIT; // current parser state
	private Deque<String> boundaries = new ArrayDeque<>(); // stack of boundary delimiters
	private boolean boundarySet;
	private String headerName;
	private ByteBuffer headerValueSink = ByteBufferPool.acquire(INITIAL_HEADER_VALUE_CAPACITY);
	protected boolean stripHeaderWhitespace = true; // leading and trailing ws in field-body
	private CharsetDecoder iso8859Decoder; // lazy, for decoding header bytes
	private boolean allowMalformedButRecoverableMultipart = false;
	private boolean allowMalformed = false; // allow malformed structures (line endings, boundaries, etc.)
	private int maxBufferSize = 4096;
	private int maxHeaderValueSize = DEFAULT_MAX_HEADER_VALUE_SIZE;
	private ParserLimits limits = new ParserLimits();
	private ByteBuffer decodeBuffer;  // Lazy allocated when needed for decoding
	private HeaderTextDecoder textDecoder = new HeaderTextDecoder(false); // reads streamed unstructured headers
	private final HeaderTextDecoder.Sink headerTextSink = new HeaderTextDecoder.Sink() {
		@Override
		public void text(String text) throws MimeParseException {
			handler.headerContent(text);
		}
	};
	private final ParameterParser params = new ParameterParser(false); // reads Content-Type and Content-Disposition
	private ParameterParser.Kind paramKind; // set while the current header is read by params
	private final ParameterCollector collector = new ParameterCollector(); // rebuilds the parameters for contentType and contentDisposition
	private final BoundaryCollector boundaryCollector = new BoundaryCollector(); // picks the boundary when the handler does not want objects
	private boolean legacy = true; // whether the handler wants the events that carry a whole header
	private String headType; // the type and subtype, or the disposition type, being collected
	private String headSubType;
	private final ParameterParser.Sink paramSink = new ParameterParser.Sink() {
		@Override
		public void head(String first, String second) throws MimeParseException {
			headType = first;
			headSubType = second;
			collector.clear();
			boundaryCollector.clear();
			if (paramKind == ParameterParser.Kind.CONTENT_TYPE) {
				handler.startContentType(first, second);
			} else {
				handler.startContentDisposition(first);
			}
		}

		@Override
		public void startParameter(String name, String language) throws MimeParseException {
			if (legacy) {
				collector.startParameter(name, language);
			} else {
				boundaryCollector.startParameter(name, language);
			}
			handler.startParameter(name, language);
		}

		@Override
		public void content(String text) throws MimeParseException {
			if (!legacy) {
				boundaryCollector.content(text);
			} else if (!collector.content(text, maxHeaderValueSize)) {
				String msg = MessageFormat.format(
					L10N.getString("err.header_value_too_long"), Integer.toString(maxHeaderValueSize));
				throw new HeaderValueTooLongException(msg, locator);
			}
			handler.parameterContent(text);
		}

		@Override
		public void endParameter() throws MimeParseException {
			if (legacy) {
				collector.endParameter();
			} else {
				boundaryCollector.endParameter();
			}
			handler.endParameter();
		}
	};
	private boolean collecting; // the value of the current header is being collected whole
	private MimeHandler.HeaderDelivery streaming; // how the current header is streamed, or null if it is not
	// Body scanner state, all of it of fixed size. See scanMultipart.
	private LineState lineState = LineState.START;
	private final byte[] held = new byte[2]; // line ending withheld until the next line is known not to be a boundary
	private int heldLen;
	private final ByteBuffer heldView = ByteBuffer.wrap(held);
	private final byte[] cand = new byte[MAX_CANDIDATE_LINE + 2]; // line being assembled to see if it is a boundary
	private int candLen;
	private final ByteBuffer candView = ByteBuffer.wrap(cand);
	private int bodyLocated; // body bytes of the current receive already counted by the locator
	private final byte[] b64Carry = new byte[3]; // base64 characters of an incomplete quantum
	private int b64CarryLen;
	private int b64LineLen; // length of the base64 line being read
	private final byte[] qpCarry = new byte[2]; // an escape in quoted-printable that is not complete
	private int qpCarryLen;
	private TransferEncoding transferEncoding = TransferEncoding.BINARY;
	private boolean allowCRLineEnd = false;
	private boolean underflow = false; // true if last receive() had unconsumed data

	/**
	 * Constructor.
	 */
	public MimeParser() {
		locator = new MimeParserLocator();
	}

	/**
	 * Sets the MIME handler instance (aka event sink) that will be used
	 * to receive parsing events.
	 * @param handler the MIME handler
	 */
	public void setHandler(MimeHandler handler) {
		this.handler = handler;
	}

	/**
	 * Returns the limits in force. The object is live: changing it changes
	 * the parser's limits.
	 * @return the limits
	 */
	public ParserLimits getLimits() {
		return limits;
	}

	/**
	 * Replaces the limits.
	 * @param limits the limits (must not be null)
	 */
	public void setLimits(ParserLimits limits) {
		if (limits == null) {
			throw new NullPointerException("limits");
		}
		this.limits = limits;
	}

	/**
	 * Gets the maximum buffer size for decoded content.
	 * @return the maximum buffer size in bytes
	 */
	public int getMaxBufferSize() {
		return maxBufferSize;
	}

	/**
	 * Sets the maximum buffer size for decoded content.
	 * This limits the amount of memory used when decoding Content-Transfer-Encoding.
	 * @param maxBufferSize the maximum buffer size in bytes (must be positive)
	 * @throws IllegalArgumentException if maxBufferSize is not positive
	 */
	public void setMaxBufferSize(int maxBufferSize) {
		if (maxBufferSize <= 0) {
			throw new IllegalArgumentException(L10N.getString("err.max_buffer_size_not_positive"));
		}
		this.maxBufferSize = maxBufferSize;
	}

	/**
	 * Gets the maximum unfolded header value size in bytes.
	 * @return the maximum size (default 32 KB)
	 */
	public int getMaxHeaderValueSize() {
		return maxHeaderValueSize;
	}

	/**
	 * Sets the maximum unfolded header value size in bytes.
	 * Prevents unbounded buffer allocation from folded header lines.
	 * @param maxHeaderValueSize the maximum size (must be positive)
	 * @throws IllegalArgumentException if maxHeaderValueSize is not positive
	 */
	public void setMaxHeaderValueSize(int maxHeaderValueSize) {
		if (maxHeaderValueSize <= 0) {
			throw new IllegalArgumentException(L10N.getString("err.max_header_value_size_not_positive"));
		}
		this.maxHeaderValueSize = maxHeaderValueSize;
	}

	/**
	 * Sets whether to allow malformed entity structures that can be recovered.
	 * When true, the parser will attempt to recover from malformed structures
	 * (like non-CRLF line endings, malformed boundaries) instead of throwing
	 * parse exceptions.
	 *
	 * @param allowMalformed true to allow malformed structures, false for strict parsing
	 */
	public void setAllowMalformed(boolean allowMalformed) {
		this.allowMalformed = allowMalformed;
	}

	protected void setAllowCRLineEnd(boolean flag) {
		allowCRLineEnd = flag;
	}

	/**
	 * Causes the parser to process as much data as it can read from the
	 * given byte buffer.
	 *
	 * <p>Multiple invocations of this method will typically occur, containing
	 * incomplete data. The parser will process as many complete header lines
	 * as it can, and as much of the body as it is given.
	 *
	 * <p><strong>Buffer Contract:</strong> The byte buffer must be in read mode
	 * (after {@code flip()}). After this method returns:
	 * <ul>
	 *   <li>The buffer's position indicates where unconsumed data begins</li>
	 *   <li>If {@code position() < limit()}, there is a partial header line that
	 *       needs more data to complete (a body is always consumed whole)</li>
	 *   <li>The caller MUST call {@code buffer.compact()} before reading more
	 *       data into the buffer</li>
	 * </ul>
	 *
	 * @param data the byte data (must be in read mode)
	 * @throws MimeParseException if any part of the parsing or
	 *         processing process wishes to cancel and abandon the parse
	 * @throws IllegalStateException if no handler has been set
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc2045#section-2">RFC 2045 §2</a>
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc2046#section-5.1">RFC 2046 §5.1</a>
	 */
	public void receive(ByteBuffer data) throws MimeParseException {
		if (handler == null) {
			throw new IllegalStateException(L10N.getString("err.no_handler"));
		}

		// Reset underflow at start of each receive
		underflow = false;

		if (state == State.INIT) {
			locator.reset();
			legacy = handler.wantsLegacyEvents();
			handler.setLocator(locator);
			handler.startEntity(null);
			startHeaders();
		}

		// Header lines are processed whole; a body is consumed as it
		// arrives, whether or not its lines are complete
		while (data.hasRemaining()) {
			if (state == State.HEADER) {
				if (!headerLineFrom(data)) {
					break;
				}
			} else {
				int used = bodyChunk(data);
				data.position(data.position() + used);
			}
		}

		if (state == State.HEADER) {
			if (data.hasRemaining()) {
				checkIncompleteHeaderLine(data);
			}
			// An incomplete header line is left in the buffer
			underflow = data.hasRemaining();
		} else {
			// A partial line of a multipart body has been consumed: it is
			// held by the scanner, and close() reports it if it never ends
			underflow = isMultipart(state) && lineState != LineState.START;
		}
	}

	private static boolean isMultipart(State state) {
		return state == State.FIRST_BOUNDARY
			|| state == State.BOUNDARY_OR_CONTENT
			|| state == State.BOUNDARY_ONLY;
	}

	/**
	 * Takes the header line at the front of data if it is complete: the
	 * line, with its line ending, is presented to headerLine with the
	 * buffer's position and limit set to enclose it.
	 * @return false if the line is incomplete (and so left in the buffer)
	 */
	private boolean headerLineFrom(ByteBuffer data) throws MimeParseException {
		int start = data.position();
		int end = data.limit();
		int eol = -1;
		boolean crOnly = false;
		for (int p = start; p < end; p++) {
			byte c = data.get(p);
			if (c == '\n') {
				eol = p + 1;
				break;
			}
			if (c == '\r' && allowCRLineEnd) {
				if (p + 1 >= end) {
					break; // CR or CRLF: wait to see
				}
				if (data.get(p + 1) != '\n') {
					eol = p + 1;
					crOnly = true;
					break;
				}
			}
		}
		if (eol < 0) {
			return false;
		}
		// The line's line feed is counted but its line is not ended until
		// the line has been dealt with
		if (crOnly) {
			locator.advance(data, start, eol, true);
		} else {
			locator.advance(data, start, eol - 1, allowCRLineEnd);
			locator.countLineFeed();
		}
		data.limit(eol);
		headerLine(data);
		data.limit(end);
		data.position(eol);
		if (!crOnly) {
			locator.endLine();
		}
		return true;
	}

	/**
	 * Checks the incomplete header line left at the front of the buffer, so
	 * that a line which can never be completed is rejected now and not
	 * waited for. The line is held by the caller's buffer, so the line limit
	 * is what bounds it.
	 */
	private void checkIncompleteHeaderLine(ByteBuffer data) throws MimeParseException {
		int maxLine = limits.getMaxHeaderLineLength();
		// The line may already hold its CR: the limit does not count the
		// line ending, so allow one byte for it
		if (maxLine > 0 && data.remaining() > maxLine + 1) {
			throw headerLineTooLong(maxLine);
		}
	}

	private HeaderLineTooLongException headerLineTooLong(int maxLine) {
		String msg = MessageFormat.format(
			L10N.getString("err.header_line_too_long"), Integer.toString(maxLine));
		return new HeaderLineTooLongException(msg, locator);
	}

	/**
	 * Returns whether the last receive() call had unconsumed data.
	 * If true, the caller should compact the buffer and provide more data.
	 * If close() is called while underflow is true, an exception is thrown.
	 *
	 * @return true if there is a partial line awaiting more data
	 */
	public boolean isUnderflow() {
		return underflow;
	}

	/**
	 * Process a header line up to its CRLF.
	 * The line delimiter (ideally CRLF) IS present at the end of the line.
	 * Buffer position/limit should be set to the line range.
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc5322#section-2.2">RFC 5322 §2.2</a>
	 */
	protected void headerLine(ByteBuffer buffer) throws MimeParseException {
		int start = buffer.position();
		int end = buffer.limit();
		// Remove line-end delimiter
		if (end - start > 0) {
			byte c = buffer.get(end - 1);
			if (c == '\n') {
				end--;
				buffer.limit(end);
				// check for preceding CR as well
				if (end - start > 0) {
					c = buffer.get(end - 1);
					if (c == '\r') {
						// it was CRLF
						end--;
						buffer.limit(end);
					}
				}
			} else if (allowCRLineEnd && c == '\r') {
				end--;
				buffer.limit(end);
			}
		}
		if (!buffer.hasRemaining()) { // empty line
			endHeaders();
			return;
		}

		int length = end - start;
		int maxLine = limits.getMaxHeaderLineLength();
		if (maxLine > 0 && length > maxLine) {
			throw headerLineTooLong(maxLine);
		}

		byte c = buffer.get(start);
		if (c == ' ' || c == '\t') {
			// LWSP-char
			// This is a header line continuation in a folded header
			if (headerName == null) { // no field-name in previous line
				throw new MimeParseException(L10N.getString("err.no_field_name"), locator);
			}

			// Note that according to RFC822 unfolding rules, the CRLF+LWSP
			// is equivalent to a LWSP. So we will append the LWSP to the
			// value here.
			if (length > 0) {
				feedValue(buffer, start, start + length);
			}
		} else {
			// flush existing header
			flushHeader();
			// field-name [FWS] ":"
			int colonPos = indexOf(buffer, (byte) ':');
			if (colonPos >= 0) {
				// Trim trailing WS from field-name
				int nameEnd = colonPos;
				while (nameEnd > start && isHeaderWhitespace(buffer.get(nameEnd - 1))) {
					nameEnd--;
				}
				if (nameEnd <= start) {
					throw new MimeParseException(L10N.getString("err.field_name_empty"), locator);
				}
				int maxName = limits.getMaxHeaderNameLength();
				if (maxName > 0 && nameEnd - start > maxName) {
					String msg = MessageFormat.format(
						L10N.getString("err.header_name_too_long"), Integer.toString(maxName));
					throw new MimeParseException(msg, locator);
				}
				for (int i = start; i < nameEnd; i++) {
					c = buffer.get(i);
					if (c < 33 || c > 126) { // illegal field-name character
						String msg = MessageFormat.format(L10N.getString("err.illegal_field_name_char"), Integer.toString(c & 0xFF));
						throw new MimeParseException(msg, locator);
					}
				}
				ByteBuffer nameView = buffer.duplicate();
				nameView.position(start).limit(nameEnd);
				headerName = decodeHeaderBytes(nameView, StandardCharsets.ISO_8859_1, true);
				startHeaderValue();
				// header value
				int valueLength = end - colonPos - 1;
				if (valueLength > 0) {
					feedValue(buffer, colonPos + 1, end);
				}
				// Note: if colonPos + 1 >= length, the header has no value (colon at end)
			} else {
				throw new MimeParseException(L10N.getString("err.no_colon_in_header"), locator);
			}
		}
	}

	/**
	 * Returns whether the whole value of a header is to be collected and
	 * given to {@link #header(String, ByteBuffer)} when it is complete. It
	 * is for the headers whose events carry a value built from the whole of
	 * it. A subclass that parses more headers than this class does extends
	 * this.
	 * @param name the header name, as written
	 * @return true if the value is collected
	 */
	protected boolean collectsHeader(String name) {
		switch (name.toLowerCase()) {
			case "content-transfer-encoding":
			case "content-id":
			case "content-description":
			case "mime-version":
				return true;
			default:
				return false;
		}
	}

	/** True for the headers this class understands, which are not streamed. */
	private static boolean isUnderstoodHeader(String name) {
		switch (name.toLowerCase()) {
			case "content-type":
			case "content-disposition":
			case "content-transfer-encoding":
			case "content-id":
			case "mime-version":
				return true;
			default:
				return false;
		}
	}

	/** A header name has been read: decide what is done with its value. */
	private void startHeaderValue() throws MimeParseException {
		collecting = collectsHeader(headerName);
		paramKind = null;
		if (headerName.equalsIgnoreCase("content-type")) {
			paramKind = ParameterParser.Kind.CONTENT_TYPE;
		} else if (headerName.equalsIgnoreCase("content-disposition")) {
			paramKind = ParameterParser.Kind.CONTENT_DISPOSITION;
		}
		if (paramKind != null) {
			params.start(paramKind, limits.getMaxTokenLength());
		}
		if (isUnderstoodHeader(headerName)) {
			streaming = null;
			return;
		}
		streaming = handler.headerDelivery(headerName);
		if (streaming == MimeHandler.HeaderDelivery.UNSTRUCTURED) {
			textDecoder.reset();
		}
		handler.startHeader(headerName);
	}

	/** Some of the current header's value, unfolded, in [from, to) of the buffer. */
	private void feedValue(ByteBuffer buffer, int from, int to) throws MimeParseException {
		if (collecting) {
			ensureHeaderValueSinkCapacity(to - from);
			headerValueSink.put(window(buffer, from, to));
		}
		if (paramKind != null) {
			int rest = params.feed(window(buffer, from, to), paramSink);
			if (rest >= 0) {
				malformedStructured(window(buffer, from + rest, to));
			}
		} else if (streaming == MimeHandler.HeaderDelivery.UNSTRUCTURED) {
			textDecoder.feed(window(buffer, from, to), headerTextSink);
		} else if (streaming == MimeHandler.HeaderDelivery.RAW) {
			handler.rawHeaderContent(window(buffer, from, to));
		}
	}

	/**
	 * The head of the Content-Type or Content-Disposition being read is not
	 * valid: the handler is told, and the value so far and the rest are
	 * delivered as unstructured text.
	 * @param rest the bytes of the value that were not read
	 */
	private void malformedStructured(ByteBuffer rest) throws MimeParseException {
		handler.malformedHeader(headerName, params.getMalformedReason());
		paramKind = null;
		streaming = MimeHandler.HeaderDelivery.UNSTRUCTURED;
		textDecoder.reset();
		textDecoder.feed(ByteBuffer.wrap(params.headBytes()), headerTextSink);
		textDecoder.feed(rest, headerTextSink);
	}

	/** The current header, if any, is complete. */
	private void flushHeader() throws MimeParseException {
		if (headerName == null) {
			return;
		}
		String name = headerName;
		if (paramKind != null) {
			ParameterParser.Kind kind = paramKind;
			if (params.finish(paramSink)) {
				paramKind = null;
				if (kind == ParameterParser.Kind.CONTENT_TYPE) {
					handler.endContentType();
					if (legacy) {
						ContentType contentType = new ContentType(headType, headSubType, collector.take());
						boundaryFound(contentType.getPrimaryType(), contentType.getParameter("boundary"));
						handler.contentType(contentType);
					} else {
						boundaryFound(headType, boundaryCollector.boundary());
					}
				} else {
					handler.endContentDisposition();
					if (legacy) {
						handler.contentDisposition(new ContentDisposition(headType, collector.take()));
					}
				}
			} else {
				malformedStructured(ByteBuffer.allocate(0));
			}
		}
		headerName = null;
		if (streaming != null) {
			if (streaming == MimeHandler.HeaderDelivery.UNSTRUCTURED) {
				textDecoder.finish(headerTextSink);
			}
			streaming = null;
			handler.endHeader();
		}
		if (collecting) {
			collecting = false;
			ByteBuffer valueView = headerValueSink.duplicate();
			valueView.flip();
			headerValueSink.clear();
			header(name, valueView);
		}
	}

	/**
	 * Sets whether raw 8-bit text in an unstructured header that is not valid
	 * UTF-8 is read as ISO-8859-1, as it is in an SMTPUTF8 message, and not as
	 * windows-1252. (Valid UTF-8 is read as UTF-8 either way.) Call before
	 * parsing.
	 * @param utf8Headers true for ISO-8859-1
	 */
	public void setUtf8Headers(boolean utf8Headers) {
		textDecoder = new HeaderTextDecoder(utf8Headers);
	}

	private static boolean isHeaderWhitespace(byte b) {
		return b == ' ' || b == '\t';
	}

	/**
	 * Ensures headerValueSink has at least {@code required} bytes remaining.
	 * Compacts and grows the buffer if necessary.
	 * @throws HeaderValueTooLongException if adding required bytes would exceed maxHeaderValueSize
	 */
	private void ensureHeaderValueSinkCapacity(int required) throws MimeParseException {
		int currentSize = headerValueSink.position();
		if (currentSize + required > maxHeaderValueSize) {
			throw new HeaderValueTooLongException(
				MessageFormat.format(L10N.getString("err.header_value_too_long"),
					Integer.valueOf(maxHeaderValueSize)), locator);
		}
		if (headerValueSink.remaining() >= required) {
			return;
		}
		// Flip to read mode so compact() preserves written data (position..limit)
		headerValueSink.flip();
		headerValueSink.compact();
		if (headerValueSink.remaining() < required) {
			int newCapacity = Math.max(headerValueSink.capacity() * 2, headerValueSink.position() + required);
			ByteBuffer newBuf = ByteBufferPool.acquire(newCapacity);
			headerValueSink.flip();
			newBuf.put(headerValueSink);
			ByteBufferPool.release(headerValueSink);
			headerValueSink = newBuf;
		}
	}

	/**
	 * Decodes header value bytes to a String using the given charset.
	 * Optionally trims leading and trailing whitespace when {@code trim} is true
	 * (respects {@link #stripHeaderWhitespace} when trim is true).
	 *
	 * @param buf the bytes to decode (position to limit)
	 * @param charset the charset for decoding
	 * @param trim whether to trim; when true, result is trimmed if stripHeaderWhitespace is set
	 * @return the decoded string, possibly trimmed
	 */
	protected String decodeHeaderBytes(ByteBuffer buf, Charset charset, boolean trim) {
		if (!buf.hasRemaining()) {
			return "";
		}
		try {
			CharsetDecoder decoder = charset.equals(StandardCharsets.ISO_8859_1)
				? getIso8859Decoder() : charset.newDecoder()
					.onMalformedInput(CodingErrorAction.REPLACE)
					.onUnmappableCharacter(CodingErrorAction.REPLACE);
			CharBuffer out = CharBuffer.allocate(buf.remaining() * 2);
			decoder.reset();
			decoder.decode(buf, out, true);
			decoder.flush(out);
			out.flip();
			String s = out.toString();
			if (trim && stripHeaderWhitespace) {
				s = s.trim();
			}
			return s;
		} catch (Exception e) {
			String s = charset.decode(buf.duplicate()).toString();
			if (trim && stripHeaderWhitespace) {
				s = s.trim();
			}
			return s;
		}
	}

	private CharsetDecoder getIso8859Decoder() {
		if (iso8859Decoder == null) {
			iso8859Decoder = StandardCharsets.ISO_8859_1.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		}
		return iso8859Decoder;
	}

	/**
	 * Finds the first occurrence of a byte in the buffer between position and limit.
	 * Does not advance the buffer.
	 *
	 * @param buffer the buffer to search (searches from position to limit)
	 * @param target the byte to find
	 * @return the index of the byte, or -1 if not found
	 */
	public static int indexOf(ByteBuffer buffer, byte target) {
		int start = buffer.position();
		int end = buffer.limit();
		for (int i = start; i < end; i++) {
			if (buffer.get(i) == target) {
				return i;
			}
		}
		return -1;
	}

	/** Position of first byte of a fold (CR or LF starting CRLF+LWSP or LF+LWSP), or -1. */
	private static int findNextFold(ByteBuffer value, int from, int stop) {
		for (int pos = from; pos < stop; pos++) {
			byte b = value.get(pos);
			if (b == '\r' && pos + 2 <= stop && value.get(pos + 1) == '\n'
					&& pos + 2 < stop && (value.get(pos + 2) == ' ' || value.get(pos + 2) == '\t')) {
				return pos;
			}
			if (b == '\n' && pos + 1 < stop && (value.get(pos + 1) == ' ' || value.get(pos + 1) == '\t')) {
				return pos;
			}
		}
		return -1;
	}

	/** Skip only the line-end (CRLF or LF); return position at the following LWSP so it is decoded. */
	private static int skipFold(ByteBuffer value, int foldStart, int limit) {
		if (foldStart + 2 <= limit && value.get(foldStart) == '\r' && value.get(foldStart + 1) == '\n') {
			return foldStart + 2;
		}
		if (foldStart + 1 < limit && value.get(foldStart) == '\n') {
			return foldStart + 1;
		}
		return foldStart + 1;
	}

	/**
	 * Decodes the buffer segment [position, limit) with the given decoder and consumes it.
	 * On return, buf.position() is set to buf.limit(). Result is trimmed.
	 *
	 * @param buf the buffer (segment to decode is position to limit)
	 * @param decoder charset decoder (will be reset)
	 * @return decoded string, or "" if position >= limit
	 */
	public static String decodeSlice(ByteBuffer buf, CharsetDecoder decoder) {
		if (!buf.hasRemaining()) {
			return "";
		}
		int end = buf.limit();
		try {
			decoder.reset();
			CharBuffer out = CharBuffer.allocate(buf.remaining() * 2);
			decoder.decode(buf, out, true);
			decoder.flush(out);
			out.flip();
			String s = out.toString().trim();
			buf.position(end);
			return s;
		} catch (Exception e) {
			ByteBuffer dup = buf.duplicate();
			byte[] bytes = new byte[dup.remaining()];
			dup.get(bytes);
			buf.position(end);
			return new String(bytes, decoder.charset()).trim();
		}
	}

	/**
	 * Decodes a token-only header value from the buffer: handles inline folding
	 * (CRLF+LWSP, LF+LWSP → space), decodes each segment with the decoder, joins with space.
	 * Advances value.position() to value.limit() (consumes the value).
	 * No RFC 2047 encoded-word expansion.
	 *
	 * @param value the header value bytes (position to limit); position is advanced to limit
	 * @param decoder charset decoder (reset per segment)
	 * @return decoded trimmed string
	 */
	protected String decodeTokenHeaderValue(ByteBuffer value, CharsetDecoder decoder) {
		int stop = value.limit();
		if (!value.hasRemaining()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		while (value.position() < stop) {
			int pos = value.position();
			int fold = findNextFold(value, pos, stop);
			int segmentEnd = fold >= 0 ? fold : stop;
			if (segmentEnd > pos) {
				int savedLimit = value.limit();
				value.limit(segmentEnd);
				String segment = decodeSlice(value, decoder);
				value.limit(savedLimit);
				if (!segment.isEmpty()) {
					if (out.length() > 0) {
						out.append(' ');
					}
					out.append(segment);
				}
			}
			if (fold < 0) {
				break;
			}
			value.position(skipFold(value, segmentEnd, stop));
		}
		value.position(stop);
		String s = out.toString();
		if (stripHeaderWhitespace) {
			s = s.trim();
		}
		return s;
	}

	/**
	 * We have received a complete header (name and value).
	 * The value is passed as bytes; each handle* method decodes to string when needed.
	 * Subclasses can override this to handle additional headers.
	 *
	 * @param name the header field name
	 * @param value the header field value bytes (position to limit)
	 */
	protected void header(String name, ByteBuffer value) throws MimeParseException {
		switch (name.toLowerCase().intern()) {
			case "content-transfer-encoding":
				handleContentTransferEncodingHeader(name, value);
				break;
			case "content-id":
				handleContentIDHeader(name, value);
				break;
			case "content-description":
				handleContentDescriptionHeader(name, value);
				break;
			case "mime-version":
				handleMIMEVersionHeader(name, value);
				break;
			default:
				// For base MIME parser, ignore other headers
				// Subclasses (MessageParser) can override to handle more
				break;
		}
	}

	/**
	 * RFC 2045 §5 / RFC 2046 §5.1.1 — a Content-Type header has been read:
	 * a multipart type with a valid boundary starts a multipart body.
	 */
	private void boundaryFound(String primaryType, String boundary) {
		if ("multipart".equalsIgnoreCase(primaryType)) {
			// check boundary matches RFC 2046 spec
			if (boundary != null && MimeUtils.isValidBoundary(boundary)) {
				if (boundarySet) {
					boundaries.removeLast(); // duplicate Content-Type
				}
				boundaries.addLast(boundary);
				boundarySet = true;
			}
		}
	}

	/** RFC 2045 §6.1 — Content-Transfer-Encoding header. */
	protected void handleContentTransferEncodingHeader(String name, ByteBuffer value) throws MimeParseException {
		String valueStr = decodeTokenHeaderValue(value.duplicate(), getIso8859Decoder());
		switch (valueStr.toLowerCase().intern()) {
			case "base64":
				transferEncoding = TransferEncoding.BASE64;
				handler.contentTransferEncoding(valueStr);
				break;
			case "quoted-printable":
				transferEncoding = TransferEncoding.QUOTED_PRINTABLE;
				handler.contentTransferEncoding(valueStr);
				break;
			case "7bit":
			case "8bit":
			case "binary":
				handler.contentTransferEncoding(valueStr);
				break;
			default:
				if (valueStr.startsWith("x-") && MimeUtils.isToken(valueStr)) {
					handler.contentTransferEncoding(valueStr);
				}
		}
	}

	/** RFC 2045 §7 — Content-ID header. */
	protected void handleContentIDHeader(String name, ByteBuffer value) throws MimeParseException {
		ContentID id = ContentIDParser.parse(value.duplicate(), getIso8859Decoder());
		if (id != null) {
			handler.contentID(id.getLocalPart(), id.getDomain());
			if (legacy) {
				handler.contentID(id);
			}
		}
	}

	/** RFC 2045 §8 — Content-Description header. */
	protected void handleContentDescriptionHeader(String name, ByteBuffer value) throws MimeParseException {
		String valueStr = decodeTokenHeaderValue(value.duplicate(), getIso8859Decoder());
		handler.contentDescription(valueStr);
	}

	/** RFC 2045 §4 — MIME-Version header. */
	protected void handleMIMEVersionHeader(String name, ByteBuffer value) throws MimeParseException {
		String valueStr = decodeTokenHeaderValue(value.duplicate(), getIso8859Decoder());
		MimeVersion mimeVersion = MimeVersion.parse(valueStr);
		if (mimeVersion != null) {
			handler.mimeVersion(1, 0); // the only version there is
			if (legacy) {
				handler.mimeVersion(mimeVersion);
			}
		}
	}

	private void startHeaders() throws MimeParseException {
		state = State.HEADER;
		boundarySet = false;
		transferEncoding = TransferEncoding.BINARY;  // Reset transfer encoding for new part
		resetBodyScanner();
	}

	private void endHeaders() throws MimeParseException {
		flushHeader();
		handler.endHeaders();
		if (boundarySet) {  // Set in header() method
			state = State.FIRST_BOUNDARY;
		} else if (!boundaries.isEmpty()) {
			state = State.BOUNDARY_OR_CONTENT;
		} else {
			state = State.BODY;
		}
	}

	/**
	 * Receives the raw bytes of the body of the entity, in order, as they
	 * are consumed: everything after the headers, including boundary lines,
	 * preamble and epilogue, exactly as they arrived. Does nothing by
	 * default. A subclass that needs the body as it was transmitted (to
	 * compute a signature) overrides this. The runs have no particular
	 * size and need not end at a line ending.
	 * @param data the consumed bytes, valid only for the duration of the call
	 * @exception MimeParseException if the subclass wishes to cancel the parse
	 */
	protected void rawBodyContent(ByteBuffer data) throws MimeParseException {
	}

	/**
	 * Consumes body bytes from the buffer. The bytes are used as they arrive
	 * and not held waiting for a line to end.
	 * @return the number of bytes consumed. This is all of them unless a
	 * boundary line was found that starts a new entity's headers, which
	 * are then to be read from the remainder.
	 */
	private int bodyChunk(ByteBuffer data) throws MimeParseException {
		int base = data.position();
		int len = data.remaining();
		bodyLocated = base;
		int used;
		if (state == State.BODY) {
			emitContent(data, base, base + len);
			used = len;
		} else {
			used = scanMultipart(data);
		}
		syncLocator(data, base + used);
		rawBodyContent(window(data, base, base + used));
		return used;
	}

	/** Brings the locator up to the given absolute position in data. */
	private void syncLocator(ByteBuffer data, int upTo) {
		if (upTo > bodyLocated) {
			locator.advance(data, bodyLocated, upTo, allowCRLineEnd);
			bodyLocated = upTo;
		}
	}

	private static ByteBuffer window(ByteBuffer src, int from, int to) {
		ByteBuffer w = src.duplicate();
		w.limit(to);
		w.position(from);
		return w;
	}

	private void resetBodyScanner() {
		lineState = LineState.START;
		heldLen = 0;
		candLen = 0;
		b64CarryLen = 0;
		b64LineLen = 0;
		qpCarryLen = 0;
	}

	/** The longest line that could be a boundary delimiter line. */
	private int boundaryLineLimit() {
		if (boundaries.isEmpty()) {
			return 0;
		}
		return Math.min(boundaries.getLast().length() + 6 + MAX_BOUNDARY_PADDING, MAX_CANDIDATE_LINE);
	}

	/**
	 * Scans multipart body bytes. Runs of ordinary lines go to the content
	 * pipeline straight from the buffer. A line that starts with '-' is
	 * assembled to see whether it is a boundary delimiter. The line ending
	 * before a line is withheld until that line is known not to be a
	 * delimiter, because the ending belongs to the delimiter if it is
	 * (RFC 2046 section 5.1.1). Nothing else is held, so the memory used
	 * does not depend on the length of a line.
	 * @return the number of bytes consumed
	 */
	private int scanMultipart(ByteBuffer data) throws MimeParseException {
		int base = data.position();
		int end = data.limit();
		int i = base;
		while (i < end) {
			switch (lineState) {
				case START:
					if (data.get(i) == '-' && !boundaries.isEmpty()) {
						candLen = 0;
						lineState = LineState.CANDIDATE;
					} else {
						releaseHeld();
						lineState = LineState.MID;
					}
					break;

				case CANDIDATE: {
					if (allowCRLineEnd && candLen > 0 && cand[candLen - 1] == '\r') {
						// The CR that ended the chunk: a line end by itself,
						// or the first half of CRLF
						if (data.get(i) == '\n') {
							cand[candLen++] = '\n';
							i++;
						}
						if (candidateLineComplete(data, i)) {
							return i - base;
						}
						break;
					}
					int take = end - i;
					boolean complete = false;
					for (int p = i; p < end; p++) {
						byte c = data.get(p);
						if (c == '\n') {
							take = p + 1 - i;
							complete = true;
							break;
						}
						if (c == '\r' && allowCRLineEnd) {
							if (p + 1 < end) {
								take = (data.get(p + 1) == '\n') ? p + 2 - i : p + 1 - i;
								complete = true;
							} else {
								take = p + 1 - i; // CR or CRLF: wait to see
							}
							break;
						}
					}
					if (candLen + take > boundaryLineLimit()) {
						// Too long to be a delimiter line: ordinary content
						releaseHeld();
						int n = candLen;
						candLen = 0;
						lineState = LineState.MID;
						// A trailing CR may be half a line ending: withhold it
						boolean cr = n > 0 && cand[n - 1] == '\r';
						if (cr) {
							n--;
						}
						candView.clear();
						emitContent(candView, 0, n);
						if (cr) {
							held[0] = '\r';
							heldLen = 1;
						}
						break;
					}
					data.get(i, cand, candLen, take);
					candLen += take;
					i += take;
					if (complete && candidateLineComplete(data, i)) {
						return i - base;
					}
					break;
				}

				case MID:
					i = scanMid(data, i, end);
					break;
			}
		}
		return i - base;
	}

	/**
	 * Scans ordinary line content. The run ends at the first line ending
	 * that is followed by a hyphen (a possible delimiter) or by the end of
	 * the input.
	 * @return the position of the first byte not consumed
	 */
	private int scanMid(ByteBuffer data, int i, int end) throws MimeParseException {
		if (heldLen == 1 && held[0] == '\r') {
			// A CR withheld at the end of the last chunk
			if (data.get(i) == '\n') {
				held[1] = '\n';
				heldLen = 2;
				lineState = LineState.START;
				return i + 1;
			}
			if (allowCRLineEnd) {
				lineState = LineState.START; // the CR ended the line by itself
				return i;
			}
			releaseHeld(); // the CR is content
		}
		int runStart = i;
		int p = i;
		while (p < end) {
			byte c = data.get(p);
			int es; // where the line ending starts
			int el; // its length
			if (c == '\n') {
				es = (p > runStart && data.get(p - 1) == '\r') ? p - 1 : p;
				el = p + 1 - es;
			} else if (c == '\r') {
				if (p + 1 >= end) {
					// Perhaps the first half of CRLF: withhold it
					emitContent(data, runStart, p);
					held[0] = '\r';
					heldLen = 1;
					return end;
				}
				if (!allowCRLineEnd) {
					p++;
					continue;
				}
				es = p;
				el = (data.get(p + 1) == '\n') ? 2 : 1;
			} else {
				p++;
				continue;
			}
			int next = es + el;
			if (next >= end || data.get(next) == '-') {
				emitContent(data, runStart, es);
				for (int k = 0; k < el; k++) {
					held[k] = data.get(es + k);
				}
				heldLen = el;
				lineState = LineState.START;
				return next;
			}
			p = next;
		}
		emitContent(data, runStart, end);
		return end;
	}

	/**
	 * A line that started with a hyphen is complete in cand. If it is a
	 * boundary delimiter the entity structure events follow from it.
	 * @param data the buffer being scanned
	 * @param i the position after the line in data
	 * @return true if the line starts a new entity, whose headers are then to
	 * be read from the data after it
	 */
	private boolean candidateLineComplete(ByteBuffer data, int i) throws MimeParseException {
		int n = candLen;
		candLen = 0;
		lineState = LineState.START;
		candView.clear();
		candView.limit(n);
		BoundaryMatch match = detectBoundary(candView);
		if (match == null) {
			releaseHeld();
			int el = lineEndingLength(n);
			candView.clear();
			emitContent(candView, 0, n - el);
			for (int k = 0; k < el; k++) {
				held[k] = cand[n - el + k];
			}
			heldLen = el;
			return false;
		}

		// The line ending before the delimiter belongs to the delimiter
		heldLen = 0;
		endContent();

		// The delimiter line is counted, but its line is not ended until its
		// events have been delivered
		boolean lineFeed = cand[n - 1] == '\n';
		if (lineFeed) {
			syncLocator(data, i - 1);
			locator.countLineFeed();
			bodyLocated = i;
		} else {
			syncLocator(data, i);
		}

		boolean startsEntity;
		switch (state) {
			case FIRST_BOUNDARY:
			case BOUNDARY_ONLY:
				if (match.isEndBoundary) {
					handler.endEntity(match.boundary);
					transferEncoding = TransferEncoding.BINARY;
					boundaries.removeLast();
					state = State.BOUNDARY_ONLY;
					startsEntity = false;
				} else {
					handler.startEntity(match.boundary);
					transferEncoding = TransferEncoding.BINARY;
					startHeaders();
					startsEntity = true;
				}
				break;
			default: // BOUNDARY_OR_CONTENT
				if (match.isEndBoundary) {
					handler.endEntity(match.boundary);
					transferEncoding = TransferEncoding.BINARY;
					boundaries.removeLast();
					if (!boundaries.isEmpty()) {
						handler.endEntity(boundaries.getLast());
					}
					state = State.BOUNDARY_ONLY;
					startsEntity = false;
				} else {
					handler.endEntity(match.boundary);
					handler.startEntity(match.boundary);
					transferEncoding = TransferEncoding.BINARY;
					startHeaders();
					startsEntity = true;
				}
				break;
		}
		if (lineFeed) {
			locator.endLine();
		}
		return startsEntity;
	}

	/** The length of the line ending at the end of the first n bytes of cand. */
	private int lineEndingLength(int n) {
		if (n >= 2 && cand[n - 2] == '\r' && cand[n - 1] == '\n') {
			return 2;
		}
		if (n >= 1 && (cand[n - 1] == '\n' || (allowCRLineEnd && cand[n - 1] == '\r'))) {
			return 1;
		}
		return 0;
	}

	/** Delivers the withheld line ending as content. */
	private void releaseHeld() throws MimeParseException {
		if (heldLen > 0) {
			int n = heldLen;
			heldLen = 0;
			heldView.clear();
			emitContent(heldView, 0, n);
		}
	}

	/**
	 * Sends body bytes in [from, to) of the buffer through the
	 * Content-Transfer-Encoding decoder, if any, to the handler.
	 */
	private void emitContent(ByteBuffer data, int from, int to) throws MimeParseException {
		if (from >= to) {
			return;
		}
		switch (transferEncoding) {
			case BASE64:
				decodeBase64Stream(data, from, to);
				break;
			case QUOTED_PRINTABLE:
				decodeQuotedPrintableStream(data, from, to);
				break;
			default:
				// Pass through as-is for binary, 7bit, 8bit, x-token encodings
				for (int p = from; p < to; p += maxBufferSize) {
					route(window(data, p, Math.min(to, p + maxBufferSize)));
				}
				break;
		}
	}

	/** Delivers content to the handler: preamble and epilogue are unexpected. */
	private void route(ByteBuffer content) throws MimeParseException {
		if (state == State.FIRST_BOUNDARY || state == State.BOUNDARY_ONLY) {
			handler.unexpectedContent(content);
		} else {
			handler.bodyContent(content);
		}
	}

	private void ensureDecodeBuffer() {
		int size = Math.max(maxBufferSize, 8);
		if (decodeBuffer == null || decodeBuffer.capacity() < size) {
			ByteBufferPool.release(decodeBuffer);
			decodeBuffer = ByteBufferPool.acquire(size);
		}
	}

	/** Hands what has been decoded to the handler, and empties the buffer. */
	private void deliverDecoded() throws MimeParseException {
		if (decodeBuffer.position() > 0) {
			decodeBuffer.flip(); // ready for reading
			route(decodeBuffer);
		}
		decodeBuffer.clear();
	}

	/**
	 * Decodes base64 as it arrives. The decoder takes every complete
	 * quantum of four characters; the characters of an incomplete one
	 * (at most three) wait in b64Carry to be completed by the next input.
	 */
	private void decodeBase64Stream(ByteBuffer data, int from, int to) throws MimeParseException {
		ensureDecodeBuffer();
		decodeBuffer.clear();
		if (!allowMalformed) {
			checkBase64LineLength(data, from, to);
		}
		ByteBuffer src = window(data, from, to);
		if (b64CarryLen > 0) {
			completeBase64Quantum(src);
			deliverDecoded();
		}
		int max = Math.max(maxBufferSize, 3);
		while (src.hasRemaining()) {
			int significant = skipToBase64(src);
			if (significant < 0) {
				break; // nothing but white space and junk is left
			}
			if (src.get(significant) == '=') {
				// Padding with no characters before it: the rest of the
				// line is ignored
				dropRestOfLine(src);
				continue;
			}
			int consumed = Base64Decoder.decode(src, decodeBuffer, max, false, false);
			deliverDecoded();
			if (consumed == 0) {
				break; // an incomplete quantum
			}
		}
		if (src.hasRemaining()) {
			saveBase64Carry(src); // the carry was decoded, if there was one
		}
	}

	/** RFC 2045 section 6.8: base64 lines are at most 76 characters. */
	private void checkBase64LineLength(ByteBuffer data, int from, int to) {
		for (int p = from; p < to; p++) {
			byte b = data.get(p);
			if (b == '\r' || b == '\n') {
				b64LineLen = 0;
			} else if (++b64LineLen > Base64Decoder.MAX_LINE_LENGTH) {
				throw new IllegalArgumentException(
						"RFC 2045 \u00a76.8: Base64 line exceeds "
						+ Base64Decoder.MAX_LINE_LENGTH + " characters");
			}
		}
	}

	/**
	 * Returns the position of the next base64 character or padding in src,
	 * or -1 if there is none; src's position is moved to it, the white space
	 * and junk before it having no effect on the decoding.
	 */
	private static int skipToBase64(ByteBuffer src) {
		while (src.hasRemaining()) {
			byte b = src.get(src.position());
			if (b == '=' || Base64Decoder.isBase64Char(b)) {
				return src.position();
			}
			src.position(src.position() + 1);
		}
		return -1;
	}

	/** Skips past the next line feed in src, or to its end if there is none. */
	private static void dropRestOfLine(ByteBuffer src) {
		while (src.hasRemaining()) {
			if (src.get() == '\n') {
				return;
			}
		}
	}

	/**
	 * Completes the carried characters of a quantum with those at the front
	 * of src, and decodes them into decodeBuffer. If src runs out first the
	 * characters join the carry.
	 */
	private void completeBase64Quantum(ByteBuffer src) {
		byte[] group = new byte[4];
		int n = b64CarryLen;
		System.arraycopy(b64Carry, 0, group, 0, n);
		boolean padded = false;
		while (n < 4 && src.hasRemaining()) {
			byte b = src.get();
			if (Base64Decoder.isBase64Char(b)) {
				group[n++] = b;
			} else if (b == '=') {
				group[n++] = b;
				padded = true;
				break;
			}
		}
		if (n == 4 || padded) {
			b64CarryLen = 0;
			Base64Decoder.decode(ByteBuffer.wrap(group, 0, n), decodeBuffer, 4, padded, false);
		} else {
			System.arraycopy(group, 0, b64Carry, 0, n);
			b64CarryLen = n;
		}
	}

	/** Keeps the base64 characters of an incomplete quantum left in src. */
	private void saveBase64Carry(ByteBuffer src) {
		int n = 0;
		while (src.hasRemaining()) {
			byte b = src.get();
			if (Base64Decoder.isBase64Char(b) && n < b64Carry.length) {
				b64Carry[n++] = b;
			}
		}
		b64CarryLen = n;
	}

	/**
	 * Decodes quoted-printable as it arrives. It is not line oriented: the
	 * decoder takes every escape that is complete, and an escape that is not
	 * (an '=' and at most one more byte) waits in qpCarry for the next input.
	 */
	private void decodeQuotedPrintableStream(ByteBuffer data, int from, int to) throws MimeParseException {
		ensureDecodeBuffer();
		decodeBuffer.clear();
		ByteBuffer src = window(data, from, to);
		int max = Math.max(maxBufferSize, 3);
		if (qpCarryLen > 0) {
			// Resolve the carried escape with the next few bytes
			byte[] tmp = new byte[qpCarry.length + 4];
			int tl = qpCarryLen;
			System.arraycopy(qpCarry, 0, tmp, 0, tl);
			int k = Math.min(4, src.remaining());
			src.get(src.position(), tmp, tl, k);
			tl += k;
			ByteBuffer tb = ByteBuffer.wrap(tmp, 0, tl);
			int consumed = QuotedPrintableDecoder.decode(tb, decodeBuffer, tl, false);
			deliverDecoded();
			if (k == src.remaining()) {
				// All the input is in tmp: what it leaves is the new carry
				src.position(src.limit());
				qpCarryLen = tl - consumed;
				System.arraycopy(tmp, consumed, qpCarry, 0, qpCarryLen);
				return;
			}
			src.position(src.position() + consumed - qpCarryLen);
			qpCarryLen = 0;
		}
		while (src.remaining() > 2 || (src.hasRemaining() && !endsInEscape(src))) {
			int consumed = QuotedPrintableDecoder.decode(src, decodeBuffer, max, false);
			deliverDecoded();
			if (consumed == 0) {
				break;
			}
		}
		// What is left is an incomplete escape of at most two bytes
		qpCarryLen = src.remaining();
		src.get(qpCarry, 0, qpCarryLen);
	}

	/** True if what remains of src (at most two bytes) starts an escape. */
	private static boolean endsInEscape(ByteBuffer src) {
		return src.get(src.position()) == '=';
	}

	/**
	 * The end of an entity's body: decodes what the decoders still hold,
	 * which is all there is of it since no more of the body follows.
	 */
	private void endContent() throws MimeParseException {
		if (b64CarryLen > 0) {
			ensureDecodeBuffer();
			decodeBuffer.clear();
			int n = b64CarryLen;
			b64CarryLen = 0;
			Base64Decoder.decode(ByteBuffer.wrap(b64Carry, 0, n), decodeBuffer, 4, true, false);
			deliverDecoded();
		}
		if (qpCarryLen > 0) {
			ensureDecodeBuffer();
			decodeBuffer.clear();
			int n = qpCarryLen;
			qpCarryLen = 0;
			QuotedPrintableDecoder.decode(ByteBuffer.wrap(qpCarry, 0, n), decodeBuffer, 4, true);
			deliverDecoded();
		}
	}

	private int decodeChunk(ByteBuffer source, ByteBuffer dest, TransferEncoding encoding,
			int max, boolean flushRemaining) {
		switch (encoding) {
			case BASE64:
				return Base64Decoder.decode(source, dest, max, flushRemaining,
						!allowMalformed); // RFC 2045 section 6.8 strict line length
			case QUOTED_PRINTABLE:
				return QuotedPrintableDecoder.decode(source, dest, max, flushRemaining);
			default:
				String msg = MessageFormat.format(
						L10N.getString("err.unsupported_parser_encoding"), encoding);
				throw new IllegalArgumentException(msg);
		}
	}

	/**
	 * Notifies the parser of an end of input, end of stream or end of
	 * connection event, indicating that there will be no more invocations
	 * of receive.
	 *
	 * <p>Use {@link #close(ByteBuffer)} if the caller may still hold data
	 * that receive left unconsumed. With this method such data is lost, and
	 * an incomplete header line is an error.
	 *
	 * @throws MimeParseException if there was unconsumed data in a context
	 *         where it's not allowed (headers or multipart boundaries)
	 */
	public void close() throws MimeParseException {
		close(null);
	}

	/**
	 * Notifies the parser of an end of input, as {@link #close()} does, and
	 * gives it what the caller still holds of the input: the part of the
	 * last line of the headers that receive left unconsumed because its line
	 * ending had not arrived. At the end of the input it is the last line of
	 * the headers, and is read as if its line ending had arrived. Nothing
	 * that is not text (only white space) is ignored. The buffer is consumed:
	 * its position is its limit when this method returns.
	 *
	 * <p>The last line of a multipart body that has no line ending is read
	 * as well, so a closing delimiter that is not followed by one is found.
	 * Text after the closing delimiter of the outermost multipart is never
	 * of any meaning, and nothing is made of it.
	 *
	 * @param remaining the unconsumed data, or null if there is none
	 * @throws MimeParseException if the end of the input is in a place where
	 *         it is an error: a multipart that is not closed, or a header
	 *         line that was left unconsumed and not given
	 */
	public void close(ByteBuffer remaining) throws MimeParseException {
		if (remaining != null && remaining.hasRemaining()) {
			if (state == State.INIT || state == State.HEADER) {
				finalHeaderLine(remaining);
				underflow = false; // it was given, so it is not lost
			}
			remaining.position(remaining.limit());
		}

		// A delimiter line at the very end has no line ending
		if (isMultipart(state) && lineState == LineState.CANDIDATE && candLen > 0) {
			candidateLineComplete(ByteBuffer.allocate(0), 0);
			underflow = lineState != LineState.START;
		}

		// Check underflow based on state
		if (underflow) {
			switch (state) {
				case INIT:
				case HEADER:
					// An incomplete header line that was not given is an error
					throw new MimeParseException(L10N.getString("err.incomplete_header"), locator);
				default:
					// A partial line of a multipart body is content, or after the
					// last delimiter nothing; an unclosed boundary is reported below.
					// A non-multipart body can end without a final newline.
					break;
			}
		}

		// Finalize header if we were accumulating one
		if (headerName != null) {
			endHeaders();
		}

		// What the scanner is holding back is content, since no boundary
		// followed it
		releaseHeld();
		endContent();

		// Validate that all multipart boundaries are properly closed
		if (!boundaries.isEmpty()) {
			String msg = MessageFormat.format(L10N.getString("err.unclosed_boundary"), boundaries.getLast());
			throw new MimeParseException(msg, locator);
		}

		handler.endEntity(null);
	}

	/**
	 * Reads the unterminated last line of the headers at the end of the
	 * input, if it is more than white space.
	 */
	private void finalHeaderLine(ByteBuffer remaining) throws MimeParseException {
		int n = remaining.remaining();
		boolean text = false;
		for (int i = remaining.position(); i < remaining.limit(); i++) {
			byte b = remaining.get(i);
			if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
				text = true;
				break;
			}
		}
		if (!text) {
			return;
		}
		ByteBuffer line = ByteBuffer.allocate(n + 2);
		line.put(remaining.duplicate());
		line.put((byte) '\r').put((byte) '\n');
		line.flip();
		receive(line);
	}

	/**
	 * Parses a whole entity from a blocking stream, and closes the parser's
	 * input at its end. It is an adapter over {@link #receive(ByteBuffer)}
	 * and {@link #close(ByteBuffer)}: it reads the stream into a buffer,
	 * gives it to the parser, and keeps what the parser leaves unconsumed
	 * (an incomplete header line) for the next read. The stream is not
	 * closed.
	 *
	 * <p>Memory use does not depend on the size of the entity. The buffer
	 * holds a header line of the longest length the limits allow.
	 *
	 * @param in the stream to read
	 * @throws IOException if the stream cannot be read, or a header line is
	 *         longer than the buffer can hold
	 * @throws MimeParseException if the entity is not valid or the handler
	 *         cancels the parse
	 */
	public void parse(InputStream in) throws IOException, MimeParseException {
		int line = limits.getMaxHeaderLineLength();
		ByteBuffer buf = ByteBuffer.allocate(Math.max(8192, line > 0 ? line + 4096 : 8192));
		byte[] array = buf.array();
		while (true) {
			int n = in.read(array, buf.position(), buf.remaining());
			if (n < 0) {
				break;
			}
			buf.position(buf.position() + n);
			buf.flip();
			receive(buf);
			if (buf.position() == 0 && buf.limit() == buf.capacity()) {
				throw new IOException(L10N.getString("err.no_progress"));
			}
			buf.compact();
		}
		buf.flip();
		close(buf);
	}

	/**
	 * Resets the parser in preparation for parsing another entity.
	 */
	public void reset() {
		locator.reset();
		state = State.INIT;
		headerName = null;
		collecting = false;
		streaming = null;
		paramKind = null;
		textDecoder.reset();
		ByteBufferPool.release(headerValueSink);
		headerValueSink = ByteBufferPool.acquire(INITIAL_HEADER_VALUE_CAPACITY);
		ByteBufferPool.release(decodeBuffer);
		decodeBuffer = null;
		resetBodyScanner();
		underflow = false;
	}

	/**
	 * Result class for boundary detection that includes boundary match information.
	 */
	protected static class BoundaryMatch {
		final String boundary;
		final boolean isEndBoundary; // true if this is a terminating boundary (ends with --)

		BoundaryMatch(String boundary, boolean isEndBoundary) {
			this.boundary = boundary;
			this.isEndBoundary = isEndBoundary;
		}
	}

	/**
	 * Detect if the buffer content matches the current (innermost) boundary.
	 * Buffer position/limit should be set to the line range.
	 *
	 * @param buffer the buffer to check
	 * @return BoundaryMatch if boundary found, null otherwise
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc2046#section-5.1.1">RFC 2046 §5.1.1</a>
	 */
	protected BoundaryMatch detectBoundary(ByteBuffer buffer) {
		if (boundaries.isEmpty()) {
			return null;
		}

		String boundary = boundaries.getLast();
		return checkBoundary(buffer, boundary);
	}

	/**
	 * Check if buffer content matches a specific boundary.
	 * Buffer position/limit should be set to the line range.
	 * Works directly with buffer bytes - no allocations.
	 *
	 * @param buffer the buffer to check
	 * @param boundary the boundary string to match
	 * @return BoundaryMatch if boundary found, null otherwise
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc2046#section-5.1.1">RFC 2046 §5.1.1</a>
	 */
	protected BoundaryMatch checkBoundary(ByteBuffer buffer, String boundary) {
		int start = buffer.position();
		int end = buffer.limit();

		int lineLength = end - start;

		// Check minimum length for boundary pattern "--boundary"
		if (lineLength < 2) {
			return null;
		}

		int pos = start; // Absolute position in buffer

		// Check initial "--" directly from buffer
		if (buffer.get(pos) != '-' || buffer.get(pos + 1) != '-') {
			return null;
		}
		pos += 2;
		// Check boundary string - compare buffer bytes directly with boundary chars (US-ASCII)
		for (int i = 0; i < boundary.length(); i++) {
			if (pos >= end || buffer.get(pos) != (byte) boundary.charAt(i)) {
				return null; // no match
			}
			pos++;
		}
		// Check what follows the boundary: an optional "--" (close delimiter),
		// optional transport padding (spaces and tabs, RFC 2046 section
		// 5.1.1), then the line end
		boolean isEnd = false;
		if (end - pos >= 2 && buffer.get(pos) == '-' && buffer.get(pos + 1) == '-') {
			isEnd = true;
			pos += 2;
		}
		while (pos < end && (buffer.get(pos) == ' ' || buffer.get(pos) == '\t')) {
			pos++;
		}
		int remaining = end - pos;
		if (remaining == 0) {
			return new BoundaryMatch(boundary, isEnd);
		}
		if (remaining == 1) {
			byte c = buffer.get(pos);
			if (c == '\n' || (allowCRLineEnd && c == '\r')) {
				return new BoundaryMatch(boundary, isEnd);
			}
			return null;
		}
		if (remaining == 2 && buffer.get(pos) == '\r' && buffer.get(pos + 1) == '\n') {
			return new BoundaryMatch(boundary, isEnd);
		}
		return null;
	}

}
