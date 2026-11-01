/*
 * MimeHandler.java
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

package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;

/**
 * Receive notification of the logical content of a MIME entity.
 * This is the main interface that a MIME processor implements: it will
 * register an implementation of this interface with the parser to be
 * notified of parsing events.
 * <p>
 * A MIME entity consists of headers followed by a body. The body may
 * contain nested entities if this is a multipart content type. The headers
 * for each entity always precede the body content.
 * </p>
 * <p>
 * The structured MIME headers (Content-Type, Content-Disposition,
 * Content-Transfer-Encoding, Content-ID, Content-Description, MIME-Version)
 * are reported via dedicated callback methods. The parser handles these
 * headers and the base MimeHandler interface ignores any other headers.
 * </p>
 * <p>
 * Body content will be reported after an invocation of endHeaders(). The
 * content may be expected or unexpected. Normal body content in a
 * well-formed entity will be reported via the bodyContent() method, whereas
 * content that occurs outside the correct MIME boundaries will be reported
 * via unexpectedContent(), for analysis purposes.
 * </p>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045'>RFC 2045</a>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2046'>RFC 2046</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // uses value classes that are deprecated for removal
public interface MimeHandler {

	/**
	 * How the value of a header the parser does not itself understand is
	 * delivered; see {@link #headerDelivery(String)}.
	 */
	enum HeaderDelivery {

		/** As decoded text in {@link MimeHandler#headerContent(String)} pieces. */
		UNSTRUCTURED,

		/**
		 * As the bytes that arrived, in
		 * {@link MimeHandler#rawHeaderContent(ByteBuffer)} pieces, for a
		 * layer that parses the value itself.
		 */
		RAW;

	}

	/**
	 * Asked when an entity starts, before any of its headers, whether the
	 * handler wants the events that carry a value built from the whole of a
	 * header (those that predate the streaming events and are deprecated in
	 * their favour). A handler that works from the streaming events answers
	 * false, and then the parser holds no header whole on its behalf.
	 * @return true (the default) if the older events are wanted
	 */
	default boolean wantsLegacyEvents() {
		return true;
	}

	/**
	 * Asked once for each header the parser does not itself understand
	 * (every header but Content-Type, Content-Disposition,
	 * Content-Transfer-Encoding, Content-ID and MIME-Version), just before
	 * {@link #startHeader(String)}. The answer says how its value arrives.
	 * A layer on top of MIME (RFC 5322 is one) that parses some headers
	 * itself asks for them {@link HeaderDelivery#RAW}.
	 * @param name the header name, as written
	 * @return how the value is to be delivered
	 */
	default HeaderDelivery headerDelivery(String name) {
		return HeaderDelivery.UNSTRUCTURED;
	}

	/**
	 * Receive notification of the start of a header the parser does not
	 * itself understand. Its value follows as {@link #headerContent(String)}
	 * calls, or {@link #rawHeaderContent(ByteBuffer)} calls if
	 * {@link #headerDelivery(String)} asked for it raw, and then
	 * {@link #endHeader()}. The parser holds none of the value, so a header
	 * of any size passes through.
	 * @param name the header name, as written
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void startHeader(String name) throws MimeParseException {
	}

	/**
	 * Receive a piece of the value of an unstructured header, in order:
	 * unfolded, with leading and trailing white space removed, RFC 2047
	 * encoded words decoded, and raw 8-bit text read as UTF-8 where it is
	 * valid and as windows-1252 where it is not. A piece has no particular
	 * size.
	 * @param text the decoded text
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void headerContent(String text) throws MimeParseException {
	}

	/**
	 * Receive a piece of the value of a header delivered raw: the bytes as
	 * they arrived, unfolded (a folded line ending is removed and the white
	 * space after it kept), with nothing decoded.
	 * @param bytes the bytes, valid only for the duration of the call
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void rawHeaderContent(ByteBuffer bytes) throws MimeParseException {
	}

	/**
	 * Receive notification of a Content-Type header: its type and subtype.
	 * Its parameters follow as {@link #startParameter(String, String)},
	 * {@link #parameterContent(String)} and {@link #endParameter()}, and then
	 * {@link #endContentType()}. A value that is not {@code type/subtype} is
	 * not reported here but through {@link #malformedHeader(String, String)}.
	 * @param primary the primary type, as written
	 * @param sub the subtype, as written
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void startContentType(String primary, String sub) throws MimeParseException {
	}

	/**
	 * The Content-Type header started by {@link #startContentType(String, String)}
	 * is complete.
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void endContentType() throws MimeParseException {
	}

	/**
	 * Receive notification of a Content-Disposition header: its type
	 * ({@code attachment}, {@code form-data}, ...). Its parameters follow as
	 * for {@link #startContentType(String, String)}, and then
	 * {@link #endContentDisposition()}.
	 * @param kind the disposition type, as written
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void startContentDisposition(String kind) throws MimeParseException {
	}

	/**
	 * The Content-Disposition header started by
	 * {@link #startContentDisposition(String)} is complete.
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void endContentDisposition() throws MimeParseException {
	}

	/**
	 * Receive notification of a parameter of the Content-Type or
	 * Content-Disposition being read. Its value follows as
	 * {@link #parameterContent(String)} pieces and then
	 * {@link #endParameter()}.
	 *
	 * <p>The language is non-null for an RFC 2231 extended value
	 * ({@code name*=...}), where it is the language tag, possibly empty. RFC
	 * 2231 continuations ({@code name*0=...; name*1=...}) are joined: they are
	 * one parameter, named without the {@code *0}. Segments have to arrive in
	 * order from 0; one that does not is dropped.
	 *
	 * <p>The same name can appear more than once, and a plain
	 * {@code filename} and an extended {@code filename*} often do: which to
	 * prefer is up to the handler. (The usual rule is that the first wins,
	 * except that an extended value replaces a plain one.)
	 * @param name the parameter name, as written
	 * @param language the language of an extended value, or null
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void startParameter(String name, String language) throws MimeParseException {
	}

	/**
	 * Receive a piece of a parameter's value, decoded: quoted-pairs
	 * resolved, percent-encoding and RFC 2047 encoded words decoded, and
	 * leading and trailing white space removed. A piece has no particular
	 * size.
	 * @param text the decoded text
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void parameterContent(String text) throws MimeParseException {
	}

	/**
	 * The parameter started by {@link #startParameter(String, String)} is
	 * complete.
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void endParameter() throws MimeParseException {
	}

	/**
	 * The value of a header that the parser would have parsed
	 * (Content-Type, Content-Disposition) is not valid. It stands in for
	 * {@link #startHeader(String)}: the value follows as unstructured text,
	 * through {@link #headerContent(String)} and then {@link #endHeader()},
	 * as if it were any other header.
	 * @param name the header name, as written
	 * @param reason what is wrong with the value
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void malformedHeader(String name, String reason) throws MimeParseException {
	}

	/**
	 * The value of the header started by {@link #startHeader(String)} is
	 * complete.
	 * @exception MimeParseException if the processor wishes to cancel the parse
	 */
	default void endHeader() throws MimeParseException {
	}

	/**
	 * Receive an object for the location of MIME parsing events.
	 * This method will be called once prior to any other events.
	 * The handler can examine the locator when it receives any event and be
	 * informed of the end position of the event.
	 * @param locator the object for event parsing location information
	 */
	void setLocator(MimeLocator locator);

	/**
	 * RFC 2046 §5.1 — receive notification of the beginning of a MIME entity.
	 * For the root entity, the boundary parameter will be null.
	 * For nested entities in a multipart body, the boundary parameter will
	 * be the MIME boundary string that started this entity.
	 * @param boundary the MIME boundary that started this entity, or null
	 * for the root entity
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void startEntity(String boundary) throws MimeParseException;

	/**
	 * RFC 2045 §5 — receive notification of a Content-Type header.
	 * @param contentType the structured content type value
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 * @deprecated to be removed in the next major version with
	 * {@link ContentType}. It holds the whole header; use
	 * {@link #startContentType(String, String)} and the parameter events.
	 * It is delivered only if {@link #wantsLegacyEvents()} is true.
	 */
	@Deprecated(since = "3.2", forRemoval = true)
	default void contentType(ContentType contentType) throws MimeParseException {
	}

	/**
	 * RFC 2183 — receive notification of a Content-Disposition header.
	 * @param contentDisposition the structured content disposition value
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 * @deprecated to be removed in the next major version with
	 * {@link ContentDisposition}. It holds the whole header; use
	 * {@link #startContentDisposition(String)} and the parameter events.
	 * It is delivered only if {@link #wantsLegacyEvents()} is true.
	 */
	@Deprecated(since = "3.2", forRemoval = true)
	default void contentDisposition(ContentDisposition contentDisposition) throws MimeParseException {
	}

	/**
	 * RFC 2045 §6.1 — receive notification of a Content-Transfer-Encoding header.
	 * The argument to this method will be a token, either one of the 5
	 * predefined ones (base64, quoted-printable, 8bit, 7bit, binary),
	 * or a string token beginning with "x-" i.e. an x-token.
	 * All tokens should be treated as case-insensitive although they will
	 * be provided here verbatim as they occur in the entity.
	 * @param encoding the content transfer encoding value
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentTransferEncoding(String encoding) throws MimeParseException;

	/**
	 * RFC 2045 §7 — receive notification of a Content-ID header.
	 * @param contentID the structured content-id value
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 * @deprecated to be removed in the next major version with
	 * {@link ContentID}; use {@link #contentID(String, String)}.
	 * It is delivered only if {@link #wantsLegacyEvents()} is true.
	 */
	@Deprecated(since = "3.2", forRemoval = true)
	default void contentID(ContentID contentID) throws MimeParseException {
	}

	/**
	 * RFC 2045 §7 — receive notification of a Content-ID header.
	 * @param localPart the part before the at sign
	 * @param domain the part after it
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	default void contentID(String localPart, String domain) throws MimeParseException {
	}

	/**
	 * RFC 2045 §8 — receive notification of a Content-Description header.
	 * The description will have any RFC 2047 encoded words decoded.
	 * @param description the content description text
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentDescription(String description) throws MimeParseException;

	/**
	 * RFC 2045 §4 — receive notification of a MIME-Version header.
	 * @param version the MIME-Version value
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 * @deprecated to be removed in the next major version with
	 * {@link MimeVersion}; use {@link #mimeVersion(int, int)}.
	 * It is delivered only if {@link #wantsLegacyEvents()} is true.
	 */
	@Deprecated(since = "3.2", forRemoval = true)
	default void mimeVersion(MimeVersion version) throws MimeParseException {
	}

	/**
	 * RFC 2045 §4 — receive notification of a MIME-Version header.
	 * @param major the major version
	 * @param minor the minor version
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	default void mimeVersion(int major, int minor) throws MimeParseException {
	}

	/**
	 * Receive notification that all the headers in an entity have been
	 * parsed. There will be no more header events issued for this entity,
	 * although if there are nested entities in a multipart content then
	 * they will have their own header sections.
	 * The handler implementation can now expect one of:
	 * <ul>
	 * <li>bodyContent: for raw body content data for a non-multipart entity</li>
	 * <li>startEntity: for a new nested entity if this is a multipart entity</li>
	 * <li>endEntity: to terminate the current entity</li>
	 * </ul>
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void endHeaders() throws MimeParseException;

	/**
	 * Receive notification of the byte content of an entity.
	 * This method will be called only if the entity is not a multipart
	 * content type. Multiple invocations of this method may occur before
	 * the end of the entity.
	 * The content has been decoded according to the Content-Transfer-Encoding.
	 * Appropriate action must still be taken to convert it to a suitable
	 * representation, such as using the charset parameter in a text/plain
	 * entity to convert byte data to character data.
	 * @param data a buffer containing the body content data, ready for reading
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void bodyContent(ByteBuffer data) throws MimeParseException;

	/**
	 * RFC 2046 §5.1.1 — receive notification of unexpected body content
	 * (preamble or epilogue).
	 * <ul>
	 * <li>at the start of a multipart entity where the first boundary would
	 * be expected (preamble), or</li>
	 * <li>after the end boundary of a multipart entity (epilogue)</li>
	 * </ul>
	 * @param data a buffer containing the body content data, ready for reading
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void unexpectedContent(ByteBuffer data) throws MimeParseException;

	/**
	 * RFC 2046 §5.1 — receive notification that a MIME entity has been
	 * completely parsed. For the root entity, the boundary parameter will be null.
	 * For nested entities in a multipart body, the boundary parameter will
	 * be the MIME boundary string that ended this entity.
	 * @param boundary the MIME boundary that ended this entity, or null
	 * for the root entity
	 * @exception MimeParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void endEntity(String boundary) throws MimeParseException;

}

