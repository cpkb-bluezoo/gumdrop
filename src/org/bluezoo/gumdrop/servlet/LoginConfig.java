/*
 * LoginConfig.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Definition of a login-config.
 *
 * The login-config is used to configure the authentication method that should
 * be used, the realm name that should be used for this application, and the
 * attributes that are needed by the form login mechanism. The sub-element
 * auth-method configures the authentication mechanism for the Web application. The
 * element content must be either BASIC, DIGEST, FORM, CLIENT-CERT, or a
 * vendor-specific authentication scheme. The realm-name indicates the realm
 * name to use for the authentication scheme chosen for the Web application. The
 * form-login-config specifies the login and error pages that should be used in
 * FORM based login. If FORM based login is not used, these elements are ignored.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class LoginConfig {

    String authMethod;
    String realmName;
    String formLoginPage;
    String formErrorPage;

}
