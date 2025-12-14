/*
 * MailFromHandler.java
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

package org.bluezoo.gumdrop.smtp.handler;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.SMTPPipeline;

/**
 * Handler for MAIL FROM commands.
 * 
 * <p>This is the "ready" state where the server can receive new mail
 * transactions. It is reached after successful HELO/EHLO or after
 * completing a previous message transaction.
 * 
 * <p>The handler provides a pipeline via {@link #getPipeline()} that
 * receives notifications as the transaction progresses. This allows
 * authentication checks (SPF, DKIM, DMARC) and content processing
 * to run alongside handler decisions.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MailFromState
 * @see SMTPPipeline
 */
public interface MailFromHandler {

    /**
     * Called before each new mail transaction to get the processing pipeline.
     * 
     * <p>The returned pipeline (if non-null) receives notifications as the
     * transaction progresses:
     * <ul>
     *   <li>{@link SMTPPipeline#mailFrom} when MAIL FROM is accepted</li>
     *   <li>{@link SMTPPipeline#rcptTo} when each RCPT TO is accepted</li>
     *   <li>{@link SMTPPipeline#getMessageChannel} at message transfer start</li>
     *   <li>{@link SMTPPipeline#endData} when message transfer completes</li>
     *   <li>{@link SMTPPipeline#reset} on RSET or transaction end</li>
     * </ul>
     * 
     * <p>This is typically used to create an {@link org.bluezoo.gumdrop.smtp.auth.AuthPipeline}
     * for SPF/DKIM/DMARC validation. Return null if no pipeline processing is needed.
     * 
     * @return the pipeline for this transaction, or null
     */
    SMTPPipeline getPipeline();

    /**
     * Called when client sends MAIL FROM.
     * 
     * <p>The handler should evaluate the sender and delivery requirements,
     * then call the appropriate method on the state interface to accept
     * or reject.
     * 
     * @param sender the sender address, or null for bounce messages (&lt;&gt;)
     * @param smtputf8 true if SMTPUTF8 extension requested (RFC 6531)
     * @param deliveryRequirements delivery options (REQUIRETLS, DSN, priority, etc.)
     * @param state operations for responding
     */
    void mailFrom(EmailAddress sender, boolean smtputf8,
                  DeliveryRequirements deliveryRequirements,
                  MailFromState state);

    /**
     * Called when client sends RSET.
     * 
     * @param state operations for responding
     */
    void reset(ResetState state);

    /**
     * Called when client sends QUIT.
     */
    void quit();

}

