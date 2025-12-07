/*
 * StreamPriorityTree.java
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
import java.util.logging.Logger;

/**
 * Manages HTTP/2 stream priority dependency tree for optimal resource allocation.
 * 
 * <p>This class implements RFC 7540 Section 5.3 Stream Priority:
 * <ul>
 * <li>Builds and maintains stream dependency trees</li>
 * <li>Calculates relative priorities based on weights and dependencies</li>
 * <li>Provides priority-ordered stream scheduling</li>
 * <li>Handles priority updates via PRIORITY frames</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe for concurrent
 * access by multiple HTTP/2 connection threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamPriorityTree {
    
    private static final Logger LOGGER = Logger.getLogger(StreamPriorityTree.class.getName());
    
    /**
     * Stream 0 is the root of the dependency tree (connection-level).
     * All streams without explicit dependencies depend on stream 0.
     */
    private static final int ROOT_STREAM_ID = 0;
    
    /**
     * Default weight for streams without explicit weight (RFC 7540 Section 5.3.2).
     */
    private static final int DEFAULT_WEIGHT = 16;
    
    /**
     * Comparator for sorting streams by calculated priority (highest first).
     */
    private class StreamPriorityComparator implements Comparator<Integer> {
        @Override
        public int compare(Integer a, Integer b) {
            PriorityNode nodeA = nodes.get(a);
            PriorityNode nodeB = nodes.get(b);
            if (nodeA == null && nodeB == null) {
                return 0;
            }
            if (nodeA == null) {
                return 1;
            }
            if (nodeB == null) {
                return -1;
            }
            return Double.compare(nodeB.calculatedPriority, nodeA.calculatedPriority);
        }
    }

    /**
     * Priority node representing a stream in the dependency tree.
     */
    public static class PriorityNode {
        final int streamId;
        int weight;
        int dependsOn;
        boolean exclusive;
        double calculatedPriority;  // Calculated based on tree position and weights
        
        // Tree structure
        final Set<Integer> children = new LinkedHashSet<>();
        
        PriorityNode(int streamId, int weight, int dependsOn, boolean exclusive) {
            this.streamId = streamId;
            this.weight = Math.max(1, Math.min(256, weight)); // RFC 7540: weight 1-256
            this.dependsOn = dependsOn;
            this.exclusive = exclusive;
            this.calculatedPriority = 0.0;
        }
        
        /**
         * Gets the effective priority for scheduling decisions.
         * Higher values indicate higher priority.
         * 
         * @return calculated priority value (0.0 to 1.0+)
         */
        public double getPriority() {
            return calculatedPriority;
        }
        
        /**
         * Gets the stream weight (1-256).
         */
        public int getWeight() {
            return weight;
        }
        
        /**
         * Gets the stream this stream depends on.
         */
        public int getDependsOn() {
            return dependsOn;
        }
        
        /**
         * Returns true if this is an exclusive dependency.
         */
        public boolean isExclusive() {
            return exclusive;
        }
        
        @Override
        public String toString() {
            return String.format("Stream %d (weight=%d, dependsOn=%d, exclusive=%s, priority=%.3f)", 
                streamId, weight, dependsOn, exclusive, calculatedPriority);
        }
    }
    
    // Thread-safe storage for priority nodes
    private final Map<Integer, PriorityNode> nodes = new ConcurrentHashMap<>();
    
    /**
     * Creates a new stream priority tree with root node.
     */
    public StreamPriorityTree() {
        // Create root node (stream 0)
        nodes.put(ROOT_STREAM_ID, new PriorityNode(ROOT_STREAM_ID, DEFAULT_WEIGHT, 0, false));
    }
    
    /**
     * Adds or updates a stream's priority information.
     * 
     * @param streamId the stream ID
     * @param weight the stream weight (1-256)
     * @param dependsOn the stream this stream depends on
     * @param exclusive whether this is an exclusive dependency
     */
    public synchronized void updateStreamPriority(int streamId, int weight, int dependsOn, boolean exclusive) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("Stream ID must be positive: " + streamId);
        }
        
        // Prevent circular dependencies
        if (wouldCreateCircularDependency(streamId, dependsOn)) {
            LOGGER.warning("Ignoring priority update that would create circular dependency: stream " + 
                streamId + " depends on " + dependsOn);
            return;
        }
        
        PriorityNode existingNode = nodes.get(streamId);
        if (existingNode != null) {
            // Update existing node
            updateExistingNode(existingNode, weight, dependsOn, exclusive);
        } else {
            // Create new node
            createNewNode(streamId, weight, dependsOn, exclusive);
        }
        
        // Recalculate priorities after tree structure change
        recalculatePriorities();
        
        LOGGER.fine("Updated stream priority: " + nodes.get(streamId));
    }
    
    /**
     * Adds a stream with default priority (depends on root, weight 16).
     */
    public void addStream(int streamId) {
        updateStreamPriority(streamId, DEFAULT_WEIGHT, ROOT_STREAM_ID, false);
    }
    
    /**
     * Removes a stream from the priority tree.
     * Children of the removed stream become children of its parent.
     */
    public synchronized void removeStream(int streamId) {
        if (streamId <= 0) {
            return; // Cannot remove root
        }
        
        PriorityNode node = nodes.get(streamId);
        if (node == null) {
            return; // Stream not in tree
        }
        
        // Reparent children to this node's parent
        PriorityNode parent = nodes.get(node.dependsOn);
        if (parent != null) {
            parent.children.remove(streamId);
            
            for (int childId : node.children) {
                PriorityNode child = nodes.get(childId);
                if (child != null) {
                    child.dependsOn = node.dependsOn;
                    parent.children.add(childId);
                }
            }
        }
        
        // Remove the node
        nodes.remove(streamId);
        
        // Recalculate priorities
        recalculatePriorities();
        
        LOGGER.fine("Removed stream " + streamId + " from priority tree");
    }
    
    /**
     * Gets streams ordered by priority (highest priority first).
     * Only includes streams that are present in the provided collection.
     * 
     * @param availableStreams streams available for processing
     * @return list of stream IDs ordered by priority
     */
    public List<Integer> getStreamsByPriority(Collection<Integer> availableStreams) {
        List<Integer> prioritizedStreams = new ArrayList<>();
        
        for (int streamId : availableStreams) {
            if (streamId > 0 && nodes.containsKey(streamId)) {
                prioritizedStreams.add(streamId);
            }
        }
        
        // Sort by calculated priority (highest first)
        Collections.sort(prioritizedStreams, new StreamPriorityComparator());
        
        return prioritizedStreams;
    }
    
    /**
     * Gets the priority information for a stream.
     * 
     * @param streamId the stream ID
     * @return priority node or null if stream not found
     */
    public PriorityNode getStreamPriority(int streamId) {
        return nodes.get(streamId);
    }
    
    /**
     * Returns a debug representation of the priority tree.
     */
    public synchronized String debugTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/2 Priority Tree:\n");
        buildDebugTree(ROOT_STREAM_ID, 0, sb, new HashSet<>());
        return sb.toString();
    }
    
    /**
     * Checks if adding a dependency would create a circular reference.
     */
    private boolean wouldCreateCircularDependency(int streamId, int dependsOn) {
        if (dependsOn == ROOT_STREAM_ID) {
            return false; // Root can never create circular dependency
        }
        
        // Follow the dependency chain upward
        Set<Integer> visited = new HashSet<>();
        int current = dependsOn;
        
        while (current != ROOT_STREAM_ID && visited.add(current)) {
            if (current == streamId) {
                return true; // Found circular dependency
            }
            
            PriorityNode node = nodes.get(current);
            if (node == null) {
                break; // Dependency chain broken
            }
            current = node.dependsOn;
        }
        
        return false;
    }
    
    /**
     * Updates an existing node's priority information.
     */
    private void updateExistingNode(PriorityNode node, int weight, int dependsOn, boolean exclusive) {
        // Remove from old parent's children
        PriorityNode oldParent = nodes.get(node.dependsOn);
        if (oldParent != null) {
            oldParent.children.remove(node.streamId);
        }
        
        // Update node properties
        node.weight = Math.max(1, Math.min(256, weight));
        node.dependsOn = dependsOn;
        node.exclusive = exclusive;
        
        // Add to new parent's children
        addToParent(node, dependsOn, exclusive);
    }
    
    /**
     * Creates a new priority node.
     */
    private void createNewNode(int streamId, int weight, int dependsOn, boolean exclusive) {
        PriorityNode node = new PriorityNode(streamId, weight, dependsOn, exclusive);
        nodes.put(streamId, node);
        
        // Add to parent's children
        addToParent(node, dependsOn, exclusive);
    }
    
    /**
     * Adds a node to its parent's children, handling exclusive dependencies.
     */
    private void addToParent(PriorityNode node, int dependsOn, boolean exclusive) {
        PriorityNode parent = nodes.get(dependsOn);
        if (parent == null) {
            // Parent doesn't exist, depend on root
            parent = nodes.get(ROOT_STREAM_ID);
            node.dependsOn = ROOT_STREAM_ID;
        }
        
        if (exclusive) {
            // Make all existing children of parent become children of this node
            Set<Integer> existingChildren = new HashSet<>(parent.children);
            parent.children.clear();
            
            for (int childId : existingChildren) {
                PriorityNode child = nodes.get(childId);
                if (child != null) {
                    child.dependsOn = node.streamId;
                    node.children.add(childId);
                }
            }
        }
        
        parent.children.add(node.streamId);
    }
    
    /**
     * Recalculates priority values for all nodes based on tree structure.
     * Uses RFC 7540 Section 5.3.2 algorithm.
     */
    private void recalculatePriorities() {
        // Reset all calculated priorities
        for (PriorityNode node : nodes.values()) {
            node.calculatedPriority = 0.0;
        }
        
        // Calculate priorities starting from root
        calculateNodePriority(ROOT_STREAM_ID, 1.0);
    }
    
    /**
     * Recursively calculates priority for a node and its children.
     */
    private void calculateNodePriority(int streamId, double parentPriority) {
        PriorityNode node = nodes.get(streamId);
        if (node == null) {
            return;
        }
        
        // Current node gets the parent priority
        node.calculatedPriority = parentPriority;
        
        if (node.children.isEmpty()) {
            return; // No children to process
        }
        
        // Calculate total weight of all children
        int totalWeight = 0;
        for (int childId : node.children) {
            PriorityNode child = nodes.get(childId);
            if (child != null) {
                totalWeight += child.weight;
            }
        }
        
        if (totalWeight == 0) {
            return; // No valid children
        }
        
        // Distribute priority among children based on their weights
        for (int childId : node.children) {
            PriorityNode child = nodes.get(childId);
            if (child != null) {
                double childPriority = parentPriority * ((double) child.weight / totalWeight);
                calculateNodePriority(childId, childPriority);
            }
        }
    }
    
    /**
     * Builds debug tree representation.
     */
    private void buildDebugTree(int streamId, int depth, StringBuilder sb, Set<Integer> visited) {
        if (visited.contains(streamId)) {
            return; // Prevent infinite recursion
        }
        visited.add(streamId);
        
        PriorityNode node = nodes.get(streamId);
        if (node == null) {
            return;
        }
        
        // Indent based on depth
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        
        if (streamId == ROOT_STREAM_ID) {
            sb.append("ROOT\n");
        } else {
            sb.append(node.toString()).append("\n");
        }
        
        // Process children
        for (int childId : node.children) {
            buildDebugTree(childId, depth + 1, sb, visited);
        }
    }
}
