/*
 * HTTPStatus.java
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

package org.bluezoo.gumdrop.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Symbolic enumeration of HTTP status codes.
 *
 * <p>This enum encourages using symbolic values rather than numeric codes,
 * providing type-safe status handling and convenient category-checking methods.
 *
 * <p>In addition to standard HTTP status codes, this enum includes client-side
 * pseudo-statuses for conditions detected by the client (e.g., redirect loops).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum HTTPStatus {

    // ─────────────────────────────────────────────────────────────────────────
    // Informational (1xx)
    // ─────────────────────────────────────────────────────────────────────────

    /** 100 Continue */
    CONTINUE(100),

    /** 101 Switching Protocols */
    SWITCHING_PROTOCOLS(101),

    /** 102 Processing (WebDAV) */
    PROCESSING(102),

    /** 103 Early Hints */
    EARLY_HINTS(103),

    // ─────────────────────────────────────────────────────────────────────────
    // Success (2xx)
    // ─────────────────────────────────────────────────────────────────────────

    /** 200 OK */
    OK(200),

    /** 201 Created */
    CREATED(201),

    /** 202 Accepted */
    ACCEPTED(202),

    /** 203 Non-Authoritative Information */
    NON_AUTHORITATIVE_INFORMATION(203),

    /** 204 No Content */
    NO_CONTENT(204),

    /** 205 Reset Content */
    RESET_CONTENT(205),

    /** 206 Partial Content */
    PARTIAL_CONTENT(206),

    /** 207 Multi-Status (WebDAV) */
    MULTI_STATUS(207),

    /** 208 Already Reported (WebDAV) */
    ALREADY_REPORTED(208),

    /** 226 IM Used (Delta encoding) */
    IM_USED(226),

    // ─────────────────────────────────────────────────────────────────────────
    // Redirection (3xx)
    // ─────────────────────────────────────────────────────────────────────────

    /** 300 Multiple Choices */
    MULTIPLE_CHOICES(300),

    /** 301 Moved Permanently */
    MOVED_PERMANENTLY(301),

    /** 302 Found (Previously "Moved Temporarily") */
    FOUND(302),

    /** 303 See Other */
    SEE_OTHER(303),

    /** 304 Not Modified */
    NOT_MODIFIED(304),

    /** 305 Use Proxy (Deprecated) */
    USE_PROXY(305),

    /** 307 Temporary Redirect */
    TEMPORARY_REDIRECT(307),

    /** 308 Permanent Redirect */
    PERMANENT_REDIRECT(308),

    // ─────────────────────────────────────────────────────────────────────────
    // Client Errors (4xx)
    // ─────────────────────────────────────────────────────────────────────────

    /** 400 Bad Request */
    BAD_REQUEST(400),

    /** 401 Unauthorized */
    UNAUTHORIZED(401),

    /** 402 Payment Required */
    PAYMENT_REQUIRED(402),

    /** 403 Forbidden */
    FORBIDDEN(403),

    /** 404 Not Found */
    NOT_FOUND(404),

    /** 405 Method Not Allowed */
    METHOD_NOT_ALLOWED(405),

    /** 406 Not Acceptable */
    NOT_ACCEPTABLE(406),

    /** 407 Proxy Authentication Required */
    PROXY_AUTHENTICATION_REQUIRED(407),

    /** 408 Request Timeout */
    REQUEST_TIMEOUT(408),

    /** 409 Conflict */
    CONFLICT(409),

    /** 410 Gone */
    GONE(410),

    /** 411 Length Required */
    LENGTH_REQUIRED(411),

    /** 412 Precondition Failed */
    PRECONDITION_FAILED(412),

    /** 413 Payload Too Large */
    PAYLOAD_TOO_LARGE(413),

    /** 414 URI Too Long */
    URI_TOO_LONG(414),

    /** 415 Unsupported Media Type */
    UNSUPPORTED_MEDIA_TYPE(415),

    /** 416 Range Not Satisfiable */
    RANGE_NOT_SATISFIABLE(416),

    /** 417 Expectation Failed */
    EXPECTATION_FAILED(417),

    /** 418 I'm a Teapot (RFC 2324) */
    IM_A_TEAPOT(418),

    /** 421 Misdirected Request */
    MISDIRECTED_REQUEST(421),

    /** 422 Unprocessable Entity (WebDAV) */
    UNPROCESSABLE_ENTITY(422),

    /** 423 Locked (WebDAV) */
    LOCKED(423),

    /** 424 Failed Dependency (WebDAV) */
    FAILED_DEPENDENCY(424),

    /** 425 Too Early */
    TOO_EARLY(425),

    /** 426 Upgrade Required */
    UPGRADE_REQUIRED(426),

    /** 428 Precondition Required */
    PRECONDITION_REQUIRED(428),

    /** 429 Too Many Requests */
    TOO_MANY_REQUESTS(429),

    /** 431 Request Header Fields Too Large */
    REQUEST_HEADER_FIELDS_TOO_LARGE(431),

    /** 451 Unavailable For Legal Reasons */
    UNAVAILABLE_FOR_LEGAL_REASONS(451),

    // ─────────────────────────────────────────────────────────────────────────
    // Server Errors (5xx)
    // ─────────────────────────────────────────────────────────────────────────

    /** 500 Internal Server Error */
    INTERNAL_SERVER_ERROR(500),

    /** 501 Not Implemented */
    NOT_IMPLEMENTED(501),

    /** 502 Bad Gateway */
    BAD_GATEWAY(502),

    /** 503 Service Unavailable */
    SERVICE_UNAVAILABLE(503),

    /** 504 Gateway Timeout */
    GATEWAY_TIMEOUT(504),

    /** 505 HTTP Version Not Supported */
    HTTP_VERSION_NOT_SUPPORTED(505),

    /** 506 Variant Also Negotiates */
    VARIANT_ALSO_NEGOTIATES(506),

    /** 507 Insufficient Storage (WebDAV) */
    INSUFFICIENT_STORAGE(507),

    /** 508 Loop Detected (WebDAV) */
    LOOP_DETECTED(508),

    /** 510 Not Extended */
    NOT_EXTENDED(510),

    /** 511 Network Authentication Required */
    NETWORK_AUTHENTICATION_REQUIRED(511),

    // ─────────────────────────────────────────────────────────────────────────
    // Client-side pseudo-statuses
    // ─────────────────────────────────────────────────────────────────────────

    /** Client detected a redirect loop */
    REDIRECT_LOOP(-1),

    /** Unknown status code received from server */
    UNKNOWN(-2);

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup table for fromCode()
    // ─────────────────────────────────────────────────────────────────────────

    private static final Map<Integer, HTTPStatus> BY_CODE = new HashMap<Integer, HTTPStatus>();

    static {
        for (HTTPStatus status : values()) {
            if (status.code > 0) {
                BY_CODE.put(status.code, status);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Instance fields
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The numeric HTTP status code.
     */
    public final int code;

    HTTPStatus(int code) {
        this.code = code;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Category methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if this is an informational status (1xx).
     *
     * @return true for 1xx statuses
     */
    public boolean isInformational() {
        return code >= 100 && code < 200;
    }

    /**
     * Returns true if this is a success status (2xx).
     *
     * @return true for 2xx statuses
     */
    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }

    /**
     * Returns true if this is a redirection status (3xx).
     *
     * @return true for 3xx statuses
     */
    public boolean isRedirection() {
        return code >= 300 && code < 400;
    }

    /**
     * Returns true if this is a client error status (4xx).
     *
     * @return true for 4xx statuses
     */
    public boolean isClientError() {
        return code >= 400 && code < 500;
    }

    /**
     * Returns true if this is a server error status (5xx).
     *
     * @return true for 5xx statuses
     */
    public boolean isServerError() {
        return code >= 500 && code < 600;
    }

    /**
     * Returns true if this is an error status (4xx or 5xx).
     *
     * @return true for 4xx or 5xx statuses
     */
    public boolean isError() {
        return code >= 400 && code < 600;
    }

    /**
     * Returns true if this is a client-side pseudo-status (not a real HTTP status).
     *
     * @return true for pseudo-statuses like REDIRECT_LOOP
     */
    public boolean isPseudoStatus() {
        return code < 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the HTTPStatus for the given numeric status code.
     *
     * @param statusCode the numeric HTTP status code
     * @return the corresponding HTTPStatus, or {@link #INTERNAL_SERVER_ERROR} if not recognized
     */
    public static HTTPStatus fromCode(int statusCode) {
        HTTPStatus status = BY_CODE.get(statusCode);
        if (status != null) {
            return status;
        }
        // For unrecognized codes, return 500 Internal Server Error rather than UNKNOWN
        // This ensures we always send a valid HTTP status code
        return INTERNAL_SERVER_ERROR;
    }
}

