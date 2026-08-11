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
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.ItemStackRequestSlot;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

public abstract class Container {

    protected final UserConnection user;
    protected final byte containerId;
    protected final ContainerType type;
    protected final TextComponent title;
    protected final BlockPosition position;
    protected final BedrockItem[] items;
    protected final Set<String> validBlockTags;
    private boolean dirty;

    public Container(final UserConnection user, final byte containerId, final ContainerType type, final TextComponent title, final BlockPosition position, final int size, final String... validBlockTags) {
        this.user = user;
        this.containerId = containerId;
        this.type = type;
        this.title = title;
        this.position = position;
        this.items = BedrockItem.emptyArray(size);
        this.validBlockTags = Set.of(validBlockTags);
    }

    protected Container(final UserConnection user, final byte containerId, final ContainerType type, final TextComponent title, final BlockPosition position, final BedrockItem[] items, final Set<String> validBlockTags) {
        this.user = user;
        this.containerId = containerId;
        this.type = type;
        this.title = title;
        this.position = position;
        this.items = items;
        this.validBlockTags = validBlockTags;
    }

    /**
     * Turns one Java {@code ContainerClick} into an {@code ItemStackRequest}.
     *
     * @return true if a request was sent and the client's own prediction can stand; false if the
     *         click could not be expressed, in which case the caller resyncs the window so the
     *         player sees the unchanged truth rather than a move that never happened
     */
    public boolean handleClick(final int revision, final short slot, final byte button, final ContainerInput action) {
        return ContainerClickTranslator.translate(this.user, this, slot, button, action);
    }

    /**
     * Where this slot sits in the addressing scheme {@code ItemStackRequest}s use, or null if it
     * cannot be addressed at all.
     *
     * <p>Bedrock names slots by <em>kind</em> rather than by container, so this is not simply the
     * container's own identity — see {@link ItemStackRequestSlot}. The default is the plain
     * block-entity container a chest, barrel or hopper presents.</p>
     */
    public ItemStackRequestSlot requestSlot(final int slot) {
        final ContainerEnumName containerName = this.requestContainerName(slot);
        if (containerName == null) {
            return null;
        }
        if (containerName == ContainerEnumName.CreatedOutputContainer) {
            // Whatever is shown here is this side's own prediction of a craft that has not happened.
            // The stack the server will make has no id yet, so it is named by the request that makes
            // it -- see ItemStackRequestSlot.PRODUCED_BY_THIS_REQUEST.
            return new ItemStackRequestSlot(containerName, this.requestSlotIndex(slot), ItemStackRequestSlot.PRODUCED_BY_THIS_REQUEST);
        }
        final Integer netId = this.getItem(slot).netId();
        return new ItemStackRequestSlot(containerName, this.requestSlotIndex(slot), netId == null ? 0 : netId);
    }

    protected ContainerEnumName requestContainerName(final int slot) {
        return ContainerEnumName.LevelEntityContainer;
    }

    protected int requestSlotIndex(final int slot) {
        return slot;
    }

    /**
     * Resolves a slot of the open Java window to the Bedrock container that actually holds it.
     *
     * <p>A Java window is one flat list of slots; on Bedrock the same slots are spread across
     * several containers — the block's own, the player's inventory, their armour, their offhand.
     * This is the seam between the two views. The layout here is the generic one: the container's
     * own slots, then 27 of inventory, then the 9 hotbar slots.</p>
     */
    public ContainerSlot resolveJavaSlot(final int javaSlot) {
        if (javaSlot >= 0 && javaSlot < this.size()) {
            return new ContainerSlot(this, javaSlot);
        }
        final InventoryContainer inventory = this.user.get(InventoryTracker.class).getInventoryContainer();
        final int playerSlot = javaSlot - this.size();
        if (playerSlot >= 0 && playerSlot < 27) { // Main inventory, which is Bedrock slots 9-35
            return new ContainerSlot(inventory, playerSlot + 9);
        }
        if (playerSlot >= 27 && playerSlot < 36) { // Hotbar, which is Bedrock slots 0-8
            return new ContainerSlot(inventory, playerSlot - 27);
        }
        return null;
    }

    /** A slot, once it is known which Bedrock container owns it. */
    public record ContainerSlot(Container container, int slot) {
    }

    public void clearItems() {
        for (int i = 0; i < this.items.length; i++) {
            this.items[i] = BedrockItem.empty();
        }
    }

    public Item getJavaItem(final int slot) {
        return this.user.get(ItemRewriter.class).javaItem(this.getItem(slot));
    }

    public Item[] getJavaItems() {
        return this.user.get(ItemRewriter.class).javaItems(this.getItems());
    }

    public BedrockItem getItem(final int slot) {
        return this.items[slot];
    }

    public BedrockItem[] getItems() {
        return Arrays.copyOf(this.items, this.items.length);
    }

    /**
     * Whether this window's slot holds the output of a crafting grid rather than an item that can
     * simply be picked up.
     *
     * <p>Taking a crafting result is a <em>craft</em>: an item stack request naming the recipe, not
     * a move of what happens to be sitting there. A window that has one has to say so, because the
     * click looks identical from the outside — see {@link ContainerClickTranslator}.</p>
     */
    public boolean isCraftingResult(final int javaSlot) {
        return false;
    }

    public boolean setItem(final int slot, final BedrockItem item) {
        if (slot < 0 || slot >= this.items.length) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set item for " + this.type + ", but slot was out of bounds (" + slot + ")");
            return false;
        }

        final BedrockItem oldItem = this.items[slot];
        this.items[slot] = item;
        if (!Objects.equals(oldItem, item)) {
            this.dirty = true;
        }
        this.onSlotChanged(slot, oldItem, item);
        return true;
    }

    /** Whether this container has changed since the Java client was last told its whole contents. */
    public boolean isDirty() {
        return this.dirty;
    }

    /**
     * Marks this container as needing to be re-sent, for a change {@link #setItem} did not make.
     *
     * <p>A window whose items live in another container — a crafting table's do — never sees the
     * write that changed what it shows, so nothing would flag it.</p>
     */
    public void markDirty() {
        this.dirty = true;
    }

    public void markClean() {
        this.dirty = false;
    }

    public boolean setItems(final BedrockItem[] items) {
        if (items.length != this.items.length) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set items for " + this.type + ", but items array length was not correct (" + items.length + " != " + this.items.length + ")");
            return false;
        }

        for (int i = 0; i < items.length; i++) {
            this.setItem(i, items[i]);
        }
        return true;
    }

    public int javaSlot(final int slot) {
        return slot;
    }

    public byte javaContainerId() {
        return this.containerId();
    }

    public int size() {
        return this.items.length;
    }

    public byte containerId() {
        return this.containerId;
    }

    public ContainerType type() {
        return this.type;
    }

    public TextComponent title() {
        return this.title;
    }

    public BlockPosition position() {
        return this.position;
    }

    /**
     * Whether the block still under this container is one it could belong to.
     *
     * <p>Declaring no tags means "do not check". Only blocks with a block entity have a tag at all,
     * so a container anchored to a plain block — a crafting table, an anvil — would otherwise be
     * judged invalid on the very first tick and closed again the instant it opened.</p>
     */
    public boolean isValidBlockTag(final String tag) {
        if (this.validBlockTags.isEmpty()) {
            return true;
        }
        if (tag == null) {
            return false;
        }
        return this.validBlockTags.contains(tag);
    }

    protected void onSlotChanged(final int slot, final BedrockItem oldItem, final BedrockItem newItem) {
    }

}
