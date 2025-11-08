import java.lang.*;
import javax.servlet.http.*;
import java.util.Date;
import java.io.*;
import javax.servlet.jsp.*;
import javax.servlet.*;

/**
 * Generated servlet from JSP: test-example.jsp
 * Generated at: Sat Nov 08 21:16:30 GMT 2025
 */
public class TestExample_jsp extends HttpServlet {

    // JSP Declarations
    
    // Instance variable for demo
    private int visitCount = 0;
    
    // Helper method for demo
    private String formatDate(Date date) {
        return date.toString();
    }


    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        HttpSession session = request.getSession();
        PageContext pageContext = null;
        try {
            // Create page context for JSP tags
            javax.servlet.jsp.JspFactory jspFactory = javax.servlet.jsp.JspFactory.getDefaultFactory();
            pageContext = jspFactory.getPageContext(this, request, response, null, true, 8192, true);
            out = pageContext.getOut();
        
            out.write("\n<!DOCTYPE html>\n<html>\n<head>\n    <title>JSP Code Generation Test</title>\n</head>\n<body>\n    <h1>JSP Code Generation Example</h1>\n    \n    ");
            // Scriptlet at line 20
            
                    // Scriptlet demo
                    visitCount++;
                    Date now = new Date();
                    String greeting = "Hello from generated servlet!";
                
            out.write("\n    \n    <p>");
            out.write(String.valueOf(greeting));
            out.write("</p>\n    <p>Current time: ");
            out.write(String.valueOf(formatDate(now)));
            out.write("</p>\n    <p>Visit count: ");
            out.write(String.valueOf(visitCount));
            out.write("</p>\n    \n    <p>This JSP file demonstrates the elements that will be converted \n       to Java servlet source code by the JSPCodeGenerator.</p>\n       \n    ");
            out.write("\n</body>\n</html>\n");
        } catch (Exception e) {
            throw new ServletException("JSP processing error", e);
        } finally {
            if (pageContext != null) {
                javax.servlet.jsp.JspFactory.getDefaultFactory().releasePageContext(pageContext);
            }
        }
    }
}
