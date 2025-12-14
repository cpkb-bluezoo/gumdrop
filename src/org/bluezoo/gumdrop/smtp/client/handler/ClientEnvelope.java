/*
 * ClientEnvelope.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * Operations available after MAIL FROM is accepted (no recipients yet).
 * 
 * <p>This interface is provided to the handler in
 * {@link ServerMailFromReplyHandler#handleMailFromOk} and represents the
 * envelope state before any recipients have been added.
 * 
 * <p>At this stage, the handler must add at least one recipient with
 * {@code rcptTo()} before being able to send message content. The
 * {@code data()} method is not available until a recipient is accepted
 * (see {@link ClientEnvelopeReady}).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerMailFromReplyHandler#handleMailFromOk
 * @see ClientEnvelopeReady
 */
public interface ClientEnvelope extends ClientEnvelopeState {

    // Inherits from ClientEnvelopeState:
    // - rcptTo(EmailAddress, ServerRcptToReplyHandler)
    // - rset(ServerRsetReplyHandler)
    // - quit()
    // - hasAcceptedRecipients() - always returns false at this stage

    // Note: data() is NOT available at this stage because no recipients
    // have been accepted yet. Use ClientEnvelopeReady after rcptTo succeeds.

}

