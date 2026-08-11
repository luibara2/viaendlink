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

    void writeData(PacketWrapper wrapper);

    default void write(final PacketWrapper wrapper) {
        wrapper.write(Types.BYTE, (byte) this.type().getValue()); // action type
        this.writeData(wrapper);
    }

    /** Moves {@code count} items onto an empty-or-matching destination, leaving the rest behind. */
    record Take(int count, ItemStackRequestSlot source, ItemStackRequestSlot destination) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Take;
        }

        @Override
        public void writeData(final PacketWrapper wrapper) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper);
            this.destination.write(wrapper);
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
        public void writeData(final PacketWrapper wrapper) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper);
            this.destination.write(wrapper);
        }
    }

    /** Exchanges two slots outright. Carries no count: it is always both stacks, whole. */
    record Swap(ItemStackRequestSlot source, ItemStackRequestSlot destination) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Swap;
        }

        @Override
        public void writeData(final PacketWrapper wrapper) {
            this.source.write(wrapper);
            this.destination.write(wrapper);
        }
    }

    /** Throws items into the world. {@code randomly} is the scatter a death drop uses; a player throw is not random. */
    record Drop(int count, ItemStackRequestSlot source, boolean randomly) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Drop;
        }

        @Override
        public void writeData(final PacketWrapper wrapper) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper);
            wrapper.write(Types.BOOLEAN, this.randomly); // randomly
        }
    }

    /** Deletes items outright — the creative-mode trash, not a world drop. */
    record Destroy(int count, ItemStackRequestSlot source) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Destroy;
        }

        @Override
        public void writeData(final PacketWrapper wrapper) {
            wrapper.write(Types.BYTE, (byte) this.count); // count
            this.source.write(wrapper);
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
        public void writeData(final PacketWrapper wrapper) {
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
        public void writeData(final PacketWrapper wrapper) {
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, this.recipeNetworkId); // recipe network id
            wrapper.write(Types.BYTE, (byte) this.numberOfRequestedCrafts); // number of requested crafts
        }
    }

    /**
     * The client's prediction of what a craft produces. Named "deprecated" in the protocol for
     * years and still required: the server checks its own recipe result against this one.
     */
    record CraftResults(BedrockItem[] resultItems, int timesCrafted) implements ItemStackRequestAction {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.CraftResults_DEPRECATEDASKTYLAING;
        }

        @Override
        public void writeData(final PacketWrapper wrapper) {
            final Type<BedrockItem> itemType = wrapper.user().get(ItemRewriter.class).newItemType();
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, this.resultItems.length); // result count
            for (BedrockItem resultItem : this.resultItems) {
                wrapper.write(itemType, resultItem); // result item
            }
            wrapper.write(Types.BYTE, (byte) this.timesCrafted); // times crafted
        }
    }

}
