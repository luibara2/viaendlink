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
package net.raphimc.viabedrock.protocol.model;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * A single step of an {@code ItemStackRequest} — the server-authoritative way to move items.
 *
 * <p>Bedrock does not send "the player clicked slot 12". It sends what the client believes should
 * happen, as a list of typed steps, and the server either applies the list whole or rejects it
 * whole. That is the model a Java {@code ContainerClick} has to be expressed in, and it is why
 * {@code handleClick} produces a list rather than mutating anything: nothing is true until the
 * server's {@code ItemStackResponse} says it is.</p>
 *
 * <p>Only the actions a Java client can actually ask for are here. The rest of the enum is for
 * screens Bedrock has and Java does not (beacon payment, loom, lab table), or is deprecated.</p>
 */
public sealed interface ItemStackRequestAction {

    ItemStackRequestActionType type();

    void writeData(PacketWrapper wrapper, int requestId);

    /**
     * @param requestId the id this request is being sent under. Threaded through because a slot
     *                  naming a stack the request has yet to create refers to it by that id — see
     *                  {@link ItemStackRequestSlot#PRODUCED_BY_THIS_REQUEST}.
     */
    default void write(final PacketWrapper wrapper, final int requestId) {
        wrapper.write(Types.BYTE, (byte) this.type().getValue()); // action type
        this.writeData(wrapper, requestId);
    }

    /** Moves {@code count} items onto an empty-or-matching destination, leaving the rest behind. */
    record Take(int count, ItemStackRequestSlot source, ItemStackRequestSlot destination) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Take;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper, requestId);
            this.destination.write(wrapper, requestId);
        }
    }

    /**
     * The same wire shape as {@link Take} and the same effect; the two differ only in which end the
     * player is thought of as acting on. Bedrock sends TAKE when picking up into the cursor and
     * PLACE when putting the cursor down, and servers accept either, but sending the one the client
     * would have sent keeps anti-cheat and plugin listeners seeing what they expect.
     */
    record Place(int count, ItemStackRequestSlot source, ItemStackRequestSlot destination) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Place;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper, requestId);
            this.destination.write(wrapper, requestId);
        }
    }

    /** Exchanges two slots outright. Carries no count: it is always both stacks, whole. */
    record Swap(ItemStackRequestSlot source, ItemStackRequestSlot destination) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Swap;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            this.source.write(wrapper, requestId);
            this.destination.write(wrapper, requestId);
        }
    }

    /** Throws items into the world. {@code randomly} is the scatter a death drop uses; a player throw is not random. */
    record Drop(int count, ItemStackRequestSlot source, boolean randomly) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Drop;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper, requestId);
            wrapper.write(Types.BOOLEAN, this.randomly); // randomly
        }
    }

    /**
     * Uses items up as part of something else — the ingredients a craft takes out of the grid.
     *
     * <p>The same wire shape as {@link Destroy}, and a different meaning: the server expects one of
     * these per ingredient after a {@link CraftRecipe} and validates them against the recipe it was
     * given. A craft that omits them is not a craft that consumes nothing; it is a malformed one.</p>
     */
    record Consume(int count, ItemStackRequestSlot source) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Consume;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper, requestId);
        }
    }

    /** Deletes items outright — the creative-mode trash, not a world drop. */
    record Destroy(int count, ItemStackRequestSlot source) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Destroy;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper, requestId);
        }
    }

    /**
     * Conjures a stack from the creative menu. {@code creativeItemNetworkId} indexes the
     * {@code CreativeContent} the server sent, not the item registry.
     */
    record CraftCreative(int creativeItemNetworkId, int numberOfRequestedCrafts) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.CraftCreative;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, this.creativeItemNetworkId); // creative item network id
            wrapper.write(Types.BYTE, (byte) this.numberOfRequestedCrafts); // number of requested crafts
        }
    }

    /**
     * Names the recipe the client thinks it is crafting. Always paired with a {@link CraftResults}
     * and the {@link Take}/{@link Place} that moves the result out.
     */
    record CraftRecipe(int recipeNetworkId, int numberOfRequestedCrafts) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.CraftRecipe;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, this.recipeNetworkId); // recipe network id
            wrapper.write(Types.BYTE, (byte) this.numberOfRequestedCrafts); // number of requested crafts
        }
    }

    /**
     * The client's prediction of what a craft produces. Named "deprecated" in the protocol for
     * years and still required: the server checks its own recipe result against this one.
     *
     * <p>The results are {@code ItemInstance}s, not the {@code Item} shape the rest of this file's
     * slots carry — no stack network id field at all, since a stack that does not exist yet cannot
     * have one. Writing the wrong one of the two shifts every byte after it, and the server sees a
     * request that decodes into something else entirely.</p>
     */
    record CraftResults(BedrockItem[] resultItems, int timesCrafted) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.CraftResults_DEPRECATEDASKTYLAING;
        }

        @Override
        public void writeData(final PacketWrapper wrapper, final int requestId) {
            final Type<BedrockItem> itemType = wrapper.user().get(ItemRewriter.class).itemInstanceType();
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, this.resultItems.length); // result count
            for (BedrockItem resultItem : this.resultItems) {
                wrapper.write(itemType, resultItem); // result item
            }
            wrapper.write(Types.BYTE, (byte) this.timesCrafted); // times crafted
        }
    }

}
