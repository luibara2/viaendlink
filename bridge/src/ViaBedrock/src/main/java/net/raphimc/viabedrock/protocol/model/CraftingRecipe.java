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

import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of {@code CraftingData} that a crafting grid can produce.
 *
 * <p>The important field is {@link #netId()}. Bedrock's server-authoritative inventory does not
 * accept "take what is in the result slot" — a craft is an {@code ItemStackRequest} naming the
 * recipe by its network id, and the server checks that the grid really does satisfy that recipe. So
 * the client has to have matched the recipe itself before it can ask for it, which is what
 * {@link #match} is for and why nothing could be crafted until {@code CraftingData} was read.</p>
 *
 * @param shaped      whether position matters. A shaped recipe's footprint may sit anywhere in the
 *                    grid and may be mirrored; a shapeless one only cares which items are present.
 * @param ingredients row-major, {@link #width} by {@link #height}, for a shaped recipe; an unordered
 *                    list for a shapeless one
 * @param priority    Bedrock's tie-break when several recipes match, lowest first
 */
public record CraftingRecipe(int netId, String blockTag, int priority, boolean shaped, int width, int height,
                             List<RecipeIngredient> ingredients, BedrockItem result) {

    /**
     * Works out whether this recipe applies to a grid, and what it would take out of it.
     *
     * @param grid      the grid's cells, row-major, {@code gridWidth} wide
     * @param gridWidth 2 for the player's own crafting square, 3 for a crafting table
     * @return how many items each grid cell loses per craft, indexed as {@code grid} is, or null if
     *         the recipe does not apply. Cells the recipe does not use hold 0.
     */
    public int[] match(final BedrockItem[] grid, final int gridWidth, final ItemRewriter itemRewriter) {
        final int gridHeight = grid.length / gridWidth;
        if (this.shaped) {
            if (this.width > gridWidth || this.height > gridHeight) {
                return null;
            }
            for (int offsetY = 0; offsetY + this.height <= gridHeight; offsetY++) {
                for (int offsetX = 0; offsetX + this.width <= gridWidth; offsetX++) {
                    // Both editions accept a shaped recipe laid out left-to-right or mirrored, and
                    // Bedrock's assumeSymmetry flag only says the two are the same picture -- it is
                    // not a permission. So both are tried whatever it says.
                    int[] consumed = this.matchShaped(grid, gridWidth, offsetX, offsetY, false, itemRewriter);
                    if (consumed == null) {
                        consumed = this.matchShaped(grid, gridWidth, offsetX, offsetY, true, itemRewriter);
                    }
                    if (consumed != null) {
                        return consumed;
                    }
                }
            }
            return null;
        }
        return this.matchShapeless(grid, itemRewriter);
    }

    private int[] matchShaped(final BedrockItem[] grid, final int gridWidth, final int offsetX, final int offsetY,
                              final boolean mirrored, final ItemRewriter itemRewriter) {
        final int gridHeight = grid.length / gridWidth;
        final int[] consumed = new int[grid.length];
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                final int gridSlot = y * gridWidth + x;
                final BedrockItem item = grid[gridSlot];
                final int recipeX = x - offsetX;
                final int recipeY = y - offsetY;

                if (recipeX < 0 || recipeX >= this.width || recipeY < 0 || recipeY >= this.height) {
                    if (item != null && !item.isEmpty()) {
                        return null; // Something outside the recipe's footprint, so this is a different craft
                    }
                    continue;
                }

                final int column = mirrored ? this.width - 1 - recipeX : recipeX;
                final RecipeIngredient ingredient = this.ingredients.get(recipeY * this.width + column);
                if (!ingredient.matches(item, itemRewriter)) {
                    return null;
                }
                if (!ingredient.isEmpty() && item.amount() < ingredient.count()) {
                    return null;
                }
                consumed[gridSlot] = ingredient.count();
            }
        }
        return consumed;
    }

    /**
     * Pairs each filled grid cell with a distinct ingredient, or reports that no such pairing exists.
     *
     * <p>Backtracking rather than a greedy first-fit, because ingredient families overlap: a recipe
     * asking for one {@code minecraft:planks} and one oak plank matches a grid holding oak and
     * birch, but only if the oak plank is not handed to the family first. Greedy would fail that,
     * and it would fail it silently — the result slot simply stays empty and nothing says why. The
     * grid is nine cells at most, so the exhaustive answer is free.</p>
     */
    private int[] matchShapeless(final BedrockItem[] grid, final ItemRewriter itemRewriter) {
        final List<RecipeIngredient> needed = new ArrayList<>(this.ingredients.size());
        for (RecipeIngredient ingredient : this.ingredients) {
            if (!ingredient.isEmpty()) {
                needed.add(ingredient);
            }
        }

        final List<Integer> filled = new ArrayList<>(grid.length);
        for (int slot = 0; slot < grid.length; slot++) {
            if (grid[slot] != null && !grid[slot].isEmpty()) {
                filled.add(slot);
            }
        }
        if (filled.size() != needed.size()) {
            return null;
        }

        final int[] consumed = new int[grid.length];
        return assign(grid, filled, needed, new boolean[needed.size()], 0, consumed, itemRewriter) ? consumed : null;
    }

    private static boolean assign(final BedrockItem[] grid, final List<Integer> filled, final List<RecipeIngredient> needed,
                                  final boolean[] used, final int index, final int[] consumed, final ItemRewriter itemRewriter) {
        if (index == filled.size()) {
            return true;
        }
        final int slot = filled.get(index);
        final BedrockItem item = grid[slot];
        for (int i = 0; i < needed.size(); i++) {
            if (used[i]) {
                continue;
            }
            final RecipeIngredient ingredient = needed.get(i);
            if (!ingredient.matches(item, itemRewriter) || item.amount() < ingredient.count()) {
                continue;
            }
            used[i] = true;
            consumed[slot] = ingredient.count();
            if (assign(grid, filled, needed, used, index + 1, consumed, itemRewriter)) {
                return true;
            }
            used[i] = false;
            consumed[slot] = 0;
        }
        return false;
    }

}
