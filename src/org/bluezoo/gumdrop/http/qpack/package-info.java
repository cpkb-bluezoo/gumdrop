/*
 * package-info.java
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

/**
 * QPACK header compression (RFC 9204), HTTP/3's replacement for HPACK
 * (RFC 7541), hand-written the same way {@code org.bluezoo.gumdrop.http.hpack}
 * was for HTTP/2.
 *
 * <p><b>Static table only -- no dynamic table.</b> RFC 9204 section 2.1
 * explicitly permits this: "An encoder that does not wish to use the
 * dynamic table can encode header fields without using the dynamic
 * table." Every field line this package encodes uses the static table
 * (RFC 9204 Appendix A) or, failing that, a literal name and literal
 * value (RFC 9204 section 4.5.6); every encoded field section prefix
 * therefore always has Required Insert Count 0 and Base 0. This
 * sidesteps the dynamic table's real complexity entirely: the
 * encoder/decoder instruction streams (RFC 9204 sections 4.3-4.4),
 * Required Insert Count/Base arithmetic (section 4.5.1), and blocking
 * decoders. It also means the QPACK encoder and decoder unidirectional
 * streams (RFC 9114 section 6.2.2-6.2.3) never need to be opened at
 * all (RFC 9204 section 4.2: "An endpoint MAY avoid creating an
 * encoder stream if it will not be used").
 *
 * <p>The consequence is a correctness-first QPACK that interoperates
 * with any compliant peer -- including real browsers -- but without
 * the dynamic table's compression benefit. When adding dynamic table
 * support, {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} must keep being
 * sent as 0 (or omitted) by the H3 layer until it lands, so a peer
 * never legally attempts a dynamic-table reference against this
 * decoder in the meantime.
 *
 * <p>The Huffman code (RFC 9204 Appendix A note; RFC 7541 Appendix B)
 * is identical to HPACK's, so this package reuses
 * {@link org.bluezoo.gumdrop.http.hpack.Huffman} directly rather than
 * duplicating the ~400-line code table.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204">RFC 9204</a>
 */
package org.bluezoo.gumdrop.http.qpack;
