/*
 * TestFilter.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Test filter for integration testing of filter chain functionality.
 * 
 * <p>This filter:
 * <ul>
 *   <li>Adds a request attribute to indicate the filter was applied</li>
 *   <li>Adds a response header X-Filter-Applied</li>
 *   <li>Logs timing information</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(TestFilter.class.getName());
    private FilterConfig filterConfig;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
        LOGGER.info("TestFilter.init() called - filter name: " + filterConfig.getFilterName());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        LOGGER.info("TestFilter.doFilter() called");
        long startTime = System.currentTimeMillis();
        
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            
            // Set request attribute to indicate filter was applied
            httpRequest.setAttribute("filterApplied", Boolean.TRUE);
            httpRequest.setAttribute("filterName", filterConfig.getFilterName());
            httpRequest.setAttribute("filterStartTime", startTime);
            
            // Add response header before chain (will be sent with response)
            httpResponse.setHeader("X-Filter-Applied", "true");
            httpResponse.setHeader("X-Filter-Name", filterConfig.getFilterName());
            
            // Continue the filter chain
            chain.doFilter(request, response);
            
            // After chain completes, add timing header if not committed
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            
            if (!httpResponse.isCommitted()) {
                httpResponse.setHeader("X-Filter-Duration-Ms", String.valueOf(duration));
            }
            
        } else {
            // Non-HTTP request, just continue the chain
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
        LOGGER.info("TestFilter.destroy() called");
        this.filterConfig = null;
    }
}
