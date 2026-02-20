/*
 * DeliveryRequirements.java
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

package org.bluezoo.gumdrop.smtp;

import java.time.Instant;

/**
 * Represents delivery requirements and preferences for a message.
 * 
 * <p>This interface encapsulates all SMTP extensions that describe how the
 * sender wants the message to be delivered. Unlike message content options
 * (like SMTPUTF8 which affects parsing), these options affect routing and
 * delivery behaviour.
 * 
 * <p>Supported extensions:
 * <ul>
 *   <li><b>REQUIRETLS</b> (RFC 8689) - Message must only traverse TLS connections</li>
 *   <li><b>MT-PRIORITY</b> (RFC 6710) - Message transfer priority level</li>
 *   <li><b>FUTURERELEASE</b> (RFC 4865) - Hold message for later delivery</li>
 *   <li><b>DELIVERBY</b> (RFC 2852) - Delivery deadline with failure action</li>
 *   <li><b>DSN</b> (RFC 3461) - Delivery Status Notification preferences (RET, ENVID)</li>
 * </ul>
 * 
 * <p>Note: Per-recipient DSN options (NOTIFY, ORCPT) are tracked separately
 * via {@link DSNRecipientParameters}.
 * 
 * <p>Handlers must respect these delivery requirements when relaying messages.
 * Failure to honour REQUIRETLS or DELIVERBY constraints should result in
 * bouncing the message rather than violating the sender's requirements.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.handler.MailFromHandler#mailFrom
 */
public interface DeliveryRequirements {

    /**
     * Returns whether REQUIRETLS was specified for this message.
     * 
     * <p>When true, the sender has indicated that this message must only be
     * transmitted over TLS-protected connections (RFC 8689). If the server
     * cannot guarantee TLS protection to the next hop during relay, the
     * message must be bounced rather than delivered insecurely.
     * 
     * @return true if REQUIRETLS was specified in MAIL FROM
     */
    boolean isRequireTls();

    /**
     * Returns the message transfer priority level.
     * 
     * <p>MT-PRIORITY (RFC 6710) allows senders to indicate the relative
     * priority of a message. Values range from -9 (lowest) to +9 (highest),
     * with 0 being normal priority.
     * 
     * <p>MTAs may use this to prioritize message processing and queue
     * management. Higher priority messages should be processed before
     * lower priority ones when resources are constrained.
     * 
     * @return priority level from -9 to +9, or null if not specified
     */
    Integer getPriority();

    /**
     * Returns whether a priority level was specified.
     * 
     * @return true if MT-PRIORITY was specified in MAIL FROM
     */
    default boolean hasPriority() {
        return getPriority() != null;
    }

    /**
     * Returns the time until which the message should be held.
     * 
     * <p>FUTURERELEASE (RFC 4865) allows senders to request that a message
     * be held for later delivery. This returns the earliest time at which
     * the message should be released for delivery.
     * 
     * <p>If both HOLDFOR and HOLDUNTIL were somehow specified, this method
     * returns the computed release time.
     * 
     * @return the release time, or null if the message should be delivered immediately
     */
    Instant getReleaseTime();

    /**
     * Returns whether the message should be held for future release.
     * 
     * @return true if HOLDFOR or HOLDUNTIL was specified in MAIL FROM
     */
    default boolean isFutureRelease() {
        return getReleaseTime() != null;
    }

    /**
     * Returns the delivery deadline for this message.
     * 
     * <p>DELIVERBY (RFC 2852) allows senders to specify a time limit for
     * message delivery. If the message cannot be delivered within this
     * time, action should be taken based on {@link #isDeliverByReturn()}.
     * 
     * @return the delivery deadline, or null if no deadline was specified
     */
    Instant getDeliverByDeadline();

    /**
     * Returns whether the message should be returned if the deadline is missed.
     * 
     * <p>When DELIVERBY is specified with the 'R' trace modifier, the message
     * should be returned (bounced) to the sender if it cannot be delivered
     * by the deadline. When 'N' is specified, a delay DSN should be sent
     * instead.
     * 
     * @return true for 'R' (return), false for 'N' (notify), or null if
     *         no DELIVERBY was specified
     */
    Boolean isDeliverByReturn();

    /**
     * Returns whether a delivery deadline was specified.
     * 
     * @return true if DELIVERBY was specified in MAIL FROM
     */
    default boolean hasDeliverByDeadline() {
        return getDeliverByDeadline() != null;
    }

    /**
     * Returns the DSN return type preference.
     * 
     * <p>DSN (RFC 3461) RET parameter indicates how much of the original
     * message to include in any Delivery Status Notification:
     * <ul>
     *   <li>{@link DSNReturn#FULL} - Include the entire message</li>
     *   <li>{@link DSNReturn#HDRS} - Include only the headers</li>
     * </ul>
     * 
     * @return the DSN return type, or null if not specified
     */
    DSNReturn getDsnReturn();

    /**
     * Returns the DSN envelope identifier.
     * 
     * <p>DSN (RFC 3461) ENVID parameter provides an opaque identifier
     * that will be included in any Delivery Status Notification, allowing
     * the original sender to correlate the DSN with the original message.
     * 
     * @return the envelope ID, or null if not specified
     */
    String getDsnEnvelopeId();

    /**
     * Returns whether DSN parameters were specified.
     * 
     * @return true if RET or ENVID was specified in MAIL FROM
     */
    default boolean hasDsnParameters() {
        return getDsnReturn() != null || getDsnEnvelopeId() != null;
    }

    /**
     * Returns whether this is an empty delivery request with no special requirements.
     * 
     * @return true if no delivery options were specified
     */
    default boolean isEmpty() {
        return !isRequireTls() && !hasPriority() && !isFutureRelease() && 
               !hasDeliverByDeadline() && !hasDsnParameters();
    }
}

