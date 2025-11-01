/*
 * IteratorEnumeration.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
