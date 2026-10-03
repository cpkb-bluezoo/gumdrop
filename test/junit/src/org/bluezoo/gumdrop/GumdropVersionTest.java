/*
 * GumdropVersionTest.java
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

package org.bluezoo.gumdrop;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * {@link Gumdrop#VERSION} is what the server tells the world (the HTTP
 * {@code Server} field, telemetry scope version), so it must be the version
 * that is actually being built.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropVersionTest {

    private static final Pattern BUILD_VERSION =
            Pattern.compile("<property name='version' value='([^']+)'/>");

    @Test
    public void versionMatchesBuildVersion() throws Exception {
        File buildFile = new File("build.xml");
        assertTrue("run from the project root: " + buildFile.getAbsolutePath(), buildFile.isFile());
        String build = new String(Files.readAllBytes(buildFile.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = BUILD_VERSION.matcher(build);
        assertTrue("build.xml declares the project version", matcher.find());
        String buildVersion = matcher.group(1);
        assertTrue("Gumdrop.VERSION " + Gumdrop.VERSION + " is not the major.minor of build version "
                + buildVersion, buildVersion.startsWith(Gumdrop.VERSION + "."));
    }

}
