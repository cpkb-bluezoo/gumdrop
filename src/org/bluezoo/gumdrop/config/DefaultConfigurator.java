/*
 * DefaultConfigurator.java
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

package org.bluezoo.gumdrop.config;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfigurator;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.TCPListener;

import java.io.File;

/**
 * Default {@link GumdropConfigurator} implementation that parses the
 * gumdroprc XML format and uses the built-in {@link ComponentRegistry}
 * for dependency injection.
 *
 * <p>This configurator is discovered automatically via
 * {@link java.util.ServiceLoader} when no alternative is present on
 * the classpath.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultConfigurator implements GumdropConfigurator {

    private ComponentRegistry registry;

    @Override
    public void configure(Gumdrop gumdrop, File configFile) throws Exception {
        ParseResult result = new ConfigurationParser().parse(configFile);
        this.registry = result.getRegistry();

        for (Service service : result.getServices()) {
            gumdrop.addService(service);
        }
        for (TCPListener listener : result.getListeners()) {
            gumdrop.addListener(listener);
        }
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.shutdown();
        }
    }
}
