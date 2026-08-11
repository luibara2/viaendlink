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
package net.raphimc.viabedrock.api.util;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.CraftingRecipe;
import net.raphimc.viabedrock.protocol.model.RecipeIngredient;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@code CraftingData} packet.
 *
 * <p>Every entry has to be read, not only the ones worth keeping. The recipe list is a flat
 * sequence with no per-entry length, so a furnace recipe that is skipped rather than parsed leaves
 * the reader in the middle of it and every entry after that is nonsense. That is why the brewing,
 * furnace and smithing shapes appear below despite nothing here using them.</p>
 *
 * <p>The layouts are Bedrock 1.26.30's, mirrored from CloudburstMC's {@code CraftingDataSerializer}
 * chain (v465 for the packet, v582 for the entry switch, v685/v748 for the recipes and their
 * unlocking requirement) and its {@code BedrockCodecHelper} (v554/v575 for ingredients, v431 for
 * item instances).</p>
 */
public final class CraftingDataReader {

    // CraftingDataType, in ordinal order -- what the entry's leading varint means
    private static final int SHAPELESS = 0;
    private static final int SHAPED = 1;
    private static final int FURNACE = 2;
    private static final int FURNACE_DATA = 3;
    private static final int MULTI = 4;
    private static final int SHULKER_BOX = 5;
    private static final int SHAPELESS_CHEMISTRY = 6;
    private static final int SHAPED_CHEMISTRY = 7;
    private static final int SMITHING_TRANSFORM = 8;
    private static final int SMITHING_TRIM = 9;

    // ItemDescriptorType, in ordinal order
    private static final int DESCRIPTOR_INVALID = 0;
    private static final int DESCRIPTOR_DEFAULT = 1;
    private static final int DESCRIPTOR_MOLANG = 2;
    private static final int DESCRIPTOR_ITEM_TAG = 3;
    private static final int DESCRIPTOR_DEFERRED = 4;
    private static final int DESCRIPTOR_COMPLEX_ALIAS = 5;

    /** RecipeUnlockingRequirement.UnlockingContext.NONE, the only one that carries ingredients. */
    private static final int UNLOCKING_CONTEXT_NONE = 0;

    private CraftingDataReader() {
    }

    /**
     * @return the shaped and shapeless recipes, in the order the server sent them. Recipe kinds that
     *         belong to screens this bridge does not open are read past and dropped.
     */
    public static Result read(final PacketWrapper wrapper, final ItemRewriter itemRewriter) {
        final Type<BedrockItem> itemInstance = itemRewriter.itemInstanceType();
        final List<CraftingRecipe> recipes = new ArrayList<>();

        final int entryCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // recipe count
        for (int i = 0; i < entryCount; i++) {
            final int type = wrapper.read(BedrockTypes.VAR_INT); // type
            switch (type) {
                case SHAPELESS, SHAPELESS_CHEMISTRY, SHULKER_BOX -> {
                    final CraftingRecipe recipe = readShapeless(wrapper, itemInstance, type);
                    if (recipe != null) {
                        recipes.add(recipe);
                    }
                }
                case SHAPED, SHAPED_CHEMISTRY -> {
                    final CraftingRecipe recipe = readShaped(wrapper, itemInstance, type);
                    if (recipe != null) {
                        recipes.add(recipe);
                    }
                }
                case FURNACE -> {
                    wrapper.read(BedrockTypes.VAR_INT); // input id
                    wrapper.read(itemInstance); // result
                    wrapper.read(BedrockTypes.STRING); // tag
                }
                case FURNACE_DATA -> {
                    wrapper.read(BedrockTypes.VAR_INT); // input id
                    wrapper.read(BedrockTypes.VAR_INT); // input data
                    wrapper.read(itemInstance); // result
                    wrapper.read(BedrockTypes.STRING); // tag
                }
                case MULTI -> {
                    wrapper.read(BedrockTypes.UUID); // uuid
                    wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // net id
                }
                case SMITHING_TRANSFORM -> {
                    wrapper.read(BedrockTypes.STRING); // id
                    readIngredient(wrapper); // template
                    readIngredient(wrapper); // base
                    readIngredient(wrapper); // addition
                    wrapper.read(itemInstance); // result
                    wrapper.read(BedrockTypes.STRING); // tag
                    wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // net id
                }
                case SMITHING_TRIM -> {
                    wrapper.read(BedrockTypes.STRING); // id
                    readIngredient(wrapper); // template
                    readIngredient(wrapper); // base
                    readIngredient(wrapper); // addition
                    wrapper.read(BedrockTypes.STRING); // tag
                    wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // net id
                }
                // Anything else has an unknown length, so there is no way to get back in step. The
                // rest of the packet is abandoned rather than read as garbage.
                default -> throw new IllegalArgumentException("Unknown CraftingDataType: " + type);
            }
        }

        final int potionMixCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // potion mix count
        for (int i = 0; i < potionMixCount; i++) {
            for (int field = 0; field < 6; field++) {
                wrapper.read(BedrockTypes.VAR_INT); // input/reagent/output id and meta
            }
        }
        final int containerMixCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container mix count
        for (int i = 0; i < containerMixCount; i++) {
            for (int field = 0; field < 3; field++) {
                wrapper.read(BedrockTypes.VAR_INT); // input/reagent/output id
            }
        }
        final int materialReducerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // material reducer count
        for (int i = 0; i < materialReducerCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // input id
            final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // output count
            for (int output = 0; output < outputCount; output++) {
                wrapper.read(BedrockTypes.VAR_INT); // item id
                wrapper.read(BedrockTypes.VAR_INT); // count
            }
        }
        final boolean cleanRecipes = wrapper.read(Types.BOOLEAN); // clean recipes

        return new Result(recipes, cleanRecipes);
    }

    private static CraftingRecipe readShapeless(final PacketWrapper wrapper, final Type<BedrockItem> itemInstance, final int type) {
        wrapper.read(BedrockTypes.STRING); // recipe id
        final int ingredientCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // ingredient count
        final List<RecipeIngredient> ingredients = new ArrayList<>(ingredientCount);
        for (int i = 0; i < ingredientCount; i++) {
            ingredients.add(readIngredient(wrapper));
        }
        final BedrockItem result = readResults(wrapper, itemInstance);
        wrapper.read(BedrockTypes.UUID); // uuid
        final String tag = wrapper.read(BedrockTypes.STRING); // tag
        final int priority = wrapper.read(BedrockTypes.VAR_INT); // priority
        if (type == SHAPELESS || type == SHULKER_BOX) {
            readUnlockingRequirement(wrapper);
        }
        final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // net id

        if (result == null || type != SHAPELESS) {
            return null; // A chemistry or shulker recipe: read for alignment, never offered
        }
        return new CraftingRecipe(netId, tag, priority, false, 0, 0, ingredients, result);
    }

    private static CraftingRecipe readShaped(final PacketWrapper wrapper, final Type<BedrockItem> itemInstance, final int type) {
        wrapper.read(BedrockTypes.STRING); // recipe id
        final int width = wrapper.read(BedrockTypes.VAR_INT); // width
        final int height = wrapper.read(BedrockTypes.VAR_INT); // height
        final List<RecipeIngredient> ingredients = new ArrayList<>(Math.max(width * height, 0));
        for (int i = 0; i < width * height; i++) {
            ingredients.add(readIngredient(wrapper));
        }
        final BedrockItem result = readResults(wrapper, itemInstance);
        wrapper.read(BedrockTypes.UUID); // uuid
        final String tag = wrapper.read(BedrockTypes.STRING); // tag
        final int priority = wrapper.read(BedrockTypes.VAR_INT); // priority
        wrapper.read(Types.BOOLEAN); // assume symmetry
        if (type == SHAPED) {
            readUnlockingRequirement(wrapper);
        }
        final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // net id

        if (result == null || type != SHAPED || width <= 0 || height <= 0) {
            return null;
        }
        return new CraftingRecipe(netId, tag, priority, true, width, height, ingredients, result);
    }

    /**
     * Reads the result list and keeps the first entry.
     *
     * <p>Bedrock allows several, but a Java crafting screen has one result slot and no way to show
     * a second, so a multi-result recipe would have to lie about what the craft produces. None of
     * the vanilla crafting-table recipes has more than one; those that do are dropped.</p>
     */
    private static BedrockItem readResults(final PacketWrapper wrapper, final Type<BedrockItem> itemInstance) {
        final int resultCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // result count
        BedrockItem first = null;
        for (int i = 0; i < resultCount; i++) {
            final BedrockItem result = wrapper.read(itemInstance); // result
            if (i == 0) {
                first = result;
            }
        }
        if (resultCount != 1 || first == null || first.isEmpty()) {
            return null;
        }
        return first;
    }

    private static void readUnlockingRequirement(final PacketWrapper wrapper) {
        final byte context = wrapper.read(Types.BYTE); // unlocking context
        if (context != UNLOCKING_CONTEXT_NONE) {
            return;
        }
        final int ingredientCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // ingredient count
        for (int i = 0; i < ingredientCount; i++) {
            readIngredient(wrapper);
        }
    }

    private static RecipeIngredient readIngredient(final PacketWrapper wrapper) {
        final short descriptorType = wrapper.read(Types.UNSIGNED_BYTE); // descriptor type
        Integer itemId = null;
        int auxValue = RecipeIngredient.ANY_AUX_VALUE;
        String tag = null;
        String unsupported = null;

        switch (descriptorType) {
            case DESCRIPTOR_INVALID -> {
            }
            case DESCRIPTOR_DEFAULT -> {
                final short id = wrapper.read(BedrockTypes.SHORT_LE); // item id
                if (id != 0) {
                    auxValue = wrapper.read(BedrockTypes.SHORT_LE); // aux value
                    itemId = (int) id;
                }
            }
            case DESCRIPTOR_MOLANG -> {
                unsupported = "molang " + wrapper.read(BedrockTypes.STRING); // tag expression
                wrapper.read(Types.UNSIGNED_BYTE); // molang version
            }
            case DESCRIPTOR_ITEM_TAG -> tag = wrapper.read(BedrockTypes.STRING); // item tag
            case DESCRIPTOR_DEFERRED -> {
                unsupported = "deferred " + wrapper.read(BedrockTypes.STRING); // full name
                wrapper.read(BedrockTypes.SHORT_LE); // aux value
            }
            case DESCRIPTOR_COMPLEX_ALIAS -> unsupported = "complex alias " + wrapper.read(BedrockTypes.STRING); // name
            default -> throw new IllegalArgumentException("Unknown ItemDescriptorType: " + descriptorType);
        }

        final int count = wrapper.read(BedrockTypes.VAR_INT); // count
        if (unsupported != null) {
            return new RecipeIngredient.Unsupported(unsupported, Math.max(count, 1));
        }
        if (tag != null) {
            return new RecipeIngredient.OfTag(tag, Math.max(count, 1));
        }
        if (itemId == null) {
            return new RecipeIngredient.Empty();
        }
        return new RecipeIngredient.OfItem(itemId, auxValue, Math.max(count, 1));
    }

    /** @param cleanRecipes whether this list replaces the previous one rather than adding to it */
    public record Result(List<CraftingRecipe> recipes, boolean cleanRecipes) {
    }

}
