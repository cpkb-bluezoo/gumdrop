/*
 * MIMEHandler.java
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
 * headers and the base MIMEHandler interface ignores any other headers.
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
public interface MIMEHandler {

	/**
	 * Receive an object for the location of MIME parsing events.
	 * This method will be called once prior to any other events.
	 * The handler can examine the locator when it receives any event and be
	 * informed of the end position of the event.
	 * @param locator the object for event parsing location information
	 */
	void setLocator(MIMELocator locator);

	/**
	 * Receive notification of the beginning of a MIME entity.
	 * For the root entity, the boundary parameter will be null.
	 * For nested entities in a multipart body, the boundary parameter will
	 * be the MIME boundary string that started this entity.
	 * @param boundary the MIME boundary that started this entity, or null
	 * for the root entity
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void startEntity(String boundary) throws MIMEParseException;

	/**
	 * Receive notification of a Content-Type header in the current entity.
	 * @param contentType the structured content type value
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentType(ContentType contentType) throws MIMEParseException;

	/**
	 * Receive notification of a Content-Disposition header in the current entity.
	 * @param contentDisposition the structured content disposition value
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException;

	/**
	 * Receive notification of a Content-Transfer-Encoding header in the current entity.
	 * The argument to this method will be a token, either one of the 5
	 * predefined ones (base64, quoted-printable, 8bit, 7bit, binary),
	 * or a string token beginning with "x-" i.e. an x-token.
	 * All tokens should be treated as case-insensitive although they will
	 * be provided here verbatim as they occur in the entity.
	 * @param encoding the content transfer encoding value
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentTransferEncoding(String encoding) throws MIMEParseException;

	/**
	 * Receive notification of a Content-ID header in the current entity.
	 * @param contentID the structured content-id value
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentID(ContentID contentID) throws MIMEParseException;

	/**
	 * Receive notification of a Content-Description header in the current entity.
	 * The description will have any RFC 2047 encoded words decoded.
	 * @param description the content description text
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void contentDescription(String description) throws MIMEParseException;

	/**
	 * Receive notification of a MIME-Version header in the current entity.
	 * @param version the MIME-Version value
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void mimeVersion(MIMEVersion version) throws MIMEParseException;

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
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void endHeaders() throws MIMEParseException;

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
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void bodyContent(ByteBuffer data) throws MIMEParseException;

	/**
	 * Receive notification of unexpected body content.
	 * This is content that is:
	 * <ul>
	 * <li>at the start of a multipart entity where the first boundary would
	 * be expected (preamble), or</li>
	 * <li>after the end boundary of a multipart entity (epilogue)</li>
	 * </ul>
	 * @param data a buffer containing the body content data, ready for reading
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void unexpectedContent(ByteBuffer data) throws MIMEParseException;

	/**
	 * Receive notification that a MIME entity has been completely parsed.
	 * For the root entity, the boundary parameter will be null.
	 * For nested entities in a multipart body, the boundary parameter will
	 * be the MIME boundary string that ended this entity.
	 * @param boundary the MIME boundary that ended this entity, or null
	 * for the root entity
	 * @exception MIMEParseException if the processor encountered a fatal
	 * condition during parsing and wishes to cancel the parse process
	 */
	void endEntity(String boundary) throws MIMEParseException;

}

