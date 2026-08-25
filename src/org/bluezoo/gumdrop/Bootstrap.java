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
import java.io.FilenameFilter;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
 * <p>Two startup layouts are supported:</p>
 * <ul>
 *   <li><strong>lib layout</strong> — {@code GUMDROP_HOME/lib/gumdrop-bootstrap.jar}
 *       plus sibling jars (production zip distribution). No nested-jar extraction.</li>
 *   <li><strong>fat jar</strong> — legacy {@code gumdrop-container.jar} with nested
 *       {@code gumdrop.jar} and dependency jars (deprecated).</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Bootstrap {

    private static final String CONTAINER_LAYOUT_LIB = "lib";
    private static final String BOOTSTRAP_JAR = "gumdrop-bootstrap.jar";
    private static final String SERVER_JAR = "gumdrop.jar";
    /** Servlet container module closure (Phase 2 modular lib/ layout). */
    private static final String[] CONTAINER_MODULE_JARS = {
        "gumdrop-core.jar",
        "gumdrop-http.jar",
        "gumdrop-servlet.jar",
        "gumdrop-mime.jar",
    };

    public static void main(String[] args) throws Exception {
        URL bootstrapUrl = Bootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        File bootstrapFile = toFile(bootstrapUrl);
        ClassLoader bootstrapClassLoader = Bootstrap.class.getClassLoader();

        File libDir = resolveLibDir(bootstrapFile);
        if (libDir != null) {
            startFromLibDir(libDir, bootstrapClassLoader, args);
            return;
        }

        startFromFatJar(bootstrapUrl, bootstrapFile, bootstrapClassLoader, args);
    }

    /**
     * Resolves the {@code lib/} directory for the external-jar distribution layout.
     */
    private static File resolveLibDir(File bootstrapFile) {
        String gumdropHome = System.getenv("GUMDROP_HOME");
        if (gumdropHome != null && !gumdropHome.isEmpty()) {
            File fromHome = new File(gumdropHome, "lib");
            if (isLibLayout(fromHome)) {
                return fromHome;
            }
        }
        if (BOOTSTRAP_JAR.equals(bootstrapFile.getName())) {
            File siblingLib = bootstrapFile.getParentFile();
            if (isLibLayout(siblingLib)) {
                return siblingLib;
            }
        }
        try (JarFile bootstrapJar = new JarFile(bootstrapFile)) {
            Manifest manifest = bootstrapJar.getManifest();
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                if (CONTAINER_LAYOUT_LIB.equals(attrs.getValue("Container-Layout"))) {
                    File siblingLib = bootstrapFile.getParentFile();
                    if (isLibLayout(siblingLib)) {
                        return siblingLib;
                    }
                }
            }
        } catch (Exception e) {
            // fall through to fat-jar detection
        }
        return null;
    }

    private static boolean isLibLayout(File libDir) {
        if (libDir == null || !libDir.isDirectory()) {
            return false;
        }
        if (new File(libDir, SERVER_JAR).isFile()) {
            return true;
        }
        return new File(libDir, CONTAINER_MODULE_JARS[0]).isFile();
    }

    private static void startFromLibDir(File libDir, ClassLoader bootstrapClassLoader, String[] args)
            throws Exception {
        List<URL> containerUrls = new ArrayList<>();
        File monolith = new File(libDir, SERVER_JAR);
        if (monolith.isFile()) {
            containerUrls.add(monolith.toURI().toURL());
        } else {
            for (String moduleJar : CONTAINER_MODULE_JARS) {
                File jar = new File(libDir, moduleJar);
                if (jar.isFile()) {
                    containerUrls.add(jar.toURI().toURL());
                }
            }
            if (containerUrls.isEmpty()) {
                throw new IllegalStateException(
                        "No gumdrop server jars found in " + libDir
                                + " (expected gumdrop.jar or servlet module jars)");
            }
        }
        List<URL> dependencyUrls = new ArrayList<>();
        File[] jars = libDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        if (jars != null) {
            Arrays.sort(jars, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (File jar : jars) {
                String name = jar.getName();
                if (BOOTSTRAP_JAR.equals(name) || SERVER_JAR.equals(name)) {
                    continue;
                }
                if (isContainerModuleJar(name)) {
                    continue;
                }
                dependencyUrls.add(jar.toURI().toURL());
            }
        }
        launchServer(containerUrls, dependencyUrls, bootstrapClassLoader, args);
    }

    private static boolean isContainerModuleJar(String name) {
        for (String moduleJar : CONTAINER_MODULE_JARS) {
            if (moduleJar.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void startFromFatJar(URL bootstrapUrl, File bootstrapFile,
            ClassLoader bootstrapClassLoader, String[] args) throws Exception {
        try (JarFile bootstrapJar = new JarFile(bootstrapFile)) {
            Manifest manifest = bootstrapJar.getManifest();
            if (manifest == null) {
                throw new IllegalStateException("Missing manifest in " + bootstrapFile);
            }
            Attributes mainAttributes = manifest.getMainAttributes();
            String containerJar = mainAttributes.getValue("Container-Jar");
            String containerDependencies = mainAttributes.getValue("Container-Dependencies");
            if (containerJar == null || containerDependencies == null) {
                throw new IllegalStateException(
                        "Not a Gumdrop container jar (missing Container-Jar manifest entries): "
                                + bootstrapFile);
            }
            URL containerUrl = new URL("jar:" + bootstrapUrl + "!/" + containerJar);
            List<URL> dependencyUrls = new ArrayList<>();
            int depStart = 0;
            int depsLen = containerDependencies.length();
            while (depStart <= depsLen) {
                int depEnd = containerDependencies.indexOf(' ', depStart);
                if (depEnd < 0) {
                    depEnd = depsLen;
                }
                String dependency = containerDependencies.substring(depStart, depEnd);
                if (!dependency.isEmpty()) {
                    dependencyUrls.add(new URL("jar:" + bootstrapUrl + "!/" + dependency));
                }
                depStart = depEnd + 1;
            }
            launchServer(Collections.singletonList(containerUrl), dependencyUrls, bootstrapClassLoader, args);
        }
    }

    private static void launchServer(List<URL> containerUrls, List<URL> dependencyUrls,
            ClassLoader bootstrapClassLoader, String[] args) throws Exception {
        ClassLoader containerClassLoader =
                new ContainerClassLoader(containerUrls, dependencyUrls, bootstrapClassLoader);
        Thread.currentThread().setContextClassLoader(containerClassLoader);
        reconfigureLogging(containerClassLoader);
        Class<?> gumdropClass = containerClassLoader.loadClass("org.bluezoo.gumdrop.Gumdrop");
        Method main = gumdropClass.getMethod("main", String[].class);
        main.invoke(null, new Object[] { args });
    }

    private static File toFile(URL url) throws Exception {
        URI uri = url.toURI();
        if ("jar".equals(uri.getScheme())) {
            String ssp = uri.getSchemeSpecificPart();
            int bang = ssp.indexOf('!');
            if (bang >= 0) {
                uri = URI.create(ssp.substring(0, bang));
            }
        }
        return new File(uri);
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
            reconfigureHandlerFormatter(handler, props, classLoader);
        }

        // Also reconfigure any named loggers that might have handlers
        Enumeration<String> loggerNames = logManager.getLoggerNames();
        while (loggerNames.hasMoreElements()) {
            String loggerName = loggerNames.nextElement();
            Logger logger = logManager.getLogger(loggerName);
            if (logger != null) {
                for (Handler handler : logger.getHandlers()) {
                    reconfigureHandlerFormatter(handler, props, classLoader);
                }
            }
        }
    }

    private static void reconfigureHandlerFormatter(Handler handler, Properties props,
            ClassLoader classLoader) {
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
