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

import java.util.concurrent.atomic.LongAdder;

/**
 * Hit statistics for a context.
 *
 * <p>{@code addHit} is called on every response commit, i.e. on the hot
 * request path for every concurrent request in this context - a {@link
 * LongAdder} per status-code bucket avoids the contention a shared
 * monitor (or a single AtomicLong per bucket) would create under
 * concurrent load, at the cost of {@link #getTotal}/{@link #getHits}
 * (called only for the occasional manager-UI stats page) being merely
 * eventually-consistent rather than atomic snapshots.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HitStatisticsImpl extends HitStatistics {

    private final LongAdder[] hits;

    HitStatisticsImpl() {
        hits = new LongAdder[6];
        for (int i = 0; i < hits.length; i++) {
            hits[i] = new LongAdder();
        }
    }

    @Override public long getTotal() {
        long acc = 0L;
        for (LongAdder hit : hits) {
            acc += hit.sum();
        }
        return acc;
    }

    @Override public long getHits(int type) {
        return hits[type].sum();
    }

    void addHit(int status) {
        int type = status / 100;
        hits[type].increment();
    }

}
