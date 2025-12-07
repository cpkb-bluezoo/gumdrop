/*
 * MIMEParser.java
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

import org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder;
import org.bluezoo.util.CompositeByteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A parser for MIME entities.
 * This parser uses a completely asynchronous, non-blocking,
 * push design pattern (also known as EDA or event driven architecture).
 * First a MIMEHandler must be supplied to the parser for receiving
 * parsing events.
 * Then byte data can be supplied to the parser via the receive method as
 * and when it arrives, obviously in order. The MIMEHandler (event sink)
 * will be notified of events that it can use to construct an in-memory
 * representation of the entity (AST) or store its component parts via some
 * kind of storage service which allows for not storing potentially large
 * representations entirely in memory.
 * The parser itself will not block the process receiving byte data.
 * <p>
 * This base class handles core MIME parsing including:
 * <ul>
 * <li>Content-Type header parsing and multipart boundary detection</li>
 * <li>Content-Disposition header parsing</li>
 * <li>Content-Transfer-Encoding handling (base64, quoted-printable)</li>
 * <li>Content-ID header parsing</li>
 * <li>MIME-Version header parsing</li>
 * <li>Multipart boundary detection and entity lifecycle</li>
 * </ul>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MIMEParser {

	/**
	 * Locator implementation for tracking parse position.
	 */
	static class MIMEParserLocator implements MIMELocator {

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

		void reset() {
			offset = 0L;
			lineNumber = 1L;
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

	protected MIMEHandler handler; // event sink
	protected MIMEParserLocator locator;
	private State state = State.INIT; // current parser state
	private Deque<String> boundaries = new ArrayDeque<>(); // stack of boundary delimiters
	private boolean boundarySet;
	private CompositeByteBuffer compositeBuffer = new CompositeByteBuffer(); // unified buffer management
	private String headerName;
	private ByteArrayOutputStream headerValueSink = new ByteArrayOutputStream();
	private boolean stripHeaderWhitespace = true; // leading and trailing ws in field-body
	private boolean allowMalformedButRecoverableMultipart = false;
	private boolean allowMalformed = false; // allow malformed structures (line endings, boundaries, etc.)
	private int maxBufferSize = 4096;
	private ByteBuffer decodeBuffer;  // Lazy allocated when needed for decoding
	private boolean contentFlushed; // manage state for content flushing
	private ByteArrayOutputStream pendingBodyContent; // Buffer for deferred body content flushing
	private boolean pendingBodyContentUnexpected; // Whether pending content is unexpected
	private TransferEncoding transferEncoding = TransferEncoding.BINARY;
	private boolean allowCRLineEnd = false;
	private byte last = (byte) 0; // last byte read (preserve over receive invocations)

	/**
	 * Constructor.
	 */
	public MIMEParser() {
		locator = new MIMEParserLocator();
	}

	/**
	 * Sets the MIME handler instance (aka event sink) that will be used
	 * to receive parsing events.
	 * @param handler the MIME handler
	 */
	public void setHandler(MIMEHandler handler) {
		this.handler = handler;
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
			throw new IllegalArgumentException("maxBufferSize must be positive");
		}
		this.maxBufferSize = maxBufferSize;
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
	 * Multiple invocations of this method will typically occur, containing
	 * incomplete data. The parser will process as much data as it can and
	 * store any remaining data for future invocations.
	 * The byte buffer supplied will be ready for reading and must not be
	 * modified by other threads during the execution of this method.
	 * Otherwise, no assumptions should be made about it; specifically, it
	 * should not be stored after this method terminates and if there is
	 * unprocessed data remaining in it, that data must be copied to an
	 * internal buffer.
	 * @param data the byte data
	 * @exception MIMEParseException if any part of the parsing or
	 * processing process wishes to cancel and abandon the parse. Calling
	 * receive after such an exception has been thrown is futile and a waste
	 * of processing resources.
	 */
	public void receive(ByteBuffer data) throws MIMEParseException {
		if (handler == null) {
			throw new IllegalStateException("No handler set");
		}

		switch (state) {
			case INIT:
				locator.reset();
				handler.setLocator(locator);
				handler.startEntity(null);
				startHeaders();
				break;
		}

		// Standard ByteBuffer workflow: put -> flip -> read -> compact
		compositeBuffer.put(data);
		compositeBuffer.flip();

		// Process data line by line using correct start/eol/end pattern
		int start = 0;  // Position at start of current line
		int end = compositeBuffer.limit();  // Original buffer limit
		int pos = start;
		int eol = -1;

		while (pos < end) {
			// Look for line ending starting from start
			byte c = compositeBuffer.get(pos++);
			locator.offset++;
			locator.columnNumber++;
			if (c == '\n') {
				eol = pos;
			} else if (allowCRLineEnd && last == '\r') {
				// Previous was CR, this is not LF, so CR was a line ending by itself
				eol = pos;
			}
			last = c;

			if (eol != -1) {
				// Found line delimiter, process the line
				// Set buffer window to line: position(start), limit(eol)
				compositeBuffer.limit(eol);
				compositeBuffer.position(start);

				switch (state) {
					case HEADER:
						// Exclude line delimiter for headers
						headerLine(compositeBuffer);
						break;
					default: // body of some variety
						// Include line delimiter for body
						bodyLine(compositeBuffer);
						break;
				}
				// Reset window
				compositeBuffer.limit(end);

				// Move to next line
				start = eol;
				eol = -1;
				locator.lineNumber++;
				locator.columnNumber = 0L;
			}
		}

		// Position buffer to unprocessed data and compact
		// position(start): position at last eol
		// limit(end): original limit
		compositeBuffer.position(start);
		compositeBuffer.limit(end);
		compositeBuffer.compact();
	}

	/**
	 * Process a header line up to its CRLF.
	 * The line delimiter (ideally CRLF) IS present at the end of the line.
	 * Buffer position/limit should be set to the line range (excluding line delimiter).
	 */
	private void headerLine(CompositeByteBuffer buffer) throws MIMEParseException {
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
		byte c = buffer.get(start);
		if (c == ' ' || c == '\t') {
			// LWSP-char
			// This is a header line continuation in a folded header
			if (headerName == null) { // no field-name in previous line
				throw new MIMEParseException("No field-name", locator);
			}

			// Note that according to RFC822 unfolding rules, the CRLF+LWSP
			// is equivalent to a LWSP. So we will append the LWSP to the
			// value here.
			if (length > 0) {
				// Write bytes directly from buffer without allocation
				byte[] bytes = new byte[length];
				buffer.get(bytes);
				headerValueSink.write(bytes, 0, length);
			}
		} else {
			// flush existing header
			if (headerName != null) {
				String headerValue = decodeHeaderValue(headerName, headerValueSink.toByteArray());
				if (stripHeaderWhitespace) {
					headerValue = headerValue.trim();
				}

				header(headerName, headerValue);
				headerName = null;
				headerValueSink.reset();
			}
			// field-name [FWS] ":"
			int colonPos = buffer.indexOf((byte) ':');
			if (colonPos >= 0) {
				byte[] bytes = new byte[length];
				buffer.get(bytes);
				colonPos -= start; // adjust to be relative to current line
				// Use iso-8859-1 and validate afterwards
				int pos = colonPos - 1;
				while (pos > 0 && (bytes[pos] == ' ' || bytes[pos] == '\t')) {
					pos--;
				}
				if (pos <= 0) {
					// header field name is empty
					throw new MIMEParseException("Field-name is empty", locator);
				}
				for (int i = 0; i <= pos; i++) {
					c = bytes[i];
					if (c < 33 || c > 126) { // illegal field-name character
						throw new MIMEParseException("Illegal field-name character", locator);
					}
				}
				headerName = new String(bytes, 0, pos + 1, StandardCharsets.ISO_8859_1).trim();
				// header value
				if (colonPos + 1 < length) {
					headerValueSink.write(bytes, colonPos + 1, length - (colonPos + 1));
				}
				// Note: if colonPos + 1 >= length, the header has no value (colon at end)
			} else {
				throw new MIMEParseException("No colon in header", locator);
			}
		}
	}

	/**
	 * Decode header value to a String.
	 * @param name the header name
	 * @param header the byte value of the header value
	 * @return a String representing the header value
	 */
	protected String decodeHeaderValue(String name, byte[] header) {
		return RFC2047Decoder.decodeHeaderValue(header);
	}

	/**
	 * We have received a complete header (name and value).
	 * At this point we will decide if this is a structured header or not. If
	 * so we will try to parse it and call the associated structured method
	 * on the handler. If parsing fails we call the unstructured header method.
	 * Subclasses can override this to handle additional headers.
	 */
	protected void header(String name, String value) throws MIMEParseException {
		// Intern the lowercase version of the name here for fast
		// comparison using ==. The set of likely possible field-names
		// here is not large.
		switch (name.toLowerCase().intern()) {
			case "content-type":
				handleContentTypeHeader(name, value);
				break;
			case "content-disposition":
				handleContentDispositionHeader(name, value);
				break;
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

	protected void handleContentTypeHeader(String name, String value) throws MIMEParseException {
		ContentType contentType = ContentTypeParser.parse(value);
		if (contentType != null) {
			if ("multipart".equalsIgnoreCase(contentType.getPrimaryType())) {
				String boundary = contentType.getParameter("boundary");
				// check boundary matches RFC 2046 spec
				if (boundary != null && MIMEUtils.isValidBoundary(boundary)) {
					if (boundarySet) {
						boundaries.removeLast(); // duplicate Content-Type
					}
					boundaries.addLast(boundary);
					boundarySet = true;
				}
			}
			handler.contentType(contentType);
		}
	}

	protected void handleContentDispositionHeader(String name, String value) throws MIMEParseException {
		ContentDisposition contentDisposition = ContentDispositionParser.parse(value);
		if (contentDisposition != null) {
			handler.contentDisposition(contentDisposition);
		}
	}

	protected void handleContentTransferEncodingHeader(String name, String value) throws MIMEParseException {
		switch (value.toLowerCase().intern()) {
			case "base64":
				transferEncoding = TransferEncoding.BASE64;
				handler.contentTransferEncoding(value);
				break;
			case "quoted-printable":
				transferEncoding = TransferEncoding.QUOTED_PRINTABLE;
				handler.contentTransferEncoding(value);
				break;
			case "7bit":
			case "8bit":
			case "binary":
				// transferEncoding already set to BINARY at startEntity
				handler.contentTransferEncoding(value);
				break;
			default:
				// x-token extension - just pass through if valid
				if (value.startsWith("x-") && MIMEUtils.isToken(value)) {
					handler.contentTransferEncoding(value);
				}
		}
	}

	protected void handleContentIDHeader(String name, String value) throws MIMEParseException {
		ContentID id = ContentIDParser.parse(value);
		if (id != null) {
			handler.contentID(id);
		}
	}

	protected void handleContentDescriptionHeader(String name, String value) throws MIMEParseException {
		handler.contentDescription(value);
	}

	protected void handleMIMEVersionHeader(String name, String value) throws MIMEParseException {
		MIMEVersion mimeVersion = MIMEVersion.parse(value);
		if (mimeVersion != null) {
			handler.mimeVersion(mimeVersion);
		}
	}

	private void startHeaders() throws MIMEParseException {
		state = State.HEADER;
		boundarySet = false;
		contentFlushed = false;  // Reset for new part boundary detection
		transferEncoding = TransferEncoding.BINARY;  // Reset transfer encoding for new part
		clearPendingBodyContent();  // Clear any pending body content from previous part
	}

	private void endHeaders() throws MIMEParseException {
		if (headerName != null) {
			String headerValue = decodeHeaderValue(headerName, headerValueSink.toByteArray());
			if (stripHeaderWhitespace) {
				headerValue = headerValue.trim();
			}

			header(headerName, headerValue);
			headerName = null;
			headerValueSink.reset();
		}
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
	 * We have received a complete or partial (end of) line up to and
	 * including the CRLF/CR/LF in the body.
	 * Buffer position/limit should be set to the line range (including line delimiter).
	 */
	private void bodyLine(CompositeByteBuffer buffer) throws MIMEParseException {
		contentFlushed = false;  // Reset per line - only prevents boundary detection within the same line
		int start = buffer.position();
		int end = buffer.limit();
		switch (state) {
			case FIRST_BOUNDARY: // expecting first boundary
			case BOUNDARY_ONLY: // we have completed a multipart part, expect parent boundary
				if (!contentFlushed && !boundaries.isEmpty()) {
					// Check if we have a boundary match
					// Buffer position/limit are already set correctly by caller
					BoundaryMatch match = detectBoundary(buffer);
					if (match != null) {
						// Flush any pending content with isBeforeBoundary=true (strip CRLF)
						flushPendingBodyContent(true);

						if (match.isEndBoundary) { // end boundary
							handler.endEntity(match.boundary);
							transferEncoding = TransferEncoding.BINARY;
							// This is the end of the multipart
							boundaries.removeLast();
							state = State.BOUNDARY_ONLY;
							return;
						} else { // boundary to start first part
							handler.startEntity(match.boundary);
							transferEncoding = TransferEncoding.BINARY;
							startHeaders(); // state will be HEADER
							return;
						}
					}
				}
				// Boundary not detected - flush pending with isBeforeBoundary=false and buffer current
				flushPendingBodyContent(false);
				bufferBodyContent(buffer, true);
				break;
			case BOUNDARY_OR_CONTENT: // subsequent body lines
				if (!contentFlushed && !boundaries.isEmpty()) {
					// Check if we have a boundary match
					// Buffer position/limit are already set correctly by caller
					BoundaryMatch match = detectBoundary(buffer);
					if (match != null) {
						// Flush any pending content with isBeforeBoundary=true (strip CRLF)
						flushPendingBodyContent(true);

						if (match.isEndBoundary) { // end boundary
							handler.endEntity(match.boundary); // end of the body part
							transferEncoding = TransferEncoding.BINARY;
							// This is the end of the multipart
							boundaries.removeLast();
							if (!boundaries.isEmpty()) {
								handler.endEntity(boundaries.getLast());
							}
							state = State.BOUNDARY_ONLY;
							return;
						} else { // boundary between body parts
							handler.endEntity(match.boundary);
							handler.startEntity(match.boundary);
							transferEncoding = TransferEncoding.BINARY;
							startHeaders(); // state will be HEADER
							return;
						}
					}
				}
				// boundary not detected - flush pending with isBeforeBoundary=false and buffer current
				flushPendingBodyContent(false);
				bufferBodyContent(buffer, false);
				break;
			case BODY: // body not in multipart
				flushBodyContent(buffer, false, false);
				break;
			case INIT:
			case HEADER:
				// Defensive programming: these states should not occur in bodyLine processing
				// If they do, it indicates a parser state management issue
				throw new MIMEParseException("Unexpected parser state " + state + " in body processing", locator);
		}
	}

	private void flushBodyContent(CompositeByteBuffer buffer, boolean unexpected, boolean isBeforeBoundary) throws MIMEParseException {
		// Buffer position/limit already correctly set by caller
		switch (transferEncoding) {
			case BASE64:
			case QUOTED_PRINTABLE:
				flushBodyContentWithDecoding(buffer, unexpected, transferEncoding, isBeforeBoundary);
				break;
			default:
				// Pass through as-is for binary, 7bit, 8bit, x-token encodings
				flushBodyContentBinary(buffer, unexpected, isBeforeBoundary);
				break;
		}
	}

	/**
	 * Flushes body content with decoding, handling chunked processing when decoded content exceeds maxBufferSize
	 * @param source CompositeByteBuffer containing the encoded content to decode
	 * @param unexpected true if this content should be reported as unexpected
	 * @param transferEncoding the Content-Transfer-Encoding (BASE64 or Quoted-Printable)
	 * @param isBeforeBoundary true if this content precedes a boundary, causing trailing CRLF/CR/LF to be stripped
	 */
	private void flushBodyContentWithDecoding(CompositeByteBuffer source, boolean unexpected, TransferEncoding transferEncoding, boolean isBeforeBoundary) throws MIMEParseException {
		// Lazy allocation of decodeBuffer - only allocate when actually needed for decoding
		if (decodeBuffer == null || decodeBuffer.capacity() < maxBufferSize) {
			decodeBuffer = ByteBuffer.allocate(maxBufferSize);
		}

		boolean hasProcessedContent = false;

		while (source.hasRemaining()) {
			decodeBuffer.clear();

			// Create temporary ByteBuffer from CompositeByteBuffer data for decoders
			int chunkSize = Math.min(source.remaining(), maxBufferSize);
			byte[] inputBytes = new byte[chunkSize];
			source.get(inputBytes);
			ByteBuffer inputBuffer = ByteBuffer.wrap(inputBytes);

			// Decode using ByteBuffer API
			DecodeResult result;
			switch (transferEncoding) {
				case BASE64:
					result = Base64Decoder.decode(inputBuffer, decodeBuffer, maxBufferSize);
					break;
				case QUOTED_PRINTABLE:
					result = QuotedPrintableDecoder.decode(inputBuffer, decodeBuffer, maxBufferSize);
					break;
				default:
					throw new IllegalArgumentException("Unsupported transfer encoding for decoding: " + transferEncoding);
			}

			if (result.decodedBytes > 0) {
				decodeBuffer.flip(); // ready for reading

				// If this is the last chunk and isBeforeBoundary, strip trailing line ending
				boolean isLastChunk = !source.hasRemaining();
				if (isLastChunk && isBeforeBoundary) {
					int limit = decodeBuffer.limit();
					// Check for CRLF
					if (limit >= 2 && decodeBuffer.get(limit - 2) == '\r' && decodeBuffer.get(limit - 1) == '\n') {
						decodeBuffer.limit(limit - 2);
					} else if (limit >= 1) {
						byte lastByte = decodeBuffer.get(limit - 1);
						if (lastByte == '\n' || (allowCRLineEnd && lastByte == '\r')) {
							decodeBuffer.limit(limit - 1);
						}
					}
				}

				if (decodeBuffer.hasRemaining()) {
					if (unexpected) {
						handler.unexpectedContent(decodeBuffer);
					} else {
						handler.bodyContent(decodeBuffer);
					}
				}
				hasProcessedContent = true;
			}

			// Check if we made progress
			if (result.consumedBytes <= 0) {
				// If no progress made, stop to avoid infinite loop
				break;
			} else if (result.consumedBytes < inputBytes.length) {
				// Partial consumption - put back unconsumed bytes
				int unconsumedBytes = inputBytes.length - result.consumedBytes;
				byte[] remaining = new byte[unconsumedBytes];
				System.arraycopy(inputBytes, result.consumedBytes, remaining, 0, unconsumedBytes);
				// We need to "rewind" the CompositeByteBuffer by putting bytes back
				// This is complex, so for now we'll process all input and let the decoder handle partial sequences
			}
		}
		// Set contentFlushed appropriately - indicates we processed content
		contentFlushed = hasProcessedContent;
	}

	/**
	 * Flushes binary content without allocation - passes through input buffer directly in chunks
	 * @param source CompositeByteBuffer containing the binary content to pass through
	 * @param unexpected true if this content should be reported as unexpected
	 * @param isBeforeBoundary true if this content precedes a boundary, causing trailing CRLF/CR/LF to be stripped
	 */
	private void flushBodyContentBinary(CompositeByteBuffer source, boolean unexpected, boolean isBeforeBoundary) throws MIMEParseException {
		boolean hasProcessedContent = false;

		while (source.hasRemaining()) {
			// Calculate how much to process in this chunk
			int chunkSize = Math.min(source.remaining(), maxBufferSize);

			// Create temporary byte array for this chunk
			byte[] chunkBytes = new byte[chunkSize];
			source.get(chunkBytes);

			// If this is the last chunk and isBeforeBoundary, strip trailing line ending
			boolean isLastChunk = !source.hasRemaining();
			int limit = chunkBytes.length;
			if (isLastChunk && isBeforeBoundary) {
				// Check for CRLF
				if (limit >= 2 && chunkBytes[limit - 2] == '\r' && chunkBytes[limit - 1] == '\n') {
					limit -= 2;
				} else if (limit >= 1) {
					byte lastByte = chunkBytes[limit - 1];
					if (lastByte == '\n' || (allowCRLineEnd && lastByte == '\r')) {
						limit -= 1;
					}
				}
			}

			// Create ByteBuffer for handler
			ByteBuffer chunkBuffer = ByteBuffer.wrap(chunkBytes, 0, limit);

			if (chunkBuffer.hasRemaining()) {
				if (unexpected) {
					handler.unexpectedContent(chunkBuffer);
				} else {
					handler.bodyContent(chunkBuffer);
				}
			}

			hasProcessedContent = true;
		}
		// Set contentFlushed appropriately - indicates we processed content in chunks
		contentFlushed = hasProcessedContent;
	}

	/**
	 * Buffers body content for deferred flushing. In multipart entities, we need to
	 * defer flushing until we know if a boundary follows, so we can strip the
	 * trailing CRLF that is part of the boundary delimiter.
	 * @param buffer the content to buffer
	 * @param unexpected whether this content is unexpected
	 */
	private void bufferBodyContent(CompositeByteBuffer buffer, boolean unexpected) {
		if (pendingBodyContent == null) {
			pendingBodyContent = new ByteArrayOutputStream();
		}
		int start = buffer.position();
		int end = buffer.limit();
		for (int i = start; i < end; i++) {
			pendingBodyContent.write(buffer.get(i));
		}
		pendingBodyContentUnexpected = unexpected;
	}

	/**
	 * Flushes any pending body content.
	 * @param isBeforeBoundary true if this content precedes a boundary
	 */
	private void flushPendingBodyContent(boolean isBeforeBoundary) throws MIMEParseException {
		if (pendingBodyContent != null && pendingBodyContent.size() > 0) {
			byte[] bytes = pendingBodyContent.toByteArray();
			CompositeByteBuffer buffer = new CompositeByteBuffer();
			buffer.put(ByteBuffer.wrap(bytes));
			buffer.flip();
			flushBodyContent(buffer, pendingBodyContentUnexpected, isBeforeBoundary);
			pendingBodyContent.reset();
		}
	}

	/**
	 * Clears any pending body content without flushing.
	 */
	private void clearPendingBodyContent() {
		if (pendingBodyContent != null) {
			pendingBodyContent.reset();
		}
	}

	/**
	 * Notifies the parser of an end of input, end of stream or end of
	 * connection event, indicating that there will be no more invocations
	 * of receive.
	 * @exception MIMEParseException if an error occurred processing the
	 * end of the input
	 */
	public void close() throws MIMEParseException {
		// After receive(), the compositeBuffer is in "write mode" (position=limit after compact()).
		// We need to flip() to "read mode" to access any unprocessed data remaining in the buffer.
		compositeBuffer.flip();

		if (headerName != null) {
			endHeaders();
		}
		// Flush any pending body content with isBeforeBoundary=false since we're at EOF
		flushPendingBodyContent(false);
		if (compositeBuffer.hasRemaining()) {
			int len;
			if (allowMalformedButRecoverableMultipart && state == State.BOUNDARY_OR_CONTENT) {
				state = State.BODY;
			}
			switch (state) {
				case INIT:
					// Defensive programming: INIT state at EOF means no entity was started
					throw new MIMEParseException("EOF in initial state (no entity started)", locator);
				case HEADER:
					throw new MIMEParseException("EOF before end of headers", locator);
				case BODY:
					len = compositeBuffer.remaining();
					if (decodeBuffer == null || decodeBuffer.capacity() < len) {
						decodeBuffer = ByteBuffer.allocate(len);
					}
					// Copy bytes from CompositeByteBuffer to decodeBuffer
					byte[] bytes = new byte[len];
					compositeBuffer.get(bytes);
					decodeBuffer.clear();
					decodeBuffer.put(bytes);
					decodeBuffer.flip();
					handler.bodyContent(decodeBuffer);
					decodeBuffer.clear();
					break;
				case FIRST_BOUNDARY:
				case BOUNDARY_OR_CONTENT:
					throw new MIMEParseException("EOF expecting boundary", locator);
				case BOUNDARY_ONLY:
					len = compositeBuffer.remaining();
					if (decodeBuffer == null || decodeBuffer.capacity() < len) {
						decodeBuffer = ByteBuffer.allocate(len);
					}
					// Copy bytes from CompositeByteBuffer to decodeBuffer
					byte[] unexpectedBytes = new byte[len];
					compositeBuffer.get(unexpectedBytes);
					decodeBuffer.clear();
					decodeBuffer.put(unexpectedBytes);
					decodeBuffer.flip();
					handler.unexpectedContent(decodeBuffer);
					decodeBuffer.clear();
					break;
			}
		}

		// Validate that all multipart boundaries are properly closed
		if (!boundaries.isEmpty()) {
			throw new MIMEParseException("Unclosed multipart boundary: " + boundaries.getLast(), locator);
		}

		handler.endEntity(null);
	}

	/**
	 * Resets the parser in preparation for parsing another entity.
	 */
	public void reset() {
		locator.reset();
		state = State.INIT;
		headerName = null;
		headerValueSink.reset();
		decodeBuffer = null;
		contentFlushed = false;
		compositeBuffer.compact(); // Clear any remaining data
		clearPendingBodyContent();  // Clear any pending body content
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
	 */
	protected BoundaryMatch detectBoundary(CompositeByteBuffer buffer) {
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
	 */
	protected BoundaryMatch checkBoundary(CompositeByteBuffer buffer, String boundary) {
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
		// Check what follows the boundary
		int remaining = end - pos;
		if (remaining == 0) {
			// Exact boundary match
			return new BoundaryMatch(boundary, false);
		} else if (remaining == 1) {
			// Single character - should be CR or LF
			byte c = buffer.get(pos);
			if (c == '\n' || (allowCRLineEnd && c == '\r')) {
				return new BoundaryMatch(boundary, false);
			} else {
				return null;
			}
		} else if (remaining == 2) {
			// Two characters - could be CRLF or end boundary "--"
			byte c1 = buffer.get(pos);
			byte c2 = buffer.get(pos + 1);
			if (c1 == '\r' && c2 == '\n') {
				return new BoundaryMatch(boundary, false);
			} else if (c1 == '-' && c2 == '-') {
				// End boundary
				return new BoundaryMatch(boundary, true);
			} else {
				return null;
			}
		} else if (remaining >= 2) {
			// Check for end boundary marker "--"
			if (buffer.get(pos) == '-' && buffer.get(pos + 1) == '-') {
				pos += 2;
				remaining -= 2;

				// Check trailing CR/LF after end marker
				if (remaining == 0) {
					return new BoundaryMatch(boundary, true);
				} else if (remaining == 1) {
					byte c = buffer.get(pos);
					if (c == '\n' || (allowCRLineEnd && c == '\r')) {
						return new BoundaryMatch(boundary, true);
					}
				} else if (remaining == 2) {
					if (buffer.get(pos) == '\r' && buffer.get(pos + 1) == '\n') {
						return new BoundaryMatch(boundary, true);
					}
				}
			}
			return null; // Invalid trailing characters
		}

		return null;
	}

}

