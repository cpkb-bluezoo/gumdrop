/*
 * Resource.java
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
import java.util.logging.Level;
import javax.servlet.ServletException;
import org.xml.sax.Attributes;

/**
 * Definition of a resource that will be instantiated and bound in the JNDI
 * context for the web application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Resource {

    /**
     * Returns the name that this resource will be bound into JNDI under.
     */
    abstract String getName();

    /**
     * Returns the name of the provider class.
     */
    abstract String getClassName();

    /**
     * Returns the name of the interface under which this will be bound to
     * JNDI.
     */
    abstract String getInterfaceName();

    Object newInstance() {
        String className = getClassName();
        String interfaceName = getInterfaceName();
        try {
            Class t = Class.forName(className);
            Class i = Class.forName(interfaceName);
            if (!i.isAssignableFrom(t)) {
                String message = Context.L10N.getString("err.not_assignable");
                message = MessageFormat.format(message, className, interfaceName);
                throw new InstantiationException(message);
            }
            return t.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            String message = Context.L10N.getString("err.init_resource");
            message = MessageFormat.format(message, className);
            Context.LOGGER.log(Level.SEVERE, message, e);
        }
        return null;
    }

    /**
     * Add the specified configuration property to this resource.
     */
    public abstract void addProperty(String name, String value);

    /**
     * Initialize this resource from the specified attributes.
     */
    public abstract void init(Attributes config);

    /**
     * Informs this resource that it is being put into service.
     */
    void init() throws ServletException {
    }

    /**
     * Informs this resource that it is being removed from service.
     */
    void close() {
    }

}
