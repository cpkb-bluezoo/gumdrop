/*
 * ZoneFileAccessMode.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

/**
 * Whether a zone file may be updated on disk after dynamic changes.
 */
public enum ZoneFileAccessMode {

    /** Load allowed; dynamic updates may change memory but are not written. */
    READ_ONLY,

    /** Dynamic updates and AXFR refresh may persist to the zone file path. */
    READ_WRITE
}
