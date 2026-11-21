/*
 * WholeEvents.java
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

package org.bluezoo.gumdrop.redis.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.redis.codec.RespValue;

/**
 * Test support: a {@link ReplyEventHandler} that builds the reply and reports
 * it whole: an array, set or push as its elements, a map flattened to
 * alternating keys and values, and anything else as the one element.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class WholeEvents extends ControlReplies.WholeReply {

    /** The elements. */
    public abstract void handleResult(List<RespValue> values, RedisSession session);

    /** The reply was null. */
    public abstract void handleNull(RedisSession session);

    /** The command failed. */
    public abstract void handleError(String error, RedisSession session);

    @Override
    void onError(String error, RedisSession session) {
        handleError(error, session);
    }

    @Override
    void onValue(RespValue response, RedisSession session) {
        if (response.isNull()) {
            handleNull(session);
        } else if (response.isArray() || response.isSet() || response.isPush()) {
            handleResult(response.asArray(), session);
        } else if (response.isMap()) {
            List<RespValue> flat = new ArrayList<RespValue>();
            for (Map.Entry<RespValue, RespValue> e : response.asMap().entrySet()) {
                flat.add(e.getKey());
                flat.add(e.getValue());
            }
            handleResult(flat, session);
        } else {
            List<RespValue> one = new ArrayList<RespValue>(1);
            one.add(response);
            handleResult(one, session);
        }
    }
}
