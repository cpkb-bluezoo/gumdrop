/*
 * HitStatistics.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */
package org.bluezoo.gumdrop.servlet;

/**
 * Hit statistics for a context.
 * We have to synchronize against this object when updating or retrieving
 * values.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HitStatistics {

    static final int INFORMATIONAL = 1;
    static final int SUCCESS = 2;
    static final int REDIRECT = 3;
    static final int CLIENT_ERROR = 4;
    static final int SERVER_ERROR = 5;

    private long[] hits = new long[6];

    long getTotal() {
        long acc = 0L;
        for (long hit : hits) {
            acc += hit;
        }
        return acc;
    }

    long getHits(int type) {
        return hits[type];
    }

    void addHit(int status) {
        int type = status / 100;
        hits[type]++;
    }

}
