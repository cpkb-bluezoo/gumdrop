/*
 * ManagerServlet.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.ThreadPoolExecutor;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Gumdrop servlet context manager servlet.
 * This servlet can be used in a web application to query and control the
 * web application context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ManagerServlet extends HttpServlet {

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Locale locale = request.getLocale();
        ResourceBundle resources =
                (locale == null)
                        ? ResourceBundle.getBundle(ManagerServlet.class.getName())
                        : ResourceBundle.getBundle(ManagerServlet.class.getName(), locale);

        String pathInfo = request.getPathInfo();
        Context ctx = (Context) getServletContext();
        Container container = ctx.container;
        ServletConnector connector = ctx.connector;
        String contextPath = request.getContextPath();
        StringBuffer buf = new StringBuffer();
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

        ThreadPoolExecutor threadPool = connector.getConnectorThreadPool();

        // Core pool size
        buf.append("\t\t<div><form method='post' action='.'><label for='core-pool-size'>");
        buf.append(resources.getString("corePoolSize"));
        buf.append(":</label>&nbsp;<input type='text' name='core-pool-size' value='");
        buf.append(threadPool.getCorePoolSize());
        buf.append("'/>&nbsp;<input type='submit' value='");
        buf.append(resources.getString("set"));
        buf.append("'/></form></div>\r\n");

        // Maximum pool size
        buf.append("\t\t<div><form method='post' action='.'><label for='maximum-pool-size'>");
        buf.append(resources.getString("maximumPoolSize"));
        buf.append(":</label>&nbsp;<input type='text' name='maximum-pool-size' value='");
        buf.append(threadPool.getMaximumPoolSize());
        buf.append("'/>&nbsp;<input type='submit' value='");
        buf.append(resources.getString("set"));
        buf.append("'/></form></div>\r\n");

        // Keep-alive time
        buf.append("\t\t<div><form method='post' action='.'><label for='keep-alive-time'>");
        buf.append(resources.getString("keepAliveTime"));
        buf.append(":</label>&nbsp;<input type='text' name='keep-alive-time' value='");
        buf.append(connector.getKeepAlive());
        buf.append("'/>&nbsp;<input type='submit' value='");
        buf.append(resources.getString("set"));
        buf.append("'/></form></div>\r\n");

        // Connector status TODO
        buf.append("\t\t<table summary='");
        buf.append(resources.getString("threads"));
        buf.append("'>\r\n");
        buf.append("\t\t\t<tr><th>");
        buf.append(resources.getString("thread"));
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
        buf.append("</th></tr>\r\n");
        /*for (Iterator i = connector.threads.iterator(); i.hasNext(); ) {
            RequestHandlerThread t = (RequestHandlerThread) i.next();
            buf.append("\t\t\t<tr><td>");
            buf.append(t.getName());
            buf.append("</td>");
            for (int j = 0; j < 6; j++) {
                buf.append("<td>");
                buf.append(t.hits[j]);
                buf.append("</td>");
            }
            buf.append("</tr>\r\n");
        }*/
        buf.append("</table>\r\n");

        // Contexts
        buf.append("\t\t<table summary='");
        buf.append(resources.getString("contexts"));
        buf.append("'>\r\n");
        for (Context context : container.contexts) {
            // Context name and description
            buf.append("\t\t\t<tr class='context'><td>");
            String icon = context.smallIcon;
            if (icon == null) {
                icon = contextPath + "/gumdrop_green_16x16.png";
            }
            buf.append("<img src='");
            buf.append(icon);
            buf.append("'/>");
            buf.append("</td><td colspan='2'>");
            if (context.displayName != null) {
                buf.append(context.displayName);
            }
            buf.append("</td><td>");
            buf.append(context.contextPath);
            buf.append("</td><td>");
            buf.append(context.root);
            buf.append("</td><td><form method='post' action='.'><input type='hidden' name='reload' value='");
            buf.append(context.contextPath);
            buf.append("'><input type='submit' value='");
            buf.append(resources.getString("reload"));
            buf.append("'/></form>");
            buf.append("</td></tr>\r\n");
            if (context.description != null) {
                buf.append("\t\t<tr><td></td><td colspan='3'>");
                buf.append(context.description);
                buf.append("</td></tr>\r\n");
            }
            // List filters
            if (!context.filterDefs.isEmpty()) {
                buf.append("\t\t<tr><td colspan='4'><h4>");
                buf.append(resources.getString("filters"));
                buf.append("</h4></td></tr>\r\n");
            }
            for (Iterator j = context.filterDefs.values().iterator(); j.hasNext(); ) {
                FilterDef fd = (FilterDef) j.next();
                buf.append("\t\t<tr><td></td><td>");
                icon = fd.smallIcon;
                if (icon == null) {
                    icon = contextPath + "/gumdrop_yellow_16x16.png";
                }
                buf.append("<img src='");
                buf.append(icon);
                buf.append("'/>");
                buf.append("</td><td>");
                if (fd.displayName != null) {
                    buf.append(fd.displayName);
                }
                buf.append("</td><td>");
                buf.append(fd.name);
                buf.append("</td><td>");
                for (Iterator k = context.filterMappings.iterator(); k.hasNext(); ) {
                    FilterMapping fm = (FilterMapping) k.next();
                    if (!fm.name.equals(fd.name)) {
                        continue;
                    }
                    if (fm.servletName != null) {
                        buf.append("<i>");
                        buf.append(fm.servletName);
                        buf.append("</i>");
                    } else {
                        buf.append(fm.urlPattern);
                    }
                    buf.append(' ');
                }
                buf.append("</td></tr>\r\n");
                if (fd.description != null) {
                    buf.append("\t\t<tr><td></td><td></td><td colspan='2'>");
                    buf.append(fd.description);
                    buf.append("</td></tr>\r\n");
                }
            }
            // List servlets
            if (!context.servletDefs.isEmpty()) {
                buf.append("\t\t<tr><td colspan='4'><h4>");
                buf.append(resources.getString("servlets"));
                buf.append("</h4></td></tr>\r\n");
            }
            for (Iterator j = context.servletDefs.values().iterator(); j.hasNext(); ) {
                ServletDef sd = (ServletDef) j.next();
                buf.append("\t\t<tr><td></td><td>");
                icon = sd.smallIcon;
                if (icon == null) {
                    icon = contextPath + "/gumdrop_purple_16x16.png";
                }
                buf.append("<img src='");
                buf.append(icon);
                buf.append("'/>");
                buf.append("</td><td>");
                if (sd.displayName != null) {
                    buf.append(sd.displayName);
                }
                buf.append("</td><td>");
                if (sd.name != null) {
                    buf.append(sd.name);
                }
                buf.append("</td><td>");
                for (Iterator k = context.servletMappings.iterator(); k.hasNext(); ) {
                    ServletMapping sm = (ServletMapping) k.next();
                    if ((sm.name == null && sd.name == null)
                            || (sm.name != null && sm.name.equals(sd.name))) {
                        buf.append(sm.urlPattern);
                        buf.append(' ');
                    }
                }
                buf.append("</td></tr>\r\n");
                if (sd.description != null) {
                    buf.append("\t\t<tr><td></td><td></td><td colspan='2'>");
                    buf.append(sd.description);
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

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getServletPath();
        Context ctx = (Context) getServletContext();
        Container container = ctx.container;
        ServletConnector connector = ctx.connector;
        ThreadPoolExecutor threadPool = connector.getConnectorThreadPool();

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
                        connector.setKeepAlive(value);
                    } catch (Exception e) {
                        response.sendError(400);
                        return;
                    }
                } else if ("reload".equals(name)) {
                    for (Context context : container.contexts) {
                        if (context.contextPath.equals(value)) {
                            try {
                                synchronized (context) {
                                    long t1 = System.currentTimeMillis();
                                    context.destroy();
                                    context.reload();
                                    context.init();
                                    long t2 = System.currentTimeMillis();
                                    Context.LOGGER.info(
                                            "Redeployed context "
                                                    + context.contextPath
                                                    + " in "
                                                    + (t2 - t1)
                                                    + "ms");
                                }
                            } catch (Exception e) {
                                response.sendError(500, "Error redeploying context");
                                return;
                            }
                        }
                    }
                }
            }
        }

        String contextPath = ctx.contextPath;
        if ("/".equals(contextPath)) {
            response.sendRedirect(contextPath);
        } else {
            response.sendRedirect(contextPath + "/");
        }
    }

}
