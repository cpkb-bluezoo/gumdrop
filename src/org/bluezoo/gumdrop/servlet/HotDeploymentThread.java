/*
 * HotDeploymentThread.java
 * Copyright (C) 2005 Chris Burdess
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

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

/**
 * This thread is used to monitor the state of various critical resources in
 * the web application (if the context uses file-based storage) or the WAR
 * file itself (if the context uses a WAR). When these resources are
 * updated, the hot-deployment thread redeploys the context.
 * <p>
 * The critical resources are defined as files under the WEB-INF directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HotDeploymentThread extends Thread {

    final Container container;
    Map contextStates;

    HotDeploymentThread(Container container) {
        super("hot-deploy");
        this.container = container;
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
        contextStates = new HashMap();
    }

    public void run() {
        // Build representation of context states
        for (Iterator i = container.contexts.iterator(); i.hasNext(); ) {
            process((Context) i.next(), false);
        }
        while (!isInterrupted()) {
            // Determine changes
            for (Iterator i = container.contexts.iterator(); i.hasNext(); ) {
                process((Context) i.next(), true);
            }

            // Wait
            try {
                synchronized (this) {
                    wait(1000);
                }
            } catch (InterruptedException e) {
                // Context.LOGGER.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    void process(Context context, boolean update) {
        Map state = (Map) contextStates.get(context);
        if (state == null) {
            state = new HashMap();
            contextStates.put(context, state);
        }
        if (context.warFile == null) {
            File webInf = new File(context.root, "WEB-INF");
            processFile(context, state, webInf, update);
        } else {
            // entire WAR file
            processFile(context, state, context.root, update);
        }
    }

    boolean processFile(Context context, Map state, File file, boolean update) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    if (processFile(context, state, files[i], update)) {
                        return true;
                    }
                }
            }
        } else if (file.exists()) {
            long lm = file.lastModified();
            Long llm = (Long) state.get(file);
            if (update && (llm == null || lm > llm.longValue())) {
                state.put(file, Long.valueOf(lm));
                return redeploy(context);
            } else {
                state.put(file, Long.valueOf(lm));
            }
        }
        return false;
    }

    boolean redeploy(Context context) {
        try {
            process(context, false);
            synchronized (context) {
                long t1 = System.currentTimeMillis();
                context.destroy();
                context.reload();
                context.init();
                long t2 = System.currentTimeMillis();
                Context.LOGGER.info(
                        "Redeployed context " + context.contextPath + " in " + (t2 - t1) + "ms");
            }
            return true;
        } catch (Exception e) {
            Context.LOGGER.log(Level.SEVERE, "Unable to redeploy context", e);
            return false;
        }
    }

}
