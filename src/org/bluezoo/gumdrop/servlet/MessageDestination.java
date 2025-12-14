/*
 * MessageDestination.java
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

package org.bluezoo.gumdrop.servlet;

/**
 * A message destination. This represents a logical name for a JMS queue
 * or topic defined in the deployment descriptor.
 * <p>
 * The actual JMS destination is looked up via JNDI using either:
 * <ul>
 *   <li>{@code lookup-name} - explicit JNDI name (preferred)</li>
 *   <li>{@code mapped-name} - product-specific name (legacy)</li>
 * </ul>
 * <p>
 * Message destination references ({@code message-destination-ref}) can
 * link to this destination via the {@code message-destination-link} element,
 * which should match the {@code message-destination-name} of this destination.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.jndi.MessageDestinationRef
 */
final class MessageDestination implements Description {

    // Description
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;

    /** The logical name of this message destination. */
    String messageDestinationName;
    
    /** Product-specific JNDI name for the actual JMS destination (legacy). */
    String mappedName;
    
    /** Explicit JNDI lookup name for the actual JMS destination. */
    String lookupName;

    // -- Description --

    @Override public String getDescription() {
        return description;
    }

    @Override public void setDescription(String description) {
        this.description = description;
    }

    @Override public String getDisplayName() {
        return displayName;
    }

    @Override public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override public String getSmallIcon() {
        return smallIcon;
    }

    @Override public void setSmallIcon(String smallIcon) {
        this.smallIcon = smallIcon;
    }

    @Override public String getLargeIcon() {
        return largeIcon;
    }

    @Override public void setLargeIcon(String largeIcon) {
        this.largeIcon = largeIcon;
    }

    // -- Accessors --

    /**
     * Returns the logical name of this message destination.
     * This is the name used in {@code message-destination-link} elements.
     *
     * @return the message destination name
     */
    String getName() {
        return messageDestinationName;
    }

    /**
     * Returns the JNDI name to use for looking up the actual JMS destination.
     * Prefers {@code lookup-name} over {@code mapped-name}.
     *
     * @return the JNDI name, or null if neither is specified
     */
    String getJndiName() {
        if (lookupName != null && !lookupName.isEmpty()) {
            return lookupName;
        }
        if (mappedName != null && !mappedName.isEmpty()) {
            return mappedName;
        }
        return null;
    }

}
