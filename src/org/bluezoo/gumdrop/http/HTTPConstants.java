/*
 * HTTPConstants.java
 * Copyright (C) 2005 Chris Burdess
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

import java.util.Map;
import java.util.TreeMap;

/**
 * HTTP status code reason phrases per RFC 9110 section 15.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HTTPConstants {

    public static final Map<Integer, String> messages;

    static {
        messages = new TreeMap<Integer, String>();
        // 1xx Informational (RFC 9110 section 15.2)
        messages.put(100, "Continue");
        messages.put(101, "Switching Protocols");
        messages.put(102, "Processing");
        messages.put(103, "Early Hints");
        // 2xx Successful (RFC 9110 section 15.3)
        messages.put(200, "OK");
        messages.put(201, "Created");
        messages.put(202, "Accepted");
        messages.put(203, "Non-Authoritative Information");
        messages.put(204, "No Content");
        messages.put(205, "Reset Content");
        messages.put(206, "Partial Content");
        messages.put(207, "Multi-Status");
        messages.put(208, "Already Reported");
        messages.put(226, "IM Used");
        // 3xx Redirection (RFC 9110 section 15.4)
        messages.put(300, "Multiple Choices");
        messages.put(301, "Moved Permanently");
        messages.put(302, "Found");
        messages.put(303, "See Other");
        messages.put(304, "Not Modified");
        messages.put(305, "Use Proxy");
        messages.put(307, "Temporary Redirect");
        messages.put(308, "Permanent Redirect");
        // 4xx Client Error (RFC 9110 section 15.5)
        messages.put(400, "Bad Request");
        messages.put(401, "Unauthorized");
        messages.put(402, "Payment Required");
        messages.put(403, "Forbidden");
        messages.put(404, "Not Found");
        messages.put(405, "Method Not Allowed");
        messages.put(406, "Not Acceptable");
        messages.put(407, "Proxy Authentication Required");
        messages.put(408, "Request Timeout");
        messages.put(409, "Conflict");
        messages.put(410, "Gone");
        messages.put(411, "Length Required");
        messages.put(412, "Precondition Failed");
        messages.put(413, "Content Too Large");       // RFC 9110 section 15.5.14
        messages.put(414, "URI Too Long");             // RFC 9110 section 15.5.15
        messages.put(415, "Unsupported Media Type");
        messages.put(416, "Range Not Satisfiable");    // RFC 9110 section 15.5.17
        messages.put(417, "Expectation Failed");
        messages.put(418, "I'm a Teapot");
        messages.put(421, "Misdirected Request");
        messages.put(422, "Unprocessable Content");    // RFC 9110 section 15.5.21
        messages.put(423, "Locked");
        messages.put(424, "Failed Dependency");
        messages.put(425, "Too Early");
        messages.put(426, "Upgrade Required");
        messages.put(428, "Precondition Required");
        messages.put(429, "Too Many Requests");
        messages.put(431, "Request Header Fields Too Large");
        messages.put(451, "Unavailable For Legal Reasons");
        // 5xx Server Error (RFC 9110 section 15.6)
        messages.put(500, "Internal Server Error");
        messages.put(501, "Not Implemented");
        messages.put(502, "Bad Gateway");
        messages.put(503, "Service Unavailable");
        messages.put(504, "Gateway Timeout");
        messages.put(505, "HTTP Version Not Supported");
        messages.put(506, "Variant Also Negotiates");
        messages.put(507, "Insufficient Storage");
        messages.put(508, "Loop Detected");
        messages.put(510, "Not Extended");
        messages.put(511, "Network Authentication Required");
    }

    public static String getMessage(int code) {
        String message = messages.get(code);
        return (message != null) ? message : "Unknown Status Code";
    }

}
