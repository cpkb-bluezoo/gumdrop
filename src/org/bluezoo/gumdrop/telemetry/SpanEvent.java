/*
 * SpanEvent.java
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

package org.bluezoo.gumdrop.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An event occurring during a span's lifetime.
 * Events have a name, timestamp, and optional attributes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanEvent {

    private final String name;
    private final long timeUnixNano;
    private final List<Attribute> attributes;

    /**
     * Creates a span event with the current time.
     *
     * @param name the event name
     */
    public SpanEvent(String name) {
        this(name, System.currentTimeMillis() * 1_000_000L);
    }

    /**
     * Creates a span event with a specific timestamp.
     *
     * @param name the event name
     * @param timeUnixNano the timestamp in nanoseconds since Unix epoch
     */
    public SpanEvent(String name, long timeUnixNano) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        this.name = name;
        this.timeUnixNano = timeUnixNano;
        this.attributes = new ArrayList<Attribute>();
    }

    /**
     * Returns the event name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the timestamp in nanoseconds since Unix epoch.
     */
    public long getTimeUnixNano() {
        return timeUnixNano;
    }

    /**
     * Returns an unmodifiable view of the event attributes.
     */
    public List<Attribute> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    /**
     * Adds an attribute to this event.
     *
     * @param attribute the attribute to add
     * @return this event for chaining
     */
    public SpanEvent addAttribute(Attribute attribute) {
        if (attribute != null) {
            attributes.add(attribute);
        }
        return this;
    }

    /**
     * Adds a string attribute to this event.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this event for chaining
     */
    public SpanEvent addAttribute(String key, String value) {
        return addAttribute(Attribute.string(key, value));
    }

    /**
     * Adds a boolean attribute to this event.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this event for chaining
     */
    public SpanEvent addAttribute(String key, boolean value) {
        return addAttribute(Attribute.bool(key, value));
    }

    /**
     * Adds an integer attribute to this event.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this event for chaining
     */
    public SpanEvent addAttribute(String key, long value) {
        return addAttribute(Attribute.integer(key, value));
    }

    /**
     * Adds a double attribute to this event.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this event for chaining
     */
    public SpanEvent addAttribute(String key, double value) {
        return addAttribute(Attribute.doubleValue(key, value));
    }

    @Override
    public String toString() {
        return "SpanEvent[" + name + " at " + timeUnixNano + "]";
    }

}

