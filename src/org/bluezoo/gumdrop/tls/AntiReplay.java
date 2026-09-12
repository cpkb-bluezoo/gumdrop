/*
 * AntiReplay.java
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

package org.bluezoo.gumdrop.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A server's early-data (0-RTT) anti-replay cache: an in-memory,
 * single-process, time-windowed deduplication of resumption ticket
 * identities, keyed by {@code SHA-256(identity)} rather than the
 * (potentially large) identity bytes themselves.
 *
 * <p>This is deliberately limited, matching hopf's own anti-replay
 * exactly -- it does <em>not</em> implement RFC 8446 section 8's full
 * anti-replay guidance (which contemplates a distributed deployment
 * sharing replay state, e.g. via a distinct signing key per instance or a
 * shared cache). A single process's own in-memory window is sufficient
 * for gumdrop's own goals here and nothing more should be assumed of it.
 * A {@link HandshakeConfig} with no {@code AntiReplay} configured accepts
 * every 0-RTT attempt unconditionally.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-8">RFC 8446 section 8</a>
 */
public final class AntiReplay {

    private final long windowMillis;
    private final Map<String, Long> seen = new HashMap<String, Long>();

    /**
     * Creates an anti-replay cache with the given freshness window --
     * typically the same value as
     * {@link HandshakeConfig#getEarlyDataFreshnessMs}, since a ticket
     * outside that window is already rejected for early data on age
     * grounds alone.
     *
     * @param windowMillis how long an accepted identity is remembered
     */
    public AntiReplay(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * Records a first-seen ticket identity for early data. Returns false
     * if this identity was already accepted within the window (a
     * replay -- 0-RTT must then be rejected, though the full handshake
     * still proceeds normally).
     *
     * @param identity the opaque ticket identity offered in {@code pre_shared_key}
     * @return true if this is the first use seen within the window
     */
    public synchronized boolean checkAndRecord(byte[] identity) {
        String key = hash(identity);
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().longValue() <= now) {
                it.remove();
            }
        }
        Long expiry = seen.get(key);
        if (expiry != null && expiry.longValue() > now) {
            return false;
        }
        seen.put(key, Long.valueOf(now + windowMillis));
        return true;
    }

    private static String hash(byte[] identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(identity);
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (int i = 0; i < raw.length; i++) {
                hex.append(Character.forDigit((raw[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(raw[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Programming error: every JDK bundles SHA-256.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

}
