/*
 * Resource.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.xml.sax.Attributes;

/**
 * Definition of a resource that will be instantiated and bound in the JNDI
 * context for the web application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Resource {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jndi.L10N");
    private static final Logger LOGGER = Logger.getLogger(Resource.class.getName());

    /**
     * Returns the name that this resource will be bound into JNDI under.
     */
    public abstract String getName();

    /**
     * Returns the name of the provider class.
     */
    public abstract String getClassName();

    /**
     * Returns the name of the interface under which this will be bound to
     * JNDI.
     */
    public abstract String getInterfaceName();

    /**
     * Creates a new instance of this resource.
     */
    public Object newInstance() {
        String className = getClassName();
        String interfaceName = getInterfaceName();
        try {
            Class t = Class.forName(className);
            Class i = Class.forName(interfaceName);
            if (!i.isAssignableFrom(t)) {
                String message = L10N.getString("err.not_assignable");
                message = MessageFormat.format(message, className, interfaceName);
                throw new InstantiationException(message);
            }
            return t.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            String message = L10N.getString("err.init_resource");
            message = MessageFormat.format(message, className);
            LOGGER.log(Level.SEVERE, message, e);
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
    public void init() throws ServletException {
    }

    /**
     * Informs this resource that it is being removed from service.
     */
    public void close() {
    }

}

