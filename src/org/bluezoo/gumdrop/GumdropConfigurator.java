/*
 * GumdropConfigurator.java
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

package org.bluezoo.gumdrop;

import java.io.File;

/**
 * Service provider interface for configuring a Gumdrop instance from an
 * external source such as a configuration file.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}.
 * The default implementation parses the gumdroprc XML format and uses the
 * built-in dependency injection container. Alternative implementations can
 * delegate to external DI frameworks such as Guice, Spring, or CDI.
 *
 * <p>The contract is simple: read the configuration source, create and
 * wire all components, then add {@link Service} and {@link TCPListener}
 * instances to the supplied {@link Gumdrop} instance.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Gumdrop#getInstance(File)
 */
public interface GumdropConfigurator {

    /**
     * Configures a Gumdrop instance from a configuration file.
     *
     * <p>Implementations should create and wire all components defined
     * in the configuration, then register them with the Gumdrop instance
     * via {@link Gumdrop#addService(Service)} and
     * {@link Gumdrop#addListener(TCPListener)}.
     *
     * @param gumdrop the Gumdrop instance to configure
     * @param configFile the configuration file
     * @throws Exception if configuration fails
     */
    void configure(Gumdrop gumdrop, File configFile) throws Exception;

    /**
     * Shuts down any resources managed by this configurator, such as
     * singleton component destroy callbacks.
     */
    void shutdown();
}
