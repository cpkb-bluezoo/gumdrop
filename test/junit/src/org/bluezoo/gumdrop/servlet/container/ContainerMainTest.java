/*
 * ContainerMainTest.java
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

package org.bluezoo.gumdrop.servlet.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;

import org.junit.Test;

/**
 * Tests the explicit-argument branch of {@link ContainerMain} config
 * resolution. Environment-dependent branches are left to integration
 * runs because the environment cannot be altered from a unit test.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContainerMainTest {

    @Test
    public void explicitArgumentWins() {
        File f = ContainerMain.resolveConfigFile(
                new String[] {"/etc/gumdrop/custom.xml"});
        assertEquals(new File("/etc/gumdrop/custom.xml"), f);
    }

    @Test
    public void emptyArgumentFallsThroughToDefaults() {
        File f = ContainerMain.resolveConfigFile(new String[] {""});
        assertNotNull(f);
    }

    @Test
    public void noArgumentsFallsThroughToDefaults() {
        File f = ContainerMain.resolveConfigFile(new String[0]);
        assertNotNull(f);
    }
}
