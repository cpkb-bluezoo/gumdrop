/*
 * SpanLink.java
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
 * A link to a span in another trace.
 * Links are used to associate spans across trace boundaries.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanLink {

    private final SpanContext context;
    private final List<Attribute> attributes;

    /**
     * Creates a link to the specified span context.
     *
     * @param context the span context to link to
     */
    public SpanLink(SpanContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        this.context = context;
        this.attributes = new ArrayList<Attribute>();
    }

    /**
     * Returns the linked span context.
     */
    public SpanContext getContext() {
        return context;
    }

    /**
     * Returns an unmodifiable view of the link attributes.
     */
    public List<Attribute> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    /**
     * Adds an attribute to this link.
     *
     * @param attribute the attribute to add
     * @return this link for chaining
     */
    public SpanLink addAttribute(Attribute attribute) {
        if (attribute != null) {
            attributes.add(attribute);
        }
        return this;
    }

    /**
     * Adds a string attribute to this link.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this link for chaining
     */
    public SpanLink addAttribute(String key, String value) {
        return addAttribute(Attribute.string(key, value));
    }

    @Override
    public String toString() {
        return "SpanLink[" + context + "]";
    }

}

