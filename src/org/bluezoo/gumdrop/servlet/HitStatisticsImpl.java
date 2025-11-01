/*
 * HitStatisticsImpl.java
 * Copyright (C) 2025 Chris Burdess
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
