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
 * QUIC connection ID issuance and retirement (RFC 9000 section 5.1,
 * section 10.3, sections 19.15-19.16).
 *
 * <p>{@link org.bluezoo.gumdrop.quic.cid.ConnectionIdManager} is a
 * self-contained, transport/frame-agnostic state machine: it never reads
 * or writes wire bytes itself. The owning connection drains its pending
 * output ({@link org.bluezoo.gumdrop.quic.cid.ConnectionIdManager#drainPendingIssuance}/
 * {@link org.bluezoo.gumdrop.quic.cid.ConnectionIdManager#drainPendingRetirement})
 * and writes the corresponding frames via
 * {@code org.bluezoo.gumdrop.quic.frame.QuicFrameWriter}, and feeds
 * received {@code NEW_CONNECTION_ID}/{@code RETIRE_CONNECTION_ID} frames
 * back in.
 *
 * <p>Connection migration and path validation (RFC 9000 sections 9,
 * 19.17-19.18) are not implemented -- {@link org.bluezoo.gumdrop.quic.cid.ConnectionIdManager}
 * always treats the most recently issued peer connection ID as active.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.1">RFC 9000 section 5.1</a>
 */
package org.bluezoo.gumdrop.quic.cid;
