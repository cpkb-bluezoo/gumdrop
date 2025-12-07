/*
 * JspFactory.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package javax.servlet.jsp;

import javax.servlet.Servlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * Minimal JSP Factory implementation for Gumdrop JSP support.
 * 
 * <p>This factory class provides the default implementation for creating
 * PageContext instances and managing JSP runtime resources.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class JspFactory {

    private static volatile JspFactory deflt = null;

    /**
     * Sole constructor. (For invocation by subclass constructors, typically implicit.)
     */
    public JspFactory() {
    }

    /**
     * Sets the default factory for this implementation.
     * It is illegal for any principal other than the JSP Engine runtime 
     * to call this method.
     *
     * @param deflt The default factory implementation
     */
    public static void setDefaultFactory(JspFactory deflt) {
        JspFactory.deflt = deflt;
    }

    /**
     * Returns the default factory for this implementation.
     *
     * @return the default factory for this implementation
     */
    public static JspFactory getDefaultFactory() {
        if (deflt == null) {
            // Create a basic default implementation
            synchronized (JspFactory.class) {
                if (deflt == null) {
                    deflt = new DefaultJspFactory();
                }
            }
        }
        return deflt;
    }

    /**
     * Obtain an instance of a PageContext class implementation for use by the calling Servlet 
     * and base implementation.
     *
     * @param servlet   the requesting servlet
     * @param request   the current request pending on the servlet
     * @param response  the current response pending on the servlet  
     * @param errorPageURL the URL of the error page for the requesting JSP, or null
     * @param needsSession true if the JSP participates in a session
     * @param bufferSize  the buffer size from the page directive, or 8192
     * @param autoFlush   whether the buffer should be auto flushed
     * @return A valid PageContext instance for use by the calling servlet.
     */
    public abstract PageContext getPageContext(Servlet servlet, ServletRequest request,
                                              ServletResponse response, String errorPageURL,
                                              boolean needsSession, int bufferSize, 
                                              boolean autoFlush);

    /**
     * Called to release a previously allocated PageContext object.
     * Results in PageContext.release() being invoked.
     * This method should be invoked prior to returning from the _jspService() method of a JSP implementation class.
     *
     * @param pc A PageContext previously obtained by getPageContext()
     */
    public abstract void releasePageContext(PageContext pc);

    /**
     * Default implementation of JspFactory.
     */
    private static class DefaultJspFactory extends JspFactory {

        @Override
        public PageContext getPageContext(Servlet servlet, ServletRequest request,
                                         ServletResponse response, String errorPageURL,
                                         boolean needsSession, int bufferSize, 
                                         boolean autoFlush) {
            return new DefaultPageContext(servlet, request, response, errorPageURL,
                                        needsSession, bufferSize, autoFlush);
        }

        @Override
        public void releasePageContext(PageContext pc) {
            if (pc != null) {
                pc.release();
            }
        }
    }
}
