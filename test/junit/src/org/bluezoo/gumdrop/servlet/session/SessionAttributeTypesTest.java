/*
 * SessionAttributeTypesTest.java
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

package org.bluezoo.gumdrop.servlet.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the attribute value types carried by session deltas and the
 * listener notifications raised by {@link Session}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionAttributeTypesTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";

    private MockSessionContext context;

    @Before
    public void setUp() {
        context = new MockSessionContext();
        context.setSessionTimeout(600);
    }

    private SessionSerializer.DeltaUpdate roundTrip(Session session) throws IOException {
        Set<String> dirty = session.getDirtyAttributes();
        Set<String> removed = session.getRemovedAttributes();
        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        return SessionSerializer.deserializeDelta(buf);
    }

    @Test
    public void testDeltaCarriesEveryScalarType() throws IOException {
        Session session = new Session(context, ID);
        session.clearDirtyState();
        session.setAttribute("s", "text");
        session.setAttribute("b", Boolean.TRUE);
        session.setAttribute("l", Long.valueOf(1234567890123L));
        session.setAttribute("i", Integer.valueOf(42));
        session.setAttribute("d", Double.valueOf(2.5d));
        session.setAttribute("f", Float.valueOf(1.5f));
        session.setAttribute("bytes", new byte[] { 1, 2, 3 });
        SessionSerializer.DeltaUpdate delta = roundTrip(session);
        assertEquals("text", delta.updatedAttributes.get("s"));
        assertEquals(Boolean.TRUE, delta.updatedAttributes.get("b"));
        assertEquals(Long.valueOf(1234567890123L), delta.updatedAttributes.get("l"));
        assertEquals(Long.valueOf(42L), delta.updatedAttributes.get("i"));
        assertEquals(Double.valueOf(2.5d), delta.updatedAttributes.get("d"));
        assertEquals(Float.valueOf(1.5f), delta.updatedAttributes.get("f"));
        byte[] bytes = (byte[]) delta.updatedAttributes.get("bytes");
        assertEquals(3, bytes.length);
        assertEquals(session.getAttribute("s"), "text");
    }

    @Test
    public void testDeltaSkipsAttributesRemovedFromSession() throws IOException {
        Session session = new Session(context, ID);
        session.setAttribute("gone", "x");
        Set<String> dirty = new HashSet<String>();
        dirty.add("gone");
        dirty.add("neverSet");
        session.removeAttribute("gone");
        Set<String> removed = new HashSet<String>();
        removed.add("gone");
        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);
        assertTrue(delta.updatedAttributes.isEmpty());
        assertTrue(delta.removedAttributes.contains("gone"));
    }

    @Test
    public void testFullSessionCarriesEveryScalarType() throws IOException {
        Session session = new Session(context, ID);
        session.setAttribute("b", Boolean.FALSE);
        session.setAttribute("i", Integer.valueOf(7));
        session.setAttribute("d", Double.valueOf(0.25d));
        session.setAttribute("f", Float.valueOf(0.5f));
        ByteBuffer buf = SessionSerializer.serialize(session);
        Session copy = SessionSerializer.deserialize(context, buf);
        assertEquals(Boolean.FALSE, copy.getAttribute("b"));
        assertEquals(Long.valueOf(7L), copy.getAttribute("i"));
        assertEquals(Double.valueOf(0.25d), copy.getAttribute("d"));
        assertEquals(Float.valueOf(0.5f), copy.getAttribute("f"));
    }

    @Test(expected = IOException.class)
    public void testDeltaWithoutIdIsRejected() throws IOException {
        ByteBuffer empty = ByteBuffer.allocate(0);
        SessionSerializer.deserializeDelta(empty);
    }

    @Test(expected = IOException.class)
    public void testDeltaGarbageIsRejected() throws IOException {
        ByteBuffer junk = ByteBuffer.wrap(new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff });
        SessionSerializer.deserializeDelta(junk);
    }

    private static final class Binding implements HttpSessionBindingListener {
        final List<String> events = new ArrayList<String>();

        @Override
        public void valueBound(HttpSessionBindingEvent event) {
            events.add("bound:" + event.getName());
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            events.add("unbound:" + event.getName());
        }
    }

    private static final class AttrListener implements HttpSessionAttributeListener {
        final List<String> events = new ArrayList<String>();

        @Override
        public void attributeAdded(HttpSessionBindingEvent event) {
            events.add("added:" + event.getName());
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event) {
            events.add("removed:" + event.getName());
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event) {
            events.add("replaced:" + event.getName());
        }
    }

    @Test
    public void testBindingListenerAndAttributeListenerEvents() {
        AttrListener attrs = new AttrListener();
        context.addSessionAttributeListener(attrs);
        Session session = new Session(context, ID);
        Binding first = new Binding();
        Binding second = new Binding();
        session.setAttribute("k", first);
        session.setAttribute("k", second);
        session.setAttribute("k", "plain");
        session.setAttribute("k2", second);
        session.removeAttribute("k2");
        session.removeAttribute("k2");
        session.removeAttribute("k");
        assertEquals(Collections.singletonList("bound:k").get(0), first.events.get(0));
        assertEquals("unbound:k", first.events.get(1));
        assertEquals("bound:k", second.events.get(0));
        assertEquals("unbound:k", second.events.get(1));
        assertEquals("bound:k2", second.events.get(2));
        assertEquals("unbound:k2", second.events.get(3));
        assertEquals(6, attrs.events.size());
        assertEquals("added:k", attrs.events.get(0));
        assertEquals("replaced:k", attrs.events.get(1));
        assertEquals("removed:k", attrs.events.get(attrs.events.size() - 1));
    }

    @Test
    public void testInvalidateNotifiesSessionListeners() {
        final List<HttpSession> destroyed = new ArrayList<HttpSession>();
        context.addSessionListener(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                destroyed.add(event.getSession());
            }
        });
        Session session = new Session(context, ID);
        session.invalidate();
        assertEquals(1, destroyed.size());
        assertTrue(destroyed.get(0) == session);
    }

    @Test
    public void testIsNewReflectsAccessTime() {
        Session session = new Session(context, ID);
        assertTrue(session.isNew());
        assertNull(session.getAttribute("none"));
        assertFalse(session.getDirtyAttributes().contains("none"));
    }
}
