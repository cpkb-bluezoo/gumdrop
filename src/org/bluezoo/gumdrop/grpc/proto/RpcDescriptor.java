/*
 * RpcDescriptor.java
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
 * Descriptor for a gRPC RPC method.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RpcDescriptor {

    private final String name;
    private final String inputTypeName;
    private final String outputTypeName;
    private final boolean clientStreaming;
    private final boolean serverStreaming;

    private RpcDescriptor(Builder b) {
        this.name = b.name;
        this.inputTypeName = b.inputTypeName;
        this.outputTypeName = b.outputTypeName;
        this.clientStreaming = b.clientStreaming;
        this.serverStreaming = b.serverStreaming;
    }

    public String getName() {
        return name;
    }

    public String getInputTypeName() {
        return inputTypeName;
    }

    public String getOutputTypeName() {
        return outputTypeName;
    }

    public boolean isClientStreaming() {
        return clientStreaming;
    }

    public boolean isServerStreaming() {
        return serverStreaming;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String inputTypeName;
        private String outputTypeName;
        private boolean clientStreaming;
        private boolean serverStreaming;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder inputTypeName(String inputTypeName) {
            this.inputTypeName = inputTypeName;
            return this;
        }

        public Builder outputTypeName(String outputTypeName) {
            this.outputTypeName = outputTypeName;
            return this;
        }

        public Builder clientStreaming(boolean clientStreaming) {
            this.clientStreaming = clientStreaming;
            return this;
        }

        public Builder serverStreaming(boolean serverStreaming) {
            this.serverStreaming = serverStreaming;
            return this;
        }

        public RpcDescriptor build() {
            return new RpcDescriptor(this);
        }
    }
}
