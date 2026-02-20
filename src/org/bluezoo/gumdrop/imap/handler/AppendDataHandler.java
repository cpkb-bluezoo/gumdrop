/*
 * AppendDataHandler.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;

import java.nio.ByteBuffer;

/**
 * Handler for receiving APPEND message data.
 * 
 * <p>This handler receives the message literal for an APPEND command.
 * Data is delivered in chunks as it arrives from the client.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AppendState#acceptLiteral
 */
public interface AppendDataHandler {

    /**
     * Called when message data is received.
     * 
     * <p>This may be called multiple times as data arrives. The handler
     * should accumulate or stream the data to storage.
     * 
     * @param mailbox the target mailbox for the message
     * @param data the received data chunk
     */
    void appendData(Mailbox mailbox, ByteBuffer data);

    /**
     * Called when all message data has been received.
     * 
     * <p>The handler should finalize the message in the mailbox.
     * 
     * @param state operations for completing the APPEND
     * @param mailbox the target mailbox for the message
     */
    void appendComplete(AppendCompleteState state, Mailbox mailbox);

}
