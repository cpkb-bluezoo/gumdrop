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
 * <p>Two encoder/decoder pairs are provided:
 *
 * <ul>
 * <li>{@link SimpleEncoder}/{@link SimpleDecoder} -- a dependency-free,
 * stateless building block using the static table only (RFC 9204
 * Appendix A) or, failing that, a literal name and literal value (RFC
 * 9204 section 4.5.6); every field section it produces therefore always
 * has Required Insert Count 0 and Base 0, and it needs no encoder/
 * decoder instruction streams at all (RFC 9204 section 4.2 permits
 * this: "An endpoint MAY avoid creating an encoder stream if it will
 * not be used").
 * <li>{@link Encoder}/{@link Decoder} -- the full RFC 9204 codec, backed
 * by a real {@link DynamicTable} and the encoder-stream/decoder-stream
 * instruction codecs ({@link EncoderStreamParser}/{@link EncoderStreamWriter},
 * {@link DecoderStreamParser}/{@link DecoderStreamWriter}). {@code Encoder}
 * operates in strictly non-blocking mode (Base = Required Insert Count =
 * Known Received Count), so a peer using {@code Decoder} never blocks
 * decoding gumdrop's own field sections; {@code Decoder} itself accepts
 * any compliant peer's dynamic-table and post-Base references.
 * </ul>
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
