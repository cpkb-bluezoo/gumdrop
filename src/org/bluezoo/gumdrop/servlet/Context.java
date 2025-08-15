/*
 * Context.java
 * Copyright (C) 2005, 2013 Chris Burdess
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

import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.util.IteratorEnumeration;

import org.xml.sax.SAXException;

import java.io.*;
import java.net.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
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

/**
 * The application context represents the single point of contact for all
 * servlets and associated data in the application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Context implements ServletContext {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    static final Logger LOGGER = Logger.getLogger("org.bluezoo.gumdrop.servlet");

    final Container container;
    final String contextPath;
    final URL rootUrl;
    final File root;
    final JarFile warFile;
    ServletConnector connector;
    private ContextClassLoader contextClassLoader;
    byte[] digest; // MD5 digest of web.xml

    List<ResourceDef> resourceDefs = new ArrayList<>();
    Map<String,Realm> realms = new LinkedHashMap<>();

    int major = 2;
    int minor = 4;
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;
    Map<String,InitParam> initParams = new LinkedHashMap<>();
    Map<String,String> initParams2 = new LinkedHashMap<>();
    Map<String,Object> attributes = new LinkedHashMap<>();
    Map<String,FilterDef> filterDefs = new LinkedHashMap<>();
    List<FilterMapping> filterMappings = new ArrayList<>();
    Map<String,Filter> filters = new LinkedHashMap<>();
    List<ListenerDef> listenerDefs = new ArrayList<>();
    Map<String,ServletDef> servletDefs = new LinkedHashMap<>();
    List<ServletMapping> servletMappings = new ArrayList<>();
    Map<String,Servlet> servlets = new LinkedHashMap<>();
    List<MimeMapping> mimeMappings = new ArrayList<>();
    List<String> welcomeFiles = new ArrayList<>();
    List<ErrorPage> errorPages = new ArrayList<>();
    List<JspConfig> jspConfigs = new ArrayList<>();
    List<SecurityConstraint> securityConstraints = new ArrayList<>();

    Collection<ServletContextListener> servletContextListeners = new ConcurrentLinkedDeque<>();
    Collection<ServletContextAttributeListener> servletContextAttributeListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionListener> sessionListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionAttributeListener> sessionAttributeListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionActivationListener> sessionActivationListeners = new ConcurrentLinkedDeque<>();
    Collection<HttpSessionBindingListener> sessionBindingListeners = new ConcurrentLinkedDeque<>();
    Collection<ServletRequestListener> servletRequestListeners = new ConcurrentLinkedDeque<>();
    Collection<ServletRequestAttributeListener> servletRequestAttributeListeners = new ConcurrentLinkedDeque<>();

    boolean authentication;
    String authMethod;
    String realmName;
    String formLoginPage;
    String formErrorPage;
    List<SecurityRole> securityRoles = new ArrayList<>();
    Map<String,String> localeEncodingMappings = new HashMap<>();
    List<ServletDataSource> dataSources = new ArrayList<>();
    Map<String,Session> sessions = new HashMap<>();
    int sessionTimeout = -1;
    long sessionsLastInvalidated;
    boolean distributable;
    boolean initialized;

    ServletDef defaultServletDef;

    String secureHost;
    String commonDir;

    HitStatistics hitStatistics = new HitStatistics();

    // TODO session activation/passivation, distributed sessions

    public Context(Container container, String contextPath, URL rootUrl, File root, JarFile warFile) {
        this.container = container;
        this.contextPath = contextPath;
        this.rootUrl = rootUrl;
        this.root = root;
        this.warFile = warFile;

        sessionsLastInvalidated = System.currentTimeMillis();

        reset();
    }

    public void setSecureHost(String value) {
        secureHost = value;
    }

    public void setCommonDir(String value) {
        commonDir = value;
    }

    void reset() {
        description = null;
        displayName = null;
        smallIcon = null;
        largeIcon = null;
        initParams.clear();
        attributes.clear();
        filterDefs.clear();
        filterMappings.clear();
        filters.clear();
        listenerDefs.clear();
        servletContextListeners.clear();
        servletContextAttributeListeners.clear();
        sessionListeners.clear();
        sessionAttributeListeners.clear();
        sessionActivationListeners.clear();
        sessionBindingListeners.clear();
        servletRequestListeners.clear();
        servletRequestAttributeListeners.clear();
        servletDefs.clear();
        servletMappings.clear();
        servlets.clear();
        mimeMappings.clear();
        welcomeFiles.clear();
        errorPages.clear();
        jspConfigs.clear();
        authentication = false;
        authMethod = null;
        realmName = null;
        formLoginPage = null;
        formErrorPage = null;
        securityConstraints.clear();
        securityRoles.clear();
        localeEncodingMappings.clear();
        distributable = false;
        initialized = false;

        dataSources.clear();
        contextClassLoader = null;

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

        hitStatistics = new HitStatistics();
    }

    /**
     * Loads this context from the deployment descriptor.
     */
    public void load() throws IOException, SAXException {
        URL webXml = getResource("/WEB-INF/web.xml");
        if (webXml != null) {
            WebAppParser parser = new WebAppParser(this);
            parser.parse(webXml);
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
            defaultServletDef = new ServletDef(this);
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
     * Reloads this context.
     */
    public void reload() throws IOException, SAXException {
        reset();
        load();
    }

    /**
     * Initializes this context and all filters and servlets in it.
     * @see SRV.9.12
     */
    public synchronized void init() {
        // Init resources
        try {
            ServletInitialContext ctx = (ServletInitialContext) new InitialContext().lookup("");
            for (ResourceDef resourceDef : resourceDefs) {
                Object resource = resourceDef.newInstance();
                if (resource instanceof ServletDataSource) {
                    dataSources.add((ServletDataSource) resource);
                }
                ctx.bind("java:comp/env/" + resourceDef.name, resourceDef.type, resource);
            }
        } catch (NamingException e) {
            String message = L10N.getString("err.init_resource");
            LOGGER.log(Level.SEVERE, message, e);
        }
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(getContextClassLoader());
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
        thread.setContextClassLoader(loader);
        initialized = true;
    }

    /**
     * Destroys this context and all filters and servlets in it.
     */
    public synchronized void destroy() {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(getContextClassLoader());
        invalidateSessions(true);
        // Destroy servlets
        for (Servlet servlet : servlets.values()) {
            servlet.destroy();
        }
        // Destroy filters
        for (Filter filter : filters.values()) {
            filter.destroy();
        }
        // Close data sources
        for (ServletDataSource ds : dataSources) {
            ds.close();
        }
        // Inform servlet context listeners
        ServletContextEvent event = new ServletContextEvent(this);
        for (ServletContextListener l : servletContextListeners) {
            l.contextDestroyed(event);
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

    public void addResource(ResourceDef resource) {
        resourceDefs.add(resource);
    }

    public boolean setInitParameter(String name, String value) {
        if (initParams2.containsKey(name)) {
            return false;
        }
        initParams2.put(name, value);
        return true;
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
     * Ensure that one and only one conext classloader is used to load all
     * servlets and the classes they reference (SRV.3.7).
     */
    ClassLoader getContextClassLoader() {
        if (contextClassLoader == null) {
            contextClassLoader = new ContextClassLoader(this);
        }
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

    public ServletContext getContext(String uripath) {
        return container.getContext(uripath);
    }

    public String getContextPath() {
        return contextPath;
    }

    public int getMajorVersion() {
        return major;
    }

    public int getMinorVersion() {
        return minor;
    }

    public String getMimeType(String file) {
        for (MimeMapping mimeMapping : mimeMappings) {
            if (file.endsWith(mimeMapping.extension)) {
                return mimeMapping.mimeType;
            }
        }
        return null;
    }

    public Set getResourcePaths(String path) {
        Set ret = null;
        if (warFile == null) {
            if (File.separatorChar != '/') {
                path = path.replace('/', File.separatorChar);
            }
            File dir = new File(root, path);
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            String[] entries = dir.list();
            if (entries == null) {
                return null;
            }
            ret = new LinkedHashSet();
            for (int i = 0; i < entries.length; i++) {
                ret.add(path + entries[i]);
            }
        } else {
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Enumeration i = warFile.entries();
            if (i == null) {
                return null;
            }
            ret = new LinkedHashSet();
            while (i.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) i.nextElement();
                String entry = jarEntry.getName();
                if (entry.startsWith(path) && !entry.equals(path)) {
                    ret.add(entry);
                }
            }
        }
        return ret;
    }

    public URL getResource(String path) throws MalformedURLException {
        path = getResourcePath(path);
        URL ret = null;
        if (path != null) {
            if (path.length() > 0) ret = new URL(rootUrl, path);
            else ret = rootUrl;
        }
        return ret;
    }

    public InputStream getResourceAsStream(String path) {
        path = getResourcePath(path);
        if (path == null) {
            return null;
        }
        try {
            if (warFile == null) {
                if (File.separatorChar != '/') {
                    path = path.replace('/', File.separatorChar);
                }
                File file = new File(root, path);
                return new FileInputStream(file);
            } else {
                JarEntry jarEntry = warFile.getJarEntry(path);
                return warFile.getInputStream(jarEntry);
            }
        } catch (IOException e) {
            log(path, e);
        }
        return null;
    }

    /**
     * Returns a valid path to the specified resource, or null.
     */
    String getResourcePath(String path) {
        while (path.length() > 0 && path.charAt(0) == '/') {
            path = path.substring(1);
        }
        path = canonicalize(path);
        // Locate resource
        if (!exists(path)) {
            // Look for welcome-file
            if (path.length() > 0 && !path.endsWith("/")) {
                path = path + "/";
            }
            for (String s : welcomeFiles) {
                String welcomeFile = path + s;
                if (exists(welcomeFile)) {
                    return welcomeFile;
                }
            }
            return null;
        }
        return path;
    }

    boolean exists(String path) {
        // Check for a static resource
        if (warFile == null) {
            if (File.separatorChar != '/') {
                path = path.replace('/', File.separatorChar);
            }
            File file = new File(root, path);
            return file.exists() && !file.isDirectory();
        } else {
            JarEntry entry = warFile.getJarEntry(path);
            return (entry != null);
        }
    }

    /**
     * Canonicalize a URI-path.
     * This removes any ".." or "." components in the path.
     * It raises an error if an attempt was made to reference a resource
     * above the root of the hierarchy.
     */
    String canonicalize(String path) {
        int start = 0, end = -1;
        boolean modified = false;
        int count = 0;
        Deque<String> stack = new ArrayDeque<String>();
        for (end = path.indexOf('/'); end > start; end = path.indexOf('/', start)) {
            String comp = path.substring(start, end);
            if ("..".equals(comp)) {
                stack.removeLast();
                count--;
                modified = true;
            } else if (".".equals(comp)) {
                modified = true;
            } else {
                stack.addLast(comp);
                count++;
            }
            start = end + 1;
        }
        if (!modified) {
            return path;
        }
        StringBuilder buf = new StringBuilder();
        for (String s : stack) {
            buf.append(s);
            count--;
            if (count > 0) {
                buf.append('/');
            }
        }
        return buf.toString();
    }

    public synchronized RequestDispatcher getRequestDispatcher(String path) {
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
        String originalPath = path;
        // Locate the target servlet
        ServletMatch match = new ServletMatch();
        matchServletMapping(path, match);
        if (match.servletDef == null) {
            Iterator i = welcomeFiles.iterator();
            while (match.servletDef == null && i.hasNext()) {
                String welcomeFile = (String) i.next();
                path = originalPath + welcomeFile;
                matchServletMapping(path, match);
            }
        }

        // 4. default servlet if none match
        if (match.servletDef == null) {
            match.servletDef = defaultServletDef;
            match.servletPath = "/";
            match.pathInfo = "/".equals(originalPath) ? null : originalPath.substring(1);
            path = originalPath;
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
                if (pattern.equals(path))
                // 1. exact match
                {
                    FilterDef fd = filterDefs.get(filterName);
                    if (fd != null) {
                        FilterMatch fm = new FilterMatch(fd, filterMapping);
                        requestFilters.put(filterName, fm);
                    }
                } else if (pattern.endsWith("/*")
                        && path.startsWith(pattern.substring(0, pattern.length() - 1)))
                // 2. match path prefix
                {
                    FilterDef fd = filterDefs.get(filterName);
                    if (fd != null) {
                        FilterMatch fm = new FilterMatch(fd, filterMapping);
                        requestFilters.put(filterName, fm);
                    }
                } else if (pattern.startsWith("*.") && path.endsWith(pattern.substring(1)))
                // 3. extension
                {
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
        return new ContextRequestDispatcher(
                this, servletPath, pathInfo, queryString, handlers, false);
    }

    void matchServletMapping(String path, ServletMatch match) {
        for (ServletMapping servletMapping : servletMappings) {
            String servletName = servletMapping.name;
            String pattern = servletMapping.urlPattern;
            if (pattern.equals(path))
            // 1. exact match
            {
                ServletDef sd = servletDefs.get(servletName);
                if (sd != null && sd != defaultServletDef) {
                    match.servletDef = sd;
                    match.servletPath = pattern;
                    match.pathInfo = null;
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
                        }
                    }
                }
            } else if (match.servletDef == null
                    && pattern.startsWith("*.")
                    && path.endsWith(pattern.substring(1)))
            // 3. extension
            {
                ServletDef sd = servletDefs.get(servletName);
                if (sd != null) {
                    match.servletDef = sd;
                    match.servletPath = path;
                    match.pathInfo = null;
                }
            }
        }
    }

    public synchronized RequestDispatcher getNamedDispatcher(String name) {
        // This method cannot return a filter chain because the path by with the
        // servlet is accessed is not specified, only its name.
        ServletDef servletDef = servletDefs.get(name);
        if (servletDef != null) {
            List handlers = Collections.singletonList(servletDef);
            return new ContextRequestDispatcher(this, null, null, null, handlers, true);
        }
        return null;
    }

    public Servlet getServlet(String name) throws ServletException {
        return null; // deprecated
    }

    public Enumeration getServlets() {
        return new IteratorEnumeration(); // deprecated
    }

    public Enumeration getServletNames() {
        return new IteratorEnumeration(); // deprecated
    }

    public void log(String msg) {
        log(msg, null);
    }

    public void log(Exception e, String msg) {
        log(msg, e);
    }

    public void log(String msg, Throwable e) {
        if (e != null) {
            LOGGER.log(Level.WARNING, msg, e);
        } else {
            LOGGER.log(Level.INFO, msg);
        }
    }

    public String getRealPath(String path) {
        if (warFile == null) {
            if (File.separatorChar != '/') {
                path = path.replace('/', File.separatorChar);
            }
            File file = new File(root, path);
            return file.getAbsolutePath();
        }
        path = getResourcePath(path);
        if (path != null) {
            // check context classloader to see if file is cached
            File file = contextClassLoader.jarCache.get(path);
            if (file == null) {
                // expand jar entry into temporary file resource
                String fileName = path.replace('/', '_');
                try {
                    JarEntry jarEntry = warFile.getJarEntry(path);
                    InputStream in = warFile.getInputStream(jarEntry);
                    file = File.createTempFile("gumdrop", fileName);
                    ContextClassLoader.copy(in, file);
                    in.close();
                    file.deleteOnExit();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    return null;
                }
            }
            return file.getAbsolutePath();
        }
        return null;
    }

    public String getServerInfo() {
        return "gumdrop/" + Server.VERSION;
    }

    public synchronized String getInitParameter(String name) {
        String ret = initParams2.get(name);
        if (ret != null) {
            return ret;
        }
        InitParam initParam = initParams.get(name);
        return (initParam != null) ? initParam.value : null;
    }

    public synchronized Enumeration getInitParameterNames() {
        Set ret = new LinkedHashSet();
        ret.addAll(initParams.keySet());
        ret.addAll(initParams2.keySet());
        return new IteratorEnumeration(ret);
    }

    public synchronized Object getAttribute(String name) {
        return attributes.get(name);
    }

    public synchronized Enumeration getAttributeNames() {
        return new IteratorEnumeration(attributes.keySet());
    }

    public void setAttribute(String name, Object value) {
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

    public synchronized void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        ServletContextAttributeEvent event = new ServletContextAttributeEvent(this, name, oldValue);
        for (ServletContextAttributeListener l : servletContextAttributeListeners) {
            l.attributeRemoved(event);
        }
    }

    public String getServletContextName() {
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
        return 2;
    }

    public int getEffectiveMinorVersion() {
        return 4;
    }

    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return null; // TODO
    }

    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return null; // TODO
    }

    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> t) {
        return null; // TODO
    }

    public <T extends Servlet> T createServlet(Class<T> t) throws ServletException {
        return null; // TODO
    }

    public ServletRegistration getServletRegistration(String servletName) {
        return null; // TODO
    }

    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return null; // TODO
    }

    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        return null; // TODO
    }

    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return null; // TODO
    }

    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> t) {
        return null; // TODO
    }

    public <T extends Filter> T createFilter(Class<T> t) throws ServletException {
        return null; // TODO
    }

    public FilterRegistration getFilterRegistration(String filterName) {
        return null; // TODO
    }

    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return null; // TODO
    }

    public SessionCookieConfig getSessionCookieConfig() {
        return null; // TODO
    }

    public void setSessionTrackingModes(Set<SessionTrackingMode> set) {
        // TODO
    }

    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return null; // TODO
    }

    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return null; // TODO
    }

    public void addListener(String className) {
        try {
            addListener((Class<? extends EventListener>) Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
    }

    public void addListener(EventListener listener) {
        if (initialized) {
            throw new IllegalStateException();
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

    public void addListener(Class<? extends EventListener> t) {
        try {
            addListener(createListener(t));
        } catch (ServletException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
    }

    public <T extends EventListener> T createListener(Class<T> listenerClass) throws ServletException {
        try {
            return listenerClass.newInstance();
        } catch (InstantiationException e) {
            throw (ServletException) new ServletException().initCause(e);
        } catch (IllegalAccessException e) {
            throw (ServletException) new ServletException().initCause(e);
        }
    }

    public JspConfigDescriptor getJspConfigDescriptor() {
        return null; // TODO
    }

    public ClassLoader getClassLoader() {
        return contextClassLoader;
    }

    public void declareRoles(String... roleNames) {
        if (roleNames != null) {
            for (int i = 0; i < roleNames.length; i++) {
                SecurityRole role = new SecurityRole();
                role.roleName = roleNames[i];
                securityRoles.add(role);
            }
        }
    }

    // -- 4.0 --

    public void setRequestCharacterEncoding(String s) {
        // TODO ?
    }

    public String getRequestCharacterEncoding() {
        return null; // TODO ?
    }

    public void setResponseCharacterEncoding(String s) {
        // TODO ?
    }

    public String getResponseCharacterEncoding() {
        return null; // TODO ?
    }

    public void setSessionTimeout(int timeout) {
        // TODO ?
    }

    public int getSessionTimeout() {
        return 0; // TODO ?
    }

    public String getVirtualServerName() {
        return null;
    }

    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        return null;
    }

}
