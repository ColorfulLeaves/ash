package com.leaves.ash.client.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Walkability queries against the client's own chunk cache.
 *
 * This is the single biggest reason to run the automation inside the game client rather
 * than in an external bot: the answers come from the real BlockState registry and the
 * real server-provided chunk data, so custom RPG blocks, unusual collision shapes and
 * non-cube blocks are handled exactly the way the server's own physics handles them.
 * A bot has to guess all of that from a block id, and guessing wrong is what gets it
 * flagged.
 */
public final class BlockNav {

    private BlockNav() {
    }

    /** Unloaded chunks are treated as walls so the search never walks off the known map. */
    public static boolean solid(Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) {
            return true;
        }
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** No collision, no fluid, nothing that hurts on contact. */
    public static boolean clear(Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return !harmful(state.getBlock());
    }

    /** Solid ground under the feet, and two clear blocks above it. */
    public static boolean canStand(Level level, BlockPos pos) {
        return solid(level, pos.below()) && clear(level, pos) && clear(level, pos.above());
    }

    public static boolean harmful(Block block) {
        return block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.CACTUS
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE
                || block == Blocks.END_PORTAL
                || block == Blocks.NETHER_PORTAL;
    }
}
