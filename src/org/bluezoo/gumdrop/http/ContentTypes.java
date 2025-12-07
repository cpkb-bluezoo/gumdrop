/*
 * ContentTypes.java
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

package org.bluezoo.gumdrop.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Fallback to discover reasonable Content-Type for a resource.
 * We could extend this to do MIME sniffing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentTypes {

    private static final Map<String,String> COMMON = new HashMap<>();
    static {
        COMMON.put("aac", "audio/aac");
        COMMON.put("abw", "application/x-abiword");
        COMMON.put("apng", "image/apng");
        COMMON.put("arc", "application/x-freearc");
        COMMON.put("avif", "image/avif");
        COMMON.put("avi", "video/x-msvideo");
        COMMON.put("azw", "application/vnd.amazon.ebook");
        COMMON.put("bin", "application/octet-stream");
        COMMON.put("bmp", "image/bmp");
        COMMON.put("bz", "application/x-bzip");
        COMMON.put("bz2", "application/x-bzip2");
        COMMON.put("cda", "application/x-cdf");
        COMMON.put("csh", "application/x-csh");
        COMMON.put("css", "text/css");
        COMMON.put("csv", "text/csv");
        COMMON.put("doc", "application/msword");
        COMMON.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        COMMON.put("eot", "application/vns.ms-fontobject");
        COMMON.put("epub", "application/epub+zip");
        COMMON.put("gz", "application/gzip");
        COMMON.put("gif", "image/gif");
        COMMON.put("htm", "text/html");
        COMMON.put("html", "text/html");
        COMMON.put("ico", "image/vnd.microsoft.icon");
        COMMON.put("ics", "text/calendar");
        COMMON.put("jar", "application/java-archive");
        COMMON.put("jpeg", "image/jpeg");
        COMMON.put("jpg", "image/jpeg");
        COMMON.put("js", "text/javascript");
        COMMON.put("json", "application/json");
        COMMON.put("jsonld", "application/ld+json");
        COMMON.put("md", "text/markdown");
        COMMON.put("mid", "audio/midi");
        COMMON.put("midi", "audio/midi");
        COMMON.put("mjs", "text/javascript");
        COMMON.put("mp3", "audio/mpeg");
        COMMON.put("mp4", "video/mp4");
        COMMON.put("mpeg", "video/mpeg");
        COMMON.put("mpkg", "application/vnd.apple.installer+xml");
        COMMON.put("odp", "application/vnd.oasis.opendocument.presentation");
        COMMON.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        COMMON.put("odt", "application/vnd.oasis.opendocument.text");
        COMMON.put("oga", "audio/ogg");
        COMMON.put("ogv", "video/ogg");
        COMMON.put("ogx", "application/ogg");
        COMMON.put("opus", "audio/ogg");
        COMMON.put("otf", "font/otf");
        COMMON.put("png", "image/png");
        COMMON.put("pdf", "application/pdf");
        COMMON.put("php", "application/x-httpd-php");
        COMMON.put("ppt", "application/vnd.ms-powerpoint");
        COMMON.put("pptx", "application/vnd.openformats-officedocument.presentationml.presentation");
        COMMON.put("rar", "application/vnd.rar");
        COMMON.put("rtf", "application/rtf");
        COMMON.put("sh", "application/x-sh");
        COMMON.put("svg", "image/svg+xml");
        COMMON.put("tar", "application/x-tar");
        COMMON.put("tif", "image/tiff");
        COMMON.put("tiff", "image/tiff");
        COMMON.put("ts", "video/mp2t");
        COMMON.put("ttf", "font/ttf");
        COMMON.put("txt", "text/plain");
        COMMON.put("vsd", "application/vnd.visio");
        COMMON.put("wav", "audio/wav");
        COMMON.put("weba", "audio/webm");
        COMMON.put("webm", "video/webm");
        COMMON.put("webmanifest", "application/manifest+json");
        COMMON.put("webp", "image/webp");
        COMMON.put("woff", "font/woff");
        COMMON.put("woff2", "font/woff2");
        COMMON.put("xhtml", "application/xhtml+xml");
        COMMON.put("xls", "application/vnd.ms-excel");
        COMMON.put("xlsx", "application/vnd.openformats-officedocument.spreadsheetml.sheet");
        COMMON.put("xml", "application/xml");
        COMMON.put("xul", "application/vnd.mozilla.xul+xml");
        COMMON.put("zip", "application/zip");
        COMMON.put("3gp", "video/3gp");
        COMMON.put("3g2", "video/3gpp2");
        COMMON.put("7z", "application/x-7z-compressed");
    }

    /**
     * Returns the Content-Type for the given extension.
     */
    public static String getContentType(String extension) {
        return COMMON.get(extension);
    }

}
