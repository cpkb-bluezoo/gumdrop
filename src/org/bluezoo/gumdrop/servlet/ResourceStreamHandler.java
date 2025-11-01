/*
 * ResourceStreamHandler.java
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
