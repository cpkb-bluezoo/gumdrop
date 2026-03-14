/*
 * EnumDescriptor.java
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

package org.bluezoo.gumdrop.grpc.proto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Descriptor for a protobuf enum type.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EnumDescriptor {

    private final String name;
    private final String fullName;
    private final Map<Integer, String> valuesByNumber;
    private final Map<String, Integer> valuesByName;

    private EnumDescriptor(Builder b) {
        this.name = b.name;
        this.fullName = b.fullName;
        this.valuesByNumber = Collections.unmodifiableMap(new HashMap<>(b.valuesByNumber));
        Map<String, Integer> byName = new HashMap<>();
        for (Map.Entry<Integer, String> e : b.valuesByNumber.entrySet()) {
            byName.put(e.getValue(), e.getKey());
        }
        this.valuesByName = Collections.unmodifiableMap(byName);
    }

    public String getName() {
        return name;
    }

    public String getFullName() {
        return fullName;
    }

    public Map<Integer, String> getValuesByNumber() {
        return valuesByNumber;
    }

    public String getValueName(int number) {
        return valuesByNumber.get(number);
    }

    public Integer getValueNumber(String name) {
        return valuesByName.get(name);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String fullName;
        private final Map<Integer, String> valuesByNumber = new HashMap<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder addValue(int number, String name) {
            this.valuesByNumber.put(number, name);
            return this;
        }

        public EnumDescriptor build() {
            return new EnumDescriptor(this);
        }
    }
}
