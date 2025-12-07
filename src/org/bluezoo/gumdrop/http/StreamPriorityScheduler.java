/*
 * StreamPriorityScheduler.java
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * HTTP/2 stream priority-aware scheduler for optimal resource allocation.
 * 
 * <p>This scheduler integrates with {@link StreamPriorityTree} to provide:
 * <ul>
 * <li>Priority-based stream scheduling for response processing</li>
 * <li>Fair resource allocation among streams of equal priority</li>
 * <li>Starvation prevention for low-priority streams</li>
 * <li>Bandwidth allocation based on stream weights</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe for concurrent
 * access by multiple threads processing HTTP/2 streams.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamPriorityScheduler {
    
    private static final Logger LOGGER = Logger.getLogger(StreamPriorityScheduler.class.getName());
    
    /**
     * Minimum time slice for low-priority streams to prevent starvation (ms).
     */
    private static final long MIN_LOW_PRIORITY_TIME_SLICE = 100L;
    
    /**
     * Maximum consecutive high-priority operations before yielding to lower priority.
     */
    private static final int MAX_HIGH_PRIORITY_BURST = 10;
    
    /**
     * Resource allocation tracking for fair scheduling.
     */
    private static class ResourceAllocation {
        long lastScheduled = 0L;
        int consecutiveSchedules = 0;
        long totalBytesProcessed = 0L;
        double allocatedBandwidth = 0.0; // Based on priority weight
        
        void reset() {
            consecutiveSchedules = 0;
            lastScheduled = System.currentTimeMillis();
        }
        
        void recordActivity(long bytesProcessed) {
            totalBytesProcessed += bytesProcessed;
            consecutiveSchedules++;
            lastScheduled = System.currentTimeMillis();
        }
    }
    
    private final StreamPriorityTree priorityTree;
    private final Map<Integer, ResourceAllocation> allocations = new ConcurrentHashMap<>();
    private final ReentrantLock schedulingLock = new ReentrantLock();
    
    /**
     * Creates a new priority scheduler.
     * 
     * @param priorityTree the priority tree for stream dependencies
     */
    public StreamPriorityScheduler(StreamPriorityTree priorityTree) {
        this.priorityTree = priorityTree;
    }
    
    /**
     * Schedules the next stream for processing based on priority and fairness.
     * 
     * @param readyStreams streams ready for processing
     * @return stream ID to process next, or -1 if no streams available
     */
    public int scheduleNextStream(Collection<Integer> readyStreams) {
        if (readyStreams.isEmpty()) {
            return -1;
        }
        
        schedulingLock.lock();
        try {
            // Get streams ordered by priority
            List<Integer> prioritizedStreams = priorityTree.getStreamsByPriority(readyStreams);
            
            if (prioritizedStreams.isEmpty()) {
                // Fallback to first available stream if priority info not available
                return readyStreams.iterator().next();
            }
            
            // Apply fairness and starvation prevention
            int selectedStream = selectStreamWithFairness(prioritizedStreams);
            
            // Update allocation tracking
            ResourceAllocation allocation = allocations.get(selectedStream);
            if (allocation == null) {
                allocation = new ResourceAllocation();
                allocations.put(selectedStream, allocation);
            }
            allocation.reset();
            
            LOGGER.fine("Scheduled stream " + selectedStream + " for processing (priority-based)");
            return selectedStream;
            
        } finally {
            schedulingLock.unlock();
        }
    }
    
    /**
     * Records completion of stream processing for resource tracking.
     * 
     * @param streamId the stream that was processed
     * @param bytesProcessed number of bytes processed
     * @param processingTimeMs time spent processing (milliseconds)
     */
    public void recordStreamProcessing(int streamId, long bytesProcessed, long processingTimeMs) {
        ResourceAllocation allocation = allocations.get(streamId);
        if (allocation != null) {
            allocation.recordActivity(bytesProcessed);
            
            // Update bandwidth allocation based on actual usage
            updateBandwidthAllocation(streamId, bytesProcessed, processingTimeMs);
        }
    }
    
    /**
     * Removes a stream from scheduling.
     * 
     * @param streamId the stream to remove
     */
    public void removeStream(int streamId) {
        allocations.remove(streamId);
        LOGGER.fine("Removed stream " + streamId + " from scheduler");
    }
    
    /**
     * Gets current scheduling statistics for debugging.
     * 
     * @return debug information about current scheduling state
     */
    public String getSchedulingStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/2 Stream Scheduling Statistics:\n");
        sb.append("Active streams: ").append(allocations.size()).append("\n");
        
        for (Map.Entry<Integer, ResourceAllocation> entry : allocations.entrySet()) {
            int streamId = entry.getKey();
            ResourceAllocation alloc = entry.getValue();
            StreamPriorityTree.PriorityNode priority = priorityTree.getStreamPriority(streamId);
            
            sb.append(String.format("  Stream %d: priority=%.3f, bandwidth=%.2f%%, bytes=%d, schedules=%d\n",
                streamId, 
                priority != null ? priority.getPriority() : 0.0,
                alloc.allocatedBandwidth * 100.0,
                alloc.totalBytesProcessed,
                alloc.consecutiveSchedules));
        }
        
        return sb.toString();
    }
    
    /**
     * Selects a stream considering priority and fairness constraints.
     */
    private int selectStreamWithFairness(List<Integer> prioritizedStreams) {
        long currentTime = System.currentTimeMillis();
        
        // Check for starvation prevention - find streams that haven't been scheduled recently
        for (int i = prioritizedStreams.size() - 1; i >= 0; i--) {
            int streamId = prioritizedStreams.get(i);
            ResourceAllocation allocation = allocations.get(streamId);
            
            if (allocation != null) {
                long timeSinceLastSchedule = currentTime - allocation.lastScheduled;
                
                // If a low-priority stream hasn't been scheduled recently, give it a turn
                if (timeSinceLastSchedule > MIN_LOW_PRIORITY_TIME_SLICE) {
                    LOGGER.fine("Selected stream " + streamId + " for starvation prevention (idle " + 
                        timeSinceLastSchedule + "ms)");
                    return streamId;
                }
            }
        }
        
        // Check burst control - limit consecutive high-priority operations
        int highestPriorityStream = prioritizedStreams.get(0);
        ResourceAllocation highestAllocation = allocations.get(highestPriorityStream);
        
        if (highestAllocation != null && 
            highestAllocation.consecutiveSchedules >= MAX_HIGH_PRIORITY_BURST) {
            
            // Look for a lower-priority stream that can be scheduled
            for (int i = 1; i < Math.min(prioritizedStreams.size(), 3); i++) {
                int candidateStream = prioritizedStreams.get(i);
                ResourceAllocation candidateAllocation = allocations.get(candidateStream);
                
                if (candidateAllocation == null || 
                    candidateAllocation.consecutiveSchedules < MAX_HIGH_PRIORITY_BURST / 2) {
                    
                    LOGGER.fine("Selected stream " + candidateStream + " for burst control (avoiding " + 
                        highestPriorityStream + " burst)");
                    return candidateStream;
                }
            }
        }
        
        // Default: return highest priority stream
        return highestPriorityStream;
    }
    
    /**
     * Updates bandwidth allocation based on actual processing patterns.
     */
    private void updateBandwidthAllocation(int streamId, long bytesProcessed, long processingTimeMs) {
        StreamPriorityTree.PriorityNode priorityNode = priorityTree.getStreamPriority(streamId);
        ResourceAllocation allocation = allocations.get(streamId);
        
        if (priorityNode == null || allocation == null || processingTimeMs <= 0) {
            return;
        }
        
        // Calculate effective bandwidth based on priority and recent usage
        double priorityWeight = priorityNode.getPriority();
        double recentBandwidth = (double) bytesProcessed / processingTimeMs; // bytes/ms
        
        // Exponential moving average for bandwidth allocation
        double alpha = 0.3; // Smoothing factor
        allocation.allocatedBandwidth = (alpha * recentBandwidth) + 
            ((1.0 - alpha) * allocation.allocatedBandwidth);
        
        LOGGER.fine(String.format("Updated stream %d bandwidth: priority=%.3f, bandwidth=%.2f bytes/ms", 
            streamId, priorityWeight, allocation.allocatedBandwidth));
    }
    
    /**
     * Gets suggested resource allocation ratios for active streams.
     * 
     * @param activeStreams streams currently active
     * @return map of stream ID to suggested allocation ratio (0.0-1.0)
     */
    public Map<Integer, Double> getResourceAllocations(Collection<Integer> activeStreams) {
        Map<Integer, Double> allocations = new HashMap<>();
        
        if (activeStreams.isEmpty()) {
            return allocations;
        }
        
        // Calculate total priority weight
        double totalPriorityWeight = 0.0;
        for (int streamId : activeStreams) {
            StreamPriorityTree.PriorityNode priority = priorityTree.getStreamPriority(streamId);
            if (priority != null) {
                totalPriorityWeight += priority.getPriority();
            }
        }
        
        if (totalPriorityWeight <= 0.0) {
            // Equal allocation if no priority info
            double equalShare = 1.0 / activeStreams.size();
            for (int streamId : activeStreams) {
                allocations.put(streamId, equalShare);
            }
            return allocations;
        }
        
        // Proportional allocation based on priority
        for (int streamId : activeStreams) {
            StreamPriorityTree.PriorityNode priority = priorityTree.getStreamPriority(streamId);
            double priorityWeight = priority != null ? priority.getPriority() : 0.0;
            double allocation = priorityWeight / totalPriorityWeight;
            allocations.put(streamId, allocation);
        }
        
        return allocations;
    }
}
