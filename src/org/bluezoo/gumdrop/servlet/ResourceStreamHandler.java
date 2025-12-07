/*
 * ResourceStreamHandler.java
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

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * URL stream handler for resources contained in servlet contexts.
 * The host part of the URL is used to determine which context to match.
 * Thus, resource: URLs will look similar to the paths that HTTP clients
 * send to the server. 
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceStreamHandler extends URLStreamHandler {

    private final Container container;

    public ResourceStreamHandler(Container container) {
        this.container = container;
    }

    @Override protected URLConnection openConnection(URL url) throws IOException {
        String contextPath = url.getHost();
        if (contextPath == null) {
            contextPath = "";
        } else if (contextPath.length() > 0) {
            contextPath = "/" + contextPath;
        }
        String resourcePath = url.getPath();
        if (!resourcePath.startsWith("/")) {
            resourcePath = "/" + resourcePath;
        }
        Context context = container.getContextByPath(contextPath);
        if (context == null) {
            throw new IOException("No context for '"+contextPath+"', url="+url);
        }
        return new ResourceURLConnection(url, context, resourcePath);
    }

}
