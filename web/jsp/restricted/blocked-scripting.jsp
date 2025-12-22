<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page import="java.util.Date" %>
<!DOCTYPE html>
<html>
<head>
    <title>Blocked Scripting Example - Gumdrop JSP</title>
    <link href="../../main.css" rel="stylesheet" type="text/css"/>
</head>
<body>
    <h1>Blocked Scripting Example</h1>
    
    <p><a href="../index.html">&larr; Back to JSP Examples</a></p>
    
    <p>This JSP file contains Java scriptlets and expressions that are 
       <strong>prohibited</strong> by the JSP configuration in web.xml.</p>
    
    <p>The web.xml contains a jsp-property-group that matches the URL pattern 
       <code>/jsp/restricted/*</code> with <code>&lt;scripting-invalid&gt;true&lt;/scripting-invalid&gt;</code>.</p>
    
    <h2>Prohibited JSP Elements</h2>
    
    <p>The following JSP elements will cause this page to fail compilation:</p>
    
    <%-- This scriptlet will cause a JSPParseException during parsing --%>
    <% 
        String message = "This scriptlet violates the scripting-invalid policy!";
        Date now = new Date();
    %>
    
    <p>Current time would be: <%= now.toString() %></p>
    
    <%-- This declaration will also cause a JSPParseException --%>
    <%!
        private int counter = 0;
        
        public String getWelcomeMessage() {
            return "Welcome visitor #" + (++counter);
        }
    %>
    
    <p>Welcome message would be: <%= getWelcomeMessage() %></p>
    
    <h2>Expected Behaviour</h2>
    
    <p>When you try to access this JSP, you should see an error message indicating 
       that scripting is disabled for this JSP page. The exact error will be:</p>
    
    <blockquote style="background-color: #ffe6e6; padding: 10px; border-left: 4px solid #ff6666;">
        <strong>JSPParseException:</strong> Scripting is disabled for this JSP page
    </blockquote>
    
    <p>This demonstrates Gumdrop's JSP property group enforcement, which allows 
       administrators to selectively disable Java scripting in JSP files based on 
       URL patterns for security or architectural reasons.</p>
    
    <h2>Alternative Approaches</h2>
    
    <p>When scripting is disabled, you can still use:</p>
    <ul>
        <li>Expression Language (EL) - e.g., <code>\${sessionScope.user.name}</code></li>
        <li>JSTL tags for control flow and formatting</li>
        <li>Custom tag libraries</li>
        <li>Static content and includes</li>
    </ul>
    
    <hr/>
    <p><em>This JSP intentionally violates scripting policy for demonstration purposes.</em></p>
</body>
</html>