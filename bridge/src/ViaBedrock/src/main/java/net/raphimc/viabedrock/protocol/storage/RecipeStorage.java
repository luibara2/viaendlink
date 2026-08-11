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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.CraftingRecipe;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

/**
 * The crafting recipes this server sent, and the matching a Java client's crafting screen needs.
 *
 * <p>The two editions put the crafting result in different places. On Java the server decides what
 * a grid produces and pushes it into slot 0; on Bedrock the <em>client</em> works it out from the
 * recipe list, and the server only ever sees a finished craft request naming a recipe by id. A
 * bridge sits on the Java side of that line, so it has to do the Bedrock client's half: hold the
 * recipes, match the grid against them, and show the answer as if a server had sent it.</p>
 *
 * <p>Only crafting-table recipes are kept. Everything else in {@code CraftingData} — furnace,
 * brewing, smithing, stonecutter, the chemistry tables — belongs to a screen this bridge does not
 * open, so keeping it would cost memory and match nothing.</p>
 */
public class RecipeStorage extends StoredObject {

    /** The block tag Bedrock puts on the recipes both the 2x2 and the crafting table can make. */
    public static final String CRAFTING_TABLE_TAG = "crafting_table";

    private List<CraftingRecipe> craftingTableRecipes = List.of();

    public RecipeStorage(final UserConnection user) {
        super(user);
    }

    /**
     * Replaces the recipe list.
     *
     * <p>{@code CraftingData} arrives whole, and its {@code cleanRecipes} flag says whether it
     * replaces what came before or adds to it. Servers that send it more than once — a plugin
     * registering recipes late — send the whole list again, so appending would double every recipe.
     *
     * @param cleanRecipes what the server said: true to forget the previous list, false to add
     */
    public void addRecipes(final List<CraftingRecipe> recipes, final boolean cleanRecipes) {
        final List<CraftingRecipe> combined = new ArrayList<>(cleanRecipes ? List.of() : this.craftingTableRecipes);
        for (CraftingRecipe recipe : recipes) {
            if (CRAFTING_TABLE_TAG.equals(recipe.blockTag())) {
                combined.add(recipe);
            }
        }
        // Bedrock's tie-break when more than one recipe fits the grid, and it is load-bearing: the
        // 3x3 "one log to four planks" and its 2x2 twin both match a single log, and priority is
        // what says which one the player gets.
        combined.sort(Comparator.comparingInt(CraftingRecipe::priority));
        this.craftingTableRecipes = combined;
        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Loaded " + combined.size()
                + " crafting table recipes. Java players can craft with them; a grid that matches none shows an empty result.");
    }

    /**
     * The recipe a grid produces, or null for one that produces nothing.
     *
     * @param grid      the grid's cells, row-major
     * @param gridWidth 2 for the player's own crafting square, 3 for a crafting table
     */
    public Match match(final BedrockItem[] grid, final int gridWidth) {
        final ItemRewriter itemRewriter = this.user().get(ItemRewriter.class);
        if (itemRewriter == null || isEmpty(grid)) {
            return null;
        }
        for (CraftingRecipe recipe : this.craftingTableRecipes) {
            final int[] consumed = recipe.match(grid, gridWidth, itemRewriter);
            if (consumed != null) {
                return new Match(recipe, consumed);
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return this.craftingTableRecipes.isEmpty();
    }

    private static boolean isEmpty(final BedrockItem[] grid) {
        for (BedrockItem item : grid) {
            if (item != null && !item.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** A recipe that fits, with what it takes out of each grid cell per craft. */
    public record Match(CraftingRecipe recipe, int[] consumed) {

        /**
         * How many times this craft could be repeated before the grid runs out.
         *
         * <p>What a shift-click asks for: Java crafts until it cannot, so the count has to be known
         * before the request is built rather than discovered by repeating it.</p>
         */
        public int maxCrafts(final BedrockItem[] grid) {
            int crafts = Integer.MAX_VALUE;
            for (int slot = 0; slot < this.consumed.length; slot++) {
                if (this.consumed[slot] <= 0) {
                    continue;
                }
                final BedrockItem item = grid[slot];
                if (item == null || item.isEmpty()) {
                    return 0;
                }
                crafts = Math.min(crafts, item.amount() / this.consumed[slot]);
            }
            return crafts == Integer.MAX_VALUE ? 0 : crafts;
        }
    }

}
