/*
 * QuotaPolicyTest.java
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

package org.bluezoo.gumdrop.quota;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QuotaPolicy} and {@link QuotaExceededException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuotaPolicyTest {

    @Test
    public void parseSizeUnits() {
        assertEquals(1234L, QuotaPolicy.parseSize("1234"));
        assertEquals(1024L, QuotaPolicy.parseSize("1KB"));
        assertEquals(2048L, QuotaPolicy.parseSize("2k"));
        assertEquals(1024L * 1024L, QuotaPolicy.parseSize("1MB"));
        assertEquals(3L * 1024L * 1024L, QuotaPolicy.parseSize("3M"));
        assertEquals(1024L * 1024L * 1024L, QuotaPolicy.parseSize("1GB"));
        assertEquals(10L * 1024L * 1024L * 1024L, QuotaPolicy.parseSize(" 10g "));
        assertEquals(1024L * 1024L * 1024L * 1024L, QuotaPolicy.parseSize("1TB"));
        assertEquals(1024L * 1024L * 1024L * 1024L, QuotaPolicy.parseSize("1T"));
    }

    @Test
    public void parseSizeUnlimited() {
        assertEquals(Quota.UNLIMITED, QuotaPolicy.parseSize("unlimited"));
        assertEquals(Quota.UNLIMITED, QuotaPolicy.parseSize("UNLIMITED"));
        assertEquals(Quota.UNLIMITED, QuotaPolicy.parseSize("-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseSizeNull() {
        QuotaPolicy.parseSize(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseSizeInvalid() {
        QuotaPolicy.parseSize("lots");
    }

    @Test
    public void formatSizeAllMagnitudes() {
        assertEquals("unlimited", QuotaPolicy.formatSize(-1));
        assertEquals("10 bytes", QuotaPolicy.formatSize(10));
        assertTrue(QuotaPolicy.formatSize(2048).endsWith("KB"));
        assertTrue(QuotaPolicy.formatSize(5L * 1024L * 1024L).endsWith("MB"));
        assertTrue(QuotaPolicy.formatSize(5L * 1024L * 1024L * 1024L).endsWith("GB"));
        assertTrue(QuotaPolicy.formatSize(5L * 1024L * 1024L * 1024L * 1024L).endsWith("TB"));
    }

    @Test
    public void policyAccessorsAndUnlimitedFlags() {
        QuotaPolicy limited = new QuotaPolicy("gold", 1000L, 50L);
        assertEquals("gold", limited.getName());
        assertEquals(1000L, limited.getStorageLimit());
        assertEquals(50L, limited.getMessageLimit());
        assertFalse(limited.isStorageUnlimited());
        assertFalse(limited.isMessageUnlimited());

        QuotaPolicy storageOnly = new QuotaPolicy("basic", 1000L);
        assertTrue(storageOnly.isMessageUnlimited());
        assertFalse(storageOnly.isStorageUnlimited());

        QuotaPolicy open = new QuotaPolicy("open", -1L);
        assertTrue(open.isStorageUnlimited());
    }

    @Test
    public void createQuotaCarriesLimitsAndSource() {
        QuotaPolicy policy = new QuotaPolicy("gold", 1000L, 50L);
        Quota quota = policy.createQuota(QuotaSource.ROLE);
        assertEquals(1000L, quota.getStorageLimit());
        assertEquals(50L, quota.getMessageLimit());
        assertEquals(QuotaSource.ROLE, quota.getSource());
        assertEquals("gold", quota.getSourceDetail());
        assertEquals(0L, quota.getStorageUsed());
    }

    @Test
    public void toStringMentionsNameAndMessages() {
        QuotaPolicy withMessages = new QuotaPolicy("gold", 1000L, 50L);
        String text = withMessages.toString();
        assertTrue(text.contains("gold"));
        assertTrue(text.contains("messages=50"));
        QuotaPolicy without = new QuotaPolicy("basic", 1000L);
        assertFalse(without.toString().contains("messages="));
    }

    @Test
    public void exceptionWithMessageOnly() {
        QuotaExceededException e = new QuotaExceededException("full");
        assertEquals("full", e.getMessage());
        assertNull(e.getUsername());
        assertNull(e.getQuota());
        assertEquals(0L, e.getRequestedBytes());
    }

    @Test
    public void exceptionWithDetails() {
        Quota quota = new Quota(1000L, 10);
        quota.setStorageUsed(900L);
        QuotaExceededException e = new QuotaExceededException("alice", quota, 500L);
        assertEquals("alice", e.getUsername());
        assertSame(quota, e.getQuota());
        assertEquals(500L, e.getRequestedBytes());
        assertTrue(e.getMessage().contains("alice"));
    }
}
