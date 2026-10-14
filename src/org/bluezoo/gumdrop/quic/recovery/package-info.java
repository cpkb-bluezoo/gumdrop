/*
 * package-info.java
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

/**
 * QUIC loss detection and congestion control (RFC 9002).
 *
 * <p>{@link org.bluezoo.gumdrop.quic.recovery.LossDetector} is the
 * per-connection orchestrator, implementing RFC 9002 Appendix A's
 * reference pseudocode directly: sent-packet tracking per packet number
 * space (reusing {@link org.bluezoo.gumdrop.quic.tls.EncryptionLevel}
 * as the space discriminator), ACK-triggered newly-acked/newly-lost
 * detection (packet threshold, RFC 9002 section 6.1.1, and time
 * threshold, section 6.1.2), and Probe Timeout computation (section
 * 6.2). It owns one {@link org.bluezoo.gumdrop.quic.recovery.RttEstimator}
 * (section 5) and one {@link org.bluezoo.gumdrop.quic.recovery.CongestionController}
 * (NewReno, section 7, Appendix B, or CUBIC, RFC 9438, per {@link
 * org.bluezoo.gumdrop.quic.recovery.CongestionAlgorithm}).
 *
 * <p>Every class here is transport/frame-agnostic and takes time
 * explicitly as a {@code long} microseconds parameter rather than
 * reading a system clock -- deterministically testable, and reusable
 * regardless of which clock source the eventual owning connection uses.
 * Nothing here schedules a real timer or writes wire bytes; the owning
 * connection (a later stage of the QUIC transport rewire) drains the
 * computed PTO/loss deadlines and newly-lost packets and acts on them.
 *
 * <p>{@link org.bluezoo.gumdrop.quic.recovery.Pacer} implements RFC 9002
 * section 7.7: a token bucket that spreads the congestion window over the
 * round trip and caps bursts at the initial window. {@link
 * org.bluezoo.gumdrop.quic.recovery.LossDetector#getPacingDelayMicros}
 * tells the connection how long bulk data must wait. It needs no timer
 * finer than a millisecond: credit accrues from the time the connection's
 * timer actually fires, so a late or rounded-up timer makes the next burst
 * larger but never lowers the average rate.
 *
 * <p>Persistent congestion (RFC 9002 section 7.6) is implemented: {@link
 * org.bluezoo.gumdrop.quic.recovery.LossDetector} detects it (a
 * deliberately conservative, packet-number-adjacency-based approximation
 * of the RFC's literal cross-packet-number-space "nothing acknowledged
 * between them" test -- see its own documentation) and, when declared,
 * calls {@link org.bluezoo.gumdrop.quic.recovery.CongestionController#onPersistentCongestion()}
 * to drop straight to the minimum window.
 *
 * <p>Not implemented: ECN processing (RFC 9002 section 7.1/7.7, an
 * optional additional congestion signal on top of loss-based detection).
 * Real ECN marking needs the sender to set the IP-layer ECN codepoint on
 * outgoing datagrams -- {@code DatagramChannel}'s {@code IP_TOS} socket
 * option is notoriously unreliable across JVMs/OSes for this, and RFC
 * 9000 section 13.4.2 additionally requires detecting and permanently
 * disabling ECN per path if marks stop working (a real "black hole"
 * risk any half-implemented version would carry) -- a materially
 * different, higher-risk scope than the loss-detection-side persistent
 * congestion work above, and one this codebase does not take on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002">RFC 9002</a>
 */
package org.bluezoo.gumdrop.quic.recovery;
