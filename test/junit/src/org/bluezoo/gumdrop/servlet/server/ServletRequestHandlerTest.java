/*
 * ServletRequestHandlerTest.java
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

package org.bluezoo.gumdrop.servlet.server;

import org.junit.Test;

import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.ServletHandler;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link ServletRequestHandler}: construction, per-stream handler
 * creation, the absent authentication provider and service shutdown.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletRequestHandlerTest {

    @Test
    public void testRejectsNullContainer() {
        try {
            new ServletRequestHandler(null);
            fail("expected NullPointerException");
        } catch (NullPointerException e) {
            org.junit.Assert.assertEquals("container", e.getMessage());
        }
    }

    @Test
    public void testExposesContainerAndNoAuthenticationProvider() {
        Container container = new Container();
        ServletRequestHandler handler = new ServletRequestHandler(container);
        assertSame(container, handler.getContainer());
        assertNull(handler.getAuthenticationProvider());
    }

    @Test
    public void testOpenStreamCreatesServletHandler() {
        Container container = new Container();
        ServletRequestHandler handler = new ServletRequestHandler(container);
        HttpRequestHandler first = handler.openStream(null);
        HttpRequestHandler second = handler.openStream(null);
        assertTrue(first instanceof ServletHandler);
        assertTrue(second instanceof ServletHandler);
        assertTrue(first != second);
    }

    @Test
    public void testDestroyServiceShutsDownContainer() {
        Container container = new Container();
        ServletRequestHandler handler = new ServletRequestHandler(container);
        handler.destroyService();
        assertTrue(container.getWorkerThreadPool().isShutdown());
    }
}
