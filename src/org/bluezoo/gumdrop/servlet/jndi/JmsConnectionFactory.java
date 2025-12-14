/*
 * JmsConnectionFactory.java
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

    // -- Setters and Getters --

    /**
     * Sets a description of this JMS connection factory.
     * Corresponds to the {@code description} element within {@code jms-connection-factory}
     * in the deployment descriptor.
     *
     * @param description a textual description of this connection factory
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the JNDI name by which this connection factory will be registered.
     * Corresponds to the {@code name} element in the deployment descriptor.
     * <p>
     * The name must be a valid JNDI name, typically in the form
     * {@code java:comp/env/jms/MyConnectionFactory}.
     *
     * @param name the JNDI name for this connection factory
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the fully-qualified interface name that this connection factory implements.
     * Corresponds to the {@code interface-name} element in the deployment descriptor.
     * <p>
     * Common values are {@code javax.jms.ConnectionFactory}, {@code javax.jms.QueueConnectionFactory},
     * or {@code javax.jms.TopicConnectionFactory}.
     *
     * @param interfaceName the connection factory interface class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    /**
     * Sets the fully-qualified class name of the connection factory implementation.
     * Corresponds to the {@code class-name} element in the deployment descriptor.
     *
     * @param className the connection factory implementation class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Sets the user name for JMS server authentication.
     * Corresponds to the {@code user} element in the deployment descriptor.
     *
     * @param user the JMS server user name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Sets the password for JMS server authentication.
     * Corresponds to the {@code password} element in the deployment descriptor.
     *
     * @param password the JMS server password
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Sets the client identifier for this connection factory.
     * Corresponds to the {@code client-id} element in the deployment descriptor.
     * <p>
     * The client ID is used to associate connections with a durable subscription.
     *
     * @param clientId the JMS client identifier
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * Sets whether connections created by this factory are transactional.
     * Corresponds to the {@code transactional} element in the deployment descriptor.
     * <p>
     * When {@code true}, connections participate in container-managed transactions.
     *
     * @param transactional {@code true} if connections should be transactional
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setTransactional(boolean transactional) {
        this.transactional = transactional;
    }

    /**
     * Sets the connection pool configuration.
     * Corresponds to the {@code pool} element in the deployment descriptor.
     *
     * @param pool the connection pool configuration
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setPool(Pool pool) {
        this.pool = pool;
    }

    /**
     * Sets the name of the resource adapter to use.
     * Corresponds to the {@code resource-adapter} element in the deployment descriptor.
     *
     * @param resourceAdapter the resource adapter name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#jms-connection-factory">
     *      Servlet 4.0 Specification, Section 5.5: JMS Connection Factory</a>
     */
    public void setResourceAdapter(String resourceAdapter) {
        this.resourceAdapter = resourceAdapter;
    }

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

    /**
     * Connection pool configuration for a JMS connection factory.
     * Corresponds to the {@code pool} element within {@code jms-connection-factory}.
     */
    public static class Pool {

        int maxPoolSize = Integer.MAX_VALUE;
        int minPoolSize = 0;
        int connectionTimeoutInSeconds = 0;

        /**
         * Sets the maximum number of connections in the pool.
         * Corresponds to the {@code max-pool-size} element in the deployment descriptor.
         *
         * @param maxPoolSize the maximum number of pooled connections
         */
        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        /**
         * Sets the minimum number of connections in the pool.
         * Corresponds to the {@code min-pool-size} element in the deployment descriptor.
         *
         * @param minPoolSize the minimum number of pooled connections
         */
        public void setMinPoolSize(int minPoolSize) {
            this.minPoolSize = minPoolSize;
        }

        /**
         * Sets the maximum time to wait for a connection from the pool.
         * Corresponds to the {@code connection-timeout-in-seconds} element in the deployment descriptor.
         *
         * @param connectionTimeoutInSeconds the timeout in seconds
         */
        public void setConnectionTimeoutInSeconds(int connectionTimeoutInSeconds) {
            this.connectionTimeoutInSeconds = connectionTimeoutInSeconds;
        }

    }

    @Override public String getName() {
        return name;
    }

    @Override public String getClassName() {
        return className;
    }

    @Override public String getInterfaceName() {
        return interfaceName;
    }

}
