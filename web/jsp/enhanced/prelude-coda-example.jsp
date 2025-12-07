<%@ page contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head>
    <title>Prelude and Coda Example - Gumdrop JSP</title>
    <link href="../../main.css" rel="stylesheet" type="text/css"/>
    <style>
        .explanation-box {
            background-color: #f8f9fa;
            border: 1px solid #dee2e6;
            padding: 15px;
            margin: 15px 0;
            border-radius: 5px;
        }
        .config-sample {
            background-color: #f4f4f4;
            padding: 10px;
            border-left: 4px solid #007acc;
            margin: 10px 0;
            font-family: monospace;
        }
    </style>
</head>
<body>
    <h1>Prelude and Coda Example</h1>
    
    <p><a href="../index.html">&larr; Back to JSP Examples</a></p>
    
    <div class="explanation-box">
        <p><strong>What you're seeing:</strong> This JSP page demonstrates automatic 
        inclusion of content via JSP property group configuration. Notice the header 
        section above (prelude) and footer section below (coda) that were automatically 
        added by Gumdrop's JSP processing.</p>
    </div>
    
    <h2>How Prelude and Coda Work</h2>
    
    <p>This page is configured in web.xml with JSP property groups that specify:</p>
    <ul>
        <li><strong>Include Prelude:</strong> Content automatically inserted at the beginning</li>
        <li><strong>Include Coda:</strong> Content automatically inserted at the end</li>
    </ul>
    
    <h3>Web.xml Configuration</h3>
    <div class="config-sample">
&lt;jsp-property-group&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;url-pattern&gt;/jsp/enhanced/*&lt;/url-pattern&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;include-prelude&gt;/WEB-INF/includes/header-prelude.jsp&lt;/include-prelude&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;include-coda&gt;/WEB-INF/includes/footer-coda.jsp&lt;/include-coda&gt;<br/>
&lt;/jsp-property-group&gt;
    </div>
    
    <h2>Main Page Content</h2>
    
    <p>This is the actual content of the JSP file. The header and footer you see 
       are automatically included by Gumdrop's JSP implementation based on the 
       configuration in web.xml.</p>
    
    <p>The prelude (<code>header-prelude.jsp</code>) includes:</p>
    <ul>
        <li>Common variable declarations available to this page</li>
        <li>Initialization code that runs before the main page</li>
        <li>HTML header content with server information</li>
    </ul>
    
    <p>The coda (<code>footer-coda.jsp</code>) includes:</p>
    <ul>
        <li>Performance metrics calculation</li>
        <li>Request information display</li>
        <li>HTML footer content</li>
    </ul>
    
    <h3>Accessing Prelude Variables</h3>
    
    <p>Variables and methods declared in the prelude are available in this page:</p>
    <ul>
        <li>Server Info: <%= request.getAttribute("serverInfo") %></li>
        <li>Current Time: <%= new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) %></li>
        <li>Session Active: <%= session != null ? "Yes" : "No" %></li>
    </ul>
    
    <h2>Processing Order</h2>
    
    <p>When Gumdrop processes this JSP, the following happens:</p>
    <ol>
        <li>JSP property groups are resolved based on the URL pattern</li>
        <li>Include preludes are identified and processed first</li>
        <li>The main JSP content is processed</li>
        <li>Include codas are processed last</li>
        <li>All content is combined into a single servlet</li>
        <li>The servlet is compiled and executed</li>
    </ol>
    
    <h2>Benefits</h2>
    
    <ul>
        <li><strong>Consistent Layout:</strong> Automatic headers/footers across page groups</li>
        <li><strong>Common Functionality:</strong> Shared variables and methods</li>
        <li><strong>Performance Monitoring:</strong> Automatic timing and metrics</li>
        <li><strong>Maintenance:</strong> Single point of change for common elements</li>
        <li><strong>Security:</strong> Consistent authentication/authorization checks</li>
    </ul>
    
    <div class="explanation-box">
        <p><strong>Implementation Note:</strong> Gumdrop's JSPPropertyGroupResolver 
        handles multiple property groups with cumulative prelude/coda lists, 
        applying them in the order they appear in web.xml.</p>
    </div>
    
    <h2>Sample JSP Source</h2>
    
    <p>The actual source of this JSP file contains only the main content. 
       The prelude and coda are automatically added during processing:</p>
    
    <div class="config-sample">
&lt;%@ page contentType="text/html; charset=UTF-8" %&gt;<br/>
&lt;!DOCTYPE html&gt;<br/>
&lt;html&gt;<br/>
&lt;head&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;title&gt;Prelude and Coda Example - Gumdrop JSP&lt;/title&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;!-- ... rest of page content ... --&gt;<br/>
&lt;/head&gt;<br/>
&lt;body&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;!-- Main page content only --&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;h1&gt;Prelude and Coda Example&lt;/h1&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;!-- No explicit includes needed! --&gt;<br/>
&lt;/body&gt;<br/>
&lt;/html&gt;
    </div>
</body>
</html>