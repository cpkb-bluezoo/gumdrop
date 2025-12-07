/*
 * QuotaTest.java
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
 * Unit tests for {@link Quota}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuotaTest {

    @Test
    public void testCreation() {
        Quota quota = new Quota(1000000L, 1000);
        
        assertEquals(1000000L, quota.getStorageLimit());
        assertEquals(1000, quota.getMessageLimit());
        assertEquals(0, quota.getStorageUsed());
        assertEquals(0, quota.getMessageCount());
    }
    
    @Test
    public void testUnlimitedQuota() {
        Quota quota = Quota.unlimited();
        
        assertEquals(Quota.UNLIMITED, quota.getStorageLimit());
        assertEquals(Quota.UNLIMITED, quota.getMessageLimit());
        assertTrue(quota.isStorageUnlimited());
        assertTrue(quota.isMessageUnlimited());
        assertEquals(QuotaSource.NONE, quota.getSource());
    }
    
    @Test
    public void testCopyConstructor() {
        Quota original = new Quota(500000L, 500);
        original.setStorageUsed(250000L);
        original.setMessageCount(100);
        original.setSource(QuotaSource.ROLE, "admin");
        
        Quota copy = new Quota(original);
        
        // Limits should be copied
        assertEquals(original.getStorageLimit(), copy.getStorageLimit());
        assertEquals(original.getMessageLimit(), copy.getMessageLimit());
        
        // Usage should be reset
        assertEquals(0, copy.getStorageUsed());
        assertEquals(0, copy.getMessageCount());
        
        // Source should be copied
        assertEquals(QuotaSource.ROLE, copy.getSource());
        assertEquals("admin", copy.getSourceDetail());
    }
    
    @Test
    public void testStorageUsage() {
        Quota quota = new Quota(1000000L, -1);
        
        quota.setStorageUsed(500000L);
        assertEquals(500000L, quota.getStorageUsed());
        
        quota.addStorageUsed(100000L);
        assertEquals(600000L, quota.getStorageUsed());
        
        quota.subtractStorageUsed(50000L);
        assertEquals(550000L, quota.getStorageUsed());
    }
    
    @Test
    public void testMessageCount() {
        Quota quota = new Quota(-1, 1000);
        
        quota.setMessageCount(100);
        assertEquals(100, quota.getMessageCount());
        
        quota.incrementMessageCount();
        assertEquals(101, quota.getMessageCount());
        
        quota.decrementMessageCount();
        assertEquals(100, quota.getMessageCount());
    }
    
    @Test
    public void testStorageExceeded() {
        Quota quota = new Quota(1000L, -1);
        
        quota.setStorageUsed(500L);
        assertFalse(quota.isStorageExceeded());
        
        quota.setStorageUsed(1000L);
        assertTrue(quota.isStorageExceeded());
        
        quota.setStorageUsed(1500L);
        assertTrue(quota.isStorageExceeded());
    }
    
    @Test
    public void testMessageLimitExceeded() {
        Quota quota = new Quota(-1, 100);
        
        quota.setMessageCount(50);
        assertFalse(quota.isMessageLimitExceeded());
        
        quota.setMessageCount(100);
        assertTrue(quota.isMessageLimitExceeded());
        
        quota.setMessageCount(150);
        assertTrue(quota.isMessageLimitExceeded());
    }
    
    @Test
    public void testUnlimitedNotExceeded() {
        Quota quota = Quota.unlimited();
        
        quota.setStorageUsed(Long.MAX_VALUE - 1);
        quota.setMessageCount(Long.MAX_VALUE - 1);
        
        assertFalse(quota.isStorageExceeded());
        assertFalse(quota.isMessageLimitExceeded());
    }
    
    @Test
    public void testStorageRemaining() {
        Quota quota = new Quota(1000L, -1);
        
        assertEquals(1000L, quota.getStorageRemaining());
        
        quota.setStorageUsed(400L);
        assertEquals(600L, quota.getStorageRemaining());
        
        quota.setStorageUsed(1200L); // Over limit
        assertEquals(0L, quota.getStorageRemaining());
    }
    
    @Test
    public void testStorageRemainingUnlimited() {
        Quota quota = new Quota(Quota.UNLIMITED, -1);
        
        assertEquals(Long.MAX_VALUE, quota.getStorageRemaining());
    }
    
    @Test
    public void testMessagesRemaining() {
        Quota quota = new Quota(-1, 100);
        
        assertEquals(100L, quota.getMessagesRemaining());
        
        quota.setMessageCount(40);
        assertEquals(60L, quota.getMessagesRemaining());
        
        quota.setMessageCount(120); // Over limit
        assertEquals(0L, quota.getMessagesRemaining());
    }
    
    @Test
    public void testMessagesRemainingUnlimited() {
        Quota quota = new Quota(-1, Quota.UNLIMITED);
        
        assertEquals(Long.MAX_VALUE, quota.getMessagesRemaining());
    }
    
    @Test
    public void testStoragePercentUsed() {
        Quota quota = new Quota(1000L, -1);
        
        assertEquals(0, quota.getStoragePercentUsed());
        
        quota.setStorageUsed(250L);
        assertEquals(25, quota.getStoragePercentUsed());
        
        quota.setStorageUsed(500L);
        assertEquals(50, quota.getStoragePercentUsed());
        
        quota.setStorageUsed(1000L);
        assertEquals(100, quota.getStoragePercentUsed());
        
        // Over 100% should be capped at 100
        quota.setStorageUsed(1500L);
        assertEquals(100, quota.getStoragePercentUsed());
    }
    
    @Test
    public void testStoragePercentUsedUnlimited() {
        Quota quota = Quota.unlimited();
        
        quota.setStorageUsed(1000000L);
        assertEquals(0, quota.getStoragePercentUsed());
    }
    
    @Test
    public void testMessagePercentUsed() {
        Quota quota = new Quota(-1, 100);
        
        assertEquals(0, quota.getMessagePercentUsed());
        
        quota.setMessageCount(50);
        assertEquals(50, quota.getMessagePercentUsed());
        
        quota.setMessageCount(100);
        assertEquals(100, quota.getMessagePercentUsed());
    }
    
    @Test
    public void testCanAddStorage() {
        Quota quota = new Quota(1000L, -1);
        quota.setStorageUsed(800L);
        
        assertTrue(quota.canAddStorage(100L));
        assertTrue(quota.canAddStorage(200L));
        assertFalse(quota.canAddStorage(201L));
        assertFalse(quota.canAddStorage(500L));
    }
    
    @Test
    public void testCanAddStorageUnlimited() {
        Quota quota = Quota.unlimited();
        
        assertTrue(quota.canAddStorage(Long.MAX_VALUE));
    }
    
    @Test
    public void testCanAddMessage() {
        Quota quota = new Quota(-1, 100);
        quota.setMessageCount(99);
        
        assertTrue(quota.canAddMessage());
        
        quota.setMessageCount(100);
        assertFalse(quota.canAddMessage());
    }
    
    @Test
    public void testCanAddMessageUnlimited() {
        Quota quota = Quota.unlimited();
        quota.setMessageCount(Long.MAX_VALUE - 1);
        
        assertTrue(quota.canAddMessage());
    }
    
    @Test
    public void testSubtractDoesNotGoBelowZero() {
        Quota quota = new Quota(1000L, -1);
        quota.setStorageUsed(100L);
        
        quota.subtractStorageUsed(200L);
        assertEquals(0, quota.getStorageUsed());
    }
    
    @Test
    public void testDecrementDoesNotGoBelowZero() {
        Quota quota = new Quota(-1, 100);
        quota.setMessageCount(0);
        
        quota.decrementMessageCount();
        assertEquals(0, quota.getMessageCount());
    }
    
    @Test
    public void testStorageLimitKB() {
        Quota quota = new Quota(1048576L, -1); // 1MB
        
        assertEquals(1024L, quota.getStorageLimitKB());
    }
    
    @Test
    public void testStorageUsedKB() {
        Quota quota = new Quota(1048576L, -1);
        quota.setStorageUsed(524288L); // 512KB
        
        assertEquals(512L, quota.getStorageUsedKB());
    }
    
    @Test
    public void testStorageLimitKBUnlimited() {
        Quota quota = Quota.unlimited();
        
        assertEquals(Quota.UNLIMITED, quota.getStorageLimitKB());
    }
    
    @Test
    public void testSource() {
        Quota quota = new Quota(1000L, 100);
        
        assertEquals(QuotaSource.NONE, quota.getSource());
        assertNull(quota.getSourceDetail());
        
        quota.setSource(QuotaSource.USER, "john@example.com");
        assertEquals(QuotaSource.USER, quota.getSource());
        assertEquals("john@example.com", quota.getSourceDetail());
        
        quota.setSource(QuotaSource.ROLE, "admin");
        assertEquals(QuotaSource.ROLE, quota.getSource());
        assertEquals("admin", quota.getSourceDetail());
    }
    
    @Test
    public void testToString() {
        Quota quota = new Quota(1000000L, 1000);
        quota.setStorageUsed(500000L);
        quota.setMessageCount(250);
        
        String str = quota.toString();
        assertTrue(str.contains("Quota"));
        assertTrue(str.contains("500000/1000000"));
        assertTrue(str.contains("250/1000"));
    }
    
    @Test
    public void testToStringUnlimited() {
        Quota quota = Quota.unlimited();
        
        String str = quota.toString();
        assertTrue(str.contains("unlimited"));
    }
    
    @Test
    public void testToStringWithSource() {
        Quota quota = new Quota(1000L, 100);
        quota.setSource(QuotaSource.ROLE, "premium");
        
        String str = quota.toString();
        assertTrue(str.contains("ROLE"));
        assertTrue(str.contains("premium"));
    }
}

