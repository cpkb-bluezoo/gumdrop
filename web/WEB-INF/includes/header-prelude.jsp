<%-- 
  Header Prelude - Automatically included at the beginning of JSP pages
  This demonstrates Gumdrop's JSP include-prelude functionality
--%>
<%@ page import="java.util.Date" %>
<%!
    // Prelude declarations available to all JSPs that include this prelude
    private String getServerInfo() {
        return "Gumdrop JSP Engine v1.0";
    }
    
    private String getPageLoadTime() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
    }
%>
<%
    // Prelude initialization code
    if (request.getAttribute("pageLoadStartTime") == null) {
        request.setAttribute("pageLoadStartTime", System.currentTimeMillis());
    }
    request.setAttribute("serverInfo", getServerInfo());
%>
<!-- Prelude HTML: Navigation and Common Header -->
<div style="background-color: #e8f4f8; padding: 10px; margin-bottom: 15px; border-radius: 5px;">
    <div style="font-size: 12px; color: #666; float: right;">
        Loaded at <%= getPageLoadTime() %> | <%= getServerInfo() %>
    </div>
    <div style="clear: both;"></div>
    <div style="background-color: #d4edda; padding: 8px; border-radius: 3px; margin-top: 5px;">
        <strong>📄 Prelude Active:</strong> This content was automatically included 
        at the beginning of the JSP via <code>include-prelude</code> configuration.
    </div>
</div>
