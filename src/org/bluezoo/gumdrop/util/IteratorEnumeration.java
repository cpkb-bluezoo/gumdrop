/*
 * IteratorEnumeration.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Enumeration wrapper for collection classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class IteratorEnumeration<T> implements Enumeration<T> {

    final Iterator<T> i;

    public IteratorEnumeration() {
        i = null;
    }

    public IteratorEnumeration(Iterator<T> i) {
        this.i = i;
    }

    public IteratorEnumeration(Collection<T> c) {
        i = (c == null) ? null : c.iterator();
    }

    public boolean hasMoreElements() {
        return (i == null) ? false : i.hasNext();
    }

    public T nextElement() {
        if (i == null) {
            throw new NoSuchElementException();
        }
        return i.next();
    }

}
