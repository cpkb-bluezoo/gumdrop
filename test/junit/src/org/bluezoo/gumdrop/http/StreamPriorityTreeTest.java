package org.bluezoo.gumdrop.http;

import org.junit.Test;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StreamPriorityTree}.
 */
public class StreamPriorityTreeTest {

    private StreamPriorityTree tree;

    @Before
    public void setUp() {
        tree = new StreamPriorityTree();
    }

    @Test
    public void testInitialState() {
        assertNotNull(tree.getStreamPriority(0));
        assertNull(tree.getStreamPriority(1));
    }

    @Test
    public void testAddStream() {
        tree.addStream(1);

        StreamPriorityTree.PriorityNode node = tree.getStreamPriority(1);
        assertNotNull(node);
        assertEquals(16, node.getWeight());
        assertEquals(0, node.getDependsOn());
        assertFalse(node.isExclusive());
    }

    @Test
    public void testAddMultipleStreams() {
        tree.addStream(1);
        tree.addStream(3);
        tree.addStream(5);

        assertNotNull(tree.getStreamPriority(1));
        assertNotNull(tree.getStreamPriority(3));
        assertNotNull(tree.getStreamPriority(5));
    }

    @Test
    public void testUpdateStreamPriority() {
        tree.addStream(1);
        tree.updateStreamPriority(1, 128, 0, false);

        StreamPriorityTree.PriorityNode node = tree.getStreamPriority(1);
        assertEquals(128, node.getWeight());
    }

    @Test
    public void testWeightClamping() {
        tree.updateStreamPriority(1, 0, 0, false);
        assertEquals(1, tree.getStreamPriority(1).getWeight());

        tree.updateStreamPriority(2, 500, 0, false);
        assertEquals(256, tree.getStreamPriority(2).getWeight());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUpdateStreamPriorityInvalidId() {
        tree.updateStreamPriority(0, 16, 0, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUpdateStreamPriorityNegativeId() {
        tree.updateStreamPriority(-1, 16, 0, false);
    }

    @Test
    public void testRemoveStream() {
        tree.addStream(1);
        tree.removeStream(1);
        assertNull(tree.getStreamPriority(1));
    }

    @Test
    public void testRemoveNonExistentStream() {
        tree.removeStream(99);
    }

    @Test
    public void testRemoveRootIgnored() {
        tree.removeStream(0);
        assertNotNull(tree.getStreamPriority(0));
    }

    @Test
    public void testRemoveStreamReparentsChildren() {
        tree.addStream(1);
        tree.updateStreamPriority(3, 16, 1, false);
        tree.updateStreamPriority(5, 16, 1, false);

        tree.removeStream(1);

        assertNull(tree.getStreamPriority(1));
        StreamPriorityTree.PriorityNode node3 = tree.getStreamPriority(3);
        StreamPriorityTree.PriorityNode node5 = tree.getStreamPriority(5);
        assertNotNull(node3);
        assertNotNull(node5);
        assertEquals(0, node3.getDependsOn());
        assertEquals(0, node5.getDependsOn());
    }

    @Test
    public void testStreamDependency() {
        tree.addStream(1);
        tree.updateStreamPriority(3, 16, 1, false);

        StreamPriorityTree.PriorityNode node = tree.getStreamPriority(3);
        assertEquals(1, node.getDependsOn());
    }

    @Test
    public void testExclusiveDependency() {
        tree.addStream(1);
        tree.addStream(3);
        // Both 1 and 3 depend on root(0)

        // Add stream 5 with exclusive dependency on root
        // Should make 1 and 3 depend on 5 instead
        tree.updateStreamPriority(5, 16, 0, true);

        StreamPriorityTree.PriorityNode node1 = tree.getStreamPriority(1);
        StreamPriorityTree.PriorityNode node3 = tree.getStreamPriority(3);
        assertEquals(5, node1.getDependsOn());
        assertEquals(5, node3.getDependsOn());
    }

    @Test
    public void testGetStreamsByPriority() {
        tree.updateStreamPriority(1, 256, 0, false);
        tree.updateStreamPriority(3, 1, 0, false);

        List<Integer> ordered = tree.getStreamsByPriority(Arrays.asList(1, 3));
        assertEquals(2, ordered.size());
        assertEquals(Integer.valueOf(1), ordered.get(0));
        assertEquals(Integer.valueOf(3), ordered.get(1));
    }

    @Test
    public void testGetStreamsByPriorityEqualWeight() {
        tree.updateStreamPriority(1, 16, 0, false);
        tree.updateStreamPriority(3, 16, 0, false);

        List<Integer> ordered = tree.getStreamsByPriority(Arrays.asList(1, 3));
        assertEquals(2, ordered.size());
        // Both should be present, order may vary for equal weight
        assertTrue(ordered.contains(1));
        assertTrue(ordered.contains(3));
    }

    @Test
    public void testGetStreamsByPriorityFiltersUnavailable() {
        tree.addStream(1);
        tree.addStream(3);
        tree.addStream(5);

        List<Integer> ordered = tree.getStreamsByPriority(Arrays.asList(1, 5));
        assertEquals(2, ordered.size());
        assertFalse(ordered.contains(3));
    }

    @Test
    public void testGetStreamsByPriorityIgnoresUnknown() {
        tree.addStream(1);

        List<Integer> ordered = tree.getStreamsByPriority(Arrays.asList(1, 99));
        assertEquals(1, ordered.size());
        assertEquals(Integer.valueOf(1), ordered.get(0));
    }

    @Test
    public void testGetStreamsByPriorityEmpty() {
        List<Integer> ordered = tree.getStreamsByPriority(Collections.emptyList());
        assertTrue(ordered.isEmpty());
    }

    @Test
    public void testCircularDependencyPrevented() {
        tree.addStream(1);
        tree.updateStreamPriority(3, 16, 1, false);
        // Try to make 1 depend on 3 (would be circular: 1->3->1)
        tree.updateStreamPriority(1, 16, 3, false);

        // Should not create circular dependency
        // Stream 1 should still depend on root (original parent)
        StreamPriorityTree.PriorityNode node1 = tree.getStreamPriority(1);
        assertNotNull(node1);
        assertNotEquals(3, node1.getDependsOn());
    }

    @Test
    public void testPriorityCalculation() {
        tree.updateStreamPriority(1, 128, 0, false);
        tree.updateStreamPriority(3, 128, 0, false);

        StreamPriorityTree.PriorityNode node1 = tree.getStreamPriority(1);
        StreamPriorityTree.PriorityNode node3 = tree.getStreamPriority(3);

        // Equal weights should give equal priorities
        assertEquals(node1.getPriority(), node3.getPriority(), 0.001);
        assertTrue(node1.getPriority() > 0.0);
    }

    @Test
    public void testHigherWeightGetsHigherPriority() {
        tree.updateStreamPriority(1, 256, 0, false);
        tree.updateStreamPriority(3, 1, 0, false);

        StreamPriorityTree.PriorityNode node1 = tree.getStreamPriority(1);
        StreamPriorityTree.PriorityNode node3 = tree.getStreamPriority(3);

        assertTrue(node1.getPriority() > node3.getPriority());
    }

    @Test
    public void testDebugTree() {
        tree.addStream(1);
        tree.updateStreamPriority(3, 16, 1, false);

        String debug = tree.debugTree();
        assertNotNull(debug);
        assertTrue(debug.contains("ROOT"));
        assertTrue(debug.contains("Stream 1"));
        assertTrue(debug.contains("Stream 3"));
    }

    @Test
    public void testPriorityNodeToString() {
        tree.addStream(1);
        StreamPriorityTree.PriorityNode node = tree.getStreamPriority(1);
        String str = node.toString();
        assertTrue(str.contains("Stream 1"));
        assertTrue(str.contains("weight=16"));
    }

    @Test
    public void testDeepDependencyChain() {
        tree.addStream(1);
        tree.updateStreamPriority(3, 16, 1, false);
        tree.updateStreamPriority(5, 16, 3, false);
        tree.updateStreamPriority(7, 16, 5, false);

        StreamPriorityTree.PriorityNode node7 = tree.getStreamPriority(7);
        assertEquals(5, node7.getDependsOn());

        // Deep chain should still work for ordering
        List<Integer> ordered = tree.getStreamsByPriority(Arrays.asList(1, 3, 5, 7));
        assertEquals(4, ordered.size());
    }
}
