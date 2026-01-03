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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

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
        List<URL> urls = new ArrayList<URL>();
        // Parse space-separated dependencies
        int depStart = 0;
        int depsLen = containerDependencies.length();
        while (depStart <= depsLen) {
            int depEnd = containerDependencies.indexOf(' ', depStart);
            if (depEnd < 0) {
                depEnd = depsLen;
            }
            String dependency = containerDependencies.substring(depStart, depEnd);
            if (!dependency.isEmpty()) {
                urls.add(new URL("jar:" + bootstrapUrl + "!/" + dependency));
            }
            depStart = depEnd + 1;
        }
        // Set up container classloader
        ClassLoader bootstrapClassLoader = Bootstrap.class.getClassLoader();
        ClassLoader containerClassLoader = new ContainerClassLoader(containerUrl, urls, bootstrapClassLoader);
        // Load the Gumdrop class in the container classloader and
        // run it
        Thread.currentThread().setContextClassLoader(containerClassLoader);

        // Reconfigure logging to use formatter classes from the ContainerClassLoader.
        // The LogManager uses the system classloader to load formatter classes,
        // but our formatters are in server.jar (ContainerClassLoader).
        reconfigureLogging(containerClassLoader);

        Class<?> gumdropClass = containerClassLoader.loadClass("org.bluezoo.gumdrop.Gumdrop");
        Method main = gumdropClass.getMethod("main", String[].class);
        main.invoke(null, new Object[] { args });
    }

    /**
     * Reconfigures the logging system to use formatter classes loaded from
     * the specified classloader.
     *
     * <p>The java.util.logging.LogManager uses the system classloader to load
     * formatter classes specified in logging.properties. Since our custom
     * formatters (like LaconicFormatter) are in server.jar which is loaded by
     * the ContainerClassLoader, we need to manually reconfigure the handlers
     * to use formatters loaded from the correct classloader.
     *
     * @param classLoader the classloader to use for loading formatter classes
     */
    private static void reconfigureLogging(ClassLoader classLoader) {
        String configFile = System.getProperty("java.util.logging.config.file");
        if (configFile == null) {
            return; // No custom configuration
        }

        Properties props = new Properties();
        try (InputStream in = new FileInputStream(configFile)) {
            props.load(in);
        } catch (Exception e) {
            // Can't read config file, skip reconfiguration
            return;
        }

        LogManager logManager = LogManager.getLogManager();
        Logger rootLogger = logManager.getLogger("");
        if (rootLogger == null) {
            return;
        }

        // Reconfigure formatters on all handlers
        for (Handler handler : rootLogger.getHandlers()) {
            String handlerClassName = handler.getClass().getName();
            String formatterProp = handlerClassName + ".formatter";
            String formatterClassName = props.getProperty(formatterProp);

            if (formatterClassName != null) {
                try {
                    Class<?> formatterClass = classLoader.loadClass(formatterClassName);
                    Formatter formatter = (Formatter) formatterClass.getDeclaredConstructor().newInstance();
                    handler.setFormatter(formatter);
                } catch (Exception e) {
                    // Failed to load formatter, keep existing one
                }
            }
        }

        // Also reconfigure any named loggers that might have handlers
        Enumeration<String> loggerNames = logManager.getLoggerNames();
        while (loggerNames.hasMoreElements()) {
            String loggerName = loggerNames.nextElement();
            Logger logger = logManager.getLogger(loggerName);
            if (logger != null) {
                for (Handler handler : logger.getHandlers()) {
                    String handlerClassName = handler.getClass().getName();
                    String formatterProp = handlerClassName + ".formatter";
                    String formatterClassName = props.getProperty(formatterProp);

                    if (formatterClassName != null) {
                        try {
                            Class<?> formatterClass = classLoader.loadClass(formatterClassName);
                            Formatter formatter = (Formatter) formatterClass.getDeclaredConstructor().newInstance();
                            handler.setFormatter(formatter);
                        } catch (Exception e) {
                            // Failed to load formatter, keep existing one
                        }
                    }
                }
            }
        }
    }

}
