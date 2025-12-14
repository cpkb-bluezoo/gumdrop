/*
 * DeleteResultHandler.java
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

package org.bluezoo.gumdrop.ldap.client;

/**
 * Handler for delete operation results.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPSession#delete
 */
public interface DeleteResultHandler {

    /**
     * Called when the delete operation completes.
     * 
     * <p>Check {@code result.isSuccess()} to determine if the
     * entry was deleted successfully.
     * 
     * <p>Common failure reasons include:
     * <ul>
     * <li>{@code NO_SUCH_OBJECT} - entry does not exist</li>
     * <li>{@code NOT_ALLOWED_ON_NONLEAF} - entry has children</li>
     * <li>{@code INSUFFICIENT_ACCESS_RIGHTS} - not authorized</li>
     * </ul>
     * 
     * @param result the operation result
     * @param session operations for further directory access
     */
    void handleDeleteResult(LDAPResult result, LDAPSession session);

}

