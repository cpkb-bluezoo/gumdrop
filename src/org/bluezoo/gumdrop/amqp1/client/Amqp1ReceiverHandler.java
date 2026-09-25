/*
 * Amqp1ReceiverHandler.java
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

package org.bluezoo.gumdrop.amqp1.client;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.MessageHandler;

/**
 * Receives the events of one receiving link and the messages delivered
 * on it.
 *
 * <p>A delivery arrives as {@link #startDelivery}, then the message
 * sections as {@link MessageHandler} events, streamed as the transfer
 * frames arrive (a large {@code data} body reaches
 * {@link MessageHandler#dataChunk} in pieces and is never assembled), and
 * finally {@link MessageHandler#endMessage()}. Dispose of the delivery
 * with {@link Amqp1IncomingDelivery#accept()} and friends once the
 * message has been processed.
 *
 * <p>An {@code amqp-value} or {@code amqp-sequence} body is decoded whole
 * and is limited to {@link org.bluezoo.gumdrop.amqp1.codec.MessageParser#DEFAULT_MAX_SECTION_SIZE}
 * (1 MiB); a message exceeding it is reported with
 * {@link MessageHandler#messageError}. Large payloads should be sent as
 * {@code data} sections, which are streamed without any such limit.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1ReceiverHandler extends MessageHandler {

    /**
     * The broker accepted the link. Grant credit with
     * {@link Amqp1Receiver#addCredit} to receive messages.
     *
     * @param receiver the attached link
     * @param peerAttach the broker's {@code attach}, showing the source
     *      it resolved and its settle modes
     */
    void handleAttached(Amqp1Receiver receiver, Attach peerAttach);

    /**
     * A delivery began. Message events for it follow.
     *
     * @param delivery the delivery, to be disposed of when processed
     */
    void startDelivery(Amqp1IncomingDelivery delivery);

    /**
     * The sender abandoned a delivery part way through. Discard anything
     * received of it; no {@link MessageHandler#endMessage()} follows.
     *
     * @param delivery the aborted delivery
     */
    void handleAborted(Amqp1IncomingDelivery delivery);

    /**
     * The link is detached: the broker detached it, acknowledged a detach
     * requested with {@link Amqp1Receiver#detach}, or the session or
     * connection ended.
     *
     * @param error the error reported, or {@code null} for a normal detach
     * @param closed whether the link was closed (destroyed) rather than
     *      merely detached. A session or connection ending under the
     *      link is reported as {@code false}: the link can be attached
     *      again on a new session
     */
    void handleDetached(Amqp1Error error, boolean closed);
}
