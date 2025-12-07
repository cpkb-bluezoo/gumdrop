/*
 * DeploymentDescriptorParserTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for DeploymentDescriptorParser.
 * 
 * Tests the parsing of web.xml and web-fragment.xml deployment descriptors
 * including:
 * - Servlet definitions
 * - Servlet mappings
 * - Filter definitions
 * - Filter mappings
 * - Context parameters
 * - Session configuration
 * - Error pages
 * - Security constraints
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeploymentDescriptorParserTest {

    private WebFragment descriptor;
    private DeploymentDescriptorParser parser;

    @Before
    public void setUp() {
        descriptor = new WebFragment();
        parser = new DeploymentDescriptorParser();
    }

    // ===== Minimal Descriptor Tests =====

    @Test
    public void testParseMinimalDescriptor() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "</web-app>";
        
        parse(xml);
        
        assertNotNull(descriptor);
    }

    @Test
    public void testParseWithDisplayName() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <display-name>Test Application</display-name>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals("Test Application", descriptor.displayName);
    }

    @Test
    public void testParseWithDescription() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <description>A test web application</description>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals("A test web application", descriptor.description);
    }

    // ===== Context Parameter Tests =====

    @Test
    public void testParseContextParam() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <context-param>\n" +
                "    <param-name>myParam</param-name>\n" +
                "    <param-value>myValue</param-value>\n" +
                "  </context-param>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.contextParams.size());
        InitParam param = descriptor.contextParams.get("myParam");
        assertNotNull(param);
        assertEquals("myParam", param.name);
        assertEquals("myValue", param.value);
    }

    @Test
    public void testParseMultipleContextParams() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <context-param>\n" +
                "    <param-name>param1</param-name>\n" +
                "    <param-value>value1</param-value>\n" +
                "  </context-param>\n" +
                "  <context-param>\n" +
                "    <param-name>param2</param-name>\n" +
                "    <param-value>value2</param-value>\n" +
                "  </context-param>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(2, descriptor.contextParams.size());
    }

    // ===== Servlet Definition Tests =====

    @Test
    public void testParseServlet() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <servlet>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <servlet-class>com.example.MyServlet</servlet-class>\n" +
                "  </servlet>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.servletDefs.size());
        ServletDef servlet = descriptor.servletDefs.get("MyServlet");
        assertNotNull(servlet);
        assertEquals("MyServlet", servlet.name);
        assertEquals("com.example.MyServlet", servlet.className);
    }

    @Test
    public void testParseServletWithInitParam() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <servlet>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <servlet-class>com.example.MyServlet</servlet-class>\n" +
                "    <init-param>\n" +
                "      <param-name>config</param-name>\n" +
                "      <param-value>/WEB-INF/config.xml</param-value>\n" +
                "    </init-param>\n" +
                "  </servlet>\n" +
                "</web-app>";
        
        parse(xml);
        
        ServletDef servlet = descriptor.servletDefs.get("MyServlet");
        assertNotNull(servlet);
        assertNotNull("initParams should not be null", servlet.initParams);
        assertEquals(1, servlet.initParams.size());
        // initParams is a Map keyed by param name
        InitParam param = servlet.initParams.get("config");
        assertNotNull("Init param 'config' should exist", param);
        assertEquals("config", param.name);
        assertEquals("/WEB-INF/config.xml", param.value);
    }

    @Test
    public void testParseServletWithLoadOnStartup() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <servlet>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <servlet-class>com.example.MyServlet</servlet-class>\n" +
                "    <load-on-startup>1</load-on-startup>\n" +
                "  </servlet>\n" +
                "</web-app>";
        
        parse(xml);
        
        ServletDef servlet = descriptor.servletDefs.get("MyServlet");
        assertEquals(1, servlet.loadOnStartup);
    }

    @Test
    public void testParseJspServlet() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <servlet>\n" +
                "    <servlet-name>MyJsp</servlet-name>\n" +
                "    <jsp-file>/WEB-INF/views/page.jsp</jsp-file>\n" +
                "  </servlet>\n" +
                "</web-app>";
        
        parse(xml);
        
        ServletDef servlet = descriptor.servletDefs.get("MyJsp");
        assertNotNull(servlet);
        assertEquals("/WEB-INF/views/page.jsp", servlet.jspFile);
    }

    // ===== Servlet Mapping Tests =====

    @Test
    public void testParseServletMapping() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <servlet>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <servlet-class>com.example.MyServlet</servlet-class>\n" +
                "  </servlet>\n" +
                "  <servlet-mapping>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <url-pattern>/api/*</url-pattern>\n" +
                "  </servlet-mapping>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.servletMappings.size());
        ServletMapping mapping = descriptor.servletMappings.get(0);
        assertEquals("MyServlet", mapping.servletName);
        assertTrue(mapping.urlPatterns.contains("/api/*"));
    }

    @Test
    public void testParseServletMappingMultiplePatterns() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <servlet>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <servlet-class>com.example.MyServlet</servlet-class>\n" +
                "  </servlet>\n" +
                "  <servlet-mapping>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "    <url-pattern>/api/*</url-pattern>\n" +
                "    <url-pattern>*.do</url-pattern>\n" +
                "  </servlet-mapping>\n" +
                "</web-app>";
        
        parse(xml);
        
        ServletMapping mapping = descriptor.servletMappings.get(0);
        assertEquals(2, mapping.urlPatterns.size());
        assertTrue(mapping.urlPatterns.contains("/api/*"));
        assertTrue(mapping.urlPatterns.contains("*.do"));
    }

    // ===== Filter Definition Tests =====

    @Test
    public void testParseFilter() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <filter>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <filter-class>com.example.MyFilter</filter-class>\n" +
                "  </filter>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.filterDefs.size());
        FilterDef filter = descriptor.filterDefs.get("MyFilter");
        assertNotNull(filter);
        assertEquals("MyFilter", filter.name);
        assertEquals("com.example.MyFilter", filter.className);
    }

    @Test
    public void testParseFilterWithInitParam() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <filter>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <filter-class>com.example.MyFilter</filter-class>\n" +
                "    <init-param>\n" +
                "      <param-name>encoding</param-name>\n" +
                "      <param-value>UTF-8</param-value>\n" +
                "    </init-param>\n" +
                "  </filter>\n" +
                "</web-app>";
        
        parse(xml);
        
        FilterDef filter = descriptor.filterDefs.get("MyFilter");
        assertEquals(1, filter.initParams.size());
        // initParams is a Map keyed by param name
        InitParam param = filter.initParams.get("encoding");
        assertNotNull("Init param 'encoding' should exist", param);
        assertEquals("encoding", param.name);
        assertEquals("UTF-8", param.value);
    }

    // ===== Filter Mapping Tests =====

    @Test
    public void testParseFilterMapping() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <filter>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <filter-class>com.example.MyFilter</filter-class>\n" +
                "  </filter>\n" +
                "  <filter-mapping>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <url-pattern>/*</url-pattern>\n" +
                "  </filter-mapping>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.filterMappings.size());
        FilterMapping mapping = descriptor.filterMappings.get(0);
        assertEquals("MyFilter", mapping.filterName);
        assertTrue(mapping.urlPatterns.contains("/*"));
    }

    @Test
    public void testParseFilterMappingWithDispatcher() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <filter>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <filter-class>com.example.MyFilter</filter-class>\n" +
                "  </filter>\n" +
                "  <filter-mapping>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <url-pattern>/*</url-pattern>\n" +
                "    <dispatcher>REQUEST</dispatcher>\n" +
                "    <dispatcher>FORWARD</dispatcher>\n" +
                "  </filter-mapping>\n" +
                "</web-app>";
        
        parse(xml);
        
        FilterMapping mapping = descriptor.filterMappings.get(0);
        assertTrue(mapping.dispatchers.contains(javax.servlet.DispatcherType.REQUEST));
        assertTrue(mapping.dispatchers.contains(javax.servlet.DispatcherType.FORWARD));
    }

    @Test
    public void testParseFilterMappingToServlet() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <filter>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <filter-class>com.example.MyFilter</filter-class>\n" +
                "  </filter>\n" +
                "  <filter-mapping>\n" +
                "    <filter-name>MyFilter</filter-name>\n" +
                "    <servlet-name>MyServlet</servlet-name>\n" +
                "  </filter-mapping>\n" +
                "</web-app>";
        
        parse(xml);
        
        FilterMapping mapping = descriptor.filterMappings.get(0);
        assertTrue(mapping.servletNames.contains("MyServlet"));
    }

    // ===== Listener Tests =====

    @Test
    public void testParseListener() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <listener>\n" +
                "    <listener-class>com.example.MyListener</listener-class>\n" +
                "  </listener>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.listenerDefs.size());
        ListenerDef listener = descriptor.listenerDefs.get(0);
        assertEquals("com.example.MyListener", listener.className);
    }

    // ===== Session Config Tests =====

    @Test
    public void testParseSessionConfig() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <session-config>\n" +
                "    <session-timeout>30</session-timeout>\n" +
                "  </session-config>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertNotNull(descriptor.sessionConfig);
        assertEquals(30, descriptor.sessionConfig.sessionTimeout);
    }

    @Test
    public void testParseSessionConfigWithCookieConfig() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <session-config>\n" +
                "    <session-timeout>30</session-timeout>\n" +
                "    <cookie-config>\n" +
                "      <name>CUSTOM_SESSION</name>\n" +
                "      <http-only>true</http-only>\n" +
                "      <secure>true</secure>\n" +
                "    </cookie-config>\n" +
                "  </session-config>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertNotNull(descriptor.sessionConfig);
        assertNotNull(descriptor.sessionConfig.cookieConfig);
        assertEquals("CUSTOM_SESSION", descriptor.sessionConfig.cookieConfig.getName());
        assertTrue(descriptor.sessionConfig.cookieConfig.isHttpOnly());
        assertTrue(descriptor.sessionConfig.cookieConfig.isSecure());
    }

    // ===== Welcome File List Tests =====

    @Test
    public void testParseWelcomeFileList() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <welcome-file-list>\n" +
                "    <welcome-file>index.html</welcome-file>\n" +
                "    <welcome-file>index.jsp</welcome-file>\n" +
                "  </welcome-file-list>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(2, descriptor.welcomeFiles.size());
        assertEquals("index.html", descriptor.welcomeFiles.get(0));
        assertEquals("index.jsp", descriptor.welcomeFiles.get(1));
    }

    // ===== Error Page Tests =====

    @Test
    public void testParseErrorPageByCode() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <error-page>\n" +
                "    <error-code>404</error-code>\n" +
                "    <location>/error/404.jsp</location>\n" +
                "  </error-page>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.errorPages.size());
        ErrorPage errorPage = descriptor.errorPages.get(0);
        assertEquals(404, errorPage.errorCode);
        assertEquals("/error/404.jsp", errorPage.location);
    }

    @Test
    public void testParseErrorPageByException() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <error-page>\n" +
                "    <exception-type>java.lang.RuntimeException</exception-type>\n" +
                "    <location>/error/exception.jsp</location>\n" +
                "  </error-page>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.errorPages.size());
        ErrorPage errorPage = descriptor.errorPages.get(0);
        assertEquals("java.lang.RuntimeException", errorPage.exceptionType);
        assertEquals("/error/exception.jsp", errorPage.location);
    }

    // ===== MIME Mapping Tests =====

    @Test
    public void testParseMimeMapping() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <mime-mapping>\n" +
                "    <extension>json</extension>\n" +
                "    <mime-type>application/json</mime-type>\n" +
                "  </mime-mapping>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.mimeMappings.size());
        MimeMapping mapping = descriptor.mimeMappings.get(0);
        assertEquals("json", mapping.extension);
        assertEquals("application/json", mapping.mimeType);
    }

    // ===== Security Constraint Tests =====

    @Test
    public void testParseSecurityConstraint() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <security-constraint>\n" +
                "    <web-resource-collection>\n" +
                "      <web-resource-name>Admin Area</web-resource-name>\n" +
                "      <url-pattern>/admin/*</url-pattern>\n" +
                "    </web-resource-collection>\n" +
                "    <auth-constraint>\n" +
                "      <role-name>admin</role-name>\n" +
                "    </auth-constraint>\n" +
                "  </security-constraint>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.securityConstraints.size());
        SecurityConstraint constraint = descriptor.securityConstraints.get(0);
        assertEquals(1, constraint.resourceCollections.size());
        assertTrue(constraint.authConstraints.contains("admin"));
    }

    // ===== Security Role Tests =====

    @Test
    public void testParseSecurityRole() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <security-role>\n" +
                "    <role-name>admin</role-name>\n" +
                "  </security-role>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals(1, descriptor.securityRoles.size());
        SecurityRole role = descriptor.securityRoles.get(0);
        assertEquals("admin", role.roleName);
    }

    // ===== Login Config Tests =====

    @Test
    public void testParseLoginConfig() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <login-config>\n" +
                "    <auth-method>FORM</auth-method>\n" +
                "    <form-login-config>\n" +
                "      <form-login-page>/login.jsp</form-login-page>\n" +
                "      <form-error-page>/login-error.jsp</form-error-page>\n" +
                "    </form-login-config>\n" +
                "  </login-config>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertNotNull(descriptor.loginConfig);
        assertEquals("FORM", descriptor.loginConfig.authMethod);
        assertEquals("/login.jsp", descriptor.loginConfig.formLoginPage);
        assertEquals("/login-error.jsp", descriptor.loginConfig.formErrorPage);
    }

    // ===== Complex Descriptor Tests =====

    @Test
    public void testParseComplexDescriptor() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"4.0\">\n" +
                "  <display-name>Complex App</display-name>\n" +
                "  <context-param>\n" +
                "    <param-name>debug</param-name>\n" +
                "    <param-value>true</param-value>\n" +
                "  </context-param>\n" +
                "  <filter>\n" +
                "    <filter-name>EncodingFilter</filter-name>\n" +
                "    <filter-class>com.example.EncodingFilter</filter-class>\n" +
                "  </filter>\n" +
                "  <filter-mapping>\n" +
                "    <filter-name>EncodingFilter</filter-name>\n" +
                "    <url-pattern>/*</url-pattern>\n" +
                "  </filter-mapping>\n" +
                "  <servlet>\n" +
                "    <servlet-name>MainServlet</servlet-name>\n" +
                "    <servlet-class>com.example.MainServlet</servlet-class>\n" +
                "    <load-on-startup>1</load-on-startup>\n" +
                "  </servlet>\n" +
                "  <servlet-mapping>\n" +
                "    <servlet-name>MainServlet</servlet-name>\n" +
                "    <url-pattern>/</url-pattern>\n" +
                "  </servlet-mapping>\n" +
                "  <session-config>\n" +
                "    <session-timeout>30</session-timeout>\n" +
                "  </session-config>\n" +
                "  <welcome-file-list>\n" +
                "    <welcome-file>index.html</welcome-file>\n" +
                "  </welcome-file-list>\n" +
                "</web-app>";
        
        parse(xml);
        
        assertEquals("Complex App", descriptor.displayName);
        assertEquals(1, descriptor.contextParams.size());
        assertEquals(1, descriptor.filterDefs.size());
        assertEquals(1, descriptor.filterMappings.size());
        assertEquals(1, descriptor.servletDefs.size());
        assertEquals(1, descriptor.servletMappings.size());
        assertNotNull(descriptor.sessionConfig);
        assertEquals(1, descriptor.welcomeFiles.size());
    }

    // ===== Helper Methods =====

    private void parse(String xml) throws IOException, SAXException {
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        try {
            parser.parse(descriptor, in);
        } finally {
            in.close();
        }
    }

}
