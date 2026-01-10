/*
 * QNamePool.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.util.ArrayDeque;

/**
 * Object pool for QName instances.
 * 
 * <p>QName objects are checked out from the pool, used, and returned.
 * If the pool is empty, a new QName is created. If the pool is full on return,
 * the QName is discarded (will be garbage collected).
 * 
 * <p>Usage pattern:
 * <pre>
 * QName qname = pool.checkout();
 * qname.update(uri, localName, qName);
 * // ... use qname ...
 * pool.returnToPool(qname);
 * </pre>
 * 
 * <p>The pool should be cleared between parse operations to release resources.
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class QNamePool {
    
    /**
     * Maximum pool size. Limits memory usage while providing enough capacity
     * for typical XML documents (elements + attributes per level).
     */
    private static final int MAX_POOL_SIZE = 24;
    
    /**
     * Pool of available QName objects ready for reuse.
     */
    private final ArrayDeque<QName> available;
    
    /**
     * Creates a new QName pool.
     */
    QNamePool() {
        this.available = new ArrayDeque<>(MAX_POOL_SIZE);
    }
    
    /**
     * Checks out a QName from the pool.
     * If the pool is empty, creates a new QName.
     * 
     * <p>The caller is responsible for calling returnToPool() when done.
     * 
     * @return a QName object (from pool or newly created)
     */
    QName checkout() {
        QName qname = available.poll();
        if (qname == null) {
            qname = new QName();
        }
        return qname;
    }
    
    /**
     * Returns a QName to the pool for reuse.
     * If the pool is at maximum capacity, the QName is discarded.
     * 
     * @param qname the QName to return (must not be null)
     */
    void returnToPool(QName qname) {
        if (qname != null && available.size() < MAX_POOL_SIZE) {
            available.add(qname);
        }
        // If pool full or qname is null, just discard (will be GC'd)
    }
    
    /**
     * Clears the pool, releasing all QName objects.
     * Should be called between parse operations to free resources.
     */
    void clear() {
        available.clear();
    }
    
    /**
     * Returns the number of QName objects currently available in the pool.
     * 
     * @return the number of available QNames
     */
    int getAvailableCount() {
        return available.size();
    }
}
