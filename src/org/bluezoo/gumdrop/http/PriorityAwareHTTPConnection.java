/*
 * PriorityAwareHTTPConnection.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * HTTP connection with HTTP/2 stream priority optimization.
 * 
 * <p>This enhanced HTTP connection extends {@link HTTPConnection} with
 * sophisticated stream priority processing:
 * <ul>
 * <li>Maintains RFC 7540 compliant priority dependency trees</li>
 * <li>Schedules stream processing based on priority and fairness</li>
 * <li>Allocates bandwidth proportionally to stream weights</li>
 * <li>Prevents starvation of low-priority streams</li>
 * <li>Tracks resource usage for optimization</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PriorityAwareHTTPConnection extends HTTPConnection {
    
    private static final Logger LOGGER = Logger.getLogger(PriorityAwareHTTPConnection.class.getName());
    
    // HTTP/2 stream priority management
    private final StreamPriorityTree priorityTree = new StreamPriorityTree();
    private final StreamPriorityScheduler priorityScheduler = new StreamPriorityScheduler(priorityTree);
    
    // Priority behaviour configuration
    private boolean priorityLogging = false;
    
    /**
     * Creates a new priority-aware HTTP connection.
     */
    protected PriorityAwareHTTPConnection(SocketChannel channel, SSLEngine engine, boolean secure, int framePadding) {
        super(channel, engine, secure, framePadding);
    }
    
    /**
     * Configures priority behaviour parameters.
     * 
     * @param minLowPriorityTimeSlice minimum time slice for low-priority streams
     * @param maxHighPriorityBurst maximum consecutive high-priority operations
     * @param enableLogging whether to enable detailed priority logging
     */
    void configurePriorityBehavior(long minLowPriorityTimeSlice, int maxHighPriorityBurst, boolean enableLogging) {
        this.priorityLogging = enableLogging;
        
        if (priorityLogging) {
            LOGGER.info("Priority-aware HTTP connection configured: minTimeSlice=" + 
                minLowPriorityTimeSlice + "ms, maxBurst=" + maxHighPriorityBurst);
        }
    }
    
    @Override
    public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
            int streamDependency, boolean exclusive, int weight,
            ByteBuffer headerBlockFragment) {
        // Handle priority information from HEADERS frame
        if (streamDependency > 0) {
            // Priority info is present
            priorityTree.updateStreamPriority(streamId, weight, streamDependency, exclusive);
            
            if (priorityLogging) {
                LOGGER.fine("Updated stream priority from HEADERS: " + priorityTree.getStreamPriority(streamId));
            }
        } else {
            // Add stream with default priority if not already present
            if (priorityTree.getStreamPriority(streamId) == null) {
                priorityTree.addStream(streamId);
                
                if (priorityLogging) {
                    LOGGER.fine("Added stream with default priority: " + streamId);
                }
            }
        }
        
        // Call parent implementation
        super.headersFrameReceived(streamId, endStream, endHeaders, 
            streamDependency, exclusive, weight, headerBlockFragment);
    }

    @Override
    public void priorityFrameReceived(int streamId, int streamDependency,
            boolean exclusive, int weight) {
        // Handle PRIORITY frame
        priorityTree.updateStreamPriority(streamId, weight, streamDependency, exclusive);
        
        if (priorityLogging) {
            LOGGER.fine("Updated stream priority from PRIORITY frame: " + priorityTree.getStreamPriority(streamId));
        }
        
        // Call parent implementation
        super.priorityFrameReceived(streamId, streamDependency, exclusive, weight);
    }
    
    /**
     * Gets priority-ordered streams for processing.
     * This method integrates with the connection's stream processing
     * to provide priority-based scheduling.
     * 
     * @return streams ordered by priority (highest first)
     */
    protected List<Integer> getPriorityOrderedStreams() {
        List<Integer> activeStreamIds;
        
        synchronized (activeStreams) {
            activeStreamIds = new ArrayList<>(activeStreams);
        }
        
        List<Integer> prioritizedStreams = priorityTree.getStreamsByPriority(activeStreamIds);
        
        if (priorityLogging && !prioritizedStreams.isEmpty()) {
            LOGGER.fine("Priority order for " + prioritizedStreams.size() + " streams: " + prioritizedStreams);
        }
        
        return prioritizedStreams;
    }
    
    /**
     * Schedules the next stream for processing based on priority and fairness.
     * 
     * @return stream ID to process next, or -1 if no streams ready
     */
    protected int scheduleNextStream() {
        List<Integer> readyStreams;
        
        synchronized (activeStreams) {
            readyStreams = new ArrayList<>(activeStreams);
        }
        
        int selectedStream = priorityScheduler.scheduleNextStream(readyStreams);
        
        if (priorityLogging && selectedStream > 0) {
            StreamPriorityTree.PriorityNode priority = priorityTree.getStreamPriority(selectedStream);
            LOGGER.fine("Scheduled stream " + selectedStream + " (priority=" + 
                (priority != null ? String.format("%.3f", priority.getPriority()) : "unknown") + ")");
        }
        
        return selectedStream;
    }
    
    /**
     * Records stream processing completion for scheduler optimization.
     * 
     * @param streamId the processed stream
     * @param bytesProcessed number of bytes processed
     * @param processingTimeMs processing time in milliseconds
     */
    protected void recordStreamProcessing(int streamId, long bytesProcessed, long processingTimeMs) {
        priorityScheduler.recordStreamProcessing(streamId, bytesProcessed, processingTimeMs);
        
        if (priorityLogging) {
            LOGGER.fine("Recorded stream " + streamId + " processing: " + bytesProcessed + 
                " bytes in " + processingTimeMs + "ms");
        }
    }
    
    /**
     * Removes a stream from priority management when it closes.
     */
    @Override
    protected Stream removeStream(int streamId) {
        Stream stream = super.removeStream(streamId);
        
        if (stream != null) {
            // Clean up priority tracking
            priorityTree.removeStream(streamId);
            priorityScheduler.removeStream(streamId);
            
            if (priorityLogging) {
                LOGGER.fine("Removed stream " + streamId + " from priority management");
            }
        }
        
        return stream;
    }
    
    /**
     * Gets current priority tree debug information.
     * 
     * @return debug representation of the priority tree
     */
    public String debugPriorityTree() {
        return priorityTree.debugTree();
    }
    
    /**
     * Gets current scheduling statistics.
     * 
     * @return scheduling statistics for debugging
     */
    public String getSchedulingStats() {
        return priorityScheduler.getSchedulingStats();
    }
    
    @Override
    protected void cleanupAllStreams() {
        // Clean up priority management
        List<Integer> streamIds;
        synchronized (streams) {
            streamIds = new ArrayList<>(streams.keySet());
        }
        
        for (int streamId : streamIds) {
            priorityTree.removeStream(streamId);
            priorityScheduler.removeStream(streamId);
        }
        
        if (priorityLogging) {
            LOGGER.fine("Cleaned up priority management for " + streamIds.size() + " streams");
        }
        
        // Perform base cleanup
        super.cleanupAllStreams();
    }
    
    @Override
    protected void disconnected() {
        if (priorityLogging) {
            LOGGER.info("Priority-aware HTTP connection disconnected. Final stats:\n" + 
                getSchedulingStats());
        }
        
        try {
            super.disconnected();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error during connection cleanup", e);
        }
    }
}
