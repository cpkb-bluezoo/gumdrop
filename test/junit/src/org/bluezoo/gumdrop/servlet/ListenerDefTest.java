/*
 * ListenerDefTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The description properties of {@link ListenerDef}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ListenerDefTest {

    @Test
    public void descriptionPropertiesRoundTrip() {
        ListenerDef def = new ListenerDef();
        assertNull(def.getDescription());
        assertNull(def.getDisplayName());
        assertNull(def.getSmallIcon());
        assertNull(def.getLargeIcon());
        def.setDescription("d");
        def.setDisplayName("n");
        def.setSmallIcon("s.png");
        def.setLargeIcon("l.png");
        assertEquals("d", def.getDescription());
        assertEquals("n", def.getDisplayName());
        assertEquals("s.png", def.getSmallIcon());
        assertEquals("l.png", def.getLargeIcon());
    }

    @Test
    public void initRecordsTheClassName() {
        ListenerDef def = new ListenerDef();
        def.init("com.example.Listener");
        assertEquals("com.example.Listener", def.className);
    }
}
