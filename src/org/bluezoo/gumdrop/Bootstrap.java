/*
 * Bootstrap.java
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
        // Load the Gumdrop class in the container classloader and
        // run it
        Thread.currentThread().setContextClassLoader(containerClassLoader);
        Class<?> gumdropClass = containerClassLoader.loadClass("org.bluezoo.gumdrop.Gumdrop");
        Method main = gumdropClass.getMethod("main", String[].class);
        main.invoke(null, new Object[] { args });
    }

}
