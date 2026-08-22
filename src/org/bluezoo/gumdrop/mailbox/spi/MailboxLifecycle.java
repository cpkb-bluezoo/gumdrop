/*
 * MailboxLifecycle.java
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

package org.bluezoo.gumdrop.mailbox.spi;

import java.io.IOException;

/**
 * SPI for optional mailbox infrastructure (indexer, filesystem watcher).
 *
 * <p>Implementations live in {@code gumdrop-mailbox.jar} and are discovered via
 * {@link java.util.ServiceLoader}. Mail protocols work only when that jar is
 * present; the servlet container core does not include mailbox storage.
 */
public interface MailboxLifecycle {

    /**
     * Starts shared mailbox background services (called from {@code Gumdrop.start()}).
     */
    void onServerStart() throws IOException;

    /**
     * Stops shared mailbox background services (called from {@code Gumdrop.shutdown()}).
     */
    void onServerStop();

}
