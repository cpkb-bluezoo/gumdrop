/*
 * FetchState.java
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

package org.bluezoo.gumdrop.imap.handler;

/**
 * Operations available when responding to a FETCH command.
 * 
 * <p>This interface is provided to {@link SelectedHandler#fetch} and
 * allows the handler to proceed with the fetch or deny it.
 * 
 * <p>Typically the handler will just call {@link #proceed} to let the
 * server perform the fetch using the Mailbox API. The handler can
 * use this callback for logging, auditing, or access control.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SelectedHandler#fetch
 */
public interface FetchState {

    /**
     * Proceeds with the fetch operation.
     * 
     * <p>The server will fetch the requested data items from the
     * mailbox and send FETCH responses to the client.
     * 
     * @param handler continues receiving commands
     */
    void proceed(SelectedHandler handler);

    /**
     * Denies the fetch operation.
     * 
     * <p>Sends a NO response to the client.
     * 
     * @param message the error message
     * @param handler continues receiving commands
     */
    void deny(String message, SelectedHandler handler);

    /**
     * Server is shutting down.
     */
    void serverShuttingDown();

}

