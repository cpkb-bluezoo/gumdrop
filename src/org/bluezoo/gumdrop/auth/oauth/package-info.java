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
 * OAuth 2.0 bearer token authentication.
 *
 * <p>{@link org.bluezoo.gumdrop.auth.oauth.OAuthRealm} implements
 * {@link org.bluezoo.gumdrop.auth.Realm}, validating access tokens
 * either via RFC 7662 token introspection against an authorization
 * server, or locally as a JWT, with configurable scope-to-role mapping
 * for {@code isUserInRole}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7662">RFC 7662 - OAuth 2.0 Token Introspection</a>
 */
package org.bluezoo.gumdrop.auth.oauth;
