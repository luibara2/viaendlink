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

import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.Set;

/**
 * One cell of a crafting recipe: what may sit there, and how much of it a craft consumes.
 *
 * <p>Bedrock describes an ingredient as a <em>descriptor</em> rather than an item, because the same
 * recipe cell can accept a whole family — "any plank", "any coal". The families are the reason this
 * cannot be a plain item comparison, and the reason a recipe list has to be matched rather than
 * looked up.</p>
 *
 * <p>Descriptors this cannot evaluate become {@link Unsupported} and never match. That is
 * deliberate: a recipe with one unevaluable cell would otherwise match on its remaining cells and
 * hand the player a result the server will refuse to produce. Not offering the craft is the honest
 * outcome, and {@code Unsupported#description} is what says which recipes were passed over.</p>
 */
public sealed interface RecipeIngredient {

    /** Bedrock's "any damage value", which is how a descriptor says the meta does not matter. */
    int ANY_AUX_VALUE = 0x7FFF;

    /** How many items one craft takes out of this cell. Zero for a cell that must be empty. */
    int count();

    /** Whether this cell has to be empty for the recipe to apply. */
    default boolean isEmpty() {
        return false;
    }

    boolean matches(BedrockItem item, ItemRewriter itemRewriter);

    /** A cell the recipe does not use, which the grid must therefore leave empty. */
    record Empty() implements RecipeIngredient {
        @Override
        public int count() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public boolean matches(final BedrockItem item, final ItemRewriter itemRewriter) {
            return item == null || item.isEmpty();
        }
    }

    /**
     * One specific item, by runtime id.
     *
     * @param auxValue the meta the item must carry, or {@link #ANY_AUX_VALUE} for any. Block items
     *                 have their meta folded into the block state on the way in and always read as
     *                 0, which is why only meta items are compared on it.
     */
    record OfItem(int itemId, int auxValue, int count) implements RecipeIngredient {
        @Override
        public boolean matches(final BedrockItem item, final ItemRewriter itemRewriter) {
            if (item == null || item.isEmpty() || item.identifier() != this.itemId) {
                return false;
            }
            return this.auxValue == ANY_AUX_VALUE || this.auxValue == item.data();
        }
    }

    /** A family of items named by a Bedrock item tag, such as {@code minecraft:planks}. */
    record OfTag(String tag, int count) implements RecipeIngredient {
        @Override
        public boolean matches(final BedrockItem item, final ItemRewriter itemRewriter) {
            if (item == null || item.isEmpty()) {
                return false;
            }
            final String identifier = itemRewriter.getItems().inverse().get(item.identifier());
            if (identifier == null) {
                return false;
            }
            final Set<String> tags = BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier);
            return tags != null && tags.contains(this.tag);
        }
    }

    /**
     * A descriptor whose meaning is not available here — a Molang predicate, or an item id this
     * session's registry does not know. Never matches, so the recipe it belongs to is never offered.
     */
    record Unsupported(String description, int count) implements RecipeIngredient {
        @Override
        public boolean matches(final BedrockItem item, final ItemRewriter itemRewriter) {
            return false;
        }
    }

}
