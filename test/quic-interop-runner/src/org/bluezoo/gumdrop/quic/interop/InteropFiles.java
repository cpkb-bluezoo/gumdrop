/*
 * InteropFiles.java
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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Maps request targets to files under the runner's {@code /www} mount,
 * refusing anything that would escape it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class InteropFiles {

    private InteropFiles() {
    }

    /**
     * Resolves a request target such as {@code /abcd} against the served
     * directory.
     *
     * @return the regular file to serve, or null if there is none
     */
    static Path resolve(Path www, String target) {
        if (target == null) {
            return null;
        }
        String relative = target;
        int query = relative.indexOf('?');
        if (query >= 0) {
            relative = relative.substring(0, query);
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.length() == 0) {
            return null;
        }
        Path root = www.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return file;
    }

}
