/*
 * Bootstrap.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.bluezoo.gumdrop;

import java.lang.reflect.Method;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * This class bootstraps the gumdrop server.
 * It will create a classloader specifically to load the gumdrop server
 * classes (including servlet container) in, and then load the server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Bootstrap {

    public static void main(String[] args) throws Exception {
        // Get URL of the jar we were loaded from
        URL bootstrapUrl = Bootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        // Open the jar to read its manifest
        JarFile bootstrapJar = new JarFile(bootstrapUrl.getPath());
        Manifest manifest = bootstrapJar.getManifest();
        Attributes mainAttributes = manifest.getMainAttributes();
        // server.jar
        String containerJar = mainAttributes.getValue("Container-Jar");
        // J2EE dependencies
        String containerDependencies = mainAttributes.getValue("Container-Dependencies");
        // URLs for container classloader
        URL containerUrl = new URL("jar:" + bootstrapUrl + "!/" + containerJar);
        List<URL> urls = new ArrayList<>();
        for (String dependency : containerDependencies.split(" ")) {
            urls.add(new URL("jar:" + bootstrapUrl + "!/" + dependency));
        }
        // Set up container classloader
        ClassLoader bootstrapClassLoader = Bootstrap.class.getClassLoader();
        ClassLoader containerClassLoader = new ContainerClassLoader(containerUrl, urls, bootstrapClassLoader);
        // Load the Server class in the container classloader and
        // run it
        Thread.currentThread().setContextClassLoader(containerClassLoader);
        Class<?> serverClass = containerClassLoader.loadClass("org.bluezoo.gumdrop.SelectorLoop");
        Method main = serverClass.getMethod("main", String[].class);
        main.invoke(null, new Object[] { args });
    }

}
