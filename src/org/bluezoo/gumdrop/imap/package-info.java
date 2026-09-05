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
 * IMAP4rev2 (RFC 9051) server for mailbox access.
 *
 * <p>{@link org.bluezoo.gumdrop.imap.IMAPService} is the abstract base
 * for IMAP application services; {@link
 * org.bluezoo.gumdrop.imap.IMAPListener} is the TCP transport listener;
 * {@link org.bluezoo.gumdrop.imap.IMAPProtocolHandler} handles the
 * protocol logic. Unlike SMTP and POP3's simpler sequential state
 * machines, IMAP's NOT_AUTHENTICATED/AUTHENTICATED/SELECTED/LOGOUT
 * states are each a dedicated state object (package {@link
 * org.bluezoo.gumdrop.imap.handler}), needed to handle IDLE, multiple
 * concurrent selected mailboxes, and unsolicited updates cleanly.
 *
 * <p>Extensions beyond the RFC 9051 core: IDLE (RFC 2177), NAMESPACE
 * (RFC 2342), MOVE (RFC 6851), QUOTA (RFC 9208), LITERAL- (RFC 7888,
 * non-synchronizing literals up to 4096 bytes), and CONDSTORE/QRESYNC
 * (RFC 7162) -- MODSEQ tracking via {@link
 * org.bluezoo.gumdrop.mailbox.Mailbox}, HIGHESTMODSEQ on
 * SELECT/EXAMINE/STATUS, MODSEQ in FETCH/SEARCH, UNCHANGEDSINCE for
 * STORE, and VANISHED in place of EXPUNGE once QRESYNC is enabled.
 *
 * <p>Transport security is implicit TLS (IMAPS, port 993) or STARTTLS,
 * with LOGINDISABLED advertised until TLS is established. Authentication
 * runs through {@link org.bluezoo.gumdrop.auth.Realm}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 - IMAP4rev2</a>
 * @see org.bluezoo.gumdrop.mailbox
 * @see org.bluezoo.gumdrop.auth
 */
package org.bluezoo.gumdrop.imap;
