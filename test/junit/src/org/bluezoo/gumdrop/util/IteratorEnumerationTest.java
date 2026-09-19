/*
 * IteratorEnumerationTest.java
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

package org.bluezoo.gumdrop.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IteratorEnumeration}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IteratorEnumerationTest {

    @Test
    public void emptyEnumerationHasNoElements() {
        IteratorEnumeration<String> e = new IteratorEnumeration<String>();
        assertFalse(e.hasMoreElements());
    }

    @Test(expected = NoSuchElementException.class)
    public void emptyEnumerationThrowsOnNext() {
        IteratorEnumeration<String> e = new IteratorEnumeration<String>();
        e.nextElement();
    }

    @Test
    public void nullCollectionIsEmpty() {
        List<String> none = null;
        IteratorEnumeration<String> e = new IteratorEnumeration<String>(none);
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void enumeratesCollectionInOrder() {
        List<String> list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        IteratorEnumeration<String> e = new IteratorEnumeration<String>(list);
        assertTrue(e.hasMoreElements());
        assertEquals("a", e.nextElement());
        assertEquals("b", e.nextElement());
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void enumeratesIterator() {
        List<Integer> list = new ArrayList<Integer>();
        list.add(7);
        Iterator<Integer> it = list.iterator();
        IteratorEnumeration<Integer> e = new IteratorEnumeration<Integer>(it);
        assertEquals(Integer.valueOf(7), e.nextElement());
        assertFalse(e.hasMoreElements());
    }
}
