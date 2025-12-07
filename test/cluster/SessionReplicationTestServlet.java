/*
 * SessionReplicationTestServlet.java
 *
 * This servlet is a simple tool to test J2EE distributed session replication.
 * It's designed to be deployed to a servlet container that is part of a cluster.
 *
 * On each GET request, it performs the following actions:
 * 1. Retrieves or creates a new HttpSession.
 * 2. Generates a new unique value (a UUID).
 * 3. Sets this value as a session attribute.
 * 4. Writes an HTML response back to the client, displaying the session ID
 * and the value stored in the session.
 *
 * To test, a user can make a request to the servlet on one node of the cluster.
 * The session will be created and the value will be displayed. Then, the user can
 * make a subsequent request to a different node in the cluster. If session
 * replication is working, the same session ID and the same value should be displayed.
 */

import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Servlet implementation class SessionReplicationTestServlet
 */
@WebServlet(urlPatterns = {"/"})
public class SessionReplicationTestServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // A constant to hold the attribute name in the session
    private static final String SESSION_VALUE_KEY = "replication_test_value";

    /**
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Get the session. If one doesn't exist, create it.
        HttpSession session = request.getSession(true);
        
        // Generate a unique value for this request
        String newValue = UUID.randomUUID().toString();
        
        // Get the existing value from the session, if it exists
        String existingValue = (String) session.getAttribute(SESSION_VALUE_KEY);
        
        // Set the new unique value into the session
        session.setAttribute(SESSION_VALUE_KEY, newValue);

        // Set the content type of the response
        response.setContentType("text/html");
        
        PrintWriter out = response.getWriter();
        
        // Build the HTML response
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\">");
        out.println("<head>");
        out.println("<meta charset=\"UTF-8\">");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        out.println("<title>Session Replication Test</title>");
        out.println("<style>");
        out.println("body { font-family: sans-serif; margin: 2rem; background-color: #f4f4f4; }");
        out.println("h1, h2 { color: #333; }");
        out.println("p { background-color: #fff; padding: 1rem; border: 1px solid #ccc; border-radius: 8px; }");
        out.println(".container { max-width: 800px; margin: 0 auto; }");
        out.println(".highlight { font-weight: bold; color: #007bff; }");
        out.println("</style>");
        out.println("</head>");
        out.println("<body>");
        out.println("<div class=\"container\">");
        out.println("<h1>Session Replication Test</h1>");
        out.println("<p><strong>This Node:</strong> " + request.getServerName() + ":" + request.getServerPort() + "</p>");
        out.println("<p><strong>Session ID:</strong> <span class=\"highlight\">" + session.getId() + "</span></p>");
        
        if (existingValue != null) {
            out.println("<p><strong>Previous value found in session:</strong> <span class=\"highlight\">" + existingValue + "</span></p>");
            out.println("<p><strong>New value set in session:</strong> <span class=\"highlight\">" + newValue + "</span></p>");
        } else {
            out.println("<p><strong>No previous value found.</strong> This is a new session.</p>");
            out.println("<p><strong>New value set in session:</strong> <span class=\"highlight\">" + newValue + "</span></p>");
        }
        
        out.println("<p>Refresh this page or access it from another cluster node to see if the session value persists.</p>");
        
        out.println("</div>");
        out.println("</body>");
        out.println("</html>");
        
        out.close();
    }
}

