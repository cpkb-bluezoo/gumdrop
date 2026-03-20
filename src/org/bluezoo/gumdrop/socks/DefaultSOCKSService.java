/*
 * DefaultSOCKSService.java
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

package org.bluezoo.gumdrop.socks;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.socks.handler.ConnectHandler;

/**
 * Default SOCKS proxy service implementation.
 *
 * <p>Provides a standard SOCKS proxy with no custom connect
 * authorization. All CONNECT requests that pass destination filtering
 * are accepted and proxied. When a {@link org.bluezoo.gumdrop.auth.Realm
 * Realm} is configured, SOCKS5 clients must authenticate; SOCKS4
 * clients are accepted without authentication.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service id="socks" class="org.bluezoo.gumdrop.socks.DefaultSOCKSService">
 *   <property name="realm" ref="#socksRealm"/>
 *   <property name="blocked-destinations">127.0.0.0/8,::1/128</property>
 *   <listener class="org.bluezoo.gumdrop.socks.SOCKSListener" port="1080"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SOCKSService
 */
public class DefaultSOCKSService extends SOCKSService {

    @Override
    protected ConnectHandler createConnectHandler(TCPListener listener) {
        return null;
    }

}
