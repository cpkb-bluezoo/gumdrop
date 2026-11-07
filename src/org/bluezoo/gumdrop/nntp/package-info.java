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
 * NNTP (RFC 3977) news reader server.
 *
 * <p>{@link org.bluezoo.gumdrop.nntp.NntpListener} accepts connections on
 * the NNTP port (119, with STARTTLS) or the NNTPS port (563, implicit TLS)
 * and serves each with an {@link org.bluezoo.gumdrop.nntp.NntpProtocolHandler},
 * which reads commands with a streaming lexer and streams articles back in
 * constant memory. Newsgroups and articles come from an application
 * {@link org.bluezoo.gumdrop.nntp.server.ReaderHandler}, by default one that
 * serves the mailboxes of a {@link org.bluezoo.gumdrop.mailbox.MailboxStore}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.nntp.server.NntpServer
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3977">RFC 3977 - NNTP</a>
 */
package org.bluezoo.gumdrop.nntp;
