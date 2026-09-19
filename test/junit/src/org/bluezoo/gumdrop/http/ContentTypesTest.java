/*
 * ContentTypesTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ContentTypes}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentTypesTest {

    @Test
    public void knownExtensions() {
        assertEquals("text/html", ContentTypes.getContentType("html"));
        assertEquals("text/html", ContentTypes.getContentType("htm"));
        assertEquals("image/png", ContentTypes.getContentType("png"));
        assertEquals("application/json", ContentTypes.getContentType("json"));
        assertEquals("text/css", ContentTypes.getContentType("css"));
        assertEquals("video/3gp", ContentTypes.getContentType("3gp"));
    }

    @Test
    public void unknownExtensionIsNull() {
        assertNull(ContentTypes.getContentType("nosuchext"));
        assertNull(ContentTypes.getContentType("HTML"));
    }
}
