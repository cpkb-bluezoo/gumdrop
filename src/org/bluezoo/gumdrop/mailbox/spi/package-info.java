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
 * SPI for optional mailbox infrastructure (message indexer, filesystem
 * watcher) that starts and stops with the server.
 *
 * <p>{@link org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle}
 * implementations live in the optional {@code gumdrop-mailbox.jar},
 * discovered via {@link java.util.ServiceLoader}; core gumdrop has no
 * mailbox storage of its own, so POP3/IMAP need that jar present to
 * actually store or index mail.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mailbox
 */
package org.bluezoo.gumdrop.mailbox.spi;
