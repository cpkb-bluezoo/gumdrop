/*
 * Context.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.ContainerClassLoader;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.servlet.jndi.AdministeredObject;
import org.bluezoo.gumdrop.servlet.jndi.ConnectionFactory;
import org.bluezoo.gumdrop.servlet.jndi.DataSourceDef;
import org.bluezoo.gumdrop.servlet.jndi.EjbRef;
import org.bluezoo.gumdrop.servlet.jndi.Injectable;
import org.bluezoo.gumdrop.servlet.jndi.InjectionTarget;
import org.bluezoo.gumdrop.servlet.jndi.JmsConnectionFactory;
import org.bluezoo.gumdrop.servlet.jndi.JmsDestination;
import org.bluezoo.gumdrop.servlet.jndi.JndiContext;
import org.bluezoo.gumdrop.servlet.jndi.MailSession;
import org.bluezoo.gumdrop.servlet.jndi.PersistenceContextRef;
import org.bluezoo.gumdrop.servlet.jndi.PersistenceUnitRef;
import org.bluezoo.gumdrop.servlet.jndi.Resource;
import org.bluezoo.gumdrop.servlet.jndi.ResourceRef;
import org.bluezoo.gumdrop.servlet.jndi.ServiceRef;
import org.bluezoo.gumdrop.servlet.jndi.ServletInitialContext;
import org.bluezoo.gumdrop.servlet.manager.ManagerContainerService;
import org.bluezoo.gumdrop.servlet.manager.ManagerContextService;
import org.bluezoo.gumdrop.servlet.manager.HitStatistics;
import org.bluezoo.gumdrop.servlet.session.SessionContext;
import org.bluezoo.gumdrop.servlet.session.SessionManager;
import org.bluezoo.gumdrop.util.IteratorEnumeration;
import org.bluezoo.gumdrop.util.JarInputStream;
import org.bluezoo.util.ByteArrays;

import org.xml.sax.SAXException;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resources;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RunAs;
import javax.ejb.EJB;
import javax.ejb.EJBs;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContexts;
import javax.persistence.PersistenceUnit;
import javax.persistence.PersistenceUnits;
import javax.servlet.*;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;
import javax.servlet.descriptor.JspConfigDescriptor;
import org.bluezoo.gumdrop.servlet.jsp.InMemoryJavaCompiler;
import org.bluezoo.gumdrop.servlet.jsp.JSPCodeGenerator;
import org.bluezoo.gumdrop.servlet.jsp.JSPDependencyTracker;
import org.bluezoo.gumdrop.servlet.jsp.JSPPage;
import org.bluezoo.gumdrop.servlet.jsp.JSPParseException;
import org.bluezoo.gumdrop.servlet.jsp.JSPParserFactory;
import org.bluezoo.gumdrop.servlet.jsp.JSPPropertyGroupResolver;
import org.bluezoo.gumdrop.servlet.jsp.JSPServlet;
import org.bluezoo.gumdrop.servlet.jsp.TaglibRegistry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.tools.StandardJavaFileManager;
import javax.tools.JavaFileObject;
import java.net.URLClassLoader;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import javax.servlet.http.MappingMatch;
import javax.xml.ws.WebServiceRef;
import javax.xml.ws.WebServiceRefs;

/**
 * The application context represents the single point of contact for all
 * servlets and associated data in the application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Context extends DeploymentDescriptor implements ManagerContextService, SessionContext, Comparator<WebFragment> {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    static final Logger LOGGER = Logger.getLogger("org.bluezoo.gumdrop.servlet");

    /**
     * Filename filter for JAR files.
     */
    private static final java.io.FilenameFilter JAR_FILTER = new java.io.FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".jar");
        }
    };

    Container container;
    String contextPath;
    File root;
    private ContainerClassLoader containerClassLoader;
    private ContextClassLoader contextClassLoader;
    ServletServer server;
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

    SessionManager sessionManager;
    int sessionTimeout = -1;
    long sessionsLastInvalidated;

    boolean distributable;
    boolean initialized;

    String moduleName; // TODO EAR
    String defaultContextPath; // TODO EAR
    String requestCharacterEncoding;
    String responseCharacterEncoding;
    List<String> absoluteOrdering = new ArrayList<>();

    Map<String,? extends ServletRegistration> servletRegistrations = new LinkedHashMap<>();

    // JSP configuration
    JspConfigDescriptor jspConfig;
    
    // Working SAX parser factory and JSP parser factory
    private javax.xml.parsers.SAXParserFactory saxParserFactory;
    private JSPParserFactory jspParserFactory;
    
    // In-memory JSP compiler (eliminates temp file I/O)
    private InMemoryJavaCompiler jspCompiler;
    
    // JSP dependency tracker for incremental compilation
    private JSPDependencyTracker jspDependencyTracker;
    
    Map<String,? extends FilterRegistration> filterRegistrations = new LinkedHashMap<>();

    ServletDef defaultServletDef;

    String secureHost;
    String commonDir;

    HitStatisticsImpl hitStatistics = new HitStatisticsImpl();

    private byte[] managerDigest = ByteArrays.toByteArray("38ef87e9959197a79990562e5a515e4f");

    /**
     * No-arg constructor for dependency injection.
     * After construction, call {@link #setContainer(Container)}, {@link #setPath(String)},
     * {@link #setRoot(File)}, then {@link #load()}.
     */
    public Context() {
        // Defer initialization - setContainer, setPath, setRoot must be called before load()
    }

    /**
     * Sets the container for this context.
     * Must be called before {@link #load()} when using the no-arg constructor.
     *
     * @param container the parent container
     */
    public void setContainer(Container container) {
        if (this.container != null) {
            throw new IllegalStateException("Container already set");
        }
        this.container = container;
    }

    /**
     * Sets the context path for this context.
     * Must be called before {@link #load()} when using the no-arg constructor.
     *
     * @param path the context path (e.g., "" for root, "/app" for an application)
     */
    public void setPath(String path) {
        if (this.contextPath != null) {
            throw new IllegalStateException("Context path already set");
        }
        if (path.endsWith("/")) {
            throw new IllegalArgumentException("Illegal context path: " + path);
        }
        this.contextPath = path;
    }

    /**
     * Sets the root directory for this context.
     * Must be called before {@link #load()} when using the no-arg constructor.
     *
     * @param root the root directory or WAR file
     */
    public void setRoot(File root) {
        if (this.root != null) {
            throw new IllegalStateException("Root already set");
        }
        this.root = root;
    }

    /**
     * Initializes internal state after container, path, and root are set.
     * Called automatically by the 3-arg constructor, or must be called
     * explicitly after using setters with the no-arg constructor.
     */
    private void initializeInternal() {
        if (container == null || contextPath == null || root == null) {
            throw new IllegalStateException("Container, path, and root must be set before initialization");
        }

        // Create session manager
        this.sessionManager = new SessionManager(this);

        // Create SAX parser factory early for web.xml and JSP parsing
        try {
            this.saxParserFactory = javax.xml.parsers.SAXParserFactory.newInstance();
            this.saxParserFactory.setNamespaceAware(true);
            this.saxParserFactory.setValidating(false);
        } catch (Exception e) {
            // Log but continue - we'll handle missing factory later
            LOGGER.log(Level.WARNING, "Failed to create SAX parser factory", e);
            this.saxParserFactory = null;
        }

        // Work out if this context is the manager webapp
        boolean manager = false;
        if (root.isFile() && root.getName().equals("manager.war")) {
            // compute checksum of the file and compare to correct version
            try (InputStream in = new FileInputStream(root)) {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                DigestInputStream md5in = new DigestInputStream(in, md5);
                byte[] buf = new byte[Math.max(4096, in.available())];
                for (int len = md5in.read(buf); len != -1; len = md5in.read(buf)) {
                }
                byte[] computedDigest = md5.digest();
                if (ByteArrays.equals(computedDigest, managerDigest)) {
                    manager = true;
                }
            } catch (NoSuchAlgorithmException e) {
                // fatal
                RuntimeException e2 = new RuntimeException("No MD5 support in JRE");
                e2.initCause(e);
                throw e2;
            } catch (IOException e) {
                String message = L10N.getString("err.manager_war_checksum");
                message = MessageFormat.format(message, root);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }

        sessionsLastInvalidated = System.currentTimeMillis();

        ClassLoader classLoader = Context.class.getClassLoader();
        if (classLoader instanceof ContainerClassLoader) {
            containerClassLoader = (ContainerClassLoader) classLoader;
            contextClassLoader = new ContextClassLoader(containerClassLoader, this, manager);
        } else {
            // Fallback for test environments without the full classloader hierarchy
            containerClassLoader = null;
            contextClassLoader = new ContextClassLoader(classLoader, this, manager);
        }

        reset();
    }

    public Context(Container container, String contextPath, File root) {
        if (contextPath.endsWith("/")) {
            throw new IllegalArgumentException("Illegal context path: " + contextPath);
        }
        this.container = container;
        this.contextPath = contextPath;
        this.root = root;
        initializeInternal();
    }

    @Override public ManagerContainerService getContainer() {
        return container;
    }

    @Override public ThreadPoolExecutor getWorkerThreadPool() {
        return server.getWorkerThreadPool();
    }

    @Override public String getWorkerKeepAlive() {
        return server.getWorkerKeepAlive();
    }

    @Override public void setWorkerKeepAlive(String val) {
        server.setWorkerKeepAlive(val);
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

    public void setDistributable(boolean flag) {
        if (initialized) {
            throw new IllegalStateException();
        }
        distributable = flag;
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
     * Returns a File for accessing the resource at the given path.
     * For exploded contexts, returns the file directly.
     * For WAR contexts, extracts the resource to a temp file if needed.
     * Used for accessing JARs in WEB-INF/lib.
     */
    File getLibFile(String path) {
        return contextClassLoader.getFile(path);
    }

    /**
     * Loads this context from the deployment descriptor.
     */
    public void load() throws IOException, SAXException {
        // Initialize internal state if not already done (for no-arg constructor path)
        if (sessionManager == null) {
            initializeInternal();
        }
        InputStream webXml = getResourceAsStream("/WEB-INF/web.xml");
        DeploymentDescriptorParser parser = new DeploymentDescriptorParser(saxParserFactory);
        if (webXml != null) {
            parser.parse(this, webXml);
            resolve();
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
            if (sm.urlPatterns.contains("/")) {
                defaultServletDef = sm.servletDef;
                break;
            }
        }
        if (defaultServletDef == null) {
            defaultServletDef = new ServletDef();
            defaultServletDef.context = this;
            defaultServletDef.displayName = L10N.getString("default_servlet_display_name");
            defaultServletDef.name = null;
            defaultServletDef.className = DefaultServlet.class.getName();
            defaultServletDef.loadOnStartup = 0;
            servletDefs.put(null, defaultServletDef);
            ServletMapping defaultServletMapping = new ServletMapping();
            defaultServletMapping.servletDef = defaultServletDef;
            defaultServletMapping.servletName = null;
            defaultServletMapping.addUrlPattern("/");
            servletMappings.add(defaultServletMapping);
        }

        // JSP servlet - configure automatically if not already mapped
        boolean jspMapped = false;
        for (ServletMapping sm : servletMappings) {
            if (sm.urlPatterns.contains("*.jsp") || sm.urlPatterns.contains("*.jspx")) {
                jspMapped = true;
                break;
            }
        }
        if (!jspMapped) {
            ServletDef jspServletDef = new ServletDef();
            jspServletDef.context = this;
            jspServletDef.displayName = L10N.getString("jsp_servlet_display_name");
            jspServletDef.name = "jsp";
            jspServletDef.className = JSPServlet.class.getName();
            jspServletDef.loadOnStartup = 3;
            servletDefs.put("jsp", jspServletDef);
            
            ServletMapping jspServletMapping = new ServletMapping();
            jspServletMapping.servletDef = jspServletDef;
            jspServletMapping.servletName = "jsp";
            jspServletMapping.addUrlPattern("*.jsp");
            jspServletMapping.addUrlPattern("*.jspx");
            servletMappings.add(jspServletMapping);
            
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Automatically configured JSP servlet for *.jsp and *.jspx files");
            }
        }

    }

    /**
     * Scan the web application and add any web fragments or annotations.
     */
    void scan(DeploymentDescriptorParser parser) throws IOException, SAXException {
        // Process WEB-INF/classes
        // classes in WEB-INF/classes must be processed before any
        // jars. See rule 2b
        Set<String> resourcePaths = getResourcePaths("/WEB-INF/classes/", false);
        if (resourcePaths != null) {
            for (String resourcePath : resourcePaths) {
                if (resourcePath.toLowerCase().endsWith(".class")) {
                    String className = resourcePath.substring(17, resourcePath.length() - 6).replace('/', '.');
                    InputStream in = getResourceAsStream(resourcePath);
                    scanClass(this, className, in);
                }
            }
        }
        // Process WEB-INF/lib
        List<WebFragment> webFragments = new ArrayList<>();
        resourcePaths = getResourcePaths("/WEB-INF/lib/", false);
        if (resourcePaths != null) {
            for (String resourcePath : resourcePaths) {
                if (resourcePath.toLowerCase().endsWith(".jar")) {
                    scanJar(parser, resourcePath, webFragments);
                }
            }
        }
        // Order web fragments
        Collections.sort(webFragments, this);
        // Merge them into context
        for (WebFragment webFragment : webFragments) {
            merge(webFragment);
        }
    }

    public int compare(WebFragment frag1, WebFragment frag2) {
        String name1 = frag1.name;
        String name2 = frag2.name;
        // 8.2.2 case 1
        int name1Index = absoluteOrdering.indexOf(frag1.name);
        int name2Index = absoluteOrdering.indexOf(frag2.name);
        int othersIndex = absoluteOrdering.indexOf(WebFragment.OTHERS);
        if (name1Index == -1) {
            name1Index = (othersIndex != -1) ? othersIndex : Integer.MAX_VALUE;
        }
        if (name2Index == -1) {
            name2Index = (othersIndex != -1) ? othersIndex : Integer.MAX_VALUE;
        }
        if (name1Index != name2Index) {
            return name1Index - name2Index;
        }
        // 8.2.2 case 2
        if (frag1.isBefore(frag2) && !frag2.isBefore(frag1)) {
            return -1;
        }
        if (frag2.isBefore(frag2) && !frag1.isBefore(frag1)) {
            return 1;
        }
        if (frag1.isAfter(frag2) && !frag2.isAfter(frag1)) {
            return 1;
        }
        if (frag2.isAfter(frag2) && !frag1.isAfter(frag1)) {
            return -1;
        }
        if (frag1.isBeforeOthers() && !frag2.isBeforeOthers()) {
            return -1;
        }
        if (frag2.isBeforeOthers() && !frag1.isBeforeOthers()) {
            return 1;
        }
        if (frag1.isAfterOthers() && !frag2.isAfterOthers()) {
            return 1;
        }
        if (frag2.isAfterOthers() && !frag1.isAfterOthers()) {
            return -1;
        }
        return 0;
    }

    void scanJar(DeploymentDescriptorParser parser, String path, List<WebFragment> webFragments) throws IOException, SAXException {
        String webFragmentPath = "/META-INF/web-fragment.xml";
        File file = contextClassLoader.getFile(path);
        try (JarFile jarFile = new JarFile(file)) {
            WebFragment webFragment = new WebFragment();
            // load web fragment
            JarEntry jarEntry = jarFile.getJarEntry(webFragmentPath);
            if (jarEntry != null) {
                InputStream in = jarFile.getInputStream(jarEntry);
                parser.parse(webFragment, in);
                webFragment.resolve();
            }
            // 8.2.2 rule 1d
            boolean noOthers = !absoluteOrdering.isEmpty() && absoluteOrdering.contains(WebFragment.OTHERS);
            boolean exclude = (noOthers && !absoluteOrdering.contains(webFragment.name));
            if (!exclude && !webFragment.metadataComplete) {
                // scan classes in jar for annotations
                Enumeration<JarEntry> i = jarFile.entries();
                if (i != null) {
                    while (i.hasMoreElements()) {
                        jarEntry = i.nextElement();
                        String entry = jarEntry.getName();
                        if (entry.endsWith(".class")) {
                            String className = entry.substring(0, entry.length() - 6).replace('/', '.');
                            InputStream in = jarFile.getInputStream(jarEntry);
                            scanClass(webFragment, className, in);
                        }
                    }
                }
            }
            if (!exclude && !webFragment.isEmpty()) {
                webFragments.add(webFragment);
            }
        } catch (IOException e) {
            String message = L10N.getString("err.load_resource");
            message = MessageFormat.format(message, path);
            LOGGER.log(Level.SEVERE, message, e);
        }
    }
    
    /**
     * Scan a class for @WebServlet, @WebFilter annotations.
     * The class will be loaded by the context classloader.
     */
    void scanClass(DeploymentDescriptor descriptor, String className, InputStream in) throws IOException, SAXException {
        contextClassLoader.assign(className, in);
        try {
            Class<?> t = contextClassLoader.loadClass(className);
            Object target = null;
            for (Annotation annotation : t.getAnnotations()) {
                if (annotation instanceof WebFilter) {
                    WebFilter webFilter = (WebFilter) annotation;
                    if (!Filter.class.isAssignableFrom(t)) {
                        String message = L10N.getString("err.bad_annotation");
                        message = MessageFormat.format(message, className);
                        LOGGER.log(Level.SEVERE, message);
                        return;
                    }
                    FilterDef filterDef;
                    if (target == null) {
                        filterDef = new FilterDef();
                        target = filterDef;
                    } else {
                        filterDef = (FilterDef) target;
                    }
                    filterDef.init(webFilter, className);
                    descriptor.addFilterDef(filterDef);
                    String[] urlPatterns = webFilter.urlPatterns();
                    if (urlPatterns.length > 0) {
                        FilterMapping filterMapping = new FilterMapping(webFilter.dispatcherTypes());
                        filterMapping.filterDef = filterDef;
                        filterMapping.filterName = filterDef.name;
                        for (String urlPattern : urlPatterns) {
                            filterMapping.addUrlPattern(urlPattern);
                        }
                        descriptor.addFilterMapping(filterMapping);
                    }
                    String[] servletNames = webFilter.urlPatterns();
                    if (servletNames.length > 0) {
                        FilterMapping filterMapping = new FilterMapping(webFilter.dispatcherTypes());
                        filterMapping.filterDef = filterDef;
                        filterMapping.filterName = filterDef.name;
                        filterMapping.filterDef = filterDef;
                        for (String servletName : servletNames) {
                            filterMapping.addServletName(servletName);
                        }
                        descriptor.addFilterMapping(filterMapping);
                    }
                } else if (annotation instanceof WebListener) {
                    WebListener webListener = (WebListener) annotation;
                    if (!EventListener.class.isAssignableFrom(t)) {
                        String message = L10N.getString("err.bad_annotation");
                        message = MessageFormat.format(message, className);
                        LOGGER.log(Level.SEVERE, message);
                        return;
                    }
                    ListenerDef listenerDef;
                    if (target == null) {
                        listenerDef = new ListenerDef();
                        descriptor.addListenerDef(listenerDef);
                        target = listenerDef;
                    } else {
                        listenerDef = (ListenerDef) target;
                    }
                    listenerDef.init(className);
                    // listener is actually in descriptionGroup so should be
                    // able to have description, icon etc but WebListener
                    // doesn't support that.
                } else if (annotation instanceof WebServlet) {
                    WebServlet webServlet = (WebServlet) annotation;
                    if (!Servlet.class.isAssignableFrom(t)) {
                        String message = L10N.getString("err.bad_annotation");
                        message = MessageFormat.format(message, className);
                        LOGGER.log(Level.SEVERE, message);
                        return;
                    }
                    ServletDef servletDef;
                    if (target == null) {
                        servletDef = new ServletDef();
                        target = servletDef;
                    } else {
                        servletDef = (ServletDef) target;
                    }
                    servletDef.init(webServlet, className);
                    descriptor.addServletDef(servletDef);
                    String[] urlPatterns = webServlet.urlPatterns();
                    if (urlPatterns.length > 0) {
                        ServletMapping servletMapping = new ServletMapping();
                        servletMapping.servletDef = servletDef;
                        servletMapping.servletName = servletDef.name;
                        for (String urlPattern : urlPatterns) {
                            servletMapping.addUrlPattern(urlPattern);
                        }
                        descriptor.addServletMapping(servletMapping);
                    }
                } else if (annotation instanceof MultipartConfig) {
                    MultipartConfig multipartConfig = (MultipartConfig) annotation;
                    if (!Servlet.class.isAssignableFrom(t)) {
                        String message = L10N.getString("err.bad_annotation");
                        message = MessageFormat.format(message, className);
                        LOGGER.log(Level.SEVERE, message);
                        return;
                    }
                    ServletDef servletDef;
                    if (target == null) {
                        servletDef = new ServletDef();
                        target = servletDef;
                    } else {
                        servletDef = (ServletDef) target;
                    }
                    servletDef.init(multipartConfig);
                } else if (annotation instanceof ServletSecurity) {
                    ServletSecurity servletSecurity = (ServletSecurity) annotation;
                    if (!Servlet.class.isAssignableFrom(t)) {
                        String message = L10N.getString("err.bad_annotation");
                        message = MessageFormat.format(message, className);
                        LOGGER.log(Level.SEVERE, message);
                        return;
                    }
                    ServletDef servletDef;
                    if (target == null) {
                        servletDef = new ServletDef();
                        target = servletDef;
                    } else {
                        servletDef = (ServletDef) target;
                    }
                    servletDef.init(servletSecurity);
                } else if (annotation instanceof DeclareRoles) {
                    DeclareRoles declareRoles = (DeclareRoles) annotation;
                    for (String roleName : declareRoles.value()) {
                        SecurityRole securityRole = new SecurityRole();
                        securityRole.roleName = roleName;
                        descriptor.addSecurityRole(securityRole);
                    }
                } else if (annotation instanceof RunAs) {
                    RunAs runAs = (RunAs) annotation;
                    ServletDef servletDef;
                    if (target == null) {
                        servletDef = new ServletDef();
                        target = servletDef;
                    } else {
                        servletDef = (ServletDef) target;
                    }
                    servletDef.init(runAs);
                } else if (annotation instanceof EJBs) {
                    EJBs ejbs = (EJBs) annotation;
                    for (EJB ejb : ejbs.value()) {
                        initEjbRef(ejb);
                    }
                } else if (annotation instanceof PersistenceContexts) {
                    PersistenceContexts persistenceContexts = (PersistenceContexts) annotation;
                    for (PersistenceContext persistenceContext : persistenceContexts.value()) {
                        initPersistenceContextRef(persistenceContext);
                    }
                } else if (annotation instanceof PersistenceUnits) {
                    PersistenceUnits persistenceUnits = (PersistenceUnits) annotation;
                    for (PersistenceUnit persistenceUnit : persistenceUnits.value()) {
                        initPersistenceUnitRef(persistenceUnit);
                    }
                } else if (annotation instanceof Resources) {
                    Resources resources = (Resources) annotation;
                    for (javax.annotation.Resource resource : resources.value()) {
                        initResourceRef(resource);
                    }
                } else if (annotation instanceof WebServiceRefs) {
                    WebServiceRefs webServiceRefs = (WebServiceRefs) annotation;
                    for (WebServiceRef webServiceRef : webServiceRefs.value()) {
                        initServiceRef(webServiceRef);
                    }
                }
            }
            // field annotations
            for (Field field : t.getDeclaredFields()) { // NB may be private
                for (Annotation annotation : field.getAnnotations()) {
                    if (annotation instanceof EJB) {
                        EJB ejb = (EJB) annotation;
                        EjbRef ejbRef = initEjbRef(ejb);
                        addInjectionTarget(ejbRef, className, field.getName());
                    } else if (annotation instanceof javax.annotation.Resource) {
                        javax.annotation.Resource resource = (javax.annotation.Resource) annotation;
                        ResourceRef resourceRef = initResourceRef(resource);
                        addInjectionTarget(resourceRef, className, field.getName());
                    } else if (annotation instanceof PersistenceContext) {
                        PersistenceContext persistenceContext = (PersistenceContext) annotation;
                        PersistenceContextRef persistenceContextRef = initPersistenceContextRef(persistenceContext);
                        addInjectionTarget(persistenceContextRef, className, field.getName());
                    } else if (annotation instanceof PersistenceUnit) {
                        PersistenceUnit persistenceUnit = (PersistenceUnit) annotation;
                        PersistenceUnitRef persistenceUnitRef = initPersistenceUnitRef(persistenceUnit);
                        addInjectionTarget(persistenceUnitRef, className, field.getName());
                    } else if (annotation instanceof WebServiceRef) {
                        WebServiceRef webServiceRef = (WebServiceRef) annotation;
                        ServiceRef serviceRef = initServiceRef(webServiceRef);
                        addInjectionTarget(serviceRef, className, field.getName());
                    }
                }
            }
            // method annotations
            for (Method method : t.getMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (annotation instanceof PostConstruct) {
                        LifecycleCallback callback = new LifecycleCallback();
                        callback.className = className;
                        callback.methodName = method.getName();
                        addPostConstruct(callback);
                    } else if (annotation instanceof PreDestroy) {
                        LifecycleCallback callback = new LifecycleCallback();
                        callback.className = className;
                        callback.methodName = method.getName();
                        addPreDestroy(callback);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            String message = L10N.getString("err.load_resource");
            message = MessageFormat.format(message, className);
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    void addInjectionTarget(Injectable injectable, String className, String name) {
        InjectionTarget it = new InjectionTarget();
        it.setClassName(className);
        it.setName(name);
        injectable.setInjectionTarget(it);
    }

    EjbRef initEjbRef(EJB config) {
        String name = config.name();
        for (EjbRef ejbRef : ejbRefs) {
            if (name.equals(ejbRef.getName())) {
                ejbRef.init(config);
                return ejbRef;
            }
        }
        // create
        EjbRef ejbRef = new EjbRef(false);
        ejbRef.init(config);
        addEjbRef(ejbRef);
        return ejbRef;
    }

    ResourceRef initResourceRef(javax.annotation.Resource config) {
        String name = config.name();
        for (ResourceRef resourceRef : resourceRefs) {
            if (name.equals(resourceRef.getName())) {
                resourceRef.init(config);
                return resourceRef;
            }
        }
        // create
        ResourceRef resourceRef = new ResourceRef();
        resourceRef.init(config);
        addResourceRef(resourceRef);
        return resourceRef;
    }

    PersistenceContextRef initPersistenceContextRef(PersistenceContext config) {
        String name = config.name();
        if (name != null) {
            for (PersistenceContextRef persistenceContextRef : persistenceContextRefs) {
                if (name.equals(persistenceContextRef.getName())) {
                    persistenceContextRef.init(config);
                    return persistenceContextRef;
                }
            }
        }
        // create
        PersistenceContextRef persistenceContextRef = new PersistenceContextRef();
        persistenceContextRef.init(config);
        addPersistenceContextRef(persistenceContextRef);
        return persistenceContextRef;
    }

    PersistenceUnitRef initPersistenceUnitRef(PersistenceUnit config) {
        String name = config.name();
        if (name != null) {
            for (PersistenceUnitRef persistenceUnitRef : persistenceUnitRefs) {
                if (name.equals(persistenceUnitRef.getName())) {
                    persistenceUnitRef.init(config);
                    return persistenceUnitRef;
                }
            }
        }
        // create
        PersistenceUnitRef persistenceUnitRef = new PersistenceUnitRef();
        persistenceUnitRef.init(config);
        addPersistenceUnitRef(persistenceUnitRef);
        return persistenceUnitRef;
    }

    ServiceRef initServiceRef(WebServiceRef config) {
        String name = config.name();
        if (name != null) {
            for (ServiceRef serviceRef : serviceRefs) {
                if (name.equals(serviceRef.getName())) {
                    serviceRef.init(config);
                    return serviceRef;
                }
            }
        }
        // create
        ServiceRef serviceRef = new ServiceRef();
        serviceRef.init(config);
        addServiceRef(serviceRef);
        return serviceRef;
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
            for (Injectable injectable : getInjectables()) {
                InjectionTarget it = injectable.getInjectionTarget();
                if (it != null) {
                    // Discover source for injection
                    Object source = injectable.resolve(ctx);
                    if (source != null) {
                        // Perform injection
                        String className = it.getClassName();
                        String name = it.getName();
                        try {
                            Class<?> t = contextClassLoader.loadClass(className);
                            Object target = t.newInstance();
                            try {
                                Field field = t.getField(name);
                                field.set(target, source);
                            } catch (NoSuchFieldException e) {
                                Method[] methods = t.getMethods();
                                for (Method method : methods) {
                                    if (method.getName().equals(name)) {
                                        Class<?>[] mt = method.getParameterTypes();
                                        if (mt.length == 1 && mt[0].isAssignableFrom(source.getClass())) {
                                            method.invoke(target, source);
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                            String message = Context.L10N.getString("err.init_resource");
                            Context.LOGGER.log(Level.SEVERE, message, e);
                        }
                    }
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
            } catch (UnavailableException e) {
                if (e.isPermanent()) {
                    String message = L10N.getString("err.filter_permanently_unavailable");
                    message = MessageFormat.format(message, filterDef.name);
                    LOGGER.log(Level.SEVERE, message, e);
                } else {
                    String message = L10N.getString("err.filter_temporarily_unavailable");
                    message = MessageFormat.format(message, filterDef.name, e.getUnavailableSeconds());
                    LOGGER.log(Level.SEVERE, message, e);
                }
            } catch (ServletException e) {
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
                } catch (UnavailableException e) {
                    if (e.isPermanent()) {
                        String message = L10N.getString("err.servlet_permanently_unavailable");
                        message = MessageFormat.format(message, servletDef.name);
                        LOGGER.log(Level.SEVERE, message, e);
                    } else {
                        String message = L10N.getString("err.servlet_temporarily_unavailable");
                        message = MessageFormat.format(message, servletDef.name, e.getUnavailableSeconds());
                        LOGGER.log(Level.SEVERE, message, e);
                    }
                } catch (ServletException e) {
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

        // Configure authentication provider if authentication is configured
        if (getAuthMethod() != null && server != null) {
            ServletAuthenticationProvider authProvider = new ServletAuthenticationProvider(this);
            server.setAuthenticationProvider(authProvider);
        }

        // Register with cluster (or re-register with new UUID after reload)
        // This triggers other nodes to replicate their sessions to us
        if (distributable) {
            sessionManager.regenerateContextUuid();
            container.registerContextWithCluster(this);
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

        // Unregister from cluster before invalidating sessions
        // This prevents session removal notifications being sent to cluster
        container.unregisterContextFromCluster(this);

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
        if (sessionsLastInvalidated + 1000 < now && !force) { // 1 second elapsed
            return;
        }
        sessionsLastInvalidated = now;
        sessionManager.invalidateExpiredSessions();
    }

    public void addRealm(String name, Realm realm) {
        realms.put(name, realm);
    }

    public void addResource(Resource resource) {
        if (resource instanceof DataSourceDef) {
            addDataSourceDef((DataSourceDef) resource);
        } else if (resource instanceof MailSession) {
            addMailSession((MailSession) resource);
        } else if (resource instanceof JmsConnectionFactory) {
            addJmsConnectionFactory((JmsConnectionFactory) resource);
        } else if (resource instanceof JmsDestination) {
            addJmsDestination((JmsDestination) resource);
        } else if (resource instanceof ConnectionFactory) {
            addConnectionFactory((ConnectionFactory) resource);
        } else if (resource instanceof AdministeredObject) {
            addAdministeredObject((AdministeredObject) resource);
        }
    }

    Realm getRealm(String name) {
        Realm realm = realms.get(name);
        if (realm == null && container != null) {
            realm = container.realms.get(name);
        }
        return realm;
    }

    /**
     * Verifies a password against the realm (preferred method).
     */
    boolean passwordMatch(String realmName, String username, String password) {
        Realm realm = getRealm(realmName);
        if (realm != null) {
            return realm.passwordMatch(username, password);
        }
        return false;
    }

    /**
     * Gets the H(A1) hash for HTTP Digest Authentication.
     */
    String getDigestHA1(String realmName, String username) {
        Realm realm = getRealm(realmName);
        if (realm != null) {
            return realm.getDigestHA1(username, realmName);
        }
        return null;
    }

    /**
     * Used during authentication to determine the password for a user.
     * @deprecated Use passwordMatch() or getDigestHA1() instead
     */
    @Deprecated
    String getPassword(String realmName, String username) {
        Realm realm = getRealm(realmName);
        if (realm != null) {
            return realm.getPassword(username);
        }
        return null;
    }

    /**
     * Used by the request (principal) to determine role membership.
     */
    boolean isUserInRole(String realmName, String username, String roleName) {
        Realm realm = getRealm(realmName);
        if (realm != null) {
            return realm.isUserInRole(username, roleName);
        }
        return false;
    }


    /**
     * Gets a session by ID.
     * @param id the session ID
     * @return the session, or null if not found
     */
    HttpSession getSession(String id) {
        return sessionManager.getSession(id);
    }

    /**
     * Removes a session.
     * @param id the session ID
     */
    void removeSession(String id) {
        sessionManager.removeSession(id);
    }

    /**
     * Returns the session manager for this context.
     * @return the session manager
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Ensure that one and only one context classloader is used to load all
     * servlets and the classes they reference (SRV.3.7).
     */
    @Override
    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }


    Servlet loadServlet(ServletDef servletDef) throws ServletException {
        String name = servletDef.name;
        Servlet servlet = servlets.get(name);
        if (servlet == null) {
            if (servletDef.unavailableUntil == -1L) { // permanently out of action
                String message = L10N.getString("err.servlet_permanently_unavailable");
                message = MessageFormat.format(message, servletDef.name);
                throw new UnavailableException(message);
            } else if (servletDef.unavailableUntil > 0L) {
                // We will not try to initialize the servlet yet
                int seconds = (int) ((servletDef.unavailableUntil - System.currentTimeMillis()) / 1000L);
                String message = L10N.getString("err.servlet_temporarily_unavailable");
                message = MessageFormat.format(message, servletDef.name, seconds);
                throw new UnavailableException(message, seconds);
            } else {
                servlet = servletDef.newInstance();
                servlets.put(name, servlet);
            }
        }
        return servlet;
    }

    Filter loadFilter(FilterDef filterDef) throws ServletException {
        String name = filterDef.name;
        Filter filter = filters.get(name);
        if (filter == null) {
            if (filterDef.unavailableUntil == -1L) { // permanently out of action
                String message = L10N.getString("err.filter_permanently_unavailable");
                message = MessageFormat.format(message, filterDef.name);
                throw new UnavailableException(message);
            } else if (filterDef.unavailableUntil > 0L) {
                // We will not try to initialize the filter yet
                int seconds = (int) ((filterDef.unavailableUntil - System.currentTimeMillis()) / 1000L);
                String message = L10N.getString("err.filter_temporarily_unavailable");
                message = MessageFormat.format(message, filterDef.name, seconds);
                throw new UnavailableException(message, seconds);
            } else {
                filter = filterDef.newInstance();
                filters.put(name, filter);
            }
        }
        return filter;
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
     * @param searchJars if false, ignore resources in the
     * META-INF/resources subdirectory of JARs in the /WEB-INF/lib
     * directory (Servlet 3.0 spec section 4.6)
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
                String message = L10N.getString("err.reading_jar");
                message = MessageFormat.format(message, root);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }
        if (searchJars) {
            // Search resources in lib jar files (Servlet 3.0 spec section 4.6)
            String prefix = "META-INF/resources" + path;
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
                    String message = L10N.getString("err.reading_jar");
                    message = MessageFormat.format(message, file);
                    LOGGER.log(Level.SEVERE, message, e);
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
                String message = L10N.getString("err.reading_jar");
                message = MessageFormat.format(message, root);
                LOGGER.log(Level.SEVERE, message, e);
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
                    String message = L10N.getString("err.reading_jar");
                    message = MessageFormat.format(message, file);
                    LOGGER.log(Level.SEVERE, message, e);
                }
            }
            if (!found) {
                return null;
            }
        }
        // URL host cannot have leading slash
        String host = contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
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
            // Search resources in lib jar files (Servlet 3.0 spec section 4.6)
            String jarResourcePath = "META-INF/resources/" + entryPath;
            for (File file : libJarFiles) {
                JarFile jarFile = new JarFile(file); // NB we cannot auto-close it, use JarInputStream
                JarEntry jarEntry = jarFile.getJarEntry(jarResourcePath);
                if (jarEntry != null) {
                    return new JarInputStream(jarFile, jarEntry);
                } else {
                    jarFile.close();
                }
            }
        } catch (IOException e) {
            String message = L10N.getString("err.reading_jar");
            message = MessageFormat.format(message, root);
            LOGGER.log(Level.SEVERE, message, e);
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
        Map<FilterDef,FilterMatch> requestFilters = new LinkedHashMap<>();
        for (FilterMapping filterMapping : filterMappings) {
            String filterName = filterMapping.filterName;
            FilterDef filterDef = filterMapping.filterDef;
            if (filterDef == null) {
                continue;
            }
            if (!filterMapping.servletDefs.isEmpty() && filterMapping.servletDefs.contains(servletDef)) {
                requestFilters.put(filterDef, new FilterMatch(filterDef, filterMapping, MappingMatch.EXACT));
            } else {
                for (String pattern : filterMapping.urlPatterns) {
                    if (pattern.equals(path)) {
                        // 1. exact match
                        requestFilters.put(filterDef, new FilterMatch(filterDef, filterMapping, MappingMatch.EXACT));
                    } else if (pattern.endsWith("/*") && path.startsWith(pattern.substring(0, pattern.length() - 1))) {
                        // 2. match path prefix
                        requestFilters.put(filterDef, new FilterMatch(filterDef, filterMapping, MappingMatch.PATH));
                    } else if (pattern.startsWith("*.") && path.endsWith(pattern.substring(1))) {
                        // 3. extension
                        requestFilters.put(filterDef, new FilterMatch(filterDef, filterMapping, MappingMatch.EXTENSION));
                    }
                }
            }
        }
        // Build list of filter matches in filter order
        List<FilterMatch> filterMatches = new ArrayList();
        for (FilterDef filterDef : filterDefs.values()) {
            FilterMatch filterMatch = requestFilters.get(filterDef);
            if (filterMatch != null) { // requestFilters contains this filter
                filterMatches.add(filterMatch);
            }
        }
        return new ContextRequestDispatcher(this, match, queryString, filterMatches, false);
    }

    void matchServletMapping(String path, ServletMatch match) {
        for (ServletMapping servletMapping : servletMappings) {
            ServletDef servletDef = servletMapping.servletDef;
            for (String pattern : servletMapping.urlPatterns) {
                if (pattern.equals(path)) {
                    // 1. exact match
                    if (servletDef != null && servletDef != defaultServletDef) {
                        match.servletDef = servletDef;
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
                            if (servletDef != null) {
                                match.servletDef = servletDef;
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
                    if (servletDef != null) {
                        match.servletDef = servletDef;
                        match.servletPath = path;
                        match.pathInfo = null;
                        match.mappingMatch = MappingMatch.EXTENSION;
                        match.matchValue = path;
                    }
                }
            }
        }
    }

    @Override public synchronized RequestDispatcher getNamedDispatcher(String name) {
        ServletDef servletDef = servletDefs.get(name);
        if (servletDef != null) {
            // Discover all the filterDefs with a mapping matching this
            // servlet name
            List<FilterMatch> filterMatches = new ArrayList<>();
            for (FilterMapping filterMapping : filterMappings) {
                if (filterMapping.servletNames.contains(name)) {
                    filterMatches.add(new FilterMatch(filterMapping.filterDef, filterMapping, MappingMatch.EXACT));
                }
            }
            // construct a ServletMatch
            ServletMatch match = new ServletMatch();
            match.servletDef = servletDef;
            match.mappingMatch = MappingMatch.EXACT;
            return new ContextRequestDispatcher(this, match, null, filterMatches, true);
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
        return "gumdrop/" + Gumdrop.VERSION;
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
            return loadServlet(defaultServletDef);
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
        return jspConfig;
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

    // -- SessionContext interface implementation --

    @Override
    public boolean isDistributable() {
        return distributable;
    }

    @Override
    public byte[] getContextDigest() {
        return digest;
    }

    @Override
    public Collection<HttpSessionAttributeListener> getSessionAttributeListeners() {
        return sessionAttributeListeners;
    }

    @Override
    public Collection<HttpSessionListener> getSessionListeners() {
        return sessionListeners;
    }

    @Override
    public Collection<HttpSessionActivationListener> getSessionActivationListeners() {
        return sessionActivationListeners;
    }

    @Override public String getVirtualServerName() {
        throw new UnsupportedOperationException(); // virtual hosts not supported
    }

    /**
     * Parses a JSP file and returns a compiled Servlet instance.
     * 
     * <p>This method uses in-memory compilation for better performance,
     * eliminating the need for temporary files on disk. It also tracks
     * dependencies to enable incremental compilation.</p>
     * 
     * @param path the resource path to the JSP file in the context
     * @return a compiled Servlet instance
     * @throws RuntimeException if parsing, compilation, or loading fails
     */
    public Servlet parseJSPFile(String path) {
        try {
            // Step 1: Initialize JSP infrastructure if needed
            initializeJSPInfrastructure();
            
            // Step 2: Get InputStream for the JSP file
            InputStream jspInputStream = getResourceAsStream(path);
            if (jspInputStream == null) {
                throw new IllegalArgumentException("JSP file not found: " + path);
            }

            // Step 3: Resolve comprehensive JSP properties from configuration
            JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties = 
                JSPPropertyGroupResolver.resolve(path, jspConfig);
            String encoding = jspProperties.getPageEncoding();

            // Step 4: Parse JSP file using parser factory with resolved properties
            JSPPage jspPage;
            try {
                jspPage = jspParserFactory.parseJSP(jspInputStream, encoding, path, jspProperties);
            } catch (JSPParseException e) {
                throw new RuntimeException("Failed to parse JSP file: " + path, e);
            } finally {
                try {
                    jspInputStream.close();
                } catch (IOException e) {
                    // Log but don't fail
                    LOGGER.warning("Failed to close JSP input stream for: " + path);
                }
            }

            // Step 5: Create taglib registry and generate Java source code in memory
            TaglibRegistry taglibRegistry = new TaglibRegistry(this);
            String className = generateClassNameFromPath(path);
            
            ByteArrayOutputStream sourceOut = new ByteArrayOutputStream();
            JSPCodeGenerator generator = new JSPCodeGenerator(jspPage, sourceOut, taglibRegistry, jspProperties);
            generator.setClassName(className);
            generator.generateCode();
            String sourceCode = sourceOut.toString("UTF-8");

            // Step 6: Compile Java source in memory (no temp files)
            InMemoryJavaCompiler.CompilationResult result = jspCompiler.compile(className, sourceCode);
            
            if (!result.isSuccess()) {
                // Build detailed error message
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("Failed to compile JSP: ").append(path).append("\n");
                for (InMemoryJavaCompiler.CompilationError error : result.getErrors()) {
                    errorMsg.append("  Line ").append(error.getJavaLine());
                    errorMsg.append(": ").append(error.getMessage()).append("\n");
                }
                throw new RuntimeException(errorMsg.toString());
            }

            // Step 7: Get compiled class directly from result
            Class<?> servletClass = result.getCompiledClass();

            // Step 8: Create instance and cast to Servlet
            Object instance = servletClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Servlet)) {
                throw new RuntimeException("Generated class is not a Servlet: " + className);
            }
            
            // Step 9: Record compilation for dependency tracking
            Set<String> dependencies = extractDependencies(jspPage);
            jspDependencyTracker.recordCompilation(path, dependencies);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Compiled JSP: " + path + " -> " + className);
            }

            return (Servlet) instance;

        } catch (IOException e) {
            throw new RuntimeException("I/O error during JSP processing: " + path, e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate JSP servlet: " + path, e);
        }
    }
    
    /**
     * Initializes JSP infrastructure components if not already initialized.
     */
    private synchronized void initializeJSPInfrastructure() {
        if (jspParserFactory == null) {
            jspParserFactory = new JSPParserFactory(saxParserFactory);
        }
        
        if (jspCompiler == null) {
            ClassLoader parentLoader = contextClassLoader != null ? contextClassLoader : getClass().getClassLoader();
            jspCompiler = new InMemoryJavaCompiler(parentLoader);
        }
        
        if (jspDependencyTracker == null) {
            jspDependencyTracker = new JSPDependencyTracker(this, root);
        }
    }
    
    /**
     * Extracts dependencies from a parsed JSP page.
     * 
     * @param jspPage the parsed JSP page
     * @return set of included file paths
     */
    private Set<String> extractDependencies(JSPPage jspPage) {
        Set<String> dependencies = new HashSet<String>();
        
        // Add static includes
        List<String> includes = jspPage.getIncludes();
        if (includes != null) {
            dependencies.addAll(includes);
        }
        
        // Add taglib TLD references
        // Note: JSPPage would need to track these - simplified for now
        
        return dependencies;
    }
    
    /**
     * Checks if a JSP file needs recompilation.
     * 
     * @param path the JSP file path
     * @return true if the JSP needs recompilation
     */
    public boolean jspNeedsRecompilation(String path) {
        if (jspDependencyTracker == null) {
            return true; // Not yet initialized, assume needs compilation
        }
        return jspDependencyTracker.needsRecompilation(path);
    }
    
    /**
     * Invalidates a JSP file and any files that depend on it.
     * Called when a file is modified (e.g., by hot deployment).
     * 
     * @param path the path that was modified
     * @return set of JSP files that need recompilation
     */
    public Set<String> invalidateJSP(String path) {
        if (jspDependencyTracker == null) {
            return new HashSet<String>();
        }
        return jspDependencyTracker.invalidate(path);
    }

    /**
     * Generates a valid Java class name from a JSP file path.
     */
    private String generateClassNameFromPath(String path) {
        // Remove leading slash and file extension
        String name = path.startsWith("/") ? path.substring(1) : path;
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }

        // Replace invalid characters with underscores
        StringBuilder className = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char c : name.toCharArray()) {
            if (Character.isJavaIdentifierPart(c)) {
                className.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else {
                className.append('_');
                capitalizeNext = true;
            }
        }

        // Ensure it starts with a valid character
        String result = className.toString();
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            result = "JSP_" + result;
        }

        return result + "_jsp";
    }

    /**
     * Extracts the character encoding for a JSP file from JSP configuration.
     * 
     * @param path the JSP file path to check
     * @return the encoding specified in JSP config, or "UTF-8" as default
     */
    private String extractEncodingFromJspConfig(String path) {
        // Default encoding
        String encoding = "UTF-8";
        
        // Check JSP configuration for encoding settings
        if (jspConfig != null) {
            // Iterate through JSP property groups
            for (JspPropertyGroupDescriptor propertyGroup : jspConfig.getJspPropertyGroups()) {
                // Check if this property group applies to our JSP file path
                if (matchesUrlPatterns(path, propertyGroup.getUrlPatterns())) {
                    String pageEncoding = propertyGroup.getPageEncoding();
                    if (pageEncoding != null && !pageEncoding.isEmpty()) {
                        encoding = pageEncoding;
                        LOGGER.fine("Using encoding '" + encoding + "' from JSP property group for: " + path);
                        break; // Use the first matching property group
                    }
                }
            }
        }
        
        return encoding;
    }

    /**
     * Checks if a JSP file path matches any of the URL patterns in a collection.
     * 
     * @param jspPath the JSP file path
     * @param urlPatterns the collection of URL patterns to match against
     * @return true if the path matches any pattern, false otherwise
     */
    private boolean matchesUrlPatterns(String jspPath, java.util.Collection<String> urlPatterns) {
        if (urlPatterns == null || urlPatterns.isEmpty()) {
            return false;
        }
        
        for (String pattern : urlPatterns) {
            if (matchesUrlPattern(jspPath, pattern)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Checks if a JSP file path matches a specific URL pattern.
     * Supports exact matches, prefix matches (ending with /*), 
     * and extension matches (starting with *.).
     * 
     * @param jspPath the JSP file path
     * @param pattern the URL pattern to match against
     * @return true if the path matches the pattern, false otherwise
     */
    private boolean matchesUrlPattern(String jspPath, String pattern) {
        if (pattern == null || jspPath == null) {
            return false;
        }
        
        // Exact match
        if (pattern.equals(jspPath)) {
            return true;
        }
        
        // Extension pattern: *.jsp, *.jspx, etc.
        if (pattern.startsWith("*.")) {
            String extension = pattern.substring(1); // Remove the *
            return jspPath.endsWith(extension);
        }
        
        // Prefix pattern: /admin/*, /secure/*, etc.
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2); // Remove the /*
            return jspPath.startsWith(prefix + "/") || jspPath.equals(prefix);
        }
        
        // Default servlet pattern: /
        if (pattern.equals("/")) {
            return true; // Matches everything
        }
        
        return false;
    }

    /**
     * Compiles a Java source file to a class file using the internal Java compiler.
     */
    private boolean compileJavaFile(File sourceFile, File classFile, File outputDir) {
        try {
            // Use javax.tools.JavaCompiler for compilation
            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                LOGGER.severe("No Java compiler available in runtime");
                return false;
            }

            // Set up compilation options
            List<String> options = new ArrayList<>();
            options.add("-cp");
            
            // Build classpath including servlet API and context classloader
            StringBuilder classpath = new StringBuilder();
            
            // Add current Java classpath (includes compiled Gumdrop classes with JSP API)
            String currentClasspath = System.getProperty("java.class.path");
            if (currentClasspath != null) {
                classpath.append(currentClasspath);
            }
            
            // Add Gumdrop build directory (where JSP API classes are compiled)
            String gumdropBuildPath = System.getProperty("gumdrop.build.path", "build");
            File buildDir = new File(gumdropBuildPath);
            if (buildDir.exists()) {
                if (classpath.length() > 0) classpath.append(File.pathSeparator);
                classpath.append(buildDir.getAbsolutePath());
            }
            
            // Add servlet API jars from lib directory
            String gumdropLibPath = System.getProperty("gumdrop.lib.path", "lib");
            File libDir = new File(gumdropLibPath);
            if (libDir.exists()) {
                File[] jars = libDir.listFiles(JAR_FILTER);
                if (jars != null) {
                    for (File jar : jars) {
                        if (classpath.length() > 0) {
                            classpath.append(File.pathSeparator);
                        }
                        classpath.append(jar.getAbsolutePath());
                    }
                }
            }
            
            // Add WEB-INF/lib jars from the context
            File webInfLib = new File(root, "WEB-INF" + File.separator + "lib");
            if (webInfLib.exists()) {
                File[] jars = webInfLib.listFiles(JAR_FILTER);
                if (jars != null) {
                    for (File jar : jars) {
                        if (classpath.length() > 0) {
                            classpath.append(File.pathSeparator);
                        }
                        classpath.append(jar.getAbsolutePath());
                    }
                }
            }
            
            // Add WEB-INF/classes directory
            File webInfClasses = new File(root, "WEB-INF" + File.separator + "classes");
            if (webInfClasses.exists()) {
                if (classpath.length() > 0) classpath.append(File.pathSeparator);
                classpath.append(webInfClasses.getAbsolutePath());
            }

            options.add(classpath.toString());
            options.add("-d");
            options.add(outputDir.getAbsolutePath());

            // Debug logging for classpath
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("JSP compilation classpath: " + classpath.toString());
            }

            // Compile the source file
            javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits = 
                fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));

            javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, null, options, null, compilationUnits);

            boolean success = task.call();
            fileManager.close();

            return success && classFile.exists();

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error during JSP compilation", e);
            return false;
        }
    }

    /**
     * Loads a compiled class file using a custom class loader.
     */
    private Class<?> loadCompiledClass(String className, File classFile) throws IOException, ClassNotFoundException {
        // Read class file bytes
        byte[] classBytes;
        try (FileInputStream fis = new FileInputStream(classFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            classBytes = baos.toByteArray();
        }

        // Define the class using a custom class loader that can access the context
        ClassLoader parentLoader = contextClassLoader != null ? contextClassLoader : getClass().getClassLoader();
        
        ClassLoader jspClassLoader = new ClassLoader(parentLoader) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (className.equals(name)) {
                    return defineClass(name, classBytes, 0, classBytes.length);
                }
                return super.findClass(name);
            }
        };

        return jspClassLoader.loadClass(className);
    }

    @Override public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        if (initialized) {
            throw new IllegalStateException();
        }
        if (servletName == null || servletName.length() == 0) {
            throw new IllegalArgumentException();
        }
        Servlet servlet = parseJSPFile(jspFile);
        ServletDef servletDef = new ServletDef();
        servletDef.name = servletName;
        servletDef.className = servlet.getClass().getName();
        addServletDef(servletDef);
        return servletDef;
    }

}
