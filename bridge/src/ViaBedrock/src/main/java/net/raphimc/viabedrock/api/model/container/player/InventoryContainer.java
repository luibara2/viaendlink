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
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingContainer;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public class InventoryContainer extends Container {

    private byte selectedHotbarSlot = 0;

    public InventoryContainer(final UserConnection user) {
        super(user, (byte) ContainerID.CONTAINER_ID_INVENTORY.getValue(), ContainerType.INVENTORY, null, null, 36);
    }

    public InventoryContainer(final UserConnection user, final byte containerId, final BlockPosition position, final InventoryContainer inventoryContainer) {
        super(user, containerId, inventoryContainer.type, inventoryContainer.title, position, inventoryContainer.items, inventoryContainer.validBlockTags);
        this.selectedHotbarSlot = inventoryContainer.selectedHotbarSlot;
    }

    @Override
    public Item[] getJavaItems() {
        final InventoryTracker inventoryTracker = this.user.get(InventoryTracker.class);
        final Item[] inventoryItems = super.getJavaItems();
        final Item[] armorItems = inventoryTracker.getArmorContainer().getActualJavaItems();
        final Item[] offhandItems = inventoryTracker.getOffhandContainer().getActualJavaItems();
        final Container hudContainer = inventoryTracker.getHudContainer();

        final Item[] combinedItems = StructuredItem.emptyArray(46);
        System.arraycopy(armorItems, 0, combinedItems, 5, armorItems.length);
        System.arraycopy(inventoryItems, 9, combinedItems, 9, 27);
        System.arraycopy(inventoryItems, 0, combinedItems, 36, 9);
        System.arraycopy(offhandItems, 0, combinedItems, 45, offhandItems.length);
        for (int i = 0; i < 4; i++) {
            combinedItems[1 + i] = hudContainer.getJavaItem(HudContainer.SMALL_GRID_FIRST + i);
        }
        // Slot 0 is what the 2x2 square currently makes. A Java server would compute it and push it
        // here; Bedrock sends no preview at all, so InventoryTracker.refreshCraftingResult works it
        // out from the recipe list and leaves the answer in the UI container's result slot.
        combinedItems[0] = hudContainer.getJavaItem(CraftingContainer.RESULT);
        return combinedItems;
    }

    @Override
    public boolean setItems(BedrockItem[] items) {
        if (items.length != this.size()) {
            final BedrockItem[] newItems = this.getItems();
            System.arraycopy(items, 0, newItems, 0, Math.min(items.length, newItems.length));
            items = newItems;
        }
        return super.setItems(items);
    }

    @Override
    public int javaSlot(final int slot) {
        if (slot < 9) {
            return 36 + slot;
        } else {
            return super.javaSlot(slot);
        }
    }

    /**
     * The player's 36 slots are one container to Bedrock but two <em>names</em>: the hotbar and the
     * inventory proper. An item stack request that calls slot 3 "inventory" rather than "hotbar" is
     * rejected, so the split matters even though both live at container id 0.
     */
    @Override
    protected ContainerEnumName requestContainerName(final int slot) {
        return slot < 9 ? ContainerEnumName.HotbarContainer : ContainerEnumName.InventoryContainer;
    }

    /**
     * The player's own window, whose layout no other container shares: result, crafting grid,
     * armour, inventory, hotbar, offhand — spread across four Bedrock containers.
     */
    @Override
    public ContainerSlot resolveJavaSlot(final int javaSlot) {
        final InventoryTracker inventoryTracker = this.user.get(InventoryTracker.class);
        if (javaSlot == 0) { // What the 2x2 square makes; taking it is a craft, not a move
            return new ContainerSlot(inventoryTracker.getHudContainer(), CraftingContainer.RESULT);
        }
        if (javaSlot >= 1 && javaSlot <= 4) { // 2x2 crafting grid, which Bedrock keeps in the UI container
            return new ContainerSlot(inventoryTracker.getHudContainer(), javaSlot + 27);
        }
        if (javaSlot >= 5 && javaSlot <= 8) {
            return new ContainerSlot(inventoryTracker.getArmorContainer(), javaSlot - 5);
        }
        if (javaSlot >= 9 && javaSlot <= 35) {
            return new ContainerSlot(this, javaSlot);
        }
        if (javaSlot >= 36 && javaSlot <= 44) {
            return new ContainerSlot(this, javaSlot - 36);
        }
        if (javaSlot == 45) {
            return new ContainerSlot(inventoryTracker.getOffhandContainer(), 0);
        }
        return null;
    }

    /** Slot 0 of the player's own window is the 2x2 square's output. */
    @Override
    public boolean isCraftingResult(final int javaSlot) {
        return javaSlot == 0;
    }

    @Override
    public byte javaContainerId() {
        return (byte) ContainerID.CONTAINER_ID_INVENTORY.getValue();
    }

    public byte getSelectedHotbarSlot() {
        return this.selectedHotbarSlot;
    }

    public BedrockItem getSelectedHotbarItem() {
        return this.getItem(this.selectedHotbarSlot);
    }

    public void sendSelectedHotbarSlotToClient() {
        final PacketWrapper setHeldSlot = PacketWrapper.create(ClientboundPackets26_1.SET_HELD_SLOT, this.user);
        setHeldSlot.write(Types.VAR_INT, (int) this.selectedHotbarSlot);
        setHeldSlot.send(BedrockProtocol.class);
    }

    public void setSelectedHotbarSlot(final byte slot, final PacketWrapper mobEquipment) {
        final BedrockItem oldItem = this.getItem(this.selectedHotbarSlot);
        final BedrockItem newItem = this.getItem(slot);
        this.selectedHotbarSlot = slot;
        this.onSelectedHotbarSlotChanged(oldItem, newItem, mobEquipment);
    }

    @Override
    protected void onSlotChanged(final int slot, final BedrockItem oldItem, final BedrockItem newItem) {
        super.onSlotChanged(slot, oldItem, newItem);
        if (slot == this.selectedHotbarSlot) {
            final PacketWrapper mobEquipment = PacketWrapper.create(ServerboundBedrockPackets.MOB_EQUIPMENT, this.user);
            this.onSelectedHotbarSlotChanged(oldItem, newItem, mobEquipment);
            mobEquipment.sendToServer(BedrockProtocol.class);
        }
    }

    private void onSelectedHotbarSlotChanged(final BedrockItem oldItem, final BedrockItem newItem, final PacketWrapper mobEquipment) {
        if (oldItem.isDifferent(newItem)) {
            final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, this.user);
            interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.InteractUpdate.getValue()); // action
            interact.write(BedrockTypes.UNSIGNED_VAR_LONG, 0L); // target entity runtime id
            interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
            interact.sendToServer(BedrockProtocol.class);
        }

        mobEquipment.write(BedrockTypes.UNSIGNED_VAR_LONG, this.user.get(EntityTracker.class).getClientPlayer().runtimeId()); // entity runtime id
        mobEquipment.write(this.user.get(ItemRewriter.class).newItemType(), newItem); // item
        mobEquipment.write(Types.BYTE, this.selectedHotbarSlot); // slot
        mobEquipment.write(Types.BYTE, this.selectedHotbarSlot); // selected slot
        mobEquipment.write(Types.BYTE, this.containerId); // container id
    }

}
