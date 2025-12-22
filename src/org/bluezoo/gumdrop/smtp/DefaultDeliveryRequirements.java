/*
 * DefaultDeliveryRequirements.java
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
 * Default implementation of {@link DeliveryRequirements}.
 * 
 * <p>This class is used internally by SMTPConnection to collect delivery
 * parameters from the MAIL FROM command and pass them to the handler.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DefaultDeliveryRequirements implements DeliveryRequirements {

    /** Singleton empty requirements for messages with no delivery options. */
    static final DeliveryRequirements EMPTY = new DefaultDeliveryRequirements();

    private boolean requireTls;
    private Integer priority;
    private Instant releaseTime;
    private Instant deliverByDeadline;
    private Boolean deliverByReturn;
    private DSNReturn dsnReturn;
    private String dsnEnvelopeId;

    /**
     * Creates an empty delivery requirements with default values.
     */
    DefaultDeliveryRequirements() {
    }

    @Override
    public boolean isRequireTls() {
        return requireTls;
    }

    /**
     * Sets the REQUIRETLS flag.
     * 
     * @param requireTls true if TLS is required
     */
    void setRequireTls(boolean requireTls) {
        this.requireTls = requireTls;
    }

    @Override
    public Integer getPriority() {
        return priority;
    }

    /**
     * Sets the MT-PRIORITY value.
     * 
     * @param priority priority level from -9 to +9
     */
    void setPriority(Integer priority) {
        this.priority = priority;
    }

    @Override
    public Instant getReleaseTime() {
        return releaseTime;
    }

    /**
     * Sets the FUTURERELEASE time.
     * 
     * @param releaseTime the time to release the message
     */
    void setReleaseTime(Instant releaseTime) {
        this.releaseTime = releaseTime;
    }

    @Override
    public Instant getDeliverByDeadline() {
        return deliverByDeadline;
    }

    /**
     * Sets the DELIVERBY deadline.
     * 
     * @param deadline the delivery deadline
     */
    void setDeliverByDeadline(Instant deadline) {
        this.deliverByDeadline = deadline;
    }

    @Override
    public Boolean isDeliverByReturn() {
        return deliverByReturn;
    }

    /**
     * Sets the DELIVERBY return/notify flag.
     * 
     * @param deliverByReturn true for return, false for notify
     */
    void setDeliverByReturn(Boolean deliverByReturn) {
        this.deliverByReturn = deliverByReturn;
    }

    @Override
    public DSNReturn getDsnReturn() {
        return dsnReturn;
    }

    /**
     * Sets the DSN return type.
     * 
     * @param dsnReturn the return type (FULL or HDRS)
     */
    void setDsnReturn(DSNReturn dsnReturn) {
        this.dsnReturn = dsnReturn;
    }

    @Override
    public String getDsnEnvelopeId() {
        return dsnEnvelopeId;
    }

    /**
     * Sets the DSN envelope ID.
     * 
     * @param dsnEnvelopeId the envelope identifier
     */
    void setDsnEnvelopeId(String dsnEnvelopeId) {
        this.dsnEnvelopeId = dsnEnvelopeId;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "DeliveryRequirements[]";
        }
        StringBuilder sb = new StringBuilder("DeliveryRequirements[");
        boolean first = true;
        if (requireTls) {
            sb.append("REQUIRETLS");
            first = false;
        }
        if (priority != null) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("MT-PRIORITY=").append(priority);
            first = false;
        }
        if (releaseTime != null) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("HOLDUNTIL=").append(releaseTime);
            first = false;
        }
        if (deliverByDeadline != null) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("BY=").append(deliverByDeadline);
            if (deliverByReturn != null) {
                sb.append(deliverByReturn ? ";R" : ";N");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}

