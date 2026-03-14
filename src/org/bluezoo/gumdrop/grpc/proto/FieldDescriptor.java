/*
 * FieldDescriptor.java
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

/**
 * Descriptor for a protobuf field.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FieldDescriptor {

    private final int number;
    private final String name;
    private final FieldType type;
    private final boolean repeated;
    private final boolean optional;
    private final String messageTypeName;
    private final String enumTypeName;
    private final String keyTypeName;
    private final String valueTypeName;

    private FieldDescriptor(Builder b) {
        this.number = b.number;
        this.name = b.name;
        this.type = b.type;
        this.repeated = b.repeated;
        this.optional = b.optional;
        this.messageTypeName = b.messageTypeName;
        this.enumTypeName = b.enumTypeName;
        this.keyTypeName = b.keyTypeName;
        this.valueTypeName = b.valueTypeName;
    }

    public int getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public FieldType getType() {
        return type;
    }

    public boolean isRepeated() {
        return repeated;
    }

    public boolean isOptional() {
        return optional;
    }

    /**
     * Returns the fully qualified message type name for MESSAGE fields, or null.
     */
    public String getMessageTypeName() {
        return messageTypeName;
    }

    /**
     * Returns the fully qualified enum type name for ENUM fields, or null.
     */
    public String getEnumTypeName() {
        return enumTypeName;
    }

    /**
     * Returns the key type for MAP fields, or null.
     */
    public String getKeyTypeName() {
        return keyTypeName;
    }

    /**
     * Returns the value type for MAP fields, or null.
     */
    public String getValueTypeName() {
        return valueTypeName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int number;
        private String name;
        private FieldType type;
        private boolean repeated;
        private boolean optional;
        private String messageTypeName;
        private String enumTypeName;
        private String keyTypeName;
        private String valueTypeName;

        public Builder number(int number) {
            this.number = number;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(FieldType type) {
            this.type = type;
            return this;
        }

        public Builder repeated(boolean repeated) {
            this.repeated = repeated;
            return this;
        }

        public Builder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        public Builder messageTypeName(String messageTypeName) {
            this.messageTypeName = messageTypeName;
            return this;
        }

        public Builder enumTypeName(String enumTypeName) {
            this.enumTypeName = enumTypeName;
            return this;
        }

        public Builder keyTypeName(String keyTypeName) {
            this.keyTypeName = keyTypeName;
            return this;
        }

        public Builder valueTypeName(String valueTypeName) {
            this.valueTypeName = valueTypeName;
            return this;
        }

        public FieldDescriptor build() {
            return new FieldDescriptor(this);
        }
    }
}
