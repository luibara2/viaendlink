/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter.blockentity;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * A chest, and which half of a double chest it is.
 *
 * <p>The two editions disagree about where that fact lives. Java puts it in the block state — a
 * chest is {@code single}, {@code left} or {@code right}, and the renderer draws the joined model
 * from that alone. Bedrock's block state says nothing about it; the pairing is a {@code pairx}/
 * {@code pairz} pointer on the block entity, and the client works the rest out for itself.</p>
 *
 * <p>So a translated double chest arrived as two independent {@code single} chests: two separate
 * lids side by side, even though opening either one showed all 54 slots, because the size comes
 * from that same pointer and was already being read. This turns the pointer into the property Java
 * actually renders from.</p>
 */
public class ChestBlockEntityRewriter extends LootableContainerBlockEntityRewriter {

    @Override
    public BlockEntity toJava(final UserConnection user, final BedrockBlockEntity bedrockBlockEntity) {
        final BlockEntity javaBlockEntity = super.toJava(user, bedrockBlockEntity);
        final CompoundTag bedrockTag = bedrockBlockEntity.tag();
        if (bedrockTag.getNumberTag("pairx") == null || bedrockTag.getNumberTag("pairz") == null) {
            return javaBlockEntity; // A single chest, which Java's default block state already says
        }

        final int javaBlockStateId = user.get(ChunkTracker.class).getJavaBlockState(bedrockBlockEntity.position());
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        if (javaBlockState == null) {
            return javaBlockEntity;
        }
        final String half = pairedHalf(javaBlockState.properties().get("facing"),
                bedrockTag.getNumberTag("pairx").asInt() - bedrockBlockEntity.position().x(),
                bedrockTag.getNumberTag("pairz").asInt() - bedrockBlockEntity.position().z());
        if (half == null) {
            return javaBlockEntity; // Not a neighbour this facing can pair with; leave it single
        }

        final Integer pairedBlockStateId = BedrockProtocol.MAPPINGS.getJavaBlockStates()
                .get(javaBlockState.replaceProperty("type", half));
        if (pairedBlockStateId == null) {
            return javaBlockEntity;
        }
        return new BlockEntityWithBlockState(javaBlockEntity, pairedBlockStateId);
    }

    /**
     * Which half a chest is, given where its partner sits relative to it.
     *
     * <p>Java defines it the other way round — {@code ChestBlock.getConnectedDirection} puts a
     * {@code left} chest's partner clockwise of the way it faces, and a {@code right} chest's
     * counter-clockwise — so this inverts that. Both halves face the same way, so each one reaches
     * the opposite answer from the same rule and the pair agrees.</p>
     *
     * @return {@code "left"}, {@code "right"}, or null if the offset is not an adjacent block to
     *         either side, which is not a pairing Java can express
     */
    private static String pairedHalf(final String facing, final int dx, final int dz) {
        if (facing == null) {
            return null;
        }
        final int clockwiseX;
        final int clockwiseZ;
        switch (facing) {
            case "north" -> { clockwiseX = 1; clockwiseZ = 0; }  // east
            case "east" -> { clockwiseX = 0; clockwiseZ = 1; }   // south
            case "south" -> { clockwiseX = -1; clockwiseZ = 0; } // west
            case "west" -> { clockwiseX = 0; clockwiseZ = -1; }  // north
            default -> {
                return null;
            }
        }
        if (dx == clockwiseX && dz == clockwiseZ) {
            return "left";
        }
        if (dx == -clockwiseX && dz == -clockwiseZ) {
            return "right";
        }
        return null;
    }

}
