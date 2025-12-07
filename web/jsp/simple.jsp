<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page import="java.util.Date" %>
<%@ page import="java.text.SimpleDateFormat" %>
<%!
    // Declaration: Instance variable and method
    private int visitCount = 0;
    
    private String formatDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date);
    }
%>
<!DOCTYPE html>
<html>
<head>
    <title>Simple JSP Example - Gumdrop</title>
    <link href="../main.css" rel="stylesheet" type="text/css"/>
    <style>
        .code-block { 
            background-color: #f4f4f4; 
            padding: 10px; 
            border-left: 4px solid #007acc; 
            margin: 10px 0; 
            font-family: monospace; 
        }
        .jsp-result { 
            background-color: #e8f5e8; 
            padding: 10px; 
            border: 1px solid #4CAF50; 
            margin: 10px 0; 
        }
    </style>
</head>
<body>
    <h1>Simple JSP Example</h1>
    
    <p><a href="index.html">&larr; Back to JSP Examples</a></p>
    
    <p>This JSP demonstrates basic JSP functionality including:</p>
    <ul>
        <li>Page directives</li>
        <li>Import statements</li>
        <li>Declarations (<%! %> blocks)</li>
        <li>Scriptlets (<% %> blocks)</li>
        <li>Expressions (<%= %> blocks)</li>
    </ul>
    
    <h2>JSP Code Examples</h2>
    
    <h3>1. Page Directive</h3>
    <div class="code-block">
&lt;%@ page contentType="text/html; charset=UTF-8" %&gt;<br/>
&lt;%@ page import="java.util.Date" %&gt;<br/>
&lt;%@ page import="java.text.SimpleDateFormat" %&gt;
    </div>
    
    <h3>2. Declaration (Instance Variables and Methods)</h3>
    <div class="code-block">
&lt;%!<br/>
&nbsp;&nbsp;&nbsp;&nbsp;private int visitCount = 0;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;private String formatDate(Date date) {<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;return sdf.format(date);<br/>
&nbsp;&nbsp;&nbsp;&nbsp;}<br/>
%&gt;
    </div>
    
    <h3>3. Scriptlet (Java Code)</h3>
    <div class="code-block">
&lt;%<br/>
&nbsp;&nbsp;&nbsp;&nbsp;visitCount++;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;Date currentTime = new Date();<br/>
&nbsp;&nbsp;&nbsp;&nbsp;String greeting;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;int hour = currentTime.getHours();<br/>
&nbsp;&nbsp;&nbsp;&nbsp;if (hour &lt; 12) {<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;greeting = "Good morning";<br/>
&nbsp;&nbsp;&nbsp;&nbsp;} else if (hour &lt; 18) {<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;greeting = "Good afternoon";<br/>
&nbsp;&nbsp;&nbsp;&nbsp;} else {<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;greeting = "Good evening";<br/>
&nbsp;&nbsp;&nbsp;&nbsp;}<br/>
%&gt;
    </div>
    
    <h3>4. Expression (Output Values)</h3>
    <div class="code-block">
&lt;p&gt;&lt;%= greeting %&gt;! Welcome to Gumdrop JSP.&lt;/p&gt;<br/>
&lt;p&gt;Current time: &lt;%= formatDate(currentTime) %&gt;&lt;/p&gt;<br/>
&lt;p&gt;This page has been visited &lt;%= visitCount %&gt; time(s) this session.&lt;/p&gt;
    </div>
    
    <h2>Generated Output</h2>
    <div class="jsp-result">
        <%
            // Scriptlet: increment visit counter and generate greeting
            visitCount++;
            Date currentTime = new Date();
            String greeting;
            
            int hour = currentTime.getHours();
            if (hour < 12) {
                greeting = "Good morning";
            } else if (hour < 18) {
                greeting = "Good afternoon";
            } else {
                greeting = "Good evening";
            }
        %>
        
        <p><%= greeting %>! Welcome to Gumdrop JSP.</p>
        <p>Current time: <%= formatDate(currentTime) %></p>
        <p>This page has been visited <%= visitCount %> time(s) this session.</p>
    </div>
    
    <h2>How This Works</h2>
    <p>
        When you request this JSP file, Gumdrop's JSP implementation:
    </p>
    <ol>
        <li>Parses the JSP source using either the traditional JSP parser or XML JSP parser</li>
        <li>Creates an Abstract Syntax Tree (AST) representing all JSP elements</li>
        <li>Generates Java servlet source code from the AST</li>
        <li>Compiles the generated servlet using the internal Java compiler</li>
        <li>Loads and instantiates the servlet to handle your request</li>
    </ol>
    
    <p>The generated servlet implements <code>javax.servlet.http.HttpServlet</code> and contains all your 
       JSP code transformed into appropriate servlet methods.</p>
    
    <hr/>
    <p><em>Powered by Gumdrop JSP Engine</em></p>
</body>
</html>
