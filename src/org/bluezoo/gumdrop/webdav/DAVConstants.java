/*
 * DAVConstants.java
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

package org.bluezoo.gumdrop.webdav;

/**
 * WebDAV (RFC 2518) constants.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DAVConstants {

    private DAVConstants() {
        // Utility class
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Namespace
    // ─────────────────────────────────────────────────────────────────────────

    /** The DAV: namespace URI */
    static final String NAMESPACE = "DAV:";

    /** Default namespace prefix for DAV elements */
    static final String PREFIX = "D";

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Headers
    // ─────────────────────────────────────────────────────────────────────────

    static final String HEADER_DAV = "DAV";
    static final String HEADER_DEPTH = "Depth";
    static final String HEADER_DESTINATION = "Destination";
    static final String HEADER_IF = "If";
    static final String HEADER_LOCK_TOKEN = "Lock-Token";
    static final String HEADER_OVERWRITE = "Overwrite";
    static final String HEADER_TIMEOUT = "Timeout";

    // ─────────────────────────────────────────────────────────────────────────
    // Depth Values
    // ─────────────────────────────────────────────────────────────────────────

    static final int DEPTH_0 = 0;
    static final int DEPTH_1 = 1;
    static final int DEPTH_INFINITY = Integer.MAX_VALUE;

    // ─────────────────────────────────────────────────────────────────────────
    // Element Names
    // ─────────────────────────────────────────────────────────────────────────

    // Request elements
    static final String ELEM_PROPFIND = "propfind";
    static final String ELEM_PROPERTYUPDATE = "propertyupdate";
    static final String ELEM_LOCKINFO = "lockinfo";

    // PROPFIND request types
    static final String ELEM_ALLPROP = "allprop";
    static final String ELEM_INCLUDE = "include";
    static final String ELEM_PROPNAME = "propname";
    static final String ELEM_PROP = "prop";

    // PROPPATCH elements
    static final String ELEM_SET = "set";
    static final String ELEM_REMOVE = "remove";

    // Response elements
    static final String ELEM_MULTISTATUS = "multistatus";
    static final String ELEM_RESPONSE = "response";
    static final String ELEM_HREF = "href";
    static final String ELEM_PROPSTAT = "propstat";
    static final String ELEM_STATUS = "status";
    static final String ELEM_ERROR = "error";
    static final String ELEM_RESPONSEDESCRIPTION = "responsedescription";

    // Lock elements
    static final String ELEM_LOCKSCOPE = "lockscope";
    static final String ELEM_LOCKTYPE = "locktype";
    static final String ELEM_OWNER = "owner";
    static final String ELEM_LOCKDISCOVERY = "lockdiscovery";
    static final String ELEM_ACTIVELOCK = "activelock";
    static final String ELEM_LOCKTOKEN = "locktoken";
    static final String ELEM_TIMEOUT = "timeout";
    static final String ELEM_DEPTH = "depth";
    static final String ELEM_LOCKROOT = "lockroot";
    static final String ELEM_EXCLUSIVE = "exclusive";
    static final String ELEM_SHARED = "shared";
    static final String ELEM_WRITE = "write";
    static final String ELEM_SUPPORTEDLOCK = "supportedlock";
    static final String ELEM_LOCKENTRY = "lockentry";
    static final String ELEM_COLLECTION = "collection";

    // ─────────────────────────────────────────────────────────────────────────
    // Live Properties (RFC 2518 Section 13)
    // ─────────────────────────────────────────────────────────────────────────

    static final String PROP_CREATIONDATE = "creationdate";
    static final String PROP_DISPLAYNAME = "displayname";
    static final String PROP_GETCONTENTLANGUAGE = "getcontentlanguage";
    static final String PROP_GETCONTENTLENGTH = "getcontentlength";
    static final String PROP_GETCONTENTTYPE = "getcontenttype";
    static final String PROP_GETETAG = "getetag";
    static final String PROP_GETLASTMODIFIED = "getlastmodified";
    static final String PROP_LOCKDISCOVERY = "lockdiscovery";
    static final String PROP_RESOURCETYPE = "resourcetype";
    static final String PROP_SOURCE = "source";
    static final String PROP_SUPPORTEDLOCK = "supportedlock";

    // ─────────────────────────────────────────────────────────────────────────
    // Timeout
    // ─────────────────────────────────────────────────────────────────────────

    static final String TIMEOUT_INFINITE = "Infinite";
    static final String TIMEOUT_SECOND_PREFIX = "Second-";
    static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 3600;
    static final long MAX_LOCK_TIMEOUT_SECONDS = 604800;

    // ─────────────────────────────────────────────────────────────────────────
    // Content Types
    // ─────────────────────────────────────────────────────────────────────────

    static final String CONTENT_TYPE_XML = "application/xml; charset=utf-8";

    // ─────────────────────────────────────────────────────────────────────────
    // Lock Token URI Scheme
    // ─────────────────────────────────────────────────────────────────────────

    static final String LOCK_TOKEN_SCHEME = "opaquelocktoken:";
}
