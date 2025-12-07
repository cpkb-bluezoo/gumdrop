/*
 * QuotaSource.java
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

package org.bluezoo.gumdrop.quota;

/**
 * Indicates the source of a quota definition.
 * 
 * <p>This is useful for administrative purposes and for informing users
 * where their quota limits come from.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Quota
 */
public enum QuotaSource {
    
    /**
     * Quota is defined specifically for this user.
     * This has the highest priority and overrides role-based quotas.
     */
    USER,
    
    /**
     * Quota is derived from the user's role membership.
     * If the user has multiple roles, the most generous quota applies.
     */
    ROLE,
    
    /**
     * Quota is the system-wide default.
     * Applied when no user-specific or role-based quota is defined.
     */
    DEFAULT,
    
    /**
     * No quota is defined (unlimited).
     */
    NONE
}

