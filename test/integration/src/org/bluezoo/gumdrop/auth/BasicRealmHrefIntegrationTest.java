/*
 * BasicRealmHrefIntegrationTest.java
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

package org.bluezoo.gumdrop.auth;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Integration test for {@link BasicRealm#href(String)}, which resolves a
 * URL string against the real working directory and opens it with the JDK
 * URL machinery, so it needs the real file system.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicRealmHrefIntegrationTest {

    @Test
    public void xmlConfigurationViaStringHref() throws Exception {
        String xml = "<realm><user name='zed' password='{SHA}AAAAAAAAAAAAAAAAAAAAAAAAAAA='/></realm>";
        Path file = Files.createTempFile("basicrealm", ".xml");
        try {
            Files.write(file, xml.getBytes(StandardCharsets.UTF_8));
            BasicRealm realm = new BasicRealm();
            realm.href(file.toUri().toString());
            assertTrue(realm.userExists("zed"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void missingConfigurationFileFails() throws IOException {
        Path file = Files.createTempFile("basicrealm", ".xml");
        Files.delete(file);
        BasicRealm realm = new BasicRealm();
        try {
            realm.href(file.toUri().toString());
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
    }
}
