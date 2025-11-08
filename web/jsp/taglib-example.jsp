<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Date" %>
<%!
    // Sample data for demonstration
    private java.util.List<String> getSampleBooks() {
        java.util.List<String> books = new java.util.ArrayList<String>();
        books.add("The Pragmatic Programmer");
        books.add("Clean Code");
        books.add("Design Patterns");
        books.add("Effective Java");
        books.add("Java: The Complete Reference");
        return books;
    }
%>
<%
    // Set up sample data for taglib demonstration
    request.setAttribute("bookList", getSampleBooks());
    request.setAttribute("currentDate", new Date());
    request.setAttribute("userCount", 42);
    request.setAttribute("serverName", "Gumdrop");
%>
<!DOCTYPE html>
<html>
<head>
    <title>Taglib Example - Gumdrop JSP</title>
    <link href="../main.css" rel="stylesheet" type="text/css"/>
    <style>
        .taglib-demo { 
            background-color: #f0f8ff; 
            padding: 15px; 
            border: 1px solid #4682b4; 
            margin: 10px 0; 
        }
        .code-sample { 
            background-color: #f4f4f4; 
            padding: 10px; 
            border-left: 4px solid #007acc; 
            margin: 10px 0; 
            font-family: monospace; 
        }
        .note-box {
            background-color: #fff3cd;
            border: 1px solid #ffeaa7;
            padding: 15px;
            margin: 15px 0;
            border-radius: 5px;
        }
    </style>
</head>
<body>
    <h1>Taglib Example</h1>
    
    <p><a href="index.html">&larr; Back to JSP Examples</a></p>
    
    <div class="note-box">
        <strong>Note:</strong> This JSP demonstrates taglib directive processing and custom tag recognition 
        by Gumdrop's JSP implementation. To fully execute JSTL tags, you would need to add the 
        JSTL JAR files to your WEB-INF/lib directory.
    </div>
    
    <p>This JSP demonstrates:</p>
    <ul>
        <li>Taglib directive processing</li>
        <li>Custom tag recognition and code generation</li>
        <li>Tag Library Descriptor (TLD) resolution</li>
        <li>Interaction with Gumdrop's TaglibRegistry</li>
    </ul>
    
    <h2>Taglib Directives</h2>
    <div class="code-sample">
&lt;%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %&gt;<br/>
&lt;%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %&gt;
    </div>
    
    <p>These directives register tag library prefixes that Gumdrop's JSP implementation 
       will recognize and process during code generation.</p>
    
    <h2>JSTL Core Tags (Demonstration)</h2>
    
    <h3>Conditional Logic</h3>
    <div class="code-sample">
&lt;c:if test="\${not empty serverName}"&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;p&gt;Server: &lt;c:out value="\${serverName}"/&gt;&lt;/p&gt;<br/>
&lt;/c:if&gt;
    </div>
    
    <div class="taglib-demo">
        <h4>Generated Output (if JSTL were available):</h4>
        <!-- Simulating what JSTL would produce -->
        <p>Server: <%= request.getAttribute("serverName") %></p>
    </div>
    
    <h3>Iteration</h3>
    <div class="code-sample">
&lt;c:forEach var="book" items="\${bookList}" varStatus="status"&gt;<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&lt;p&gt;\${status.index + 1}. &lt;c:out value="\${book}"/&gt;&lt;/p&gt;<br/>
&lt;/c:forEach&gt;
    </div>
    
    <div class="taglib-demo">
        <h4>Generated Output (if JSTL were available):</h4>
        <!-- Simulating what JSTL would produce -->
        <%
            java.util.List<String> books = (java.util.List<String>) request.getAttribute("bookList");
            for (int i = 0; i < books.size(); i++) {
                String book = books.get(i);
        %>
        <p><%= (i + 1) %>. <%= book %></p>
        <%
            }
        %>
    </div>
    
    <h3>Variable Setting and Output</h3>
    <div class="code-sample">
&lt;c:set var="welcomeMsg" value="Welcome to \${serverName} JSP Engine"/&gt;<br/>
&lt;p&gt;&lt;c:out value="\${welcomeMsg}"/&gt;&lt;/p&gt;
    </div>
    
    <div class="taglib-demo">
        <h4>Generated Output (if JSTL were available):</h4>
        <p>Welcome to <%= request.getAttribute("serverName") %> JSP Engine</p>
    </div>
    
    <h2>Formatting Tags</h2>
    <div class="code-sample">
&lt;fmt:formatDate value="\${currentDate}" pattern="yyyy-MM-dd HH:mm:ss"/&gt;<br/>
&lt;fmt:formatNumber value="\${userCount}" type="number"/&gt;
    </div>
    
    <div class="taglib-demo">
        <h4>Generated Output (if JSTL were available):</h4>
        <!-- Simulating formatted date and number -->
        <%
            Date currentDate = (Date) request.getAttribute("currentDate");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        %>
        <p>Formatted Date: <%= sdf.format(currentDate) %></p>
        <p>Formatted Number: <%= request.getAttribute("userCount") %></p>
    </div>
    
    <h2>How Gumdrop Processes Taglibs</h2>
    
    <ol>
        <li><strong>Taglib Directive Processing:</strong> When parsing, Gumdrop extracts 
            taglib directives and registers prefix-to-URI mappings in the JSPPage.</li>
        
        <li><strong>TLD Resolution:</strong> The TaglibRegistry resolves taglib URIs to 
            Tag Library Descriptor (TLD) files in WEB-INF/lib JARs or WEB-INF/ directory.</li>
        
        <li><strong>Custom Tag Recognition:</strong> During parsing, custom tags 
            (like <code>&lt;c:if&gt;</code>) are recognized based on registered prefixes.</li>
        
        <li><strong>Code Generation:</strong> The JSPCodeGenerator creates Java code 
            that instantiates tag handler classes and calls their lifecycle methods.</li>
        
        <li><strong>Tag Handler Integration:</strong> Generated servlet code properly 
            initializes PageContext and manages tag handler lifecycle 
            (doStartTag, doEndTag, release).</li>
    </ol>
    
    <h2>To Enable Full JSTL Support</h2>
    
    <p>To make these tags fully functional, you would need to:</p>
    <ol>
        <li>Download JSTL JAR files (jstl-1.2.jar, standard-1.1.2.jar)</li>
        <li>Place them in your <code>WEB-INF/lib/</code> directory</li>
        <li>Restart Gumdrop to reload the web application</li>
    </ol>
    
    <p>Gumdrop's JSP implementation would then:</p>
    <ul>
        <li>Find the TLD files inside the JSTL JARs</li>
        <li>Generate appropriate Java code for tag instantiation</li>
        <li>Handle tag attributes and body content</li>
        <li>Manage tag handler lifecycle properly</li>
    </ul>
    
    <h2>Taglib Architecture</h2>
    
    <p>Gumdrop's taglib support includes:</p>
    <ul>
        <li><strong>TaglibRegistry:</strong> Manages taglib URI-to-TLD mappings</li>
        <li><strong>TldParser:</strong> SAX-based parser for Tag Library Descriptors</li>
        <li><strong>TagLibraryDescriptor:</strong> In-memory representation of TLD metadata</li>
        <li><strong>Custom Tag Code Generation:</strong> Generates proper tag handler instantiation and lifecycle management</li>
    </ul>
    
    <hr/>
    <p><em>Gumdrop JSP Engine - Full JSP 2.0+ Taglib Support</em></p>
</body>
</html>
