/*
 * ManagerServlet.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet.manager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.FilterRegistration;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Gumdrop servlet context manager servlet.
 * This servlet can be used in a web application to query and control the
 * web application container.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ManagerServlet extends HttpServlet {

    static final Logger LOGGER = Logger.getLogger(ManagerServlet.class.getName());

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Locale locale = request.getLocale();
        ResourceBundle resources = (locale == null)
                        ? ResourceBundle.getBundle(ManagerServlet.class.getName())
                        : ResourceBundle.getBundle(ManagerServlet.class.getName(), locale);

        String pathInfo = request.getPathInfo();
        ContextService ctx = (ContextService) getServletContext();
        ContainerService container = ctx.getContainer();
        ThreadPoolExecutor threadPool = ctx.getConnectorThreadPool();

        String contextPath = request.getContextPath();
        StringBuilder buf = new StringBuilder();
        buf.append("<html>\r\n");
        buf.append("\t<head>\r\n");
        buf.append("\t\t<title>");
        buf.append(resources.getString("title"));
        buf.append("</title>\r\n");
        buf.append("\t\t<style type='text/css'>\r\n");
        buf.append(".context { background: #cccccc; }\r\n");
        buf.append(".server-info {\r\n");
        buf.append("\tbackground: #cccccc;\r\n");
        buf.append("\tfont-size: small;\r\n");
        buf.append("\tmargin-top: 0.5em;\r\n");
        buf.append("\tmargin-bottom: 0.5em;\r\n");
        buf.append("}\r\n");
        buf.append("\t\t</style>\r\n");
        buf.append("\t</head>\r\n");
        buf.append("\t<body>\r\n");
        buf.append("\t\t<h1>");
        buf.append(resources.getString("title"));
        buf.append("</h1>\r\n");


        // Core pool size
        buf.append("\t\t<div><form method='post'><label for='core-pool-size'>");
        buf.append(resources.getString("corePoolSize"));
        buf.append(":</label>&nbsp;<input type='text' name='core-pool-size' value='");
        buf.append(threadPool.getCorePoolSize());
        buf.append("'/>&nbsp;<input type='submit' value='");
        buf.append(resources.getString("set"));
        buf.append("'/></form></div>\r\n");

        // Maximum pool size
        buf.append("\t\t<div><form method='post'><label for='maximum-pool-size'>");
        buf.append(resources.getString("maximumPoolSize"));
        buf.append(":</label>&nbsp;<input type='text' name='maximum-pool-size' value='");
        buf.append(threadPool.getMaximumPoolSize());
        buf.append("'/>&nbsp;<input type='submit' value='");
        buf.append(resources.getString("set"));
        buf.append("'/></form></div>\r\n");

        // Keep-alive time
        buf.append("\t\t<div><form method='post'><label for='keep-alive-time'>");
        buf.append(resources.getString("keepAliveTime"));
        buf.append(":</label>&nbsp;<input type='text' name='keep-alive-time' value='");
        buf.append(ctx.getConnectorKeepAlive());
        buf.append("'/>&nbsp;<input type='submit' value='");
        buf.append(resources.getString("set"));
        buf.append("'/></form></div>\r\n");

        // Contexts
        buf.append("\t\t<h3>");
        buf.append(resources.getString("contexts"));
        buf.append("</h3>\r\n");
        buf.append("\t\t<table summary='");
        buf.append(resources.getString("contexts"));
        buf.append("'>\r\n");
        // Header
        buf.append("\t\t\t<tr><th></th><th colspan='3'>"); // nb icon
        buf.append(resources.getString("displayName"));
        buf.append("</th><th>");
        buf.append(resources.getString("contextPath"));
        buf.append("</th><th>");
        buf.append(resources.getString("root"));
        buf.append("</th><th>");
        buf.append(resources.getString("total"));
        buf.append("</th><th>");
        buf.append(resources.getString("informational"));
        buf.append("</th><th>");
        buf.append(resources.getString("success"));
        buf.append("</th><th>");
        buf.append(resources.getString("redirect"));
        buf.append("</th><th>");
        buf.append(resources.getString("clientError"));
        buf.append("</th><th>");
        buf.append(resources.getString("serverError"));
        buf.append("</th><th></th></tr>\r\n"); // nb reload button

        for (ContextService context : container.getContexts()) {
            HitStatistics hitStatistics = context.getHitStatistics();
            // Context name and description
            buf.append("\t\t\t<tr class='context'><td>");
            String icon = context.getSmallIcon();
            if (icon == null) {
                icon = contextPath + "/gumdrop_green_16x16.png";
            }
            buf.append("<img src='");
            buf.append(icon);
            buf.append("'/>");
            buf.append("</td><td colspan='3'>");
            if (context.getDisplayName() != null) {
                buf.append(context.getDisplayName());
            }
            buf.append("</td><td>");
            buf.append(context.getContextPath());
            buf.append("</td><td>");
            buf.append(context.getRoot());
            buf.append("</td><td>");
            synchronized (hitStatistics) {
                buf.append(hitStatistics.getTotal());
                buf.append("</td><td>");
                buf.append(hitStatistics.getHits(HitStatistics.INFORMATIONAL));
                buf.append("</td><td>");
                buf.append(hitStatistics.getHits(HitStatistics.SUCCESS));
                buf.append("</td><td>");
                buf.append(hitStatistics.getHits(HitStatistics.REDIRECT));
                buf.append("</td><td>");
                buf.append(hitStatistics.getHits(HitStatistics.CLIENT_ERROR));
                buf.append("</td><td>");
                buf.append(hitStatistics.getHits(HitStatistics.SERVER_ERROR));
            }
            buf.append("</td><td><form method='post'><input type='hidden' name='reload' value='");
            buf.append(context.getContextPath());
            buf.append("'><input type='submit' value='");
            buf.append(resources.getString("reload"));
            buf.append("'/></form>");
            buf.append("</td></tr>\r\n");
            if (context.getDescription() != null) {
                buf.append("\t\t<tr><td></td><td colspan='10'>");
                buf.append(context.getDescription());
                buf.append("</td></tr>\r\n");
            }
            // List filters
            Map<String,? extends FilterRegistration> filterRegistrations = context.getFilterRegistrations();
            if (!filterRegistrations.isEmpty()) {
                buf.append("\t\t<tr><td colspan='10'><h4>");
                buf.append(resources.getString("filters"));
                buf.append("</h4></td></tr>\r\n");
            }
            for (Iterator j = filterRegistrations.values().iterator(); j.hasNext(); ) {
                FilterReg fd = (FilterReg) j.next();
                buf.append("\t\t<tr><td></td><td>");
                icon = fd.getSmallIcon();
                if (icon == null) {
                    icon = contextPath + "/gumdrop_yellow_16x16.png";
                }
                buf.append("<img src='");
                buf.append(icon);
                buf.append("'/>");
                buf.append("</td><td>");
                if (fd.getDisplayName() != null) {
                    buf.append(fd.getDisplayName());
                }
                buf.append("</td><td>");
                buf.append(fd.getName());
                buf.append("</td><td>");
                Collection<String> servletNameMappings = fd.getServletNameMappings();
                Collection<String> urlPatternMappings = fd.getUrlPatternMappings();
                for (String servletName : servletNameMappings) {
                    buf.append("<i>");
                    buf.append(servletName);
                    buf.append("</i>");
                    buf.append(' ');
                }
                for (String urlPattern : urlPatternMappings) {
                    buf.append(urlPattern);
                    buf.append(' ');
                }
                buf.append("</td></tr>\r\n");
                if (fd.getDescription() != null) {
                    buf.append("\t\t<tr><td></td><td></td><td colspan='2'>");
                    buf.append(fd.getDescription());
                    buf.append("</td></tr>\r\n");
                }
            }
            // List servlets
            Map<String,? extends ServletRegistration> servletRegistrations = context.getServletRegistrations();
            if (!servletRegistrations.isEmpty()) {
                buf.append("\t\t<tr><td colspan='4'><h4>");
                buf.append(resources.getString("servlets"));
                buf.append("</h4></td></tr>\r\n");
            }
            for (Iterator j = servletRegistrations.values().iterator(); j.hasNext(); ) {
                ServletReg sd = (ServletReg) j.next();
                buf.append("\t\t<tr><td></td><td>");
                icon = sd.getSmallIcon();
                if (icon == null) {
                    icon = contextPath + "/gumdrop_purple_16x16.png";
                }
                buf.append("<img src='");
                buf.append(icon);
                buf.append("'/>");
                buf.append("</td><td>");
                if (sd.getDisplayName() != null) {
                    buf.append(sd.getDisplayName());
                }
                buf.append("</td><td>");
                if (sd.getName() != null) {
                    buf.append(sd.getName());
                }
                buf.append("</td><td>");
                Collection<String> urlPatterns = sd.getMappings();
                for (String urlPattern : urlPatterns) {
                    buf.append(urlPattern);
                    buf.append(' ');
                }
                buf.append("</td></tr>\r\n");
                if (sd.getDescription() != null) {
                    buf.append("\t\t<tr><td></td><td></td><td colspan='2'>");
                    buf.append(sd.getDescription());
                    buf.append("</td></tr>\r\n");
                }
            }
        }
        buf.append("\t\t</table>\r\n");
        buf.append("\t\t<p class='server-info'>");
        MessageFormat mf = new MessageFormat(resources.getString("serverInfo"));
        Object[] args =
            new Object[] {
                getServletContext().getServerInfo(),
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version")
            };
        buf.append(mf.format(args));
        // buf.append(System.getProperties().toString());
        buf.append("</p>\r\n");
        buf.append("\t</body>\r\n");
        buf.append("</html>\r\n");

        // Convert to UTF-8 encoded byte array
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(sink, "UTF-8");
        writer.write(buf.toString());
        writer.flush();
        byte[] bytes = sink.toByteArray();

        // Response
        response.setStatus(200);
        response.setContentType("text/html; charset=UTF-8");
        response.setContentLength(bytes.length);
        OutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Locale locale = request.getLocale();
        ResourceBundle resources = (locale == null)
                        ? ResourceBundle.getBundle(ManagerServlet.class.getName())
                        : ResourceBundle.getBundle(ManagerServlet.class.getName(), locale);

        String path = request.getServletPath();
        String contextPath = request.getContextPath();
        ContextService ctx = (ContextService) getServletContext();
        ContainerService container = ctx.getContainer();
        ThreadPoolExecutor threadPool = ctx.getConnectorThreadPool();

        for (Enumeration<String> names = request.getParameterNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            String value = request.getParameter(name);
            if (value != null) {
                if ("core-pool-size".equals(name)) {
                    try {
                        threadPool.setCorePoolSize(Integer.parseInt(value));
                    } catch (Exception e) {
                        response.sendError(400);
                        return;
                    }
                } else if ("maximum-pool-size".equals(name)) {
                    try {
                        threadPool.setMaximumPoolSize(Integer.parseInt(value));
                    } catch (Exception e) {
                        response.sendError(400);
                        return;
                    }
                } else if ("keep-alive-time".equals(name)) {
                    try {
                        ctx.setConnectorKeepAlive(value);
                    } catch (Exception e) {
                        response.sendError(400);
                        return;
                    }
                } else if ("reload".equals(name)) {
                    ContextService context = container.getContext(value);
                    try {
                        context.reload();
                    } catch (Exception e) {
                        String message = resources.getString("err.reload");
                        LOGGER.log(Level.SEVERE, message, e);
                        response.sendError(500, message);
                        return;
                    }
                }
            }
        }

        response.sendRedirect(contextPath + "/");
    }

}
