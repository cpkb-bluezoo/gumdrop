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

package org.bluezoo.gumdrop.servlet.jndi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;

import org.xml.sax.Attributes;

/**
 * Corresponds to a <code>jms-destination</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class JmsDestination extends Resource {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jndi.L10N");

    String description;
    String name;
    String interfaceName;
    String className;
    Map<String,String> properties = new LinkedHashMap<>();

    // -- Setters and Getters --

    /**
     * Sets a description of this JMS destination.
     * Corresponds to the {@code description} element within {@code jms-destination}
     * in the deployment descriptor.
     *
     * @param description a textual description of this destination
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-destination">
     *      Servlet 4.0 Specification, Section 5.5: JMS Destination</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the JNDI name by which this destination will be registered.
     * Corresponds to the {@code name} element in the deployment descriptor.
     * <p>
     * The name must be a valid JNDI name, typically in the form
     * {@code java:comp/env/jms/queue/MyQueue} or {@code java:comp/env/jms/topic/MyTopic}.
     *
     * @param name the JNDI name for this destination
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-destination">
     *      Servlet 4.0 Specification, Section 5.5: JMS Destination</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the interface implemented by this destination.
     * Corresponds to the {@code interface-name} element in the deployment descriptor.
     * <p>
     * Valid values are {@code javax.jms.Queue} or {@code javax.jms.Topic}.
     * If not specified, the interface is inferred from the JNDI name.
     *
     * @param interfaceName the destination interface class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-destination">
     *      Servlet 4.0 Specification, Section 5.5: JMS Destination</a>
     */
    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    /**
     * Sets the fully-qualified class name of the destination implementation.
     * Corresponds to the {@code class-name} element in the deployment descriptor.
     *
     * @param className the destination implementation class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-destination">
     *      Servlet 4.0 Specification, Section 5.5: JMS Destination</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        name = config.getValue("name");
        interfaceName = config.getValue("interface-name");
        className = config.getValue("class-name");
    }

    @Override public String getName() {
        return name;
    }

    @Override public String getClassName() {
        return className;
    }

    @Override public String getInterfaceName() {
        if (interfaceName == null) {
            String jndiName = JndiContext.stripCompEnv(name);
            if (jndiName.startsWith("queue/")) {
                interfaceName = "javax.jms.Queue";
            } else if (jndiName.startsWith("topic/")) {
                interfaceName = "javax.jms.Topic";
            } else {
                String message = L10N.getString("err.no_destination_interface");
                throw new IllegalStateException(message);
            }
        }
        return interfaceName;
    }

}

