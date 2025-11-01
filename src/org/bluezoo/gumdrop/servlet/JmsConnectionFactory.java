/*
 * JmsConnectionFactory.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import java.util.LinkedHashMap;
import java.util.Map;

import org.xml.sax.Attributes;

/**
 * Corresponds to a <code>jms-connection-factory</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class JmsConnectionFactory extends Resource {

    String description;
    String name; // name
    String interfaceName = "javax.jms.ConnectionFactory";
    String className;
    String user;
    String password;
    String clientId;

    boolean transactional = true;
    Pool pool;
    Map<String,String> properties = new LinkedHashMap<>();
    String resourceAdapter;

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        name = config.getValue("name");
        interfaceName = config.getValue("interface-name");
        if (interfaceName == null) {
            interfaceName = "javax.jms.ConnectionFactory";
        }
        className = config.getValue("class-name");
        user = config.getValue("user");
        password = config.getValue("password");
        clientId = config.getValue("client-id");
    }

    static class Pool {

        int maxPoolSize = Integer.MAX_VALUE;
        int minPoolSize = 0;
        int connectionTimeoutInSeconds = 0;

    }

    @Override String getName() {
        return name;
    }

    @Override String getClassName() {
        return className;
    }

    @Override String getInterfaceName() {
        return interfaceName;
    }

}
