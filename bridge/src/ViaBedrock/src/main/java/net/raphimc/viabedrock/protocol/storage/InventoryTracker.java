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
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.dynamic.BundleContainer;
import net.raphimc.viabedrock.api.model.container.player.ArmorContainer;
import net.raphimc.viabedrock.api.model.container.player.HudContainer;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.container.player.OffhandContainer;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ModalFormCancelReason;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomItemTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class InventoryTracker extends StoredObject {

    private final InventoryContainer inventoryContainer = new InventoryContainer(this.user());
    private final OffhandContainer offhandContainer = new OffhandContainer(this.user());
    private final ArmorContainer armorContainer = new ArmorContainer(this.user());
    private final HudContainer hudContainer = new HudContainer(this.user());
    private final Map<FullContainerName, BundleContainer> dynamicContainerRegistry = new HashMap<>();

    /**
     * How long to wait for the server to acknowledge the player's own inventory screen before
     * giving up on it. One second is far longer than a local round trip and short enough that a
     * player who notices the delay at all only notices it once.
     */
    private static final int INVENTORY_OPEN_TIMEOUT_TICKS = 20;

    private Container currentContainer = null;
    private DeferredClick deferredClick = null;
    private boolean awaitingInventoryOpen;
    private int awaitingInventoryOpenTicks;
    private boolean inventoryScreenAnnounced;
    private boolean inventoryOpenUnanswered;
    private int revision;
    private Container pendingCloseContainer = null;
    private IntObjectPair<Form> currentForm = null;

    public InventoryTracker(final UserConnection user) {
        super(user);
    }

    /**
     * The next state id to stamp on a container update sent to the Java client.
     *
     * <p>Java uses this to tell its own predicted inventory apart from the server's: it echoes the
     * last id it saw back in every click, and a client that has predicted ahead reconciles against
     * it. Everything here used to send a literal 0, so from the client's point of view the state
     * never advanced no matter how much changed.</p>
     */
    public int nextRevision() {
        return ++this.revision;
    }

    public Container getContainerClientbound(final byte containerId, final FullContainerName containerName, final BedrockItem storageItem) {
        if (containerId == this.inventoryContainer.containerId()) return this.inventoryContainer;
        if (containerId == this.offhandContainer.containerId()) return this.offhandContainer;
        if (containerId == this.armorContainer.containerId()) return this.armorContainer;
        if (containerId == this.hudContainer.containerId()) return this.hudContainer;
        // INVENTORY_SLOT carries the container name as an optional, so it can legitimately be absent
        // -- and then this dereferenced null and took the packet down with it, which for an open
        // chest means its slot updates simply stop arriving.
        if (containerId == ContainerID.CONTAINER_ID_REGISTRY.getValue() && containerName != null && containerName.name() == ContainerEnumName.DynamicContainer) {
            final String itemTag = BedrockProtocol.MAPPINGS.getBedrockCustomItemTags().get(this.user().get(ItemRewriter.class).getItems().inverse().get(storageItem.identifier()));
            if (!storageItem.isEmpty() && CustomItemTags.BUNDLE.equals(itemTag)) {
                return this.dynamicContainerRegistry.computeIfAbsent(containerName, cn -> new BundleContainer(this.user(), cn));
            } else {
                return null;
            }
        }
        if (this.currentContainer != null && containerId == this.currentContainer.containerId()) {
            return this.currentContainer;
        }
        return null;
    }

    public Container getContainerServerbound(final byte containerId) {
        if (this.currentContainer != null && containerId == this.currentContainer.javaContainerId()) {
            return this.currentContainer;
        }
        return null;
    }

    /**
     * Resolves a slot named the way an {@code ItemStackResponse} names it back to a container here.
     *
     * <p>The inverse of {@code Container.requestSlot}, and it has to undo the same two quirks: the
     * player's 36 slots arrive under two different names, and the offhand arrives as slot 1 of a
     * container whose only slot is 0. Anything naming a screen this client cannot open — a beacon's
     * payment slot, a loom's dye slot — resolves to null and is skipped rather than guessed at.</p>
     */
    public Container.ContainerSlot resolveRequestSlot(final FullContainerName containerName, final int slot) {
        if (containerName == null || containerName.name() == null) {
            return null;
        }
        return switch (containerName.name()) {
            case HotbarContainer, InventoryContainer, CombinedHotbarAndInventoryContainer ->
                    new Container.ContainerSlot(this.inventoryContainer, slot);
            case ArmorContainer -> new Container.ContainerSlot(this.armorContainer, slot);
            case OffhandContainer -> new Container.ContainerSlot(this.offhandContainer, 0);
            case CursorContainer -> new Container.ContainerSlot(this.hudContainer, 0);
            case CraftingInputContainer -> new Container.ContainerSlot(this.hudContainer, slot);
            case DynamicContainer -> {
                final BundleContainer bundle = this.dynamicContainerRegistry.get(containerName);
                yield bundle == null ? null : new Container.ContainerSlot(bundle, slot);
            }
            default -> this.currentContainer == null ? null : new Container.ContainerSlot(this.currentContainer, slot);
        };
    }

    public BundleContainer getDynamicContainer(final FullContainerName containerName) {
        return this.dynamicContainerRegistry.get(containerName);
    }

    public void removeDynamicContainer(final FullContainerName containerName) {
        this.dynamicContainerRegistry.remove(containerName);
    }

    public void markPendingClose(final Container container) {
        if (this.pendingCloseContainer != null) {
            throw new IllegalStateException("There is already another container pending close");
        }
        if (this.currentContainer == container) {
            this.currentContainer = null;
        }
        this.pendingCloseContainer = container;
    }

    public void setCurrentContainerClosed(final boolean serverInitiated) {
        if (serverInitiated) {
            PacketFactory.sendBedrockContainerClose(this.user(), this.currentContainer.containerId(), ContainerType.NONE);
        }
        this.currentContainer = null;
        this.pendingCloseContainer = null;
    }

    public void closeCurrentForm() {
        if (this.currentForm == null) {
            throw new IllegalStateException("There is no form currently open");
        }
        final PacketWrapper modalFormResponse = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, this.user());
        modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, this.currentForm.leftInt()); // id
        modalFormResponse.write(Types.BOOLEAN, false); // has response
        modalFormResponse.write(Types.BOOLEAN, true); // has cancel reason
        modalFormResponse.write(Types.BYTE, (byte) ModalFormCancelReason.UserClosed.getValue()); // cancel reason
        modalFormResponse.sendToServer(BedrockProtocol.class);
        this.currentForm = null;
    }

    public void tick() {
        this.expireInventoryOpenWait();
        this.synchroniseDirtyContainers();

        if (this.currentContainer != null && this.currentContainer.position() != null) {
            if (this.currentContainer.type() == ContainerType.INVENTORY) return;

            final ChunkTracker chunkTracker = this.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
            final int blockState = chunkTracker.getBlockState(this.currentContainer.position());
            final String tag = blockStateRewriter.tag(blockState);
            if (!this.currentContainer.isValidBlockTag(tag)) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Closing " + this.currentContainer.type() + " because block state is not valid for container type: " + blockState);
                this.forceCloseCurrentContainer();
                return;
            }

            final EntityTracker entityTracker = this.user().get(EntityTracker.class);
            final Position3f containerPosition = new Position3f(this.currentContainer.position().x() + 0.5F, this.currentContainer.position().y() + 0.5F, this.currentContainer.position().z() + 0.5F);
            final Position3f playerPosition = entityTracker.getClientPlayer().position();
            if (playerPosition.distanceTo(containerPosition) > 6) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Closing " + this.currentContainer.type() + " because player is too far away (" + playerPosition.distanceTo(containerPosition) + " > 6)");
                this.forceCloseCurrentContainer();
            }
        }
    }

    /**
     * Sends the Java client any container it has not been told the truth about yet.
     *
     * <p>Every packet that changes an item goes through {@code Container.setItem}, but only some of
     * them go on to tell the client — and which ones do has turned out to depend on how the server
     * chose to report the change. A slot the server fills on pickup arrives differently from one it
     * fills because a request was granted, and a handler that forgets to forward its own change
     * leaves the player looking at an inventory that is wrong until something unrelated refreshes
     * it. That was the "my items only appear after I hit something" bug.</p>
     *
     * <p>Doing it here instead makes the client's view converge on the tracked state no matter which
     * packet moved it, and coalesces a burst of slot updates into one send. It costs one packet in
     * a tick where something changed and nothing in a tick where nothing did.</p>
     */
    private void synchroniseDirtyContainers() {
        if (this.user().getProtocolInfo().getServerState() != State.PLAY) {
            return; // Nothing to send to yet; the join will push the contents when it completes
        }

        // The player's four containers all render into the same Java window, and the inventory
        // container's contents already include the armour, offhand and crafting slots -- so one
        // send covers all four, and all four have to be cleared together.
        if (this.inventoryContainer.isDirty() || this.offhandContainer.isDirty()
                || this.armorContainer.isDirty() || this.hudContainer.isDirty()) {
            this.inventoryContainer.markClean();
            this.offhandContainer.markClean();
            this.armorContainer.markClean();
            this.hudContainer.markClean();
            PacketFactory.sendJavaContainerSetContent(this.user(), this.inventoryContainer);
        }

        final Container container = this.currentContainer;
        if (container != null && container.isDirty()) {
            container.markClean();
            if (container.type() != ContainerType.INVENTORY) { // That one is the window above, already sent
                PacketFactory.sendJavaContainerSetContent(this.user(), container);
            }
        }
    }

    public boolean isContainerOpen() {
        return this.currentContainer != null || this.pendingCloseContainer != null;
    }

    public boolean isAnyScreenOpen() {
        return this.isContainerOpen() || this.currentForm != null;
    }

    public InventoryContainer getInventoryContainer() {
        return this.inventoryContainer;
    }

    public OffhandContainer getOffhandContainer() {
        return this.offhandContainer;
    }

    public ArmorContainer getArmorContainer() {
        return this.armorContainer;
    }

    public HudContainer getHudContainer() {
        return this.hudContainer;
    }

    public Container getCurrentContainer() {
        return this.currentContainer;
    }

    public void setCurrentContainer(final Container container) {
        if (this.isContainerOpen()) {
            throw new IllegalStateException("There is already another container open");
        }
        this.currentContainer = container;
        this.replayDeferredClick();
    }

    /**
     * Holds the click that opened the player's own inventory until the server agrees it is open.
     *
     * <p>Java clients open their inventory entirely client-side — no packet is sent — so the first
     * the server hears of it is a click on a screen it does not believe exists, and it refuses the
     * move. Announcing the screen and replaying the click once it has been acknowledged is what a
     * Bedrock client's ordering amounts to, and when the server plays along it costs the player
     * nothing: their first click works like the rest.</p>
     *
     * <p>Nothing here assumes it will play along. A server that never answers would otherwise make
     * the inventory permanently dead and silent, so the wait expires (see
     * {@link #expireInventoryOpenWait()}) and every click after that is handled directly — refused,
     * perhaps, but refused out loud.</p>
     *
     * <p>Only one is held. A player clicking again before the server answers is asking for the newer
     * click, and stale ones would be applied against an inventory that has moved on.</p>
     */
    public boolean deferClickUntilInventoryOpens(final int revision, final short slot, final byte button, final ContainerInput action) {
        this.announceInventoryScreen();
        if (this.awaitingInventoryOpen || this.inventoryOpenUnanswered) {
            // Already asked and still waiting, or asked before and learnt that this server does not
            // answer. Holding the click would mean silently eating every click the player makes,
            // which is worse than one the server might refuse — the caller handles it directly.
            return false;
        }
        this.awaitingInventoryOpen = true;
        this.awaitingInventoryOpenTicks = 0;
        this.deferredClick = new DeferredClick(revision, slot, button, action);
        return true;
    }

    /**
     * Tells the server the player is looking at their inventory.
     *
     * <p>A Bedrock client sends this when the screen opens; a Java client sends nothing at all, so
     * without it the server has no reason to believe the screen exists and refuses item stack
     * requests against it.</p>
     */
    private void announceInventoryScreen() {
        final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, this.user());
        interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.OpenInventory.getValue()); // action
        interact.write(BedrockTypes.UNSIGNED_VAR_LONG, this.user().get(EntityTracker.class).getClientPlayer().runtimeId()); // target entity runtime id
        interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
        interact.sendToServer(BedrockProtocol.class);

        if (!this.inventoryScreenAnnounced) {
            this.inventoryScreenAnnounced = true;
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Announced the player's own inventory screen to the server (INTERACT OpenInventory). "
                    + "The server should answer with CONTAINER_OPEN for container id 0; clicks in the inventory are refused until it does.");
        }
    }

    /**
     * Stops waiting on a server that is not going to answer.
     *
     * <p>Holding the click forever would make the inventory look permanently dead, and it would
     * look that way in silence. Replaying it means the player sees either the move or — via the
     * rejection log — the reason the server would not allow it.</p>
     */
    private void expireInventoryOpenWait() {
        if (!this.awaitingInventoryOpen) {
            return;
        }
        if (++this.awaitingInventoryOpenTicks < INVENTORY_OPEN_TIMEOUT_TICKS) {
            return;
        }

        this.inventoryOpenUnanswered = true;
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "The server did not answer the inventory screen announcement within "
                + INVENTORY_OPEN_TIMEOUT_TICKS + " ticks. Handling inventory clicks directly from now on; if the server refuses them, "
                + "the rejection is logged with its reason.");

        final DeferredClick click = this.deferredClick;
        this.deferredClick = null;
        this.awaitingInventoryOpen = false;
        if (click != null && !this.inventoryContainer.handleClick(click.revision(), click.slot(), click.button(), click.action())) {
            PacketFactory.sendJavaContainerSetContent(this.user(), this.inventoryContainer);
        }
    }

    private void replayDeferredClick() {
        final DeferredClick click = this.deferredClick;
        this.deferredClick = null;
        this.awaitingInventoryOpen = false;
        if (click == null || this.currentContainer == null) {
            return;
        }
        if (this.currentContainer.type() != ContainerType.INVENTORY) {
            // A chest won the race to open. Replaying a click meant for the player's own window
            // against someone else's container would move the wrong item, so drop it and put the
            // window the player is actually looking at back the way it is.
            PacketFactory.sendJavaContainerSetContent(this.user(), this.inventoryContainer);
            return;
        }
        if (!this.currentContainer.handleClick(click.revision(), click.slot(), click.button(), click.action())) {
            PacketFactory.sendJavaContainerSetContent(this.user(), this.inventoryContainer);
        }
    }

    private record DeferredClick(int revision, short slot, byte button, ContainerInput action) {
    }

    public Container getPendingCloseContainer() {
        return this.pendingCloseContainer;
    }

    public IntObjectPair<Form> getCurrentForm() {
        return this.currentForm;
    }

    public void setCurrentForm(final IntObjectPair<Form> currentForm) {
        this.currentForm = currentForm;
    }

    private void forceCloseCurrentContainer() {
        this.markPendingClose(this.currentContainer);
        PacketFactory.sendJavaContainerClose(this.user(), this.pendingCloseContainer.javaContainerId());
        PacketFactory.sendBedrockContainerClose(this.user(), this.pendingCloseContainer.containerId(), ContainerType.NONE);
    }

}
