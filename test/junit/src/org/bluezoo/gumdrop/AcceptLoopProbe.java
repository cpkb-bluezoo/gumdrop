/*
 * AcceptLoopProbe.java
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

package org.bluezoo.gumdrop;

import java.nio.channels.ServerSocketChannel;

/**
 * Test-only public window onto the package-private
 * {@link AcceptSelectorLoop#isRegistered} so tests in other packages can
 * check that a closed raw acceptor has really been deregistered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AcceptLoopProbe {

    private AcceptLoopProbe() {
    }

    /**
     * @param gumdrop the runtime whose accept loop is consulted
     * @param channel the listening channel
     * @return true if the channel still holds a key in the accept loop's selector
     */
    public static boolean isRegistered(Gumdrop gumdrop, ServerSocketChannel channel) {
        AcceptSelectorLoop loop = gumdrop.getAcceptLoop();
        return loop != null && loop.isRegistered(channel);
    }
}
