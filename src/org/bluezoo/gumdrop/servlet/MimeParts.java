/*
 * MimeParts.java
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
