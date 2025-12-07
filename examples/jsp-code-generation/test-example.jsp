<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page import="java.util.Date" %>
<%!
    // Instance variable for demo
    private int visitCount = 0;
    
    // Helper method for demo
    private String formatDate(Date date) {
        return date.toString();
    }
%>
<!DOCTYPE html>
<html>
<head>
    <title>JSP Code Generation Test</title>
</head>
<body>
    <h1>JSP Code Generation Example</h1>
    
    <%
        // Scriptlet demo
        visitCount++;
        Date now = new Date();
        String greeting = "Hello from generated servlet!";
    %>
    
    <p><%= greeting %></p>
    <p>Current time: <%= formatDate(now) %></p>
    <p>Visit count: <%= visitCount %></p>
    
    <p>This JSP file demonstrates the elements that will be converted 
       to Java servlet source code by the JSPCodeGenerator.</p>
       
    <%-- This comment will be preserved in the generated code --%>
</body>
</html>
