/*
 * ContextService.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet.manager;

import java.io.IOException;
import java.util.concurrent.ThreadPoolExecutor;
import javax.servlet.ServletContext;
import org.bluezoo.gumdrop.servlet.Description;
import org.xml.sax.SAXException;

/**
 * Remote interface to manage a web application context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ContextService extends ServletContext, Description {

    /**
     * Returns the container this context is operating in.
     */
    ContainerService getContainer();

    /**
     * Returns the thread pool executor managing tasks for connections.
     */
    ThreadPoolExecutor getConnectorThreadPool();

    /**
     * Returns the keep-alive time for the connector thread pool.
     */
    String getConnectorKeepAlive();

    /**
     * Sets the keep-alive time for the connector thread pool.
     */
    void setConnectorKeepAlive(String val);

    /**
     * Returns the hit statistics for this context.
     */
    HitStatistics getHitStatistics();

    /**
     * Returns the root of this context.
     * This may be a directory or a war file.
     */
    String getRoot();

    /**
     * Reloads this context.
     */
    void reload() throws IOException, SAXException;

}
