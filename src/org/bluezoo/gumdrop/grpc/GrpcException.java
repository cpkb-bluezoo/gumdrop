/*
 * GrpcException.java
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

package org.bluezoo.gumdrop.grpc;

/**
 * Exception for gRPC errors, carrying the {@linkplain GrpcStatus gRPC status}
 * the call ended with, or that Gumdrop assigned to a failure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates a new exception.
     *
     * @param status the gRPC status, see {@link GrpcStatus}
     * @param message the error message
     */
    public GrpcException(int status, String message) {
        super(describe(status, message));
        this.status = status;
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param status the gRPC status, see {@link GrpcStatus}
     * @param message the error message
     * @param cause the cause
     */
    public GrpcException(int status, String message, Throwable cause) {
        super(describe(status, message), cause);
        this.status = status;
    }

    /**
     * Returns the gRPC status.
     *
     * @return the status, see {@link GrpcStatus}
     */
    public int getStatus() {
        return status;
    }

    private static String describe(int status, String message) {
        String name = GrpcStatus.name(status);
        String prefix = "gRPC " + (name != null ? name : "status " + status);
        return (message == null || message.isEmpty()) ? prefix : prefix + ": " + message;
    }
}
