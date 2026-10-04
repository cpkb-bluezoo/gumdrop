/*
 * NamedGroupTest.java
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

package org.bluezoo.gumdrop.crypto;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Checks that {@link NamedGroup} is looked up by its IANA TLS Supported
 * Groups registry name and codepoint, and not by its Java constant name.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NamedGroupTest {

    @Test
    public void registryNamesAndCodepointsMatchIana() {
        assertGroup(NamedGroup.X25519, "x25519", 0x001d);
        assertGroup(NamedGroup.SECP256R1, "secp256r1", 0x0017);
        assertGroup(NamedGroup.SECP384R1, "secp384r1", 0x0018);
        assertGroup(NamedGroup.X25519_MLKEM768, "X25519MLKEM768", 0x11ec);
        assertGroup(NamedGroup.SECP256R1_MLKEM768, "SecP256r1MLKEM768", 0x11eb);
        assertGroup(NamedGroup.SECP384R1_MLKEM1024, "SecP384r1MLKEM1024", 0x11ed);
    }

    @Test
    public void lookupByNameIgnoresCase() {
        assertSame(NamedGroup.X25519_MLKEM768, NamedGroup.fromName("x25519mlkem768"));
        assertSame(NamedGroup.SECP256R1, NamedGroup.fromName("SECP256R1"));
    }

    @Test
    public void constantNamesAndUnknownNamesAreNotGroupNames() {
        assertNull(NamedGroup.fromName("X25519_MLKEM768"));
        assertNull(NamedGroup.fromName("SECP256R1_MLKEM768"));
        assertNull(NamedGroup.fromName("x448"));
        assertNull(NamedGroup.fromName(""));
        assertNull(NamedGroup.fromName(null));
    }

    private static void assertGroup(NamedGroup group, String name, int code) {
        assertEquals(name, group.getName());
        assertEquals(code, group.getCode());
        assertSame(group, NamedGroup.fromName(name));
        assertSame(group, NamedGroup.fromCode(code));
    }

}
