/*
 * TestServlet.java
 * Copyright (C) 2005 Chris Burdess
 * 
 * This file is part of GNU gumdrop, a multipurpose Java server.
 * 
 * GNU gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * GNU gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package test;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TestServlet
  extends HttpServlet
{

  public void doGet(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
  {
    // Get the request locale and use it to localise everything
    Locale locale = request.getLocale();
    Date now = new Date();
    DateFormat df = (locale == null) ?
      DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG) :
      DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG,
                                     locale);
    ResourceBundle bundle = (locale == null) ?
      ResourceBundle.getBundle(TestServlet.class.getName()) :
      ResourceBundle.getBundle(TestServlet.class.getName(), locale);
    String title = bundle.getString("title");
    MessageFormat mf = new MessageFormat(bundle.getString("who"));
    String who = mf.format(new Object[] { request.getRemoteUser()} );
    mf = new MessageFormat(bundle.getString("time"));
    String time = mf.format(new Object[] { df.format(now) });
    String serverInfo = getServletContext().getServerInfo();
    String contextPath = request.getContextPath();
    if (!contextPath.endsWith("/"))
      {
        contextPath = contextPath + "/";
      } 
    
    // HTTP header
    String charset = response.getCharacterEncoding();
    response.setStatus(200);
    response.setContentType("text/html; charset=" + charset);
    // HTTP body
    PrintWriter writer = response.getWriter();
    writer.println("<html>");
    writer.println("\t<head>");
    writer.println("\t\t<title>" + title + "</title>");
    writer.println("\t\t<link href='" + contextPath +
                   "main.css' rel='stylesheet' type='text/css'/>");
    writer.println("\t\t<link rel='icon' href='" + contextPath +
                   "favicon.ico'/>");
    writer.println("\t\t<link rel='SHORTCUT ICON' href='" + contextPath +
                   "favicon.ico'/>");
    writer.println("\t</head>");
    writer.println("\t<body>");
    writer.println("\t\t<h1 class='heading'>" + title + "</h1>");
    writer.println("\t\t<p>" + who + "</p>");
    writer.println("\t\t<p>" + time + "</p>");
    writer.println("\t\t<p class='server-info'>" + serverInfo + "</p>");
    writer.println("\t</body>");
    writer.println("</html>");
  }
  
}

