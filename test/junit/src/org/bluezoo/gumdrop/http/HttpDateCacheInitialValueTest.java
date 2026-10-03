/*
 * HttpDateCacheInitialValueTest.java
 * HTTPDateCacheTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

/**
 * The value the cache holds from the moment the class is loaded. Kept in a
 * class of its own, which the build runs in its own JVM, so that the call
 * here is the first reference to {@link HttpDateCache}: the value under test
 * is the one its static initialiser produced, before any timer refresh.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpDateCacheInitialValueTest {

    /**
     * The first value came from a refresh that ran before the test clock
     * field had been set to "use the system clock", so every response in the
     * first second after start-up said 1 January 1970.
     */
    @Test
    public void initialValueComesFromSystemClock() {
        String date = HttpDateCache.get();
        assertFalse("initial Date must be the current time, got: " + date,
                date.contains(" 1970 "));
    }

}
