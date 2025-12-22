/*
 * DSNRecipientParameters.java
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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * DSN recipient parameters from the RCPT TO command.
 * 
 * <p>This class holds the DSN-related parameters specified in the
 * RCPT TO command as defined in RFC 3461:
 * 
 * <ul>
 *   <li><b>NOTIFY</b> - When to send DSN (NEVER, SUCCESS, FAILURE, DELAY)</li>
 *   <li><b>ORCPT</b> - Original recipient address (for forwarding)</li>
 * </ul>
 * 
 * <p>Example RCPT TO with DSN parameters:
 * <pre>
 * RCPT TO:&lt;recipient@example.com&gt; NOTIFY=SUCCESS,FAILURE ORCPT=rfc822;original@example.com
 * </pre>
 * 
 * <p>The ORCPT address is transmitted as xtext (RFC 3461 Section 4) and is
 * automatically decoded when parsed.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DSNNotify
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3461">RFC 3461 - SMTP DSN</a>
 */
public class DSNRecipientParameters {

    private final Set<DSNNotify> notify;
    private final String orcptType;
    private final String orcptAddress;

    /**
     * Creates new DSN recipient parameters.
     * 
     * @param notify the set of notification conditions, may be null or empty
     * @param orcptType the original recipient address type (e.g., "rfc822"), may be null
     * @param orcptAddress the original recipient address, may be null
     */
    public DSNRecipientParameters(Set<DSNNotify> notify, String orcptType, String orcptAddress) {
        if (notify != null && !notify.isEmpty()) {
            this.notify = Collections.unmodifiableSet(EnumSet.copyOf(notify));
        } else {
            this.notify = Collections.emptySet();
        }
        this.orcptType = orcptType;
        this.orcptAddress = orcptAddress;
    }

    /**
     * Returns the NOTIFY parameter values.
     * 
     * <p>This indicates when DSN messages should be generated for this recipient.
     * If empty, the server uses its default behaviour.
     * 
     * @return an unmodifiable set of notification conditions
     */
    public Set<DSNNotify> getNotify() {
        return notify;
    }

    /**
     * Returns true if NOTIFY=NEVER was specified.
     * 
     * @return true if no DSN should ever be sent for this recipient
     */
    public boolean isNotifyNever() {
        return notify.contains(DSNNotify.NEVER);
    }

    /**
     * Returns true if NOTIFY=SUCCESS was specified.
     * 
     * @return true if DSN should be sent on successful delivery
     */
    public boolean isNotifySuccess() {
        return notify.contains(DSNNotify.SUCCESS);
    }

    /**
     * Returns true if NOTIFY=FAILURE was specified.
     * 
     * @return true if DSN should be sent on delivery failure
     */
    public boolean isNotifyFailure() {
        return notify.contains(DSNNotify.FAILURE);
    }

    /**
     * Returns true if NOTIFY=DELAY was specified.
     * 
     * @return true if DSN should be sent on delivery delay
     */
    public boolean isNotifyDelay() {
        return notify.contains(DSNNotify.DELAY);
    }

    /**
     * Returns the ORCPT address type.
     * 
     * <p>This is typically "rfc822" for standard email addresses.
     * 
     * @return the address type, or null if not specified
     */
    public String getOrcptType() {
        return orcptType;
    }

    /**
     * Returns the ORCPT address.
     * 
     * <p>This is the original recipient address before any forwarding or
     * aliasing. It can be used in DSN messages to indicate the address
     * the sender originally used.
     * 
     * @return the original recipient address, or null if not specified
     */
    public String getOrcptAddress() {
        return orcptAddress;
    }

    /**
     * Returns true if an ORCPT was specified.
     * 
     * @return true if original recipient information is available
     */
    public boolean hasOrcpt() {
        return orcptType != null && orcptAddress != null;
    }

    /**
     * Returns true if any DSN parameters were specified.
     * 
     * @return true if NOTIFY or ORCPT is set
     */
    public boolean hasParameters() {
        return !notify.isEmpty() || hasOrcpt();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DSNRecipientParameters[");
        boolean first = true;
        if (!notify.isEmpty()) {
            sb.append("NOTIFY=").append(notify);
            first = false;
        }
        if (hasOrcpt()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("ORCPT=").append(orcptType).append(";").append(orcptAddress);
        }
        sb.append("]");
        return sb.toString();
    }
}

