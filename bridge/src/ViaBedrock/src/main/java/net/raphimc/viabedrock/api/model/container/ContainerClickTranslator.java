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
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.ItemStackRequestAction;
import net.raphimc.viabedrock.protocol.model.ItemStackRequestSlot;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.storage.ItemStackRequestTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Expresses a Java inventory click as the Bedrock item movements that would have the same effect.
 *
 * <p>The two editions disagree about who decides what a click means. Java sends the click — "left
 * button, slot 12, shift held" — and the server works out the consequence. Bedrock sends the
 * consequence — "take 3 items from here to there" — and the server only checks that it is legal.
 * Translating therefore means implementing the part of the Java client's inventory logic that
 * decides what a click does, because nothing downstream will do it for us.</p>
 *
 * <p>Everything happens through the cursor, which on Bedrock is slot 0 of the player UI container
 * rather than a field of its own. The remaining asymmetry is that Java's shift-click has no Bedrock
 * counterpart at all: a real Bedrock client picks the destination slots itself and sends ordinary
 * moves, so {@link #quickMove} does the same search here.</p>
 *
 * <p>Clicks this cannot express return false and the caller resyncs the window. That is the honest
 * outcome — the player sees the click do nothing, rather than seeing it appear to work against a
 * server that never agreed.</p>
 */
public final class ContainerClickTranslator {

    /** Java's sentinel for "clicked outside the window", which means throw the cursor on the floor. */
    private static final short SLOT_OUTSIDE = -999;
    /** Java's button number for the offhand swap (the F key), where 0-8 are the hotbar number keys. */
    private static final byte SWAP_OFFHAND_BUTTON = 40;
    /**
     * Bedrock sends no stack size limits, so merging has to assume one. 64 is right for all but a
     * handful of items; where it is wrong the server rejects the request and the pre-click state is
     * restored, so the cost of guessing is a click that does nothing rather than a desync.
     */
    private static final int ASSUMED_MAX_STACK_SIZE = 64;

    private ContainerClickTranslator() {
    }

    public static boolean translate(final UserConnection user, final Container window, final short javaSlot, final byte button, final ContainerInput action) {
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final Container hudContainer = inventoryTracker.getHudContainer();
        final ItemStackRequestSlot cursorSlot = hudContainer.requestSlot(HudSlots.CURSOR);
        if (cursorSlot == null) {
            return false;
        }
        final BedrockItem cursorItem = hudContainer.getItem(HudSlots.CURSOR);

        return switch (action) {
            case PICKUP -> pickup(user, window, hudContainer, javaSlot, button, cursorItem);
            case THROW -> throwOut(user, window, hudContainer, javaSlot, button, cursorItem);
            case SWAP -> swap(user, window, javaSlot, button);
            case QUICK_MOVE -> quickMove(user, window, javaSlot);
            // CLONE is creative middle-click, which needs the creative item network id rather than a
            // move; QUICK_CRAFT is a multi-click drag with state of its own; PICKUP_ALL is the
            // double-click gather. None has a single-request equivalent, so they resync instead.
            case CLONE, QUICK_CRAFT, PICKUP_ALL -> false;
        };
    }

    /** Left or right click: move between the clicked slot and the cursor. */
    private static boolean pickup(final UserConnection user, final Container window, final Container hudContainer,
                                  final short javaSlot, final byte button, final BedrockItem cursorItem) {
        final boolean rightClick = button == 1;

        if (javaSlot == SLOT_OUTSIDE) { // Clicking the world behind the window throws the cursor
            if (cursorItem.isEmpty()) {
                return true; // Nothing held, nothing happens -- and no need to tell the server so
            }
            final int count = rightClick ? 1 : cursorItem.amount();
            return dropFrom(user, hudContainer, HudSlots.CURSOR, count);
        }

        final Container.ContainerSlot target = window.resolveJavaSlot(javaSlot);
        if (target == null) {
            return false;
        }
        final ItemStackRequestSlot targetSlot = target.container().requestSlot(target.slot());
        final ItemStackRequestSlot cursorSlot = hudContainer.requestSlot(HudSlots.CURSOR);
        if (targetSlot == null || cursorSlot == null) {
            return false;
        }
        final BedrockItem targetItem = target.container().getItem(target.slot());

        if (cursorItem.isEmpty()) {
            if (targetItem.isEmpty()) {
                return true; // Clicking an empty slot with an empty hand
            }
            // Right click takes the larger half, which is what Java does for odd stacks
            final int count = rightClick ? (targetItem.amount() + 1) / 2 : targetItem.amount();
            return send(user,
                    List.of(new ItemStackRequestAction.Take(count, targetSlot, cursorSlot)),
                    () -> move(target.container(), target.slot(), hudContainer, HudSlots.CURSOR, count),
                    target.container(), hudContainer);
        }

        if (targetItem.isEmpty() || !cursorItem.isDifferent(targetItem)) {
            final int count = rightClick ? 1 : cursorItem.amount();
            return send(user,
                    List.of(new ItemStackRequestAction.Place(count, cursorSlot, targetSlot)),
                    () -> move(hudContainer, HudSlots.CURSOR, target.container(), target.slot(), count),
                    target.container(), hudContainer);
        }

        if (rightClick) {
            return true; // Java does nothing when right-clicking a different item while holding one
        }
        return send(user,
                List.of(new ItemStackRequestAction.Swap(cursorSlot, targetSlot)),
                () -> swapItems(hudContainer, HudSlots.CURSOR, target.container(), target.slot()),
                target.container(), hudContainer);
    }

    /** The throw key: button 0 drops a single item, button 1 the whole stack. */
    private static boolean throwOut(final UserConnection user, final Container window, final Container hudContainer,
                                    final short javaSlot, final byte button, final BedrockItem cursorItem) {
        if (javaSlot == SLOT_OUTSIDE) {
            if (cursorItem.isEmpty()) {
                return true;
            }
            return dropFrom(user, hudContainer, HudSlots.CURSOR, button == 1 ? cursorItem.amount() : 1);
        }

        final Container.ContainerSlot target = window.resolveJavaSlot(javaSlot);
        if (target == null) {
            return false;
        }
        final BedrockItem targetItem = target.container().getItem(target.slot());
        if (targetItem.isEmpty()) {
            return true;
        }
        return dropFrom(user, target.container(), target.slot(), button == 1 ? targetItem.amount() : 1);
    }

    /** A hotbar number key or the offhand key: exchange the clicked slot with that destination. */
    private static boolean swap(final UserConnection user, final Container window, final short javaSlot, final byte button) {
        final Container.ContainerSlot target = window.resolveJavaSlot(javaSlot);
        if (target == null) {
            return false;
        }

        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final Container destinationContainer;
        final int destinationSlot;
        if (button == SWAP_OFFHAND_BUTTON) {
            destinationContainer = inventoryTracker.getOffhandContainer();
            destinationSlot = 0;
        } else if (button >= 0 && button < 9) {
            destinationContainer = inventoryTracker.getInventoryContainer();
            destinationSlot = button; // Bedrock's hotbar is slots 0-8 of the inventory container
        } else {
            return false;
        }

        if (destinationContainer == target.container() && destinationSlot == target.slot()) {
            return true;
        }
        final ItemStackRequestSlot source = target.container().requestSlot(target.slot());
        final ItemStackRequestSlot destination = destinationContainer.requestSlot(destinationSlot);
        if (source == null || destination == null) {
            return false;
        }

        return send(user,
                List.of(new ItemStackRequestAction.Swap(source, destination)),
                () -> swapItems(target.container(), target.slot(), destinationContainer, destinationSlot),
                target.container(), destinationContainer);
    }

    /**
     * Shift-click: move the whole stack to the other half of the window.
     *
     * <p>No Bedrock action means "shift-click", so the destination search Java's server would do is
     * done here instead: fill compatible stacks first, then take empty slots, exactly as vanilla
     * does. Each partial move is its own action and they travel in one request, so the server
     * applies all of them or none.</p>
     */
    private static boolean quickMove(final UserConnection user, final Container window, final short javaSlot) {
        final Container.ContainerSlot source = window.resolveJavaSlot(javaSlot);
        if (source == null) {
            return false;
        }
        final BedrockItem sourceItem = source.container().getItem(source.slot());
        if (sourceItem.isEmpty()) {
            return true;
        }

        final List<Container.ContainerSlot> destinations = quickMoveDestinations(user, window, javaSlot);
        if (destinations == null) {
            return false;
        }

        final List<ItemStackRequestAction> actions = new ArrayList<>();
        final List<int[]> moves = new ArrayList<>(); // {destinationIndex, count} in destination order
        int remaining = sourceItem.amount();

        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            final boolean mergeOnly = pass == 0;
            for (int i = 0; i < destinations.size() && remaining > 0; i++) {
                final Container.ContainerSlot destination = destinations.get(i);
                if (destination.container() == source.container() && destination.slot() == source.slot()) {
                    continue;
                }
                final BedrockItem destinationItem = destination.container().getItem(destination.slot());
                final int room;
                if (mergeOnly) {
                    if (destinationItem.isEmpty() || destinationItem.isDifferent(sourceItem)) {
                        continue;
                    }
                    room = ASSUMED_MAX_STACK_SIZE - destinationItem.amount();
                } else {
                    if (!destinationItem.isEmpty()) {
                        continue;
                    }
                    room = ASSUMED_MAX_STACK_SIZE;
                }
                if (room <= 0) {
                    continue;
                }

                final int count = Math.min(room, remaining);
                final ItemStackRequestSlot from = source.container().requestSlot(source.slot());
                final ItemStackRequestSlot to = destination.container().requestSlot(destination.slot());
                if (from == null || to == null) {
                    return false;
                }
                actions.add(new ItemStackRequestAction.Place(count, from, to));
                moves.add(new int[]{i, count});
                remaining -= count;
            }
        }

        if (actions.isEmpty()) {
            return true; // Nowhere for it to go, which is a no-op rather than a failure
        }

        return send(user, actions, () -> {
            for (int[] entry : moves) {
                final Container.ContainerSlot destination = destinations.get(entry[0]);
                move(source.container(), source.slot(), destination.container(), destination.slot(), entry[1]);
            }
        }, affectedOf(source, destinations, moves));
    }

    /**
     * The slots a shift-click may move into, in the order vanilla would try them.
     *
     * <p>Java's rule is "to the other half": from the container to the player's inventory, or from
     * the player's inventory into the container. In the player's own window there is no container
     * half, so it is the inventory and the hotbar that swap roles.</p>
     */
    private static List<Container.ContainerSlot> quickMoveDestinations(final UserConnection user, final Container window, final short javaSlot) {
        final List<Container.ContainerSlot> destinations = new ArrayList<>();
        final InventoryContainer inventory = user.get(InventoryTracker.class).getInventoryContainer();

        if (window instanceof InventoryContainer) {
            if (javaSlot >= 36 && javaSlot <= 44) { // Hotbar goes up into the main inventory
                for (int slot = 9; slot < 36; slot++) {
                    destinations.add(new Container.ContainerSlot(inventory, slot));
                }
            } else { // Everything else in the player window goes down to the hotbar
                for (int slot = 0; slot < 9; slot++) {
                    destinations.add(new Container.ContainerSlot(inventory, slot));
                }
                if (javaSlot < 9 || javaSlot == 45) { // Crafting, armour and offhand also reach the inventory
                    for (int slot = 9; slot < 36; slot++) {
                        destinations.add(new Container.ContainerSlot(inventory, slot));
                    }
                }
            }
            return destinations;
        }

        if (javaSlot >= 0 && javaSlot < window.size()) { // Out of the container, into the player
            for (int slot = 9; slot < 36; slot++) {
                destinations.add(new Container.ContainerSlot(inventory, slot));
            }
            for (int slot = 0; slot < 9; slot++) {
                destinations.add(new Container.ContainerSlot(inventory, slot));
            }
        } else { // Out of the player, into the container
            for (int slot = 0; slot < window.size(); slot++) {
                destinations.add(new Container.ContainerSlot(window, slot));
            }
        }
        return destinations;
    }

    private static Container[] affectedOf(final Container.ContainerSlot source, final List<Container.ContainerSlot> destinations,
                                          final List<int[]> moves) {
        final List<Container> affected = new ArrayList<>();
        affected.add(source.container());
        for (int[] entry : moves) {
            final Container container = destinations.get(entry[0]).container();
            if (!affected.contains(container)) {
                affected.add(container);
            }
        }
        return affected.toArray(new Container[0]);
    }

    private static boolean dropFrom(final UserConnection user, final Container container, final int slot, final int count) {
        final ItemStackRequestSlot source = container.requestSlot(slot);
        if (source == null) {
            return false;
        }
        return send(user,
                List.of(new ItemStackRequestAction.Drop(count, source, false)),
                () -> take(container, slot, count),
                container);
    }

    private static boolean send(final UserConnection user, final List<ItemStackRequestAction> actions,
                                final Runnable prediction, final Container... affected) {
        user.get(ItemStackRequestTracker.class).send(actions, prediction, affected);
        return true;
    }

    // --- local prediction ------------------------------------------------------------------
    // Applied straight away so the click feels immediate, then confirmed or undone by the
    // server's response. None of it is authoritative.

    private static void move(final Container from, final int fromSlot, final Container to, final int toSlot, final int count) {
        final BedrockItem source = from.getItem(fromSlot);
        if (source.isEmpty()) {
            return;
        }
        final BedrockItem destination = to.getItem(toSlot);
        final BedrockItem moved = source.copy();
        moved.setAmount(destination.isEmpty() ? count : destination.amount() + count);
        to.setItem(toSlot, moved);
        take(from, fromSlot, count);
    }

    private static void take(final Container from, final int fromSlot, final int count) {
        final BedrockItem source = from.getItem(fromSlot);
        if (source.amount() <= count) {
            from.setItem(fromSlot, BedrockItem.empty());
        } else {
            final BedrockItem remainder = source.copy();
            remainder.setAmount(source.amount() - count);
            from.setItem(fromSlot, remainder);
        }
    }

    private static void swapItems(final Container first, final int firstSlot, final Container second, final int secondSlot) {
        final BedrockItem firstItem = first.getItem(firstSlot).copy();
        final BedrockItem secondItem = second.getItem(secondSlot).copy();
        first.setItem(firstSlot, secondItem);
        second.setItem(secondSlot, firstItem);
    }

    /** Fixed positions inside the 54-slot player UI container. */
    public static final class HudSlots {

        /** The stack the player is holding on the pointer. */
        public static final int CURSOR = 0;
        /** The 2x2 crafting grid, which Java addresses as slots 1-4 of the player window. */
        public static final int CRAFTING_INPUT_FIRST = 28;
        public static final int CRAFTING_INPUT_LAST = 31;

        private HudSlots() {
        }
    }

}
