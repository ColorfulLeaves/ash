package com.leaves.ash.client.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over standing positions, eight directions horizontally, with step-up, drop-down and
 * corner checks that mirror vanilla movement:
 *   - step up one block (vanilla step height is 0.6, but a jump clears a full block)
 *   - drop up to three blocks (fall damage starts at four)
 *   - diagonals are only allowed when both orthogonal neighbours are open, so the path
 *     never clips a corner
 *
 * Expansion is capped. When the goal cannot be reached inside the budget the result is
 * marked incomplete; the caller decides what to do about that, because walking a partial
 * path into a dead end is worse than stopping.
 */
public final class PathFinder {

    /** A path plus the one bit the caller actually needs: did the search reach the goal? */
    public static final class PathResult {
        private final List<BlockPos> nodes;
        private final boolean complete;

        PathResult(List<BlockPos> nodes, boolean complete) {
            this.nodes = nodes;
            this.complete = complete;
        }

        public List<BlockPos> nodes() {
            return nodes;
        }

        public boolean isEmpty() {
            return nodes.isEmpty();
        }

        /** true only when the search actually expanded the goal block */
        public boolean isComplete() {
            return complete && !nodes.isEmpty();
        }

        /**
         * The last node reached when the search gave up. Distance-coded usefulness: if
         * this is the start node the search never even left the player's feet.
         */
        public BlockPos tail() {
            return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
        }
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int[] DROP = {0, 1, -1, -2, -3};

    private PathFinder() {
    }

    public static PathResult find(Level level, BlockPos start, BlockPos goal, int maxNodes, boolean diagonal) {
        if (level == null || start == null || goal == null) {
            return new PathResult(Collections.emptyList(), false);
        }
        if (start.equals(goal)) {
            // already there: empty but complete, so the caller does not read it as a failure
            return new PathResult(Collections.emptyList(), true);
        }

        Map<BlockPos, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        Node root = new Node(start, null, 0.0D, heuristic(start, goal));
        nodes.put(start, root);
        open.add(root);

        Node best = root;
        boolean reachedGoal = false;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            expanded++;

            if (current.h < best.h) {
                best = current;
            }
            if (current.pos.equals(goal)) {
                best = current;
                reachedGoal = true;
                break;
            }

            for (int i = 0; i < DX.length; i++) {
                if (i >= 4 && !diagonal) {
                    break;
                }
                int dx = DX[i];
                int dz = DZ[i];
                if (dx != 0 && dz != 0) {
                    // no corner cutting
                    if (!BlockNav.clear(level, current.pos.offset(dx, 0, 0))
                            || !BlockNav.clear(level, current.pos.offset(0, 0, dz))) {
                        continue;
                    }
                }
                boolean landed = false;
                for (int dy : DROP) {
                    BlockPos next = current.pos.offset(dx, dy, dz);
                    if (next.equals(current.pos)) {
                        continue;
                    }
                    if (!BlockNav.canStand(level, next)) {
                        continue;
                    }
                    if (dy > 0 && !BlockNav.clear(level, current.pos.above())) {
                        continue;
                    }
                    double stepCost = (dx != 0 && dz != 0 ? 1.41421356D : 1.0D)
                            + (dy > 0 ? 0.8D : 0.0D)
                            + (dy < 0 ? 0.2D : 0.0D);
                    double g = current.g + stepCost;
                    Node existing = nodes.get(next);
                    if (existing == null) {
                        Node node = new Node(next, current, g, heuristic(next, goal));
                        nodes.put(next, node);
                        open.add(node);
                    } else if (g < existing.g - 0.0001D) {
                        existing.g = g;
                        existing.f = g + existing.h;
                        existing.parent = current;
                        existing.closed = false;
                        open.add(existing);
                    }
                    landed = true;
                    break;
                }
                // unreachable neighbour, nothing to do
                if (!landed) {
                    continue;
                }
            }
        }

        return new PathResult(rebuild(best), reachedGoal);
    }

    private static List<BlockPos> rebuild(Node end) {
        List<BlockPos> path = new ArrayList<>();
        Node cursor = end;
        while (cursor != null && cursor.parent != null) {
            path.add(cursor.pos);
            cursor = cursor.parent;
        }
        Collections.reverse(path);
        return path;
    }

    /** Octile distance plus a small vertical term. */
    private static double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dz = Math.abs(from.getZ() - to.getZ());
        double low = Math.min(dx, dz);
        double high = Math.max(dx, dz);
        return (high - low) + 1.41421356D * low + Math.abs(from.getY() - to.getY()) * 0.5D;
    }

    private static final class Node {
        final BlockPos pos;
        Node parent;
        double g;
        final double h;
        double f;
        boolean closed;

        Node(BlockPos pos, Node parent, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }
}
