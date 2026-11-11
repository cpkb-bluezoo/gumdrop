/*
 * SetOptions.java
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

/**
 * Options for the {@code SET} command: a condition ({@code NX}, {@code XX}),
 * an expiry ({@code EX}, {@code PX}, {@code EXAT}, {@code PXAT},
 * {@code KEEPTTL}) and {@code GET}.
 *
 * <p>Options are set fluently and checked as they are set: a second
 * condition, a second expiry, or a non-positive time is rejected at once.
 *
 * <pre>{@code
 * session.set("lock", "me", new SetOptions().nx().px(30000), handler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisSession#set(String, String, SetOptions, BulkResultHandler)
 */
public final class SetOptions {

    private String condition;
    private String expiry;
    private long expiryValue;
    private boolean get;

    /** Creates options that add nothing to a plain {@code SET}. */
    public SetOptions() {
    }

    /** Only set the key if it does not already exist. */
    public SetOptions nx() {
        return condition("NX");
    }

    /** Only set the key if it already exists. */
    public SetOptions xx() {
        return condition("XX");
    }

    /** Expire the key after {@code seconds}. */
    public SetOptions ex(long seconds) {
        return expiry("EX", seconds);
    }

    /** Expire the key after {@code milliseconds}. */
    public SetOptions px(long milliseconds) {
        return expiry("PX", milliseconds);
    }

    /** Expire the key at a Unix time in seconds. */
    public SetOptions exat(long unixSeconds) {
        return expiry("EXAT", unixSeconds);
    }

    /** Expire the key at a Unix time in milliseconds. */
    public SetOptions pxat(long unixMilliseconds) {
        return expiry("PXAT", unixMilliseconds);
    }

    /** Keep the time to live already on the key. */
    public SetOptions keepTtl() {
        if (expiry != null && !expiry.equals("KEEPTTL")) {
            throw new IllegalStateException(
                    "KEEPTTL cannot be combined with " + expiry);
        }
        expiry = "KEEPTTL";
        return this;
    }

    /** Return the old string stored at the key (Redis 6.2 and later). */
    public SetOptions get() {
        get = true;
        return this;
    }

    private SetOptions condition(String name) {
        if (condition != null && !condition.equals(name)) {
            throw new IllegalStateException(
                    name + " cannot be combined with " + condition);
        }
        condition = name;
        return this;
    }

    private SetOptions expiry(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " requires a positive time: " + value);
        }
        if (expiry != null && !expiry.equals(name)) {
            throw new IllegalStateException(
                    name + " cannot be combined with " + expiry);
        }
        expiry = name;
        expiryValue = value;
        return this;
    }

    /** The command arguments these options add after the key and value. */
    String[] toArguments() {
        List<String> args = new ArrayList<String>(4);
        if (condition != null) {
            args.add(condition);
        }
        if (get) {
            args.add("GET");
        }
        if (expiry != null) {
            args.add(expiry);
            if (!expiry.equals("KEEPTTL")) {
                args.add(Long.toString(expiryValue));
            }
        }
        return args.toArray(new String[args.size()]);
    }
}
