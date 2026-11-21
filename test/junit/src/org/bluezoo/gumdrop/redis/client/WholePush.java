/*
 * WholePush.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Test support: a {@link PushHandler} that builds each push and reports it as
 * the kind and the remaining elements as plain Java values.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class WholePush extends ControlReplies.WholeReply implements PushHandler {

    /** A push arrived. */
    public abstract void onPush(String kind, List<Object> payload);

    @Override
    void onError(String error, RedisSession session) {
    }

    @Override
    void onValue(RespValue value, RedisSession session) {
    }

    @Override
    public void endReply(boolean skipped) {
        super.endReply(skipped);
        handleComplete(null, null);
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        RespValue v = builderValue();
        if (v == null || !v.isPush()) {
            return;
        }
        List<RespValue> push = v.asPush();
        if (push == null || push.isEmpty()) {
            return;
        }
        List<Object> payload = new ArrayList<Object>(push.size() - 1);
        for (int i = 1; i < push.size(); i++) {
            payload.add(toJava(push.get(i)));
        }
        onPush(push.get(0).asString(), payload);
    }

    private static Object toJava(RespValue value) {
        if (value == null || value.isNull()) {
            return null;
        } else if (value.isInteger()) {
            return Long.valueOf(value.asLong());
        } else if (value.isDouble()) {
            return Double.valueOf(value.asDouble());
        } else if (value.isBoolean()) {
            return Boolean.valueOf(value.asBoolean());
        } else if (value.isArray() || value.isSet() || value.isPush()) {
            List<RespValue> items = value.asArray();
            List<Object> out = new ArrayList<Object>(items.size());
            for (RespValue item : items) {
                out.add(toJava(item));
            }
            return out;
        } else if (value.isMap()) {
            Map<Object, Object> out = new LinkedHashMap<Object, Object>();
            for (Map.Entry<RespValue, RespValue> e : value.asMap().entrySet()) {
                out.put(toJava(e.getKey()), toJava(e.getValue()));
            }
            return out;
        }
        return value.asString();
    }
}
