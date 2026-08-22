/*
 * DoQListenerTest.java
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

package org.bluezoo.gumdrop.dns;

import java.lang.reflect.Method;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.QuicTransportFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link DoQListener} Retry-based address validation
 * (RFC 9000 section 8.1.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQListenerTest {

    @Test
    public void testRequireRetryEnabledByDefault() {
        DoQListener listener = new DoQListener();
        assertTrue(listener.isRequireRetry());
    }

    @Test
    public void testSetRequireRetry() {
        DoQListener listener = new DoQListener();
        listener.setRequireRetry(false);
        assertFalse(listener.isRequireRetry());
    }

    @Test
    public void testCreateTransportFactoryEnablesRetryByDefault() throws Exception {
        DoQListener listener = new DoQListener();
        QuicTransportFactory factory = invokeCreateTransportFactory(listener);
        assertTrue(factory.isRequireRetry());
    }

    @Test
    public void testCreateTransportFactoryHonoursRetryOptOut() throws Exception {
        DoQListener listener = new DoQListener();
        listener.setRequireRetry(false);
        QuicTransportFactory factory = invokeCreateTransportFactory(listener);
        assertFalse(factory.isRequireRetry());
    }

    private static QuicTransportFactory invokeCreateTransportFactory(
            DoQListener listener) throws Exception {
        Method method = DoQListener.class.getDeclaredMethod("createTransportFactory");
        method.setAccessible(true);
        return (QuicTransportFactory) method.invoke(listener);
    }
}
