/*
 * ResetState.java
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

package org.bluezoo.gumdrop.pop3.handler;

/**
 * Operations available when responding to a reset request.
 * 
 * <p>This interface is provided to {@link TransactionHandler#reset} and
 * allows the handler to confirm that all deleted messages have been undeleted.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#reset
 */
public interface ResetState {

    /**
     * Confirms all messages have been undeleted.
     * 
     * <p>Sends a +OK response with the current mailbox statistics.
     * 
     * @param messageCount number of messages after reset
     * @param totalSize total size after reset
     * @param handler continues receiving transaction commands
     */
    void resetComplete(int messageCount, long totalSize, TransactionHandler handler);

    /**
     * Reports an error during reset.
     * 
     * @param message the error message
     * @param handler continues receiving transaction commands
     */
    void error(String message, TransactionHandler handler);

}

