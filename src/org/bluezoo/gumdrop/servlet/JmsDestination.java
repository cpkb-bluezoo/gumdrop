/*
 * JmsDestination.java
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
