<%-- 
  Footer Coda - Automatically included at the end of JSP pages
  This demonstrates Gumdrop's JSP include-coda functionality
--%>
<%
    // Coda processing code - calculate page generation time
    Long startTime = (Long) request.getAttribute("pageLoadStartTime");
    long generationTime = 0;
    if (startTime != null) {
        generationTime = System.currentTimeMillis() - startTime;
    }
%>
<!-- Coda HTML: Footer and Performance Info -->
<div style="background-color: #f8f9fa; padding: 15px; margin-top: 20px; border-top: 2px solid #dee2e6;">
    <div style="background-color: #d1ecf1; padding: 8px; border-radius: 3px; margin-bottom: 10px;">
        <strong>📄 Coda Active:</strong> This content was automatically included 
        at the end of the JSP via <code>include-coda</code> configuration.
    </div>
    
    <div style="font-size: 12px; color: #6c757d;">
        <div style="margin-bottom: 5px;">
            <strong>Page Generation Time:</strong> <%= generationTime %> ms
        </div>
        <div style="margin-bottom: 5px;">
            <strong>Request Method:</strong> <%= request.getMethod() %>
        </div>
        <div style="margin-bottom: 5px;">
            <strong>Request URI:</strong> <%= request.getRequestURI() %>
        </div>
        <div style="margin-bottom: 5px;">
            <strong>Session ID:</strong> <%= session.getId() %>
        </div>
        <div>
            <strong>User Agent:</strong> <%= request.getHeader("User-Agent") != null ? 
                request.getHeader("User-Agent").substring(0, Math.min(60, request.getHeader("User-Agent").length())) + "..." 
                : "Unknown" %>
        </div>
    </div>
    
    <div style="text-align: center; margin-top: 10px; font-style: italic; color: #495057;">
        Powered by Gumdrop JSP Engine | Prelude &amp; Coda Processing Active
    </div>
</div>
