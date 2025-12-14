/*
 * ExtendedResultHandler.java
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
 * Handler for extended operation results.
 * 
 * <p>Extended operations allow protocol extensions beyond the core
 * LDAP operations. Common examples include:
 * <ul>
 * <li>Password Modify (RFC 3062)</li>
 * <li>Who Am I (RFC 4532)</li>
 * <li>Cancel (RFC 3909)</li>
 * </ul>
 * 
 * <p>Note: STARTTLS is an extended operation but is handled specially
 * by {@link LDAPConnected#startTLS} and {@link StartTLSResultHandler}.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPSession#extended
 */
public interface ExtendedResultHandler {

    /**
     * Called when the extended operation completes.
     * 
     * @param result the operation result
     * @param responseName the OID of the extended response (may be null)
     * @param responseValue the extended response value (may be null)
     * @param session operations for further directory access
     */
    void handleExtendedResult(LDAPResult result, String responseName,
                              byte[] responseValue, LDAPSession session);

}

