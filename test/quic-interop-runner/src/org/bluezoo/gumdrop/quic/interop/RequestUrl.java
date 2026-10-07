/*
 * RequestUrl.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * One entry of the runner's {@code REQUESTS} list, such as
 * {@code https://server4:443/abcdefgh}: where to connect, what to ask
 * for, and the name to store the download under in {@code /downloads}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RequestUrl {

    final String host;
    final int port;
    final String path;
    final String fileName;

    private RequestUrl(String host, int port, String path, String fileName) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.fileName = fileName;
    }

    static RequestUrl parse(String text) throws URISyntaxException {
        URI uri = new URI(text);
        String host = uri.getHost();
        if (host == null) {
            throw new URISyntaxException(text, "no host");
        }
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.length() == 0) {
            path = "/";
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
        if (fileName.length() == 0 || fileName.indexOf('/') >= 0 || "..".equals(fileName)) {
            throw new URISyntaxException(text, "no file name to store the download under");
        }
        return new RequestUrl(host, port, path, fileName);
    }

    @Override
    public String toString() {
        return "https://" + host + ":" + port + path;
    }

}
