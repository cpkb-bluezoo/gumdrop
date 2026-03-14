/*
 * ProtoFile.java
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
 * Descriptor for a parsed .proto file (Proto model).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoFile {

    private final String packageName;
    private final String syntax;
    private final List<MessageDescriptor> messages;
    private final List<EnumDescriptor> enums;
    private final List<ServiceDescriptor> services;
    private final Map<String, MessageDescriptor> messagesByFullName;
    private final Map<String, EnumDescriptor> enumsByFullName;
    private final Map<String, ServiceDescriptor> servicesByFullName;

    private ProtoFile(Builder b) {
        this.packageName = b.packageName;
        this.syntax = b.syntax;
        this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.enums = Collections.unmodifiableList(new ArrayList<>(b.enums));
        this.services = Collections.unmodifiableList(new ArrayList<>(b.services));
        Map<String, MessageDescriptor> msgMap = new HashMap<>();
        for (MessageDescriptor m : messages) {
            msgMap.put(m.getFullName(), m);
        }
        this.messagesByFullName = Collections.unmodifiableMap(msgMap);
        Map<String, EnumDescriptor> enumMap = new HashMap<>();
        for (EnumDescriptor e : enums) {
            enumMap.put(e.getFullName(), e);
        }
        this.enumsByFullName = Collections.unmodifiableMap(enumMap);
        Map<String, ServiceDescriptor> svcMap = new HashMap<>();
        for (ServiceDescriptor s : services) {
            svcMap.put(s.getFullName(), s);
        }
        this.servicesByFullName = Collections.unmodifiableMap(svcMap);
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSyntax() {
        return syntax;
    }

    public List<MessageDescriptor> getMessages() {
        return messages;
    }

    public List<EnumDescriptor> getEnums() {
        return enums;
    }

    public List<ServiceDescriptor> getServices() {
        return services;
    }

    public MessageDescriptor getMessage(String fullName) {
        return messagesByFullName.get(fullName);
    }

    public EnumDescriptor getEnum(String fullName) {
        return enumsByFullName.get(fullName);
    }

    public ServiceDescriptor getService(String fullName) {
        return servicesByFullName.get(fullName);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String packageName = "";
        private String syntax = "proto3";
        private final List<MessageDescriptor> messages = new ArrayList<>();
        private final List<EnumDescriptor> enums = new ArrayList<>();
        private final List<ServiceDescriptor> services = new ArrayList<>();

        public Builder packageName(String packageName) {
            this.packageName = packageName != null ? packageName : "";
            return this;
        }

        public Builder syntax(String syntax) {
            this.syntax = syntax != null ? syntax : "proto3";
            return this;
        }

        public Builder addMessage(MessageDescriptor msg) {
            this.messages.add(msg);
            return this;
        }

        public Builder addEnum(EnumDescriptor enm) {
            this.enums.add(enm);
            return this;
        }

        public Builder addService(ServiceDescriptor svc) {
            this.services.add(svc);
            return this;
        }

        public ProtoFile build() {
            return new ProtoFile(this);
        }
    }
}
