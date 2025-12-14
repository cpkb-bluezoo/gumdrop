/*
 * ManagerServlet.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet.manager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Enumeration;
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

    static final String L10N_NAME = "org.bluezoo.gumdrop.servlet.manager.L10N";
    static final Logger LOGGER = Logger.getLogger(ManagerServlet.class.getName());

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Locale locale = request.getLocale();
        ResourceBundle resources = (locale == null)
                        ? ResourceBundle.getBundle(L10N_NAME)
                        : ResourceBundle.getBundle(L10N_NAME, locale);

        ManagerContextService ctx = (ManagerContextService) getServletContext();
        ManagerContainerService container = ctx.getContainer();
        ThreadPoolExecutor threadPool = ctx.getWorkerThreadPool();

        String contextPath = request.getContextPath();
        StringBuilder buf = new StringBuilder();
        
        appendHtmlHead(buf, resources, contextPath);
        appendHeader(buf, resources, contextPath);
        appendThreadPoolSection(buf, resources, ctx, threadPool);
        appendContextsSection(buf, resources, container, contextPath);
        appendHtmlFooter(buf);

        writeResponse(response, buf.toString());
    }
    
    private void appendHtmlHead(StringBuilder buf, ResourceBundle resources, String contextPath) {
        buf.append("<!DOCTYPE html>\n");
        buf.append("<html lang='en'>\n");
        buf.append("<head>\n");
        buf.append("  <meta charset='UTF-8'>\n");
        buf.append("  <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        buf.append("  <title>").append(resources.getString("title")).append("</title>\n");
        buf.append("  <link rel='stylesheet' href='").append(contextPath).append("/manager.css'>\n");
        buf.append("</head>\n");
        buf.append("<body>\n");
        buf.append("<div class='container'>\n");
    }
    
    private void appendHtmlFooter(StringBuilder buf) {
        buf.append("</div>\n");
        buf.append("</body>\n");
        buf.append("</html>\n");
    }
    
    private void appendHeader(StringBuilder buf, ResourceBundle resources, String contextPath) {
        buf.append("  <header class='header'>\n");
        buf.append("    <div class='logo'>\n");
        buf.append("      <img src='").append(contextPath).append("/gumdrop.png' alt='Gumdrop' class='logo-icon'>\n");
        buf.append("      <h1>").append(resources.getString("title")).append("</h1>\n");
        buf.append("    </div>\n");
        buf.append("    <div class='server-info'>\n");
        MessageFormat mf = new MessageFormat(resources.getString("serverInfo"));
        Object[] args = new Object[] {
            getServletContext().getServerInfo(),
            System.getProperty("java.vm.name"),
            System.getProperty("java.vm.version")
        };
        buf.append("      <span>").append(escapeHtml(mf.format(args))).append("</span>\n");
        buf.append("    </div>\n");
        buf.append("  </header>\n");
    }
    
    private void appendThreadPoolSection(StringBuilder buf, ResourceBundle resources, 
                                         ManagerContextService ctx, ThreadPoolExecutor threadPool) {
        buf.append("  <section class='card thread-pool'>\n");
        buf.append("    <h2>").append(resources.getString("threads")).append("</h2>\n");
        
        // Live stats
        buf.append("    <div class='pool-stats'>\n");
        appendStatBox(buf, resources.getString("activeThreads"), threadPool.getActiveCount());
        appendStatBox(buf, resources.getString("poolSize"), threadPool.getPoolSize());
        appendStatBox(buf, resources.getString("queuedTasks"), threadPool.getQueue().size());
        appendStatBox(buf, resources.getString("completedTasks"), threadPool.getCompletedTaskCount());
        buf.append("    </div>\n");
        
        // Configuration forms
        buf.append("    <div class='pool-config'>\n");
        appendConfigForm(buf, resources, "core-pool-size", 
                        resources.getString("corePoolSize"), threadPool.getCorePoolSize(),
                        resources.getString("corePoolSize.caption"));
        appendConfigForm(buf, resources, "maximum-pool-size", 
                        resources.getString("maximumPoolSize"), threadPool.getMaximumPoolSize(),
                        resources.getString("maximumPoolSize.caption"));
        appendConfigForm(buf, resources, "keep-alive-time", 
                        resources.getString("keepAliveTime"), ctx.getWorkerKeepAlive(),
                        resources.getString("keepAliveTime.caption"));
        buf.append("    </div>\n");
        buf.append("  </section>\n");
    }
    
    private void appendStatBox(StringBuilder buf, String label, long value) {
        buf.append("      <div class='stat-box'>\n");
        buf.append("        <div class='stat-value'>").append(value).append("</div>\n");
        buf.append("        <div class='stat-label'>").append(escapeHtml(label)).append("</div>\n");
        buf.append("      </div>\n");
    }
    
    private void appendConfigForm(StringBuilder buf, ResourceBundle resources, 
                                  String name, String label, Object value, String caption) {
        buf.append("      <form method='post' class='config-form'>\n");
        buf.append("        <div class='form-row'>\n");
        buf.append("          <label for='").append(name).append("'>");
        buf.append(escapeHtml(label)).append("</label>\n");
        buf.append("          <input type='text' id='").append(name).append("' name='").append(name);
        buf.append("' value='").append(escapeHtml(String.valueOf(value))).append("'/>\n");
        buf.append("          <button type='submit' class='btn'>");
        buf.append(resources.getString("set")).append("</button>\n");
        buf.append("        </div>\n");
        buf.append("        <div class='caption'>").append(escapeHtml(caption)).append("</div>\n");
        buf.append("      </form>\n");
    }
    
    private void appendContextsSection(StringBuilder buf, ResourceBundle resources, 
                                       ManagerContainerService container, String managerContextPath) {
        buf.append("  <section class='card contexts'>\n");
        buf.append("    <h2>").append(resources.getString("contexts")).append("</h2>\n");
        buf.append("    <div class='context-list'>\n");
        
        for (ManagerContextService context : container.getContexts()) {
            appendContextCard(buf, resources, context, managerContextPath);
        }
        
        buf.append("    </div>\n");
        buf.append("  </section>\n");
    }
    
    private void appendContextCard(StringBuilder buf, ResourceBundle resources, 
                                   ManagerContextService context, String managerContextPath) {
        HitStatistics stats = context.getHitStatistics();
        String icon = context.getSmallIcon();
        if (icon == null) {
            icon = managerContextPath + "/gumdrop_green_16x16.png";
        }
        
        buf.append("      <div class='context-card'>\n");
        
        // Header with name and reload button
        buf.append("        <div class='context-header'>\n");
        buf.append("          <div class='context-info'>\n");
        buf.append("            <img src='").append(icon).append("' alt='' class='context-icon'/>\n");
        buf.append("            <div>\n");
        buf.append("              <div class='context-name'>");
        buf.append(escapeHtml(context.getDisplayName() != null ? context.getDisplayName() : context.getContextPath()));
        buf.append("</div>\n");
        buf.append("              <div class='context-path'>").append(escapeHtml(context.getContextPath())).append("</div>\n");
        buf.append("            </div>\n");
        buf.append("          </div>\n");
        buf.append("          <form method='post' style='margin:0'>\n");
        buf.append("            <input type='hidden' name='reload' value='");
        buf.append(escapeHtml(context.getContextPath())).append("'/>\n");
        buf.append("            <button type='submit' class='btn btn-secondary'>");
        buf.append(resources.getString("reload")).append("</button>\n");
        buf.append("          </form>\n");
        buf.append("        </div>\n");
        
        // Body
        buf.append("        <div class='context-body'>\n");
        
        if (context.getDescription() != null) {
            buf.append("          <div class='context-description'>");
            buf.append(escapeHtml(context.getDescription())).append("</div>\n");
        }
        
        buf.append("          <div class='context-meta'>").append(escapeHtml(context.getRoot())).append("</div>\n");
        
        // Hit statistics
        buf.append("          <div class='hit-stats'>\n");
        synchronized (stats) {
            appendHitStat(buf, "total", resources.getString("total"), stats.getTotal());
            appendHitStat(buf, "info", "1xx", stats.getHits(HitStatistics.INFORMATIONAL));
            appendHitStat(buf, "success", "2xx", stats.getHits(HitStatistics.SUCCESS));
            appendHitStat(buf, "redirect", "3xx", stats.getHits(HitStatistics.REDIRECT));
            appendHitStat(buf, "client-error", "4xx", stats.getHits(HitStatistics.CLIENT_ERROR));
            appendHitStat(buf, "server-error", "5xx", stats.getHits(HitStatistics.SERVER_ERROR));
        }
        buf.append("          </div>\n");
        
        // Filters
        appendFiltersSection(buf, resources, context, managerContextPath);
        
        // Servlets
        appendServletsSection(buf, resources, context, managerContextPath);
        
        buf.append("        </div>\n");
        buf.append("      </div>\n");
    }
    
    private void appendHitStat(StringBuilder buf, String cssClass, String label, long value) {
        buf.append("            <div class='hit-stat ").append(cssClass).append("'>\n");
        buf.append("              <span class='dot'></span>\n");
        buf.append("              <span class='value'>").append(value).append("</span>\n");
        buf.append("              <span class='label'>").append(escapeHtml(label)).append("</span>\n");
        buf.append("            </div>\n");
    }
    
    private void appendFiltersSection(StringBuilder buf, ResourceBundle resources, 
                                      ManagerContextService context, String managerContextPath) {
        Map<String,? extends FilterRegistration> filters = context.getFilterRegistrations();
        if (filters.isEmpty()) {
            return;
        }
        
        buf.append("          <div class='components-section'>\n");
        buf.append("            <details>\n");
        buf.append("              <summary>").append(resources.getString("filters"));
        buf.append(" <span class='component-count'>").append(filters.size()).append("</span></summary>\n");
        buf.append("              <div class='component-list'>\n");
        
        for (FilterRegistration fr : filters.values()) {
            FilterReg fd = (FilterReg) fr;
            String icon = fd.getSmallIcon();
            if (icon == null) {
                icon = managerContextPath + "/gumdrop_yellow_16x16.png";
            }
            appendComponentItem(buf, icon, 
                               fd.getDisplayName() != null ? fd.getDisplayName() : fd.getName(),
                               buildFilterMappings(fd), fd.getDescription());
        }
        
        buf.append("              </div>\n");
        buf.append("            </details>\n");
        buf.append("          </div>\n");
    }
    
    private String buildFilterMappings(FilterReg fd) {
        StringBuilder mappings = new StringBuilder();
        for (String servletName : fd.getServletNameMappings()) {
            if (mappings.length() > 0) mappings.append(" ");
            mappings.append("→").append(servletName);
        }
        for (String urlPattern : fd.getUrlPatternMappings()) {
            if (mappings.length() > 0) mappings.append(" ");
            mappings.append(urlPattern);
        }
        return mappings.toString();
    }
    
    private void appendServletsSection(StringBuilder buf, ResourceBundle resources, 
                                       ManagerContextService context, String managerContextPath) {
        Map<String,? extends ServletRegistration> servlets = context.getServletRegistrations();
        if (servlets.isEmpty()) {
            return;
        }
        
        buf.append("          <div class='components-section'>\n");
        buf.append("            <details>\n");
        buf.append("              <summary>").append(resources.getString("servlets"));
        buf.append(" <span class='component-count'>").append(servlets.size()).append("</span></summary>\n");
        buf.append("              <div class='component-list'>\n");
        
        for (ServletRegistration sr : servlets.values()) {
            ServletReg sd = (ServletReg) sr;
            String icon = sd.getSmallIcon();
            if (icon == null) {
                icon = managerContextPath + "/gumdrop_purple_16x16.png";
            }
            String name = sd.getDisplayName() != null ? sd.getDisplayName() : sd.getName();
            String mappings = String.join(" ", sd.getMappings());
            appendComponentItem(buf, icon, name, mappings, sd.getDescription());
        }
        
        buf.append("              </div>\n");
        buf.append("            </details>\n");
        buf.append("          </div>\n");
    }
    
    private void appendComponentItem(StringBuilder buf, String icon, String name, 
                                     String mappings, String description) {
        buf.append("                <div class='component-item'>\n");
        buf.append("                  <img src='").append(icon).append("' alt='' class='component-icon'/>\n");
        buf.append("                  <div class='component-details'>\n");
        buf.append("                    <div class='component-name'>").append(escapeHtml(name)).append("</div>\n");
        if (mappings != null && !mappings.isEmpty()) {
            buf.append("                    <div class='component-mapping'>");
            buf.append(escapeHtml(mappings)).append("</div>\n");
        }
        if (description != null) {
            buf.append("                    <div class='component-desc'>");
            buf.append(escapeHtml(description)).append("</div>\n");
        }
        buf.append("                  </div>\n");
        buf.append("                </div>\n");
    }
    
    private void writeResponse(HttpServletResponse response, String content) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(sink, "UTF-8");
        writer.write(content);
        writer.flush();
        byte[] bytes = sink.toByteArray();

        response.setStatus(200);
        response.setContentType("text/html; charset=UTF-8");
        response.setContentLength(bytes.length);
        OutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
    
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Locale locale = request.getLocale();
        ResourceBundle resources = (locale == null)
                        ? ResourceBundle.getBundle(ManagerServlet.class.getName())
                        : ResourceBundle.getBundle(ManagerServlet.class.getName(), locale);

        String contextPath = request.getContextPath();
        ManagerContextService ctx = (ManagerContextService) getServletContext();
        ManagerContainerService container = ctx.getContainer();
        ThreadPoolExecutor threadPool = ctx.getWorkerThreadPool();

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
                        ctx.setWorkerKeepAlive(value);
                    } catch (Exception e) {
                        response.sendError(400);
                        return;
                    }
                } else if ("reload".equals(name)) {
                    ManagerContextService context = container.getContext(value);
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
