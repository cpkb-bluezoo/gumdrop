/*
 * package-info.java
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

/**
 * ASN.1 BER (ITU-T X.690) codec for LDAP's wire encoding.
 *
 * <p>{@link org.bluezoo.gumdrop.ldap.asn1.BERDecoder} is a streaming
 * decoder built for non-blocking I/O: it accepts partial reads and
 * returns null from {@code next()} until a complete element is
 * available, retaining partial data across calls to {@code receive()}.
 * {@link org.bluezoo.gumdrop.ldap.asn1.BEREncoder} is the corresponding
 * encoder. {@link org.bluezoo.gumdrop.ldap.asn1.ASN1Element} is the
 * decoded TLV (tag-length-value) representation; {@link
 * org.bluezoo.gumdrop.ldap.asn1.ASN1Type} holds the universal type tags
 * BER uses, alongside the context-specific tags RFC 4511 defines for
 * each LDAP operation (BindRequest 0x60, SearchRequest 0x63, and so on).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ldap.client
 * @see <a href="https://www.itu.int/rec/T-REC-X.690">ITU-T X.690</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511 - LDAP Protocol</a>
 */
package org.bluezoo.gumdrop.ldap.asn1;
