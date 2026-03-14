/*
 * MessageDescriptor.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Descriptor for a protobuf message type.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageDescriptor {

    private final String name;
    private final String fullName;
    private final List<FieldDescriptor> fields;
    private final Map<Integer, FieldDescriptor> fieldsByNumber;
    private final Map<String, MessageDescriptor> nestedMessages;
    private final Map<String, EnumDescriptor> nestedEnums;

    private MessageDescriptor(Builder b) {
        this.name = b.name;
        this.fullName = b.fullName;
        this.fields = Collections.unmodifiableList(new ArrayList<>(b.fields));
        Map<Integer, FieldDescriptor> byNum = new HashMap<>();
        for (FieldDescriptor f : fields) {
            byNum.put(f.getNumber(), f);
        }
        this.fieldsByNumber = Collections.unmodifiableMap(byNum);
        this.nestedMessages = Collections.unmodifiableMap(new HashMap<>(b.nestedMessages));
        this.nestedEnums = Collections.unmodifiableMap(new HashMap<>(b.nestedEnums));
    }

    public String getName() {
        return name;
    }

    public String getFullName() {
        return fullName;
    }

    public List<FieldDescriptor> getFields() {
        return fields;
    }

    public FieldDescriptor getFieldByNumber(int number) {
        return fieldsByNumber.get(number);
    }

    public Map<String, MessageDescriptor> getNestedMessages() {
        return nestedMessages;
    }

    public Map<String, EnumDescriptor> getNestedEnums() {
        return nestedEnums;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String fullName;
        private final List<FieldDescriptor> fields = new ArrayList<>();
        private final Map<String, MessageDescriptor> nestedMessages = new HashMap<>();
        private final Map<String, EnumDescriptor> nestedEnums = new HashMap<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder addField(FieldDescriptor field) {
            this.fields.add(field);
            return this;
        }

        public Builder addNestedMessage(MessageDescriptor msg) {
            this.nestedMessages.put(msg.getName(), msg);
            return this;
        }

        public Builder addNestedEnum(EnumDescriptor enm) {
            this.nestedEnums.put(enm.getName(), enm);
            return this;
        }

        public MessageDescriptor build() {
            return new MessageDescriptor(this);
        }
    }
}
