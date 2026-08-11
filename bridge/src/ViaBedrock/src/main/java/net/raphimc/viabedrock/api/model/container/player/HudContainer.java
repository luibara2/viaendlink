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
package net.raphimc.viabedrock.api.model.container.player;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.api.model.container.CraftingContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

public class HudContainer extends InventoryRedirectContainer {

    /** The 2x2 crafting square in the player's own window. */
    public static final int SMALL_GRID_FIRST = 28;
    public static final int SMALL_GRID_LAST = 31;

    public HudContainer(final UserConnection user) {
        super(user, (byte) ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), ContainerType.HUD, 54);
    }

    @Override
    public boolean setItem(final int slot, final BedrockItem item) {
        if (!super.setItem(slot, item)) {
            return false;
        }
        // Forwarding a crafting slot as a single-slot update would have to name a window: the
        // crafting table's while one is open, the player's own otherwise. The tick's whole-window
        // sync already knows which, so both return false and let it do it.
        if (isGridSlot(slot) || slot == CraftingContainer.RESULT) {
            final InventoryTracker inventoryTracker = this.user.get(InventoryTracker.class);
            inventoryTracker.markCraftingWindowDirty();
            if (isGridSlot(slot)) {
                // The grid changed, so what it produces changed with it. Nothing else will say so --
                // Bedrock sends no crafting preview, this side works it out. Writing the answer back
                // into slot 50 re-enters here, which is what the guard inside it is for.
                inventoryTracker.refreshCraftingResult();
            }
            return false;
        }
        return slot == 0 || (slot >= SMALL_GRID_FIRST && slot <= SMALL_GRID_LAST);
    }

    @Override
    public int javaSlot(final int slot) {
        if (slot >= SMALL_GRID_FIRST && slot <= SMALL_GRID_LAST) {
            return slot - 27;
        } else {
            return super.javaSlot(slot);
        }
    }

    /**
     * Four regions of the player UI container can be addressed by an item stack request: the cursor,
     * the 2x2 crafting square, a crafting table's 3x3 grid, and the slot a finished craft appears
     * in. The rest are screens Java has no counterpart for, so a request naming them would be
     * meaningless — null keeps them out rather than guessing.
     */
    @Override
    protected ContainerEnumName requestContainerName(final int slot) {
        if (slot == 0) {
            return ContainerEnumName.CursorContainer;
        }
        if (slot >= SMALL_GRID_FIRST && slot <= SMALL_GRID_LAST) {
            return ContainerEnumName.CraftingInputContainer;
        }
        if (slot >= CraftingContainer.GRID_FIRST && slot < CraftingContainer.GRID_FIRST + 9) {
            return ContainerEnumName.CraftingInputContainer;
        }
        if (slot == CraftingContainer.RESULT) {
            return ContainerEnumName.CreatedOutputContainer;
        }
        return null;
    }

    /** Whether this slot is part of either crafting grid — the 2x2 square or a table's 3x3. */
    public static boolean isGridSlot(final int slot) {
        return (slot >= SMALL_GRID_FIRST && slot <= SMALL_GRID_LAST)
                || (slot >= CraftingContainer.GRID_FIRST && slot < CraftingContainer.GRID_FIRST + 9);
    }

}
