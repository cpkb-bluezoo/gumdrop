/*
 * Context.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */
package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.ContainerClassLoader;
import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.servlet.manager.ContainerService;
import org.bluezoo.gumdrop.servlet.manager.ContextService;
import org.bluezoo.gumdrop.servlet.manager.HitStatistics;
import org.bluezoo.gumdrop.util.IteratorEnumeration;
import org.bluezoo.gumdrop.util.JarInputStream;

import org.xml.sax.SAXException;

import java.io.*;
import java.net.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.*;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionListener;
import javax.servlet.http.MappingMatch;

/**
 * The application context represents the single point of contact for all
 * servlets and associated data in the application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Context extends DeploymentDescriptor implements ContextService {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    static final Logger LOGGER = Logger.getLogger("org.bluezoo.gumdrop.servlet");

    final Container container;
    final String contextPath;
    final File root;
    private final ContainerClassLoader containerClassLoader;
    private final ContextClassLoader contextClassLoader;
    ServletConnector connector;
    byte[] digest; // MD5 digest of web.xml

    Map<String,Realm> realms = new LinkedHashMap<>();

    Map<String,String> initParams = new LinkedHashMap<>();
    Map<String,Object> attributes = new LinkedHashMap<>();
    Map<String,Filter> filters = new LinkedHashMap<>();
    Map<String,Servlet> servlets = new LinkedHashMap<>();

    Collection<ServletContextListener> servletContextListeners = new ConcurrentLinkedDeque<>();
    Collection<ServletContextAttributeListener> servletContextAttributeListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionListener> sessionListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionAttributeListener> sessionAttributeListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionActivationListener> sessionActivationListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionBindingListener> sessionBindingListeners = new ConcurrentLinkedDeque<>();
    Collection<ServletRequestListener> servletRequestListeners = new ConcurrentLinkedDeque<>();
    Collection<ServletRequestAttributeListener> servletRequestAttributeListeners = new ConcurrentLinkedDeque<>();

    Map<String,Session> sessions = new HashMap<>();
    int sessionTimeout = -1;
    long sessionsLastInvalidated;

    boolean distributable;
    boolean initialized;
    boolean metadataComplete;

    String moduleName; // TODO
    String defaultContextPath; // TODO
    String requestCharacterEncoding; // TODO
    String responseCharacterEncoding; // TODO
    List<String> absoluteOrdering = new ArrayList<>();

    Map<String,? extends ServletRegistration> servletRegistrations = new LinkedHashMap<>();
    Map<String,? extends FilterRegistration> filterRegistrations = new LinkedHashMap<>();

    ServletDef defaultServletDef;

    String secureHost;
    String commonDir;

    HitStatisticsImpl hitStatistics = new HitStatisticsImpl();

    // TODO session activation/passivation, distributed sessions

    public Context(Container container, String contextPath, File root) {
        this.container = container;
        this.contextPath = contextPath;
        this.root = root;
        if (contextPath.endsWith("/")) {
            throw new IllegalArgumentException("Illegal context path: "+contextPath);
        }

        // Work out if this context is the manager webapp
        boolean manager = false;
        if (root.isFile() && root.getName().equals("manager.war")) {
            // TODO compute checksum of the file and compare to correct
            // version
            manager = true;
        }

        sessionsLastInvalidated = System.currentTimeMillis();

        containerClassLoader = (ContainerClassLoader) Context.class.getClassLoader();
        contextClassLoader = new ContextClassLoader(containerClassLoader, this, manager);

        reset();
    }

    @Override public ContainerService getContainer() {
        return container;
    }

    @Override public ThreadPoolExecutor getConnectorThreadPool() {
        return connector.getConnectorThreadPool();
    }

    @Override public String getConnectorKeepAlive() {
        return connector.getKeepAlive();
    }

    @Override public void setConnectorKeepAlive(String val) {
        connector.setKeepAlive(val);
    }

    @Override public HitStatistics getHitStatistics() {
        return hitStatistics;
    }

    @Override public String getRoot() {
        return root.toString();
    }

    public void setSecureHost(String value) {
        secureHost = value;
    }

    public void setCommonDir(String value) {
        commonDir = value;
    }

    void reset() {
        super.reset();

        initParams.clear();
        attributes.clear();
        filters.clear();
        servlets.clear();
        distributable = false;
        initialized = false;

        servletContextListeners.clear();
        servletContextAttributeListeners.clear();
        sessionListeners.clear();
        sessionAttributeListeners.clear();
        sessionActivationListeners.clear();
        sessionBindingListeners.clear();
        servletRequestListeners.clear();
        servletRequestAttributeListeners.clear();

        contextClassLoader.reset();

        // Temporary working directory (SRV.3.7.1)
        try {
            File tmpDir = File.createTempFile("gumdrop", contextPath.replace('/', '_'));
            tmpDir.delete(); // delete file
            tmpDir.mkdirs(); // replace by directory
            tmpDir.deleteOnExit();
            attributes.put("javax.servlet.context.tempdir", tmpDir);
        } catch (IOException e) {
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }

        hitStatistics = new HitStatisticsImpl();
    }

    /**
     * Loads this context from the deployment descriptor.
     */
    public void load() throws IOException, SAXException {
        InputStream webXml = getResourceAsStream("/WEB-INF/web.xml");
        DeploymentDescriptorParser parser = new DeploymentDescriptorParser();
        if (webXml != null) {
            parser.parse(this, webXml);
        }
        if (!metadataComplete) {
            scan(parser);
        }
        // digest
        this.digest = parser.getDigest();
        // Set context on filterdefs, servletdefs, listenerdefs
        for (FilterDef fd : filterDefs.values()) {
            fd.context = this;
        }
        for (ServletDef sd : servletDefs.values()) {
            sd.context = this;
        }
        for (ListenerDef ld : listenerDefs) {
            ld.context = this;
        }

        // Default servlet
        defaultServletDef = null;
        for (ServletMapping sm : servletMappings) {
            if ("/".equals(sm.urlPattern)) {
                defaultServletDef = (ServletDef) servletDefs.get(sm.name);
                break;
            }
        }
        if (defaultServletDef == null) {
            defaultServletDef = new ServletDef();
            defaultServletDef.context = this;
            defaultServletDef.displayName = "(default servlet)";
            defaultServletDef.name = null;
            defaultServletDef.className = DefaultServlet.class.getName();
            defaultServletDef.loadOnStartup = 0;
            servletDefs.put(null, defaultServletDef);
            ServletMapping defaultServletMapping = new ServletMapping();
            defaultServletMapping.name = null;
            defaultServletMapping.urlPattern = "/";
            servletMappings.add(defaultServletMapping);
        }

    }

    /**
     * Scan the web application and add any web fragments or annotations.
     */
    void scan(DeploymentDescriptorParser parser) throws IOException, SAXException {
        Set<String> resourcePaths = getResourcePaths("/WEB-INF/");
        for (String resourcePath : resourcePaths) {
            if (resourcePath.startsWith("/WEB-INF/lib/") && resourcePath.toLowerCase().endsWith(".jar")) {
                scanJar(parser, resourcePath);
            } else if (resourcePath.startsWith("/WEB-INF/classes/") && resourcePath.toLowerCase().endsWith(".class")) {
                scanClass(resourcePath);
            }
        }
    }

    void scanJar(DeploymentDescriptorParser parser, String path) throws IOException, SAXException {
        String webFragmentPath = "/META-INF/web-fragment.xml";
        // TODO
    }
    
    /**
     * Scan a class for @WebServlet, @WebFilter annotations
     */
    void scanClass(String path) throws IOException, SAXException {
        ContextClassLoader cl = (ContextClassLoader) getContextClassLoader();
        // TODO
    }
    
    /**
     * Reloads this context.
     */
    @Override public synchronized void reload() throws IOException, SAXException {
        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(containerClassLoader);
        try {
            long t1 = System.currentTimeMillis();
            destroy();
            reset();
            load();
            init();
            long t2 = System.currentTimeMillis();
            String message = L10N.getString("info.reloaded_context");
            message = MessageFormat.format(message, contextPath, (t2 - t1));
            LOGGER.info(message);
        } finally {
            thread.setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * Initializes this context and all filters and servlets in it.
     * @see SRV.9.12
     */
    public synchronized void init() {
        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        ClassLoader containerClassLoader = Context.class.getClassLoader();
        ClassLoader contextClassLoader = getContextClassLoader();

        // Binding to JNDI must be done in the container classloader
        thread.setContextClassLoader(containerClassLoader);

        // JNDI
        // The InitialContext is established by the Container
        try {
            ServletInitialContext ctx = (ServletInitialContext) new InitialContext().lookup("");
            for (Resource resource : getResources()) {
                try {
                    resource.init();
                    String name = stripCompEnv(resource.getName());
                    String interfaceName = resource.getInterfaceName();
                    Object instance = resource.newInstance();
                    if (instance != null) {
                        ctx.bind("java:comp/env/" + name, interfaceName, instance);
                    }
                } catch (ServletException e) {
                    String message = Context.L10N.getString("err.init_resource");
                    Context.LOGGER.log(Level.SEVERE, message, e);
                }
            }
        } catch (NamingException e) {
            String message = Context.L10N.getString("err.bind_resource");
            Context.LOGGER.log(Level.SEVERE, message, e);
        }

        // Ensure that listener, filter, servlets, and lifecycle callbacks
        // operate in the context classloader
        thread.setContextClassLoader(contextClassLoader);

        // Init listeners
        for (ListenerDef listenerDef : listenerDefs) {
            EventListener listener = listenerDef.newInstance();
            addListener(listener);
        }
        // Inform servlet context listeners
        ServletContextEvent event = new ServletContextEvent(this);
        for (ServletContextListener l : servletContextListeners) {
            l.contextInitialized(event);
        }
        // Init filters
        for (FilterDef filterDef : filterDefs.values()) {
            try {
                Filter filter = filterDef.newInstance();
                filters.put(filterDef.name, filter);
            } catch (ServletException e) {
                // TODO UnavailableException
                String message = L10N.getString("err.init_filter");
                message = MessageFormat.format(message, filterDef.name);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }
        // Init servlets in loadOnStartup order
        List<ServletDef> loadableServletDefs = new ArrayList<ServletDef>(servletDefs.values());
        Collections.sort(loadableServletDefs);
        for (ServletDef servletDef : loadableServletDefs) {
            if (servletDef.loadOnStartup >= 0) {
                try {
                    Servlet servlet = servletDef.newInstance();
                    servlets.put(servletDef.name, servlet);
                } catch (ServletException e) {
                    // TODO UnavailableException
                    String message = L10N.getString("err.init_servlet");
                    message = MessageFormat.format(message, servletDef.name);
                    LOGGER.log(Level.SEVERE, message, e);
                }
            }
        }

        // Post-construct
        for (LifecycleCallback postConstruct : postConstructs) {
            postConstruct.execute();
        }

        thread.setContextClassLoader(originalClassLoader);
        initialized = true;
    }

    /**
     * This is necessary because all JNDI names are supposed to be relative
     * to java:comp/env/
     * However some deployment descriptor authors like to put the absolute
     * java: name for resources when defining them. So make it relative
     * here.
     */
    static String stripCompEnv(String jndiName) {
        return jndiName.startsWith("java:comp/env/") ? jndiName.substring(14) : jndiName;
    }

    /**
     * Destroys this context and all filters and servlets in it.
     */
    public synchronized void destroy() {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(getContextClassLoader());
        invalidateSessions(true);

        // Pre-destroy
        for (LifecycleCallback preDestroy : preDestroys) {
            preDestroy.execute();
        }

        // Destroy servlets
        for (Servlet servlet : servlets.values()) {
            servlet.destroy();
        }
        // Destroy filters
        for (Filter filter : filters.values()) {
            filter.destroy();
        }
        // Inform servlet context listeners
        ServletContextEvent event = new ServletContextEvent(this);
        for (ServletContextListener l : servletContextListeners) {
            l.contextDestroyed(event);
        }

        // Close JNDI resources
        for (Resource resource : getResources()) {
            resource.close();
        }

        thread.setContextClassLoader(loader);
        initialized = false;
    }

    void invalidateSessions(boolean force) {
        long now = System.currentTimeMillis();
        if (sessionsLastInvalidated + 1000 < now && !force) { // !1 second elapsed
            return;
        }
        synchronized (sessions) {
            for (Session session : sessions.values()) {
                if (session.maxInactiveInterval >= 0
                        && session.lastAccessedTime + (session.maxInactiveInterval * 1000) >= now) {
                    try {
                        session.invalidate();
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    public void addRealm(String name, Realm realm) {
        realms.put(name, realm);
    }

    Realm getRealm(String name) {
        Realm realm = realms.get(name);
        if (realm == null && container != null) {
            realm = container.realms.get(name);
        }
        return realm;
    }

    /**
     * Used during authentication to determine the password for a user.
     */
    String getPassword(String realmName, String username) {
        Realm realm = getRealm(realmName);
        if (realm != null) {
            return realm.getPassword(username);
        }
        return null;
    }

    /**
     * Used by the request (principal) to determine group membership.
     */
    boolean isMember(String realmName, String username, String roleName) {
        Realm realm = getRealm(realmName);
        if (realm != null) {
            return realm.isMember(username, roleName);
        }
        return false;
    }

    /**
     * Ensure that one and only one context classloader is used to load all
     * servlets and the classes they reference (SRV.3.7).
     */
    ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    Servlet parseJSPFile(String path) {
        // TODO JSP
        throw new UnsupportedOperationException("JSP not yet supported");
    }

    Servlet loadServlet(String name) throws ServletException {
        Servlet servlet = servlets.get(name);
        if (servlet == null) {
            ServletDef servletDef = servletDefs.get(name);
            servlet = servletDef.newInstance();
            servlets.put(name, servlet);
        }
        return servlet;
    }

    /**
     * Returns the encoding specified for the given locale in the deployment
     * descriptor.
     */
    String getEncoding(Locale locale) {
        String ret = localeEncodingMappings.get(locale.toString());
        if (ret == null) {
            ret = localeEncodingMappings.get(locale.getLanguage());
        }
        return ret;
    }

    // -- ServletContext --

    @Override public ServletContext getContext(String uripath) {
        return container.getContext(uripath);
    }

    @Override public String getContextPath() {
        return contextPath;
    }

    @Override public int getMajorVersion() {
        return 4;
    }

    @Override public int getMinorVersion() {
        return 0;
    }

    @Override public String getMimeType(String file) {
        for (MimeMapping mimeMapping : mimeMappings) {
            if (file.endsWith(mimeMapping.extension)) {
                return mimeMapping.mimeType;
            }
        }
        return null;
    }

    @Override public Set<String> getResourcePaths(String path) {
        return getResourcePaths(path, true);
    }

    /**
     * @param searchJars if false, ignore resources in
     * /META-INF/lib/resources subdirectory of jars in the /WEB-INF/lib
     * directory
     */
    Set<String> getResourcePaths(String path, boolean searchJars) {
        if (path == null || "".equals(path)) {
            return null;
        }
        if (path.charAt(0) != '/') {
            // it must start with /
            path = "/" + path;
        }
        if (!path.endsWith("/")) {
            // this is a "directory"
            path = path + "/";
        }
        String entryPath = path.substring(1); // without leading /
        String libPath = "WEB-INF/lib/";
        Set<String> ret = new LinkedHashSet();
        List<File> libJarFiles = new ArrayList<>(); // list of jar files to search WEB-INF/resources
        if (root.isDirectory()) {
            if (File.separatorChar != '/') {
                entryPath = entryPath.replace('/', File.separatorChar);
                libPath = libPath.replace('/', File.separatorChar);
            }
            File dir = new File(root, entryPath);
            // Check file entries in root
            String[] entries = dir.list();
            if (entries != null) { // may not be a directory
                for (String entry : entries) {
                    ret.add(path + entry);
                }
            }
            // Check entries in jars in WEB-INF/lib
            dir = new File(root, libPath);
            if (dir.exists() && dir.isDirectory()) {
                FilenameFilter filenameFilter = new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.toLowerCase().endsWith(".jar");
                    }
                };
                entries = dir.list(filenameFilter);
                if (entries != null) {
                    for (String entry : entries) {
                        libJarFiles.add(contextClassLoader.getFile(libPath + entry));
                    }
                }
            }
        } else { // war file
            try (JarFile warFile = new JarFile(root)) {
                Enumeration<JarEntry> i = warFile.entries();
                if (i != null) {
                    while (i.hasMoreElements()) {
                        JarEntry jarEntry = i.nextElement();
                        String entry = jarEntry.getName();
                        // entries in root
                        if (entry.startsWith(entryPath) && !entry.equals(entryPath)) {
                            // check that it is directly contained in entryPath
                            if (entry.indexOf('/', entryPath.length()) == -1) {
                                ret.add(entry);
                            }
                        }
                        // entries in jar in WEB-INF/lib
                        if (entry.startsWith(libPath) && entry.toLowerCase().endsWith(".jar")) {
                            // Check that jar is directly contained in lib
                            if (entry.indexOf('/', libPath.length()) == -1) {
                                libJarFiles.add(contextClassLoader.getFile(entry));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // TODO log
            }
        }
        if (searchJars) {
            // Search resources in lib jar files
            String prefix = "WEB-INF/resources/" + path;
            for (File file : libJarFiles) {
                try (JarFile jarFile = new JarFile(file)) {
                    Enumeration<JarEntry> jarEntries = jarFile.entries();
                    while (jarEntries.hasMoreElements()) {
                        JarEntry jarEntry = jarEntries.nextElement();
                        String entryName = jarEntry.getName();
                        if (entryName.startsWith(prefix)) {
                            String tail = entryName.substring(prefix.length());
                            int si = tail.indexOf('/');
                            if (si != -1) {
                                tail = tail.substring(0, si);
                            }
                            ret.add(path + tail);
                        }
                    }
                } catch (IOException e) {
                    // TODO log
                }
            }
        }
        return ret.isEmpty() ? null : ret;
    }

    /**
     * Search the given lib jar files and add any entries in their
     * WEB/INF/resources/ directory to the given collection.
     */
    private void searchJars(Collection<String> acc, List<File> libJarFiles, String path) {
    }

    /**
     * This will return a <code>resource:</code> URL. You can open a
     * connection to this URL to see its size and date and read its
     * contents. If you just want to read the contents, use @link
     * #getResourceAsStream(String) as this is more efficient.
     * @param path the path to the resource within this context
     */
    @Override public URL getResource(String path) throws MalformedURLException {
        boolean found = false;
        if (path == null || "".equals(path)) {
            return null;
        }
        if (path.charAt(0) != '/') {
            // it must start with /
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            // it can't end with /
            return null;
        }
        String entryPath = path.substring(1); // without leading /
        String libPath = "WEB-INF/lib/";
        List<File> libJarFiles = new ArrayList<>(); // list of jar files to search WEB-INF/resources
        if (root.isDirectory()) {
            if (File.separatorChar != '/') {
                entryPath = entryPath.replace('/', File.separatorChar);
                libPath = libPath.replace('/', File.separatorChar);
            }
            File file = new File(root, entryPath);
            if (file.exists() && file.isFile()) {
                found = true;
            } else {
                // Check entries in jars in WEB-INF/lib
                File dir = new File(root, libPath);
                if (dir.exists() && dir.isDirectory()) {
                    FilenameFilter filenameFilter = new FilenameFilter() {
                        public boolean accept(File dir, String name) {
                            return name.toLowerCase().endsWith(".jar");
                        }
                    };
                    String[] entries = dir.list(filenameFilter);
                    if (entries != null) {
                        for (String entry : entries) {
                            libJarFiles.add(contextClassLoader.getFile(libPath + entry));
                        }
                    }
                }
            }
        } else { // war file
            try (JarFile warFile = new JarFile(root)) {
                JarEntry jarEntry = warFile.getJarEntry(entryPath);
                if (jarEntry != null) {
                    // entry in root
                    found = true;
                } else {
                    Enumeration<JarEntry> i = warFile.entries();
                    if (i != null) {
                        while (i.hasMoreElements()) {
                            jarEntry = i.nextElement();
                            String entry = jarEntry.getName();
                            // entries in jar in WEB-INF/lib
                            if (entry.startsWith(libPath) && entry.toLowerCase().endsWith(".jar")) {
                                // Check that jar is directly contained in lib
                                if (entry.indexOf('/', libPath.length()) == -1) {
                                    libJarFiles.add(contextClassLoader.getFile(entry));
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // TODO log
            }
        }
        if (!found) {
            // Search resources in lib jar files
            entryPath = "WEB-INF/resources/" + entryPath;
            for (File file : libJarFiles) {
                try (JarFile jarFile = new JarFile(file)) {
                    JarEntry jarEntry = jarFile.getJarEntry(entryPath);
                    if (jarEntry != null) {
                        found = true;
                        break;
                    }
                } catch (IOException e) {
                    // TODO log
                }
            }
            if (!found) {
                return null;
            }
        }
        String host = contextPath;
        return new URL("resource", host, path);
    }

    /**
     * Returns an InputStream from which the given resource's contents can
     * be read, or null if the resource is not valid in this context.
     * @param path the path to the resource within this context
     */
    @Override public InputStream getResourceAsStream(String path) {
        if (path == null || "".equals(path)) {
            return null;
        }
        if (path.charAt(0) != '/') {
            // it must start with /
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            // it can't end with /
            return null;
        }
        String entryPath = path.substring(1); // without leading /
        String libPath = "WEB-INF/lib/";
        List<File> libJarFiles = new ArrayList<>(); // list of jar files to search WEB-INF/resources
        try {
            if (root.isDirectory()) {
                if (File.separatorChar != '/') {
                    entryPath = entryPath.replace('/', File.separatorChar);
                    libPath = libPath.replace('/', File.separatorChar);
                }
                File file = new File(root, entryPath);
                if (file.exists() && file.isFile()) {
                    return new FileInputStream(file);
                } else {
                    // Check entries in jars in WEB-INF/lib
                    File dir = new File(root, libPath);
                    if (dir.exists() && dir.isDirectory()) {
                        FilenameFilter filenameFilter = new FilenameFilter() {
                            public boolean accept(File dir, String name) {
                                return name.toLowerCase().endsWith(".jar");
                            }
                        };
                        String[] entries = dir.list(filenameFilter);
                        if (entries != null) {
                            for (String entry : entries) {
                                libJarFiles.add(contextClassLoader.getFile(libPath + entry));
                            }
                        }
                    }
                }
            } else { // war file
                JarFile warFile = new JarFile(root); // NB we cannot auto-close the jarfile, use JarInputStream
                JarEntry jarEntry = warFile.getJarEntry(entryPath);
                if (jarEntry != null) {
                    // entry in war file
                    return new JarInputStream(warFile, jarEntry);
                } else {
                    Enumeration<JarEntry> i = warFile.entries();
                    if (i != null) {
                        while (i.hasMoreElements()) {
                            jarEntry = i.nextElement();
                            String entry = jarEntry.getName();
                            // entries in jar in WEB-INF/lib
                            if (entry.startsWith(libPath) && entry.toLowerCase().endsWith(".jar")) {
                                // Check that jar is directly contained in lib
                                if (entry.indexOf('/', libPath.length()) == -1) {
                                    libJarFiles.add(contextClassLoader.getFile(entry));
                                }
                            }
                        }
                    }
                    // now close it
                    warFile.close();
                }
            }
            // Nothing matched so far
            // Search resources in lib jar files
            entryPath = "WEB-INF/resources/" + entryPath;
            for (File file : libJarFiles) {
                JarFile jarFile = new JarFile(file); // NB we cannot auto-close it, use JarInputStream
                JarEntry jarEntry = jarFile.getJarEntry(entryPath);
                if (jarEntry != null) {
                    return new JarInputStream(jarFile, jarEntry);
                } else {
                    jarFile.close();
                }
            }
        } catch (IOException e) {
            // TODO log
        }
        return null;
    }

    @Override public synchronized RequestDispatcher getRequestDispatcher(String path) {
        invalidateSessions(false);
        // Strip anchor
        int hi = path.indexOf('#');
        if (hi != -1) {
            path = path.substring(0, hi);
        }
        // Extract query-string
        String queryString = null;
        int qi = path.indexOf('?');
        if (qi != -1) {
            queryString = path.substring(qi + 1);
            path = path.substring(0, qi);
        }
        // Locate the target servlet
        ServletMatch match = new ServletMatch();
        matchServletMapping(path, match);
        if (match.servletDef == null) {
            for (String welcomeFile : welcomeFiles) {
                String welcomePath = path + welcomeFile;
                matchServletMapping(welcomePath, match);
                if (match.servletDef != null) {
                    break;
                }
            }
        }

        // 4. default servlet if none match
        if (match.servletDef == null) {
            match.servletDef = defaultServletDef;
            match.mappingMatch = MappingMatch.DEFAULT;
            match.matchValue = path;
            match.servletPath = "/";
            match.pathInfo = "/".equals(path) ? null : path;
            // DefaultServlet will just look for resources via getResource.
            // Ensure that if the resource doesn't exist we try welcome
            // files.
            try {
                URL resource = getResource(path);
                if (resource == null) {
                    String pathDir = path.endsWith("/") ? path : path + "/";
                    for (String welcomeFile : welcomeFiles) {
                        String welcomePath = pathDir + welcomeFile;
                        resource = getResource(welcomePath);
                        if (resource != null) {
                            path = match.matchValue = welcomePath;
                            match.pathInfo = path;
                            break;
                        }
                    }
                }
            } catch (MalformedURLException e) {
                RuntimeException e2 = new RuntimeException();
                e2.initCause(e);
                throw e2;
            }
        }
        ServletDef servletDef = match.servletDef;
        String servletPath = match.servletPath;
        String pathInfo = match.pathInfo;

        // Locate applicable filters
        Map<String,FilterMatch> requestFilters = new LinkedHashMap<>();
        for (FilterMapping filterMapping : filterMappings) {
            String filterName = filterMapping.name;
            if (filterMapping.servletName != null
                    && filterMapping.servletName.equals(servletDef.name)) {
                FilterDef fd = filterDefs.get(filterName);
                if (fd != null) {
                    FilterMatch fm = new FilterMatch(fd, filterMapping);
                    requestFilters.put(filterName, fm);
                }
            } else if (filterMapping.urlPattern != null) {
                String pattern = filterMapping.urlPattern;
                if (pattern.equals(path)) {
                    // 1. exact match
                    FilterDef fd = filterDefs.get(filterName);
                    if (fd != null) {
                        FilterMatch fm = new FilterMatch(fd, filterMapping);
                        requestFilters.put(filterName, fm);
                    }
                } else if (pattern.endsWith("/*")
                        && path.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    // 2. match path prefix
                    FilterDef fd = filterDefs.get(filterName);
                    if (fd != null) {
                        FilterMatch fm = new FilterMatch(fd, filterMapping);
                        requestFilters.put(filterName, fm);
                    }
                } else if (pattern.startsWith("*.") && path.endsWith(pattern.substring(1))) {
                    // 3. extension
                    FilterDef fd = filterDefs.get(filterName);
                    if (fd != null) {
                        FilterMatch fm = new FilterMatch(fd, filterMapping);
                        requestFilters.put(filterName, fm);
                    }
                }
            }
        }
        // Build list of handlers and defs in filter order
        // Servlet is the last handler in the list
        List handlers = new ArrayList();
        for (String filterName : filterDefs.keySet()) {
            if (requestFilters.containsKey(filterName)) {
                FilterMatch fm = requestFilters.get(filterName);
                handlers.add(fm);
            }
        }
        // Add servlet def
        handlers.add(servletDef);
        return new ContextRequestDispatcher(this, match, queryString, handlers, false);
    }

    void matchServletMapping(String path, ServletMatch match) {
        for (ServletMapping servletMapping : servletMappings) {
            String servletName = servletMapping.name;
            String pattern = servletMapping.urlPattern;
            if (pattern.equals(path)) {
                // 1. exact match
                ServletDef sd = servletDefs.get(servletName);
                if (sd != null && sd != defaultServletDef) {
                    match.servletDef = sd;
                    match.servletPath = pattern;
                    match.pathInfo = null;
                    match.mappingMatch = "/".equals(path) ? MappingMatch.CONTEXT_ROOT : MappingMatch.EXACT;
                    match.matchValue = path;
                    break;
                }
            } else if (pattern.endsWith("/*")) {
                pattern = pattern.substring(0, pattern.length() - 2);
                if (path.startsWith(pattern)) {
                    // 2. longest path prefix
                    if (match.servletPath == null
                            || pattern.length() > match.servletPath.length()) {
                        ServletDef sd = servletDefs.get(servletName);
                        if (sd != null) {
                            match.servletDef = sd;
                            match.servletPath = pattern;
                            match.pathInfo = path.substring(pattern.length());
                            if (match.pathInfo.length() == 0) {
                                match.pathInfo = null;
                            }
                            match.mappingMatch = MappingMatch.PATH;
                            match.matchValue = path;
                        }
                    }
                }
            } else if (match.servletDef == null
                    && pattern.startsWith("*.")
                    && path.endsWith(pattern.substring(1))) {
                // 3. extension
                ServletDef sd = servletDefs.get(servletName);
                if (sd != null) {
                    match.servletDef = sd;
                    match.servletPath = path;
                    match.pathInfo = null;
                    match.mappingMatch = MappingMatch.EXTENSION;
                    match.matchValue = path;
                }
            }
        }
    }

    @Override public synchronized RequestDispatcher getNamedDispatcher(String name) {
        // This method cannot return a filter chain because the path by with the
        // servlet is accessed is not specified, only its name.
        ServletDef servletDef = servletDefs.get(name);
        if (servletDef != null) {
            List handlers = Collections.singletonList(servletDef);
            // TODO fabricate a ServletMatch
            return new ContextRequestDispatcher(this, null, null, handlers, true);
        }
        return null;
    }

    @Override public Servlet getServlet(String name) throws ServletException {
        return null; // deprecated
    }

    @Override public Enumeration<Servlet> getServlets() {
        return new IteratorEnumeration(); // deprecated
    }

    @Override public Enumeration<String> getServletNames() {
        return new IteratorEnumeration(); // deprecated
    }

    @Override public void log(String msg) {
        log(msg, null);
    }

    @Override public void log(Exception e, String msg) {
        log(msg, e);
    }

    @Override public void log(String msg, Throwable e) {
        if (e != null) {
            LOGGER.log(Level.WARNING, msg, e);
        } else {
            LOGGER.log(Level.INFO, msg);
        }
    }

    @Override public String getRealPath(String path) {
        throw new UnsupportedOperationException("ServletContext.getRealPath is a security vulnerability, do not use it");
    }

    @Override public String getServerInfo() {
        return "gumdrop/" + Server.VERSION;
    }

    @Override public synchronized String getInitParameter(String name) {
        String ret = initParams.get(name);
        if (ret != null) {
            return ret;
        }
        InitParam initParam = contextParams.get(name);
        return (initParam != null) ? initParam.value : null;
    }

    @Override public synchronized Enumeration<String> getInitParameterNames() {
        Set<String> ret = new LinkedHashSet<>();
        ret.addAll(contextParams.keySet());
        ret.addAll(initParams.keySet());
        return new IteratorEnumeration(ret);
    }

    @Override public boolean setInitParameter(String name, String value) {
        if (initParams.containsKey(name) || contextParams.containsKey(name)) {
            return false;
        }
        initParams.put(name, value);
        return true;
    }

    @Override public synchronized Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override public synchronized Enumeration<String> getAttributeNames() {
        return new IteratorEnumeration(attributes.keySet());
    }

    @Override public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
        } else {
            synchronized (this) {
                Object oldValue = attributes.put(name, value);
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(this, name, value);
                for (ServletContextAttributeListener l : servletContextAttributeListeners) {
                    if (oldValue == null) {
                        l.attributeAdded(event);
                    } else {
                        l.attributeReplaced(event);
                    }
                }
            }
        }
    }

    @Override public synchronized void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        ServletContextAttributeEvent event = new ServletContextAttributeEvent(this, name, oldValue);
        for (ServletContextAttributeListener l : servletContextAttributeListeners) {
            l.attributeRemoved(event);
        }
    }

    @Override public String getServletContextName() {
        return displayName;
    }

    public Servlet getDefaultServlet() {
        try {
            return loadServlet(defaultServletDef.name);
        } catch (ServletException e) {
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    // -- 3.0 --

    public int getEffectiveMajorVersion() {
        return majorVersion;
    }

    public int getEffectiveMinorVersion() {
        return minorVersion;
    }

    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        if (initialized) {
            throw new IllegalStateException();
        }
        ServletDef servletDef = new ServletDef();
        servletDef.name = servletName;
        servletDef.className = className;
        addServletDef(servletDef);
        return servletDef;
    }

    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return addServlet(servletName, servlet.getClass());
    }

    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> t) {
        if (t.getClassLoader() == contextClassLoader) {
            return addServlet(servletName, t.getName());
        } else {
            String message = L10N.getString("err.bad_servlet");
            message = MessageFormat.format(message, t.getName());
            throw new SecurityException(message);
        }
    }

    public <T extends Servlet> T createServlet(Class<T> t) throws ServletException {
        if (t.getClassLoader() == contextClassLoader) {
            // Find ServletDef by className
            ServletDef servletDef = null;
            for (ServletDef fd : servletDefs.values()) {
                if (fd.className.equals(t.getName())) {
                    servletDef = fd;
                    break;
                }
            }
            if (servletDef == null) {
                String message = L10N.getString("err.servlet_not_registered");
                message = MessageFormat.format(message, t.getName());
                throw new SecurityException(message);
            }
            Servlet servlet = servlets.get(servletDef.name);
            if (servlet == null) {
                servlet = servletDef.newInstance();
                servlets.put(servletDef.name, servlet);
            }
            return (T) servlet;
        } else {
            String message = L10N.getString("err.bad_servlet");
            message = MessageFormat.format(message, t.getName());
            throw new SecurityException(message);
        }
    }

    public ServletRegistration getServletRegistration(String servletName) {
        return servletDefs.get(servletName);
    }

    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        // Do not include defaultServlet
        Map<String,ServletDef> ret = new LinkedHashMap<>();
        for (ServletDef servletDef : servletDefs.values()) {
            if (servletDef.name != null) {
                ret.put(servletDef.name, servletDef);
            }
        }
        return Collections.unmodifiableMap(ret);
    }

    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        if (initialized) {
            throw new IllegalStateException();
        }
        FilterDef filterDef = new FilterDef();
        filterDef.name = filterName;
        filterDef.className = className;
        addFilterDef(filterDef);
        return filterDef;
    }

    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return addFilter(filterName, filter.getClass());
    }

    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> t) {
        if (t.getClassLoader() == contextClassLoader) {
            return addFilter(filterName, t.getName());
        } else {
            String message = L10N.getString("err.bad_filter");
            message = MessageFormat.format(message, t.getName());
            throw new SecurityException(message);
        }
    }

    public <T extends Filter> T createFilter(Class<T> t) throws ServletException {
        if (t.getClassLoader() == contextClassLoader) {
            // Find FilterDef by className
            FilterDef filterDef = null;
            for (FilterDef fd : filterDefs.values()) {
                if (fd.className.equals(t.getName())) {
                    filterDef = fd;
                    break;
                }
            }
            if (filterDef == null) {
                String message = L10N.getString("err.filter_not_registered");
                message = MessageFormat.format(message, t.getName());
                throw new SecurityException(message);
            }
            Filter filter = filters.get(filterDef.name);
            if (filter == null) {
                filter = filterDef.newInstance();
                filters.put(filterDef.name, filter);
            }
            return (T) filter;
        } else {
            String message = L10N.getString("err.bad_filter");
            message = MessageFormat.format(message, t.getName());
            throw new SecurityException(message);
        }
    }

    public FilterRegistration getFilterRegistration(String filterName) {
        return filterDefs.get(filterName);
    }

    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.unmodifiableMap(filterDefs);
    }

    @Override public SessionCookieConfig getSessionCookieConfig() {
        if (sessionConfig == null) {
            sessionConfig = new SessionConfig();
        }
        if (sessionConfig.cookieConfig == null) {
            sessionConfig.cookieConfig = new CookieConfig();
        }
        return sessionConfig.cookieConfig;
    }

    @Override public void setSessionTrackingModes(Set<SessionTrackingMode> set) {
        if (sessionConfig == null) {
            sessionConfig = new SessionConfig();
        }
        sessionConfig.trackingModes.clear();
        sessionConfig.trackingModes.addAll(set);
    }

    @Override public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Collections.<SessionTrackingMode>emptySet();
    }

    @Override public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return Collections.<SessionTrackingMode>emptySet();
    }

    @Override public void addListener(String className) {
        try {
            addListener((Class<? extends EventListener>) Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
    }

    @Override public void addListener(EventListener listener) {
        if (initialized) {
            throw new IllegalStateException();
        }
        // check classloader
        Class t = listener.getClass();
        if (t.getClassLoader() != contextClassLoader) {
            String message = L10N.getString("err.bad_listener");
            message = MessageFormat.format(message, t.getName());
            throw new SecurityException(message);
        }
        boolean match = false;
        if (listener instanceof ServletContextListener) {
            match = true;
            servletContextListeners.add((ServletContextListener) listener);
        }
        if (listener instanceof ServletContextAttributeListener) {
            match = true;
            servletContextAttributeListeners.add((ServletContextAttributeListener) listener);
        }
        if (listener instanceof HttpSessionListener) {
            match = true;
            sessionListeners.add((HttpSessionListener) listener);
        }
        if (listener instanceof HttpSessionAttributeListener) {
            match = true;
            sessionAttributeListeners.add((HttpSessionAttributeListener) listener);
        }
        if (listener instanceof HttpSessionActivationListener) {
            match = true;
            sessionActivationListeners.add((HttpSessionActivationListener) listener);
        }
        if (listener instanceof HttpSessionBindingListener) {
            match = true;
            sessionBindingListeners.add((HttpSessionBindingListener) listener);
        }
        if (listener instanceof ServletRequestListener) {
            match = true;
            servletRequestListeners.add((ServletRequestListener) listener);
        }
        if (listener instanceof ServletRequestAttributeListener) {
            match = true;
            servletRequestAttributeListeners.add((ServletRequestAttributeListener) listener);
        }
        if (!match) {
            throw new IllegalArgumentException();
        }
    }

    @Override public void addListener(Class<? extends EventListener> t) {
        try {
            addListener(createListener(t));
        } catch (ServletException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
    }

    @Override public <T extends EventListener> T createListener(Class<T> listenerClass) throws ServletException {
        try {
            // Check that listenerClass has been loaded by the
            // ContextClassLoader. If not, reload it
            ClassLoader lcl = listenerClass.getClassLoader();
            if (lcl != contextClassLoader) {
                String name = listenerClass.getName();
                Class<?> loadedClass = contextClassLoader.loadClass(name);
                if (!listenerClass.isAssignableFrom(loadedClass)) {
                    String message = L10N.getString("err.class_not_assignable");
                    message = MessageFormat.format(message, loadedClass.getName(), name);
                    throw new ServletException(message);
                }
                listenerClass = (Class<T>) loadedClass;
            }
            return listenerClass.newInstance();
        } catch (ClassNotFoundException e) {
            throw (ServletException) new ServletException().initCause(e);
        } catch (InstantiationException e) {
            throw (ServletException) new ServletException().initCause(e);
        } catch (IllegalAccessException e) {
            throw (ServletException) new ServletException().initCause(e);
        }
    }

    public JspConfigDescriptor getJspConfigDescriptor() {
        return null; // TODO
    }

    @Override public ClassLoader getClassLoader() {
        return contextClassLoader;
    }

    @Override public void declareRoles(String... roleNames) {
        if (roleNames != null) {
            for (int i = 0; i < roleNames.length; i++) {
                SecurityRole role = new SecurityRole();
                role.roleName = roleNames[i];
                securityRoles.add(role);
            }
        }
    }

    // -- 4.0 --

    @Override public void setRequestCharacterEncoding(String s) {
        requestCharacterEncoding = s;
    }

    @Override public String getRequestCharacterEncoding() {
        return requestCharacterEncoding;
    }

    @Override public void setResponseCharacterEncoding(String s) {
        responseCharacterEncoding = s;
    }

    @Override public String getResponseCharacterEncoding() {
        return responseCharacterEncoding;
    }

    @Override public void setSessionTimeout(int timeout) {
        sessionTimeout = timeout;
    }

    @Override public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override public String getVirtualServerName() {
        return null; // TODO
    }

    @Override public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        return null; // TODO
    }

}
