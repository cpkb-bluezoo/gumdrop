/*
 * HitStatisticsImpl.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.servlet.manager.HitStatistics;

/**
 * Hit statistics for a context.
 * We have to synchronize against this object when updating or retrieving
 * values.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HitStatisticsImpl extends HitStatistics {

    private long[] hits = new long[6];

    @Override public long getTotal() {
        long acc = 0L;
        for (long hit : hits) {
            acc += hit;
        }
        return acc;
    }

    @Override public long getHits(int type) {
        return hits[type];
    }

    void addHit(int status) {
        int type = status / 100;
        hits[type]++;
    }

}
