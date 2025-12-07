/*
 * ErrorCategory.java
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

/**
 * Standardized error categories for telemetry across all protocols.
 * 
 * <p>These categories provide a protocol-agnostic way to classify errors,
 * enabling consistent analysis and alerting across HTTP, SMTP, IMAP, POP3,
 * FTP, and other protocols.
 * 
 * <p>Each category maps to an OpenTelemetry semantic convention where applicable.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum ErrorCategory {

    // -- Connection/Transport Errors --

    /**
     * Connection establishment failed (TCP connect, DNS resolution).
     * Examples: Connection refused, host unreachable, timeout during connect.
     */
    CONNECTION_ERROR("connection", "error.type.connection"),

    /**
     * Connection lost unexpectedly (peer closed, reset, timeout).
     * Examples: Connection reset by peer, broken pipe, idle timeout.
     */
    CONNECTION_LOST("connection_lost", "error.type.connection_lost"),

    /**
     * Network I/O error during read or write.
     * Examples: Socket read/write error, network unreachable.
     */
    IO_ERROR("io", "error.type.io"),

    /**
     * TLS/SSL handshake or encryption error.
     * Examples: Certificate validation failed, protocol mismatch, cipher error.
     */
    TLS_ERROR("tls", "error.type.tls"),

    // -- Authentication/Authorization Errors --

    /**
     * Authentication failed (invalid credentials).
     * Examples: Wrong password, unknown user, expired credentials.
     */
    AUTHENTICATION_FAILED("auth_failed", "error.type.authentication"),

    /**
     * Authorization denied (insufficient permissions).
     * Examples: Access denied, permission denied, quota exceeded.
     */
    AUTHORIZATION_DENIED("auth_denied", "error.type.authorization"),

    // -- Protocol Errors --

    /**
     * Protocol syntax or format error.
     * Examples: Malformed command, invalid header, parse error.
     */
    PROTOCOL_ERROR("protocol", "error.type.protocol"),

    /**
     * Invalid command or operation for current state.
     * Examples: Command out of sequence, operation not allowed in state.
     */
    STATE_ERROR("state", "error.type.state"),

    /**
     * Command or operation not supported.
     * Examples: Unknown command, unimplemented feature.
     */
    NOT_SUPPORTED("not_supported", "error.type.not_supported"),

    // -- Resource Errors --

    /**
     * Resource not found.
     * Examples: File not found, mailbox not found, message not found.
     */
    NOT_FOUND("not_found", "error.type.not_found"),

    /**
     * Resource already exists.
     * Examples: Mailbox already exists, duplicate message.
     */
    ALREADY_EXISTS("already_exists", "error.type.already_exists"),

    /**
     * Resource limit or quota exceeded.
     * Examples: Mailbox full, message size limit, rate limit.
     */
    LIMIT_EXCEEDED("limit_exceeded", "error.type.limit_exceeded"),

    /**
     * Storage or filesystem error.
     * Examples: Disk full, I/O error, permission denied.
     */
    STORAGE_ERROR("storage", "error.type.storage"),

    // -- Policy Errors --

    /**
     * Policy violation or rejection.
     * Examples: Spam rejected, virus detected, content filtered.
     */
    POLICY_VIOLATION("policy", "error.type.policy"),

    /**
     * Rate limiting or throttling.
     * Examples: Too many connections, too many requests.
     */
    RATE_LIMITED("rate_limited", "error.type.rate_limited"),

    // -- Server Errors --

    /**
     * Internal server error.
     * Examples: Unexpected exception, configuration error.
     */
    INTERNAL_ERROR("internal", "error.type.internal"),

    /**
     * Server temporarily unavailable.
     * Examples: Service overloaded, maintenance mode.
     */
    TEMPORARILY_UNAVAILABLE("unavailable", "error.type.unavailable"),

    /**
     * Request timeout.
     * Examples: Command timeout, response timeout.
     */
    TIMEOUT("timeout", "error.type.timeout"),

    // -- Client Errors --

    /**
     * Client-side error (bad request).
     * Examples: Invalid parameters, missing required data.
     */
    CLIENT_ERROR("client", "error.type.client"),

    /**
     * Unknown or uncategorized error.
     */
    UNKNOWN("unknown", "error.type.unknown");

    private final String code;
    private final String attributeKey;

    ErrorCategory(String code, String attributeKey) {
        this.code = code;
        this.attributeKey = attributeKey;
    }

    /**
     * Returns the error code string.
     *
     * @return the error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the telemetry attribute key for this category.
     *
     * @return the attribute key
     */
    public String getAttributeKey() {
        return attributeKey;
    }

    /**
     * Returns the OpenTelemetry semantic convention attribute value.
     *
     * @return the attribute value
     */
    public String getAttributeValue() {
        return code;
    }

    // -- Helper methods for mapping protocol errors --

    /**
     * Maps an HTTP status code to an error category.
     *
     * @param statusCode the HTTP status code
     * @return the error category, or null if not an error
     */
    public static ErrorCategory fromHttpStatus(int statusCode) {
        if (statusCode < 400) {
            return null;
        }
        switch (statusCode) {
            case 400:
                return CLIENT_ERROR;
            case 401:
                return AUTHENTICATION_FAILED;
            case 403:
                return AUTHORIZATION_DENIED;
            case 404:
                return NOT_FOUND;
            case 405:
            case 501:
                return NOT_SUPPORTED;
            case 408:
                return TIMEOUT;
            case 409:
                return ALREADY_EXISTS;
            case 413:
            case 429:
                return LIMIT_EXCEEDED;
            case 500:
                return INTERNAL_ERROR;
            case 502:
            case 503:
            case 504:
                return TEMPORARILY_UNAVAILABLE;
            default:
                if (statusCode >= 400 && statusCode < 500) {
                    return CLIENT_ERROR;
                }
                return INTERNAL_ERROR;
        }
    }

    /**
     * Maps an SMTP reply code to an error category.
     *
     * @param replyCode the SMTP reply code (4xx or 5xx)
     * @return the error category, or null if not an error
     */
    public static ErrorCategory fromSmtpReplyCode(int replyCode) {
        if (replyCode < 400) {
            return null;
        }
        // Enhanced status codes (X.Y.Z) provide more detail
        // but basic mapping from reply codes:
        switch (replyCode) {
            case 421:
                return TEMPORARILY_UNAVAILABLE;
            case 450:
            case 451:
                return TEMPORARILY_UNAVAILABLE;
            case 452:
                return LIMIT_EXCEEDED;
            case 500:
            case 501:
            case 502:
            case 503:
                return PROTOCOL_ERROR;
            case 504:
                return NOT_SUPPORTED;
            case 530:
            case 535:
                return AUTHENTICATION_FAILED;
            case 550:
                return NOT_FOUND; // Or POLICY_VIOLATION depending on context
            case 551:
            case 552:
                return LIMIT_EXCEEDED;
            case 553:
                return CLIENT_ERROR;
            case 554:
                return POLICY_VIOLATION;
            default:
                if (replyCode >= 400 && replyCode < 500) {
                    return TEMPORARILY_UNAVAILABLE;
                }
                return INTERNAL_ERROR;
        }
    }

    /**
     * Maps an FTP reply code to an error category.
     *
     * @param replyCode the FTP reply code (4xx or 5xx)
     * @return the error category, or null if not an error
     */
    public static ErrorCategory fromFtpReplyCode(int replyCode) {
        if (replyCode < 400) {
            return null;
        }
        switch (replyCode) {
            case 421:
                return TEMPORARILY_UNAVAILABLE;
            case 425:
            case 426:
                return CONNECTION_ERROR;
            case 430:
            case 530:
            case 532:
                return AUTHENTICATION_FAILED;
            case 450:
                return TEMPORARILY_UNAVAILABLE;
            case 451:
            case 452:
                return STORAGE_ERROR;
            case 500:
            case 501:
            case 502:
            case 503:
                return PROTOCOL_ERROR;
            case 504:
                return NOT_SUPPORTED;
            case 550:
                return NOT_FOUND;
            case 551:
            case 552:
            case 553:
                return STORAGE_ERROR;
            default:
                if (replyCode >= 400 && replyCode < 500) {
                    return TEMPORARILY_UNAVAILABLE;
                }
                return INTERNAL_ERROR;
        }
    }

    /**
     * Maps an exception type to an error category.
     *
     * @param exception the exception
     * @return the error category
     */
    public static ErrorCategory fromException(Throwable exception) {
        if (exception == null) {
            return UNKNOWN;
        }

        String className = exception.getClass().getName();
        String message = exception.getMessage();

        // Connection errors
        if (className.contains("ConnectException") ||
            className.contains("NoRouteToHostException") ||
            className.contains("UnknownHostException")) {
            return CONNECTION_ERROR;
        }

        // I/O errors
        if (className.contains("SocketException") ||
            className.contains("EOFException")) {
            if (message != null && (message.contains("reset") || 
                                    message.contains("Broken pipe"))) {
                return CONNECTION_LOST;
            }
            return IO_ERROR;
        }

        // TLS errors
        if (className.contains("SSL") || className.contains("Certificate")) {
            return TLS_ERROR;
        }

        // Timeout errors
        if (className.contains("Timeout") ||
            (message != null && message.toLowerCase().contains("timeout"))) {
            return TIMEOUT;
        }

        // File/resource errors
        if (className.contains("FileNotFoundException") ||
            className.contains("NoSuchFile")) {
            return NOT_FOUND;
        }

        if (className.contains("FileAlreadyExists")) {
            return ALREADY_EXISTS;
        }

        // Auth errors
        if (className.contains("Auth") || className.contains("Login") ||
            className.contains("Credential")) {
            return AUTHENTICATION_FAILED;
        }

        // Protocol errors
        if (className.contains("Protocol") || className.contains("Parse") ||
            className.contains("Syntax")) {
            return PROTOCOL_ERROR;
        }

        // Default to internal error for unexpected exceptions
        return INTERNAL_ERROR;
    }
}

