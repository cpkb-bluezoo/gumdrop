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
 * (NewReno, section 7, Appendix B).
 *
 * <p>Every class here is transport/frame-agnostic and takes time
 * explicitly as a {@code long} milliseconds parameter rather than
 * reading a system clock -- deterministically testable, and reusable
 * regardless of which clock source the eventual owning connection uses.
 * Nothing here schedules a real timer or writes wire bytes; the owning
 * connection (a later stage of the QUIC transport rewire) drains the
 * computed PTO/loss deadlines and newly-lost packets and acts on them.
 *
 * <p>Not implemented: ECN processing (RFC 9002 section 7.1/7.7, an
 * optional additional congestion signal) and persistent congestion
 * detection (section 7.6, a refinement on top of ordinary
 * loss-triggered congestion response). Their absence means slightly
 * less aggressive backoff under sustained loss, not incorrect protocol
 * behaviour.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002">RFC 9002</a>
 */
package org.bluezoo.gumdrop.quic.recovery;
