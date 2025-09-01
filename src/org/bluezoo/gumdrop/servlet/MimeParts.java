/*
 * MimeParts.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.AbstractList;
import java.util.List;
import org.bluezoo.gumdrop.util.Multipart;
import org.bluezoo.gumdrop.util.Part;

/**
 * A collection of MIME parts for multipart/form-data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MimeParts extends AbstractList<javax.servlet.http.Part> {

    private final Request request;
    private final Multipart multipart;
    private final javax.servlet.http.Part[] parts;

    MimeParts(Request request, Multipart multipart) {
        this.request = request;
        this.multipart = multipart;
        List<Part> parts = multipart.getParts();
        int len = parts.size();
        this.parts = new javax.servlet.http.Part[len];
        for (int i = 0; i < len; i++) {
            this.parts[i] = new MimePart(request, parts.get(i));
        }
    }

    @Override public javax.servlet.http.Part get(int index) {
        return parts[index];
    }

    @Override public int size() {
        return parts.length;
    }

}
