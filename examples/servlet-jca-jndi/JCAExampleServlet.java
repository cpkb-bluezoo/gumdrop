/*
 * JCAExampleServlet.java  
 * JCA and JNDI Demonstration for Gumdrop Server
 * 
 * This example demonstrates complete JCA (Java Connector Architecture) and 
 * JNDI (Java Naming and Directory Interface) support in Servlet containers.
 */

package examples.jcajndi;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.annotation.Resource;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.resource.cci.ConnectionFactory;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

/**
 * Comprehensive example demonstrating JCA and JNDI resource usage.
 * 
 * <p>This servlet shows different approaches for accessing enterprise resources:
 * <ul>
 * <li><strong>Resource Injection</strong> - Automatic {@code @Resource} annotation processing</li>
 * <li><strong>JNDI Lookup</strong> - Manual resource lookup via InitialContext</li>
 * <li><strong>JCA Integration</strong> - Java Connector Architecture for external systems</li>
 * <li><strong>DataSource Pooling</strong> - JDBC connection pooling and management</li>
 * </ul>
 * 
 * <h3>Resource Configuration Example</h3>
 * <pre>{@code
 * <!-- In web.xml -->
 * <data-source>
 *     <name>jdbc/AppDatabase</name>
 *     <class-name>org.postgresql.ds.PGSimpleDataSource</class-name>
 *     <server-name>localhost</server-name>
 *     <database-name>myapp</database-name>
 *     <user>appuser</user>
 *     <password>password</password>
 * </data-source>
 * 
 * <connection-factory>
 *     <jndi-name>jca/ERPConnector</jndi-name>
 *     <connection-definition-id>ERPConnection</connection-definition-id>
 * </connection-factory>
 * }</pre>
 */
@WebServlet("/jca-demo")
public class JCAExampleServlet extends HttpServlet {
    
    // Resource Injection Examples
    
    @Resource(name = "jdbc/AppDatabase")
    private DataSource database;
    
    @Resource(name = "jca/ERPConnector") 
    private ConnectionFactory erpConnector;
    
    @Resource(name = "mail/AppMailSession")
    private javax.mail.Session mailSession;
    
    // Method injection is also supported
    private javax.jms.ConnectionFactory jmsFactory;
    
    @Resource(name = "jms/AppConnectionFactory")
    public void setJmsConnectionFactory(javax.jms.ConnectionFactory jmsFactory) {
        this.jmsFactory = jmsFactory;
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String demo = request.getParameter("demo");
        if (demo == null) {
            demo = "overview";
        }
        
        switch (demo) {
            case "injection":
                demonstrateResourceInjection(request, response);
                break;
            case "jndi":
                demonstrateJndiLookup(request, response);
                break;
            case "jca":
                demonstrateJcaUsage(request, response);
                break;
            case "database":
                demonstrateDatabaseAccess(request, response);
                break;
            default:
                serveOverviewPage(request, response);
                break;
        }
    }
    
    /**
     * Demonstrate automatic resource injection via @Resource annotations.
     */
    private void demonstrateResourceInjection(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head><title>Resource Injection Demo</title></head>");
            out.println("<body>");
            out.println("<h1>📦 Resource Injection Demonstration</h1>");
            
            out.println("<h2>Injected Resources Status</h2>");
            out.println("<table border='1' cellpadding='8'>");
            out.println("<tr><th>Resource Type</th><th>JNDI Name</th><th>Status</th><th>Details</th></tr>");
            
            // Check DataSource injection
            out.println("<tr>");
            out.println("<td>JDBC DataSource</td>");
            out.println("<td>jdbc/AppDatabase</td>");
            if (database != null) {
                out.println("<td style='color: green'>✅ Injected</td>");
                out.println("<td>" + database.getClass().getName() + "</td>");
            } else {
                out.println("<td style='color: red'>❌ Not Found</td>");
                out.println("<td>Check web.xml configuration</td>");
            }
            out.println("</tr>");
            
            // Check JCA ConnectionFactory injection
            out.println("<tr>");
            out.println("<td>JCA ConnectionFactory</td>");
            out.println("<td>jca/ERPConnector</td>");
            if (erpConnector != null) {
                out.println("<td style='color: green'>✅ Injected</td>");
                out.println("<td>" + erpConnector.getClass().getName() + "</td>");
            } else {
                out.println("<td style='color: red'>❌ Not Found</td>");
                out.println("<td>Check JCA connector configuration</td>");
            }
            out.println("</tr>");
            
            // Check Mail Session injection
            out.println("<tr>");
            out.println("<td>Mail Session</td>");
            out.println("<td>mail/AppMailSession</td>");
            if (mailSession != null) {
                out.println("<td style='color: green'>✅ Injected</td>");
                out.println("<td>" + mailSession.getClass().getName() + "</td>");
            } else {
                out.println("<td style='color: red'>❌ Not Found</td>");
                out.println("<td>Check mail session configuration</td>");
            }
            out.println("</tr>");
            
            // Check JMS ConnectionFactory injection (method-based)
            out.println("<tr>");
            out.println("<td>JMS ConnectionFactory</td>");
            out.println("<td>jms/AppConnectionFactory</td>");
            if (jmsFactory != null) {
                out.println("<td style='color: green'>✅ Injected</td>");
                out.println("<td>" + jmsFactory.getClass().getName() + "</td>");
            } else {
                out.println("<td style='color: red'>❌ Not Found</td>");
                out.println("<td>Check JMS configuration</td>");
            }
            out.println("</tr>");
            
            out.println("</table>");
            
            out.println("<h2>Resource Injection Code</h2>");
            out.println("<pre>");
            out.println("@Resource(name = \"jdbc/AppDatabase\")");
            out.println("private DataSource database;");
            out.println("");
            out.println("@Resource(name = \"jca/ERPConnector\")");
            out.println("private ConnectionFactory erpConnector;");
            out.println("");
            out.println("@Resource(name = \"mail/AppMailSession\")");
            out.println("private javax.mail.Session mailSession;");
            out.println("");
            out.println("// Method injection");
            out.println("@Resource(name = \"jms/AppConnectionFactory\")");
            out.println("public void setJmsConnectionFactory(ConnectionFactory jmsFactory) {");
            out.println("    this.jmsFactory = jmsFactory;");
            out.println("}");
            out.println("</pre>");
            
            out.println("<h2>Benefits of Resource Injection</h2>");
            out.println("<ul>");
            out.println("<li><strong>Automatic</strong> - Container handles resource lookup</li>");
            out.println("<li><strong>Type-safe</strong> - Compile-time type checking</li>");
            out.println("<li><strong>Clean code</strong> - No manual JNDI lookup code</li>");
            out.println("<li><strong>Testable</strong> - Easy to mock for unit tests</li>");
            out.println("</ul>");
            
            out.println("<p><a href='?demo=jndi'>Next: Manual JNDI Lookup →</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Demonstrate manual JNDI resource lookup.
     */
    private void demonstrateJndiLookup(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head><title>JNDI Lookup Demo</title></head>");
            out.println("<body>");
            out.println("<h1>🔍 JNDI Manual Lookup Demonstration</h1>");
            
            out.println("<h2>Manual Resource Lookup Results</h2>");
            out.println("<table border='1' cellpadding='8'>");
            out.println("<tr><th>Resource Name</th><th>Lookup Result</th><th>Resource Type</th></tr>");
            
            // Perform manual JNDI lookups
            try {
                InitialContext ctx = new InitialContext();
                
                // Lookup DataSource
                try {
                    Object ds = ctx.lookup("java:comp/env/jdbc/AppDatabase");
                    out.println("<tr>");
                    out.println("<td>java:comp/env/jdbc/AppDatabase</td>");
                    out.println("<td style='color: green'>✅ Found</td>");
                    out.println("<td>" + ds.getClass().getName() + "</td>");
                    out.println("</tr>");
                } catch (NamingException e) {
                    out.println("<tr>");
                    out.println("<td>java:comp/env/jdbc/AppDatabase</td>");
                    out.println("<td style='color: red'>❌ Not Found</td>");
                    out.println("<td>" + e.getMessage() + "</td>");
                    out.println("</tr>");
                }
                
                // Lookup JCA ConnectionFactory
                try {
                    Object cf = ctx.lookup("java:comp/env/jca/ERPConnector");
                    out.println("<tr>");
                    out.println("<td>java:comp/env/jca/ERPConnector</td>");
                    out.println("<td style='color: green'>✅ Found</td>");
                    out.println("<td>" + cf.getClass().getName() + "</td>");
                    out.println("</tr>");
                } catch (NamingException e) {
                    out.println("<tr>");
                    out.println("<td>java:comp/env/jca/ERPConnector</td>");
                    out.println("<td style='color: red'>❌ Not Found</td>");
                    out.println("<td>" + e.getMessage() + "</td>");
                    out.println("</tr>");
                }
                
                // Lookup Mail Session
                try {
                    Object mail = ctx.lookup("java:comp/env/mail/AppMailSession");
                    out.println("<tr>");
                    out.println("<td>java:comp/env/mail/AppMailSession</td>");
                    out.println("<td style='color: green'>✅ Found</td>");
                    out.println("<td>" + mail.getClass().getName() + "</td>");
                    out.println("</tr>");
                } catch (NamingException e) {
                    out.println("<tr>");
                    out.println("<td>java:comp/env/mail/AppMailSession</td>");
                    out.println("<td style='color: red'>❌ Not Found</td>");
                    out.println("<td>" + e.getMessage() + "</td>");
                    out.println("</tr>");
                }
                
            } catch (NamingException e) {
                out.println("<tr colspan='3'>");
                out.println("<td style='color: red'>JNDI Context Error: " + e.getMessage() + "</td>");
                out.println("</tr>");
            }
            
            out.println("</table>");
            
            out.println("<h2>Manual JNDI Lookup Code</h2>");
            out.println("<pre>");
            out.println("try {");
            out.println("    InitialContext ctx = new InitialContext();");
            out.println("    ");
            out.println("    // Lookup DataSource");
            out.println("    DataSource ds = (DataSource) ctx.lookup(\"java:comp/env/jdbc/AppDatabase\");");
            out.println("    ");
            out.println("    // Lookup JCA ConnectionFactory");
            out.println("    ConnectionFactory cf = (ConnectionFactory) ctx.lookup(\"java:comp/env/jca/ERPConnector\");");
            out.println("    ");
            out.println("    // Use resources...");
            out.println("    ");
            out.println("} catch (NamingException e) {");
            out.println("    // Handle lookup failure");
            out.println("}");
            out.println("</pre>");
            
            out.println("<h2>JNDI Namespace Structure</h2>");
            out.println("<ul>");
            out.println("<li><strong>java:comp/env/</strong> - Component environment naming context</li>");
            out.println("<li><strong>java:comp/env/jdbc/</strong> - JDBC DataSource resources</li>");
            out.println("<li><strong>java:comp/env/jca/</strong> - JCA connection factories</li>");
            out.println("<li><strong>java:comp/env/jms/</strong> - JMS resources</li>");
            out.println("<li><strong>java:comp/env/mail/</strong> - JavaMail sessions</li>");
            out.println("</ul>");
            
            out.println("<p><a href='?demo=jca'>Next: JCA Usage →</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Demonstrate JCA (Java Connector Architecture) usage.
     */
    private void demonstrateJcaUsage(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head><title>JCA Usage Demo</title></head>");
            out.println("<body>");
            out.println("<h1>🔌 JCA (Java Connector Architecture) Demonstration</h1>");
            
            if (erpConnector != null) {
                out.println("<div style='background: #d4edda; padding: 15px; border-radius: 8px; margin: 20px 0;'>");
                out.println("<h3>✅ JCA ConnectionFactory Available</h3>");
                out.println("<p><strong>Type:</strong> " + erpConnector.getClass().getName() + "</p>");
                
                try {
                    // Demonstrate JCA connection usage
                    javax.resource.cci.Connection conn = erpConnector.getConnection();
                    out.println("<p><strong>Connection:</strong> " + conn.getClass().getName() + "</p>");
                    
                    // Get connection metadata
                    javax.resource.cci.ConnectionMetaData metaData = conn.getMetaData();
                    out.println("<p><strong>EIS Product:</strong> " + metaData.getEISProductName() + "</p>");
                    out.println("<p><strong>User:</strong> " + metaData.getUserName() + "</p>");
                    
                    conn.close();
                    out.println("<p><strong>Status:</strong> Connection created and closed successfully</p>");
                    
                } catch (Exception e) {
                    out.println("<p style='color: red'><strong>Error:</strong> " + e.getMessage() + "</p>");
                }
                
                out.println("</div>");
                
                out.println("<h2>JCA Connection Usage Pattern</h2>");
                out.println("<pre>");
                out.println("// Get connection from factory");
                out.println("Connection conn = erpConnector.getConnection();");
                out.println("");
                out.println("try {");
                out.println("    // Create interaction");
                out.println("    Interaction interaction = conn.createInteraction();");
                out.println("    ");
                out.println("    // Execute business function");
                out.println("    Record input = recordFactory.createMappedRecord(\"InputRecord\");");
                out.println("    Record output = interaction.execute(spec, input);");
                out.println("    ");
                out.println("    // Process results...");
                out.println("    ");
                out.println("} finally {");
                out.println("    conn.close(); // Return to pool");
                out.println("}");
                out.println("</pre>");
                
            } else {
                out.println("<div style='background: #fff3cd; padding: 15px; border-radius: 8px; margin: 20px 0;'>");
                out.println("<h3>⚠️ JCA ConnectionFactory Not Available</h3>");
                out.println("<p>To enable JCA support, configure a connection factory in web.xml:</p>");
                out.println("<pre>");
                out.println("&lt;connection-factory&gt;");
                out.println("    &lt;jndi-name&gt;jca/ERPConnector&lt;/jndi-name&gt;");
                out.println("    &lt;connection-definition-id&gt;ERPConnection&lt;/connection-definition-id&gt;");
                out.println("    &lt;property&gt;");
                out.println("        &lt;name&gt;ServerUrl&lt;/name&gt;");
                out.println("        &lt;value&gt;erp.company.com&lt;/value&gt;");
                out.println("    &lt;/property&gt;");
                out.println("&lt;/connection-factory&gt;");
                out.println("</pre>");
                out.println("</div>");
            }
            
            out.println("<h2>JCA Benefits</h2>");
            out.println("<ul>");
            out.println("<li><strong>Standard Integration</strong> - Uniform API for external systems</li>");
            out.println("<li><strong>Connection Pooling</strong> - Efficient resource management</li>");
            out.println("<li><strong>Transaction Support</strong> - XA transaction coordination</li>");
            out.println("<li><strong>Security</strong> - Container-managed authentication</li>");
            out.println("<li><strong>Vendor Independence</strong> - Switch adapters without code changes</li>");
            out.println("</ul>");
            
            out.println("<h2>Common JCA Use Cases</h2>");
            out.println("<ul>");
            out.println("<li><strong>ERP Systems</strong> - SAP, Oracle, PeopleSoft integration</li>");
            out.println("<li><strong>Messaging</strong> - IBM MQ, Apache ActiveMQ, RabbitMQ</li>");
            out.println("<li><strong>Databases</strong> - Mainframe DB2, Oracle, SQL Server</li>");
            out.println("<li><strong>Legacy Systems</strong> - CICS, IMS, file systems</li>");
            out.println("</ul>");
            
            out.println("<p><a href='?demo=database'>Next: Database Access →</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Demonstrate database access via injected DataSource.
     */
    private void demonstrateDatabaseAccess(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head><title>Database Access Demo</title></head>");
            out.println("<body>");
            out.println("<h1>🗄️ Database Access Demonstration</h1>");
            
            if (database != null) {
                out.println("<div style='background: #d4edda; padding: 15px; border-radius: 8px; margin: 20px 0;'>");
                out.println("<h3>✅ DataSource Available</h3>");
                out.println("<p><strong>Type:</strong> " + database.getClass().getName() + "</p>");
                
                try (Connection conn = database.getConnection()) {
                    out.println("<p><strong>Database:</strong> " + conn.getMetaData().getDatabaseProductName() + 
                               " " + conn.getMetaData().getDatabaseProductVersion() + "</p>");
                    out.println("<p><strong>Driver:</strong> " + conn.getMetaData().getDriverName() + 
                               " " + conn.getMetaData().getDriverVersion() + "</p>");
                    out.println("<p><strong>URL:</strong> " + conn.getMetaData().getURL() + "</p>");
                    
                    // Try a simple query (this will fail on most databases without setup, but demonstrates the pattern)
                    try {
                        PreparedStatement stmt = conn.prepareStatement("SELECT CURRENT_TIMESTAMP as server_time");
                        ResultSet rs = stmt.executeQuery();
                        if (rs.next()) {
                            out.println("<p><strong>Server Time:</strong> " + rs.getTimestamp("server_time") + "</p>");
                        }
                        rs.close();
                        stmt.close();
                        out.println("<p><strong>Query Status:</strong> ✅ Successfully executed test query</p>");
                        
                    } catch (SQLException e) {
                        out.println("<p><strong>Query Status:</strong> ⚠️ Connection works, but test query failed: " + 
                                   e.getMessage() + "</p>");
                    }
                    
                } catch (SQLException e) {
                    out.println("<p style='color: red'><strong>Connection Error:</strong> " + e.getMessage() + "</p>");
                }
                
                out.println("</div>");
                
            } else {
                out.println("<div style='background: #fff3cd; padding: 15px; border-radius: 8px; margin: 20px 0;'>");
                out.println("<h3>⚠️ DataSource Not Available</h3>");
                out.println("<p>To enable database access, configure a DataSource in web.xml:</p>");
                out.println("<pre>");
                out.println("&lt;data-source&gt;");
                out.println("    &lt;name&gt;jdbc/AppDatabase&lt;/name&gt;");
                out.println("    &lt;class-name&gt;org.postgresql.ds.PGSimpleDataSource&lt;/class-name&gt;");
                out.println("    &lt;server-name&gt;localhost&lt;/server-name&gt;");
                out.println("    &lt;port-number&gt;5432&lt;/port-number&gt;");
                out.println("    &lt;database-name&gt;myapp&lt;/database-name&gt;");
                out.println("    &lt;user&gt;appuser&lt;/user&gt;");
                out.println("    &lt;password&gt;password&lt;/password&gt;");
                out.println("&lt;/data-source&gt;");
                out.println("</pre>");
                out.println("</div>");
            }
            
            out.println("<h2>DataSource Usage Pattern</h2>");
            out.println("<pre>");
            out.println("@Resource(name = \"jdbc/AppDatabase\")");
            out.println("private DataSource database;");
            out.println("");
            out.println("public void processData() throws SQLException {");
            out.println("    try (Connection conn = database.getConnection()) {");
            out.println("        PreparedStatement stmt = conn.prepareStatement(");
            out.println("            \"SELECT * FROM users WHERE active = ?\");");
            out.println("        stmt.setBoolean(1, true);");
            out.println("        ");
            out.println("        ResultSet rs = stmt.executeQuery();");
            out.println("        while (rs.next()) {");
            out.println("            String username = rs.getString(\"username\");");
            out.println("            // Process each row...");
            out.println("        }");
            out.println("    } // Connection automatically returned to pool");
            out.println("}");
            out.println("</pre>");
            
            out.println("<h2>Connection Pooling Benefits</h2>");
            out.println("<ul>");
            out.println("<li><strong>Performance</strong> - Reuse expensive database connections</li>");
            out.println("<li><strong>Scalability</strong> - Handle many concurrent requests</li>");
            out.println("<li><strong>Resource Management</strong> - Limit database connections</li>");
            out.println("<li><strong>Automatic Cleanup</strong> - Container manages connection lifecycle</li>");
            out.println("</ul>");
            
            out.println("<p><a href='?'>← Back to Overview</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Main overview page with navigation and explanations.
     */
    private void serveOverviewPage(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>JCA & JNDI Comprehensive Demo</title>");
            out.println("    <style>");
            out.println("        body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }");
            out.println("        .demo-link { display: block; margin: 15px 0; padding: 20px;");
            out.println("                    background: #3498db; color: white; text-decoration: none; border-radius: 8px; }");
            out.println("        .demo-link:hover { background: #2980b9; }");
            out.println("        .info-box { background: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🏢 JCA & JNDI Comprehensive Demonstration</h1>");
            out.println("    <p>This demonstration showcases enterprise-grade resource management in Gumdrop servlet container.</p>");
            
            out.println("    <div class='info-box'>");
            out.println("        <h3>📋 What You'll Learn</h3>");
            out.println("        <ul>");
            out.println("            <li><strong>Resource Injection</strong> - Automatic dependency injection with @Resource</li>");
            out.println("            <li><strong>JNDI Lookups</strong> - Manual resource discovery and binding</li>");
            out.println("            <li><strong>JCA Integration</strong> - Connector architecture for external systems</li>");
            out.println("            <li><strong>Connection Pooling</strong> - Efficient database resource management</li>");
            out.println("        </ul>");
            out.println("    </div>");
            
            out.println("    <h2>🎯 Interactive Demonstrations</h2>");
            
            out.println("    <a href='?demo=injection' class='demo-link'>");
            out.println("        📦 Resource Injection");
            out.println("        <br><small>Automatic @Resource annotation processing and dependency injection</small>");
            out.println("    </a>");
            
            out.println("    <a href='?demo=jndi' class='demo-link'>");
            out.println("        🔍 JNDI Manual Lookup"); 
            out.println("        <br><small>Manual resource lookup via InitialContext and java:comp/env</small>");
            out.println("    </a>");
            
            out.println("    <a href='?demo=jca' class='demo-link'>");
            out.println("        🔌 JCA Usage");
            out.println("        <br><small>Java Connector Architecture for external system integration</small>");
            out.println("    </a>");
            
            out.println("    <a href='?demo=database' class='demo-link'>");
            out.println("        🗄️ Database Access");
            out.println("        <br><small>Connection pooling and JDBC resource management</small>");
            out.println("    </a>");
            
            out.println("    <h2>🏗️ Architecture Overview</h2>");
            out.println("    <div class='info-box'>");
            out.println("        <h4>Resource Management Stack:</h4>");
            out.println("        <ol>");
            out.println("            <li><strong>Application Layer</strong> - Servlets use @Resource or JNDI lookup</li>");
            out.println("            <li><strong>Container Layer</strong> - Gumdrop provides injection and JNDI context</li>");
            out.println("            <li><strong>Resource Layer</strong> - JCA adapters, DataSources, administered objects</li>");
            out.println("            <li><strong>External Systems</strong> - Databases, ERP, messaging systems</li>");
            out.println("        </ol>");
            out.println("    </div>");
            
            out.println("    <h2>🔧 Configuration</h2>");
            out.println("    <p>Resources are configured in <code>web.xml</code> deployment descriptors:");
            out.println("    <pre>");
            out.println("&lt;!-- JDBC DataSource --&gt;");
            out.println("&lt;data-source&gt;");
            out.println("    &lt;name&gt;jdbc/AppDatabase&lt;/name&gt;");
            out.println("    &lt;class-name&gt;org.postgresql.ds.PGSimpleDataSource&lt;/class-name&gt;");
            out.println("    &lt;server-name&gt;localhost&lt;/server-name&gt;");
            out.println("    &lt;database-name&gt;myapp&lt;/database-name&gt;");
            out.println("&lt;/data-source&gt;");
            out.println("");
            out.println("&lt;!-- JCA Connection Factory --&gt;");
            out.println("&lt;connection-factory&gt;");
            out.println("    &lt;jndi-name&gt;jca/ERPConnector&lt;/jndi-name&gt;");
            out.println("    &lt;connection-definition-id&gt;ERPConnection&lt;/connection-definition-id&gt;");
            out.println("&lt;/connection-factory&gt;");
            out.println("    </pre>");
            
            out.println("    <h2>✨ Benefits</h2>");
            out.println("    <ul>");
            out.println("        <li><strong>Enterprise Integration</strong> - Connect to external systems</li>");
            out.println("        <li><strong>Resource Pooling</strong> - Efficient connection management</li>");
            out.println("        <li><strong>Declarative Configuration</strong> - XML-based resource setup</li>");
            out.println("        <li><strong>Container Management</strong> - Automatic lifecycle handling</li>");
            out.println("        <li><strong>Standards Compliance</strong> - Java EE/Jakarta EE compatibility</li>");
            out.println("    </ul>");
            
            out.println("</body>");
            out.println("</html>");
        }
    }
}
