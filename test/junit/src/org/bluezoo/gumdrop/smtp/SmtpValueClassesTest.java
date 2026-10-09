/*
 * SmtpValueClassesTest.java
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

package org.bluezoo.gumdrop.smtp;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * Tests for the small SMTP value classes: delivery requirements, DSN
 * parameters, listener configuration and server metrics.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpValueClassesTest {

    @Test
    public void testEmptyDeliveryRequirements() {
        DefaultDeliveryRequirements req = new DefaultDeliveryRequirements();
        assertTrue(req.isEmpty());
        assertFalse(req.isRequireTls());
        assertFalse(req.hasPriority());
        assertFalse(req.isFutureRelease());
        assertFalse(req.hasDeliverByDeadline());
        assertFalse(req.hasDsnParameters());
        assertEquals("DeliveryRequirements[]", req.toString());
        assertTrue(DeliveryRequirementsHolder.empty().isEmpty());
    }

    @Test
    public void testPopulatedDeliveryRequirements() {
        DefaultDeliveryRequirements req = new DefaultDeliveryRequirements();
        Instant release = Instant.parse("2030-01-01T00:00:00Z");
        Instant deadline = Instant.parse("2031-01-01T00:00:00Z");
        req.setRequireTls(true);
        req.setPriority(Integer.valueOf(5));
        req.setReleaseTime(release);
        req.setDeliverByDeadline(deadline);
        req.setDeliverByReturn(Boolean.TRUE);
        req.setDsnReturn(DsnReturn.FULL);
        req.setDsnEnvelopeId("env-1");
        assertFalse(req.isEmpty());
        assertTrue(req.isRequireTls());
        assertEquals(Integer.valueOf(5), req.getPriority());
        assertTrue(req.hasPriority());
        assertEquals(release, req.getReleaseTime());
        assertTrue(req.isFutureRelease());
        assertEquals(deadline, req.getDeliverByDeadline());
        assertTrue(req.hasDeliverByDeadline());
        assertEquals(Boolean.TRUE, req.isDeliverByReturn());
        assertEquals(DsnReturn.FULL, req.getDsnReturn());
        assertEquals("env-1", req.getDsnEnvelopeId());
        assertTrue(req.hasDsnParameters());
        String text = req.toString();
        assertTrue(text, text.contains("REQUIRETLS"));
        assertTrue(text, text.contains("MT-PRIORITY=5"));
        assertTrue(text, text.contains("HOLDUNTIL="));
        assertTrue(text, text.contains("BY="));
        assertTrue(text, text.endsWith(";R]"));
        req.setDeliverByReturn(Boolean.FALSE);
        assertTrue(req.toString().endsWith(";N]"));
    }

    @Test
    public void testDeliveryRequirementsToStringSeparators() {
        DefaultDeliveryRequirements req = new DefaultDeliveryRequirements();
        req.setPriority(Integer.valueOf(-2));
        String text = req.toString();
        assertEquals("DeliveryRequirements[MT-PRIORITY=-2]", text);
        req.setReleaseTime(Instant.parse("2030-01-01T00:00:00Z"));
        text = req.toString();
        assertTrue(text, text.contains(", HOLDUNTIL="));
        req.setDeliverByDeadline(Instant.parse("2031-01-01T00:00:00Z"));
        text = req.toString();
        assertTrue(text, text.contains(", BY="));
    }

    @Test
    public void testDsnRecipientParameters() {
        Set<DsnNotify> notify = EnumSet.of(DsnNotify.SUCCESS, DsnNotify.DELAY);
        DsnRecipientParameters p = new DsnRecipientParameters(notify,
                "rfc822", "a@example.com");
        assertEquals(2, p.getNotify().size());
        assertTrue(p.isNotifySuccess());
        assertTrue(p.isNotifyDelay());
        assertFalse(p.isNotifyFailure());
        assertFalse(p.isNotifyNever());
        assertEquals("rfc822", p.getOrcptType());
        assertEquals("a@example.com", p.getOrcptAddress());
        assertTrue(p.hasOrcpt());
        assertTrue(p.hasParameters());
        String text = p.toString();
        assertTrue(text, text.contains("NOTIFY="));
        assertTrue(text, text.contains("ORCPT=rfc822;a@example.com"));
    }

    @Test
    public void testDsnRecipientParametersMinimal() {
        DsnRecipientParameters none = new DsnRecipientParameters(null, null, null);
        assertTrue(none.getNotify().isEmpty());
        assertFalse(none.hasOrcpt());
        assertFalse(none.hasParameters());
        assertEquals("DsnRecipientParameters[]", none.toString());
        DsnRecipientParameters never = new DsnRecipientParameters(
                EnumSet.of(DsnNotify.NEVER), null, null);
        assertTrue(never.isNotifyNever());
        assertTrue(never.hasParameters());
        assertFalse(never.hasOrcpt());
        DsnRecipientParameters failure = new DsnRecipientParameters(
                EnumSet.of(DsnNotify.FAILURE), "rfc822", null);
        assertTrue(failure.isNotifyFailure());
        assertFalse(failure.hasOrcpt());
        DsnRecipientParameters orcptOnly = new DsnRecipientParameters(
                EnumSet.noneOf(DsnNotify.class), "rfc822", "x@y");
        assertTrue(orcptOnly.hasParameters());
        assertEquals("DsnRecipientParameters[ORCPT=rfc822;x@y]", orcptOnly.toString());
    }

    @Test
    public void testDsnEnvelopeParameters() {
        DsnEnvelopeParameters none = new DsnEnvelopeParameters(null, null);
        assertFalse(none.hasParameters());
        assertNull(none.getRet());
        assertNull(none.getEnvid());
        assertEquals("DsnEnvelopeParameters[]", none.toString());
        DsnEnvelopeParameters both = new DsnEnvelopeParameters(DsnReturn.HDRS, "id1");
        assertTrue(both.hasParameters());
        assertEquals(DsnReturn.HDRS, both.getRet());
        assertEquals("id1", both.getEnvid());
        String text = both.toString();
        assertTrue(text, text.contains("RET="));
        assertTrue(text, text.contains(", ENVID=id1"));
        DsnEnvelopeParameters envidOnly = new DsnEnvelopeParameters(null, "id2");
        assertTrue(envidOnly.hasParameters());
        assertEquals("DsnEnvelopeParameters[ENVID=id2]", envidOnly.toString());
        DsnEnvelopeParameters retOnly = new DsnEnvelopeParameters(DsnReturn.FULL, null);
        assertTrue(retOnly.hasParameters());
    }

    @Test
    public void testListenerConfiguration() {
        SmtpListener listener = new SmtpListener();
        listener.port(2525);
        assertEquals(2525, listener.getPort());
        SmtpListener chained = listener.port(2526);
        assertSame(listener, chained);
        assertEquals(2526, listener.getPort());
        assertNotNull(listener.getDescription());
        listener.maxMessageSize(12345L);
        assertEquals(12345L, listener.getMaxMessageSize());
        listener.maxRecipients(7);
        assertEquals(7, listener.getMaxRecipients());
        listener.maxTransactionsPerSession(3);
        assertEquals(3, listener.getMaxTransactionsPerSession());
        assertFalse(listener.isAuthRequired());
        listener.authRequired(true);
        assertTrue(listener.isAuthRequired());
        assertNull(listener.getRealm());
        assertNull(listener.getMailboxFactory());
        assertNull(listener.getServer());
        assertNull(listener.getSessionProvider());
        assertNull(listener.getGSSAPIServer());
        assertSame(listener, listener.secure(false));
    }

    @Test
    public void testMetricsAcceptUpdates() {
        TelemetryConfig config = new TelemetryConfig();
        SmtpServerMetrics m = new SmtpServerMetrics(config);
        m.connectionOpened();
        m.messageReceived(1024L, 2);
        m.messageReceived(2048L, 1, true);
        m.messageReceived(0L, 0, false);
        m.authAttempt("PLAIN");
        m.authSuccess("PLAIN");
        m.authFailure("LOGIN");
        m.starttlsUpgraded();
        m.connectionClosed(100.0);
    }

    /** Access to the package-private EMPTY constant. */
    private static final class DeliveryRequirementsHolder {
        static DeliveryRequirements empty() {
            return DefaultDeliveryRequirements.EMPTY;
        }
    }
}
