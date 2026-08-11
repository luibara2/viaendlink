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
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * A crafting table, laid out the way a Java client's crafting screen is: result at slot 0, the 3x3
 * grid at slots 1-9.
 *
 * <p>It owns none of those items. Bedrock keeps the crafting grid in the <em>player UI</em>
 * container — the same one the 2x2 square and the cursor live in — at slots 32-40, and the result
 * at slot 50, whichever table is open. So this is a view: it maps the Java window's slot numbers
 * onto {@link net.raphimc.viabedrock.api.model.container.player.HudContainer}, and that container
 * stays the single place an item actually sits. Copying them here instead would mean two answers to
 * "what is in the grid", which is exactly the shape of bug the one-owner rule exists to avoid.</p>
 *
 * <p>The result slot is not the server's. Bedrock never sends a crafting preview: the client
 * matches the grid against the recipe list and draws the answer itself, and the server only ever
 * sees a finished craft request. {@link InventoryTracker#refreshCraftingResult()} does that
 * matching, and {@link ContainerClickTranslator} turns a click on the result into the request.</p>
 */
public class CraftingContainer extends Container {

    /** Slot of the player UI container where a crafting table's 3x3 grid begins. */
    public static final int GRID_FIRST = 32;
    /** Slot of the player UI container holding whatever the open grid currently produces. */
    public static final int RESULT = 50;

    private static final int GRID_SIZE = 9;
    /** Result plus the 3x3 grid: the ten slots a Java crafting window owns before the player's own. */
    private static final int SIZE = GRID_SIZE + 1;

    public CraftingContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.WORKBENCH, title, position, SIZE);
    }

    /** How many cells across the grid is, which is what decides whether a 3x3 recipe can be made. */
    public int gridWidth() {
        return 3;
    }

    /** The grid, row-major, as the recipe matcher wants it. */
    public BedrockItem[] grid() {
        final Container hud = this.hud();
        final BedrockItem[] grid = new BedrockItem[GRID_SIZE];
        for (int i = 0; i < GRID_SIZE; i++) {
            grid[i] = hud.getItem(GRID_FIRST + i);
        }
        return grid;
    }

    /** Where in the player UI container a grid cell lives, given this window's slot number. */
    public static int hudSlotOf(final int javaSlot) {
        return javaSlot == 0 ? RESULT : GRID_FIRST + javaSlot - 1;
    }

    @Override
    public BedrockItem getItem(final int slot) {
        if (slot < 0 || slot >= SIZE) {
            return BedrockItem.empty();
        }
        return this.hud().getItem(hudSlotOf(slot));
    }

    @Override
    public boolean setItem(final int slot, final BedrockItem item) {
        if (slot < 0 || slot >= SIZE) {
            return false;
        }
        return this.hud().setItem(hudSlotOf(slot), item);
    }

    @Override
    public BedrockItem[] getItems() {
        final BedrockItem[] items = new BedrockItem[SIZE];
        for (int slot = 0; slot < SIZE; slot++) {
            items[slot] = this.getItem(slot);
        }
        return items;
    }

    @Override
    public boolean isCraftingResult(final int javaSlot) {
        return javaSlot == 0;
    }

    /**
     * Bedrock names the grid and the result by their <em>kind</em>, not by this window.
     *
     * <p>The result is a slot the server creates in answer to a craft, not one that holds anything
     * beforehand, and it has a name of its own.</p>
     */
    @Override
    protected ContainerEnumName requestContainerName(final int slot) {
        if (slot < 0 || slot >= SIZE) {
            return null;
        }
        return slot == 0 ? ContainerEnumName.CreatedOutputContainer : ContainerEnumName.CraftingInputContainer;
    }

    /**
     * The numbers are the player UI container's — 32-40 for the grid, 50 for the result — not this
     * window's 1-9 and 0, because that is where the slots actually live. A request that called them
     * anything else would be refused with {@code FailedToValidateSrcSlot}.
     */
    @Override
    protected int requestSlotIndex(final int slot) {
        return hudSlotOf(slot);
    }

    /**
     * A crafting window's slots: the ten above, then the player's inventory and hotbar.
     *
     * <p>The generic layout in {@link Container} would place the inventory after this container's
     * own slots too, which is the same arithmetic — but only by coincidence, since here slot 0 is
     * the result rather than storage. Spelling it out keeps that coincidence from becoming a
     * silent dependency.</p>
     */
    @Override
    public ContainerSlot resolveJavaSlot(final int javaSlot) {
        if (javaSlot >= 0 && javaSlot < SIZE) {
            return new ContainerSlot(this, javaSlot);
        }
        final InventoryContainer inventory = this.user.get(InventoryTracker.class).getInventoryContainer();
        final int playerSlot = javaSlot - SIZE;
        if (playerSlot >= 0 && playerSlot < 27) { // Main inventory, which is Bedrock slots 9-35
            return new ContainerSlot(inventory, playerSlot + 9);
        }
        if (playerSlot >= 27 && playerSlot < 36) { // Hotbar, which is Bedrock slots 0-8
            return new ContainerSlot(inventory, playerSlot - 27);
        }
        return null;
    }

    private Container hud() {
        return this.user.get(InventoryTracker.class).getHudContainer();
    }

}
