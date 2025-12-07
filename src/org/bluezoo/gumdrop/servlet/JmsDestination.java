/*
 * JmsDestination.java
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

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import org.xml.sax.Attributes;

/**
 * Corresponds to a <code>jms-destination</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class JmsDestination extends Resource {

    String description;
    String name;
    String interfaceName;
    String className;
    Map<String,String> properties = new LinkedHashMap<>();

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        name = config.getValue("name");
        interfaceName = config.getValue("interface-name");
        className = config.getValue("class-name");
    }

    @Override String getName() {
        return name;
    }

    @Override String getClassName() {
        return className;
    }

    @Override String getInterfaceName() {
        if (interfaceName == null) {
            String jndiName = Context.stripCompEnv(name);
            if (jndiName.startsWith("queue/")) {
                interfaceName = "javax.jms.Queue";
            } else if (jndiName.startsWith("topic/")) {
                interfaceName = "javax.jms.Topic";
            } else {
                String message = Context.L10N.getString("err.no_destination_interface");
                throw new IllegalStateException(message);
            }
        }
        return interfaceName;
    }

}
