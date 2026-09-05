/*
 * package-info.java
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

/**
 * LDAP-backed authentication realm.
 *
 * <p>{@link org.bluezoo.gumdrop.auth.ldap.LDAPRealm} implements {@link
 * org.bluezoo.gumdrop.auth.Realm} by performing an LDAP bind against a
 * directory server through {@link org.bluezoo.gumdrop.ldap.client} --
 * since a directory only ever verifies a bind, not the password itself,
 * it can only ever back the mechanisms that need nothing more than
 * that (USER/PASS-style and SASL PLAIN/LOGIN), not challenge-response
 * mechanisms that need the raw or hashed password server-side.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see org.bluezoo.gumdrop.ldap.client
 */
package org.bluezoo.gumdrop.auth.ldap;
