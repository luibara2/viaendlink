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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.ItemStackRequestAction;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Sends {@code ItemStackRequest}s and reconciles what comes back.
 *
 * <p>Server-authoritative inventory is a request/response protocol, not a stream of updates, and
 * that shapes everything here. The server's {@code ItemStackResponse} reports the resulting
 * <em>count</em> and <em>stack network id</em> of each touched slot — never which item is in it. So
 * the response alone cannot tell us what the inventory now looks like; it can only confirm or deny
 * what we already believed.</p>
 *
 * <p>Hence the three-step cycle, which is what a real Bedrock client does too:</p>
 *
 * <ol>
 *   <li><b>Snapshot</b> the containers about to change, so there is something to fall back to.</li>
 *   <li><b>Predict</b>: apply the move locally and show it to the Java client at once. Waiting for
 *       the round trip instead would make every click feel like it had been ignored.</li>
 *   <li><b>Reconcile</b>: on success, take the counts and stack ids from the response, which is
 *       where a prediction that was slightly off gets corrected. On failure, restore the snapshot —
 *       the server did nothing, so neither did we.</li>
 * </ol>
 *
 * <p>Restoring on failure is the important half. Without it a rejected request leaves the Java
 * client showing an item that does not exist, which is worse than the click not working: the player
 * builds on a lie until something forces a resync.</p>
 */
public class ItemStackRequestTracker extends StoredObject {

    /**
     * Request ids must be <b>negative and odd</b>: -1, -3, -5, …
     *
     * <p>This is not a convention to be polite about, it is validated. A positive id makes the
     * server reject the packet outright and <em>terminate the connection</em>:</p>
     *
     * <pre>
     * packet 147 rejected (terminating connection): expected a valid ItemStackRequestId
     * readNoHeader failed! packetId: 147
     * </pre>
     *
     * <p>Which the player experiences as being kicked the moment they pick anything up in their
     * inventory. The sign is what separates a client's request from a server-generated id; the
     * parity is checked with it (see the note in pmmp/BedrockProtocol's
     * {@code readItemStackNetIdVariant}: "ItemStackRequest request ID is negative and odd").</p>
     */
    private static final int REQUEST_ID_STEP = -2;
    private static final int FIRST_REQUEST_ID = -1;

    /** Far above any plausible number of clicks in flight; exceeding it means nothing is coming back. */
    private static final int MAX_IN_FLIGHT = 64;

    /** Enough to diagnose why clicks are being refused, few enough not to fill the log if they all are. */
    private static final int MAX_REJECTIONS_LOGGED = 10;

    private final Map<Integer, PendingRequest> pending = new LinkedHashMap<>();
    private int nextRequestId = FIRST_REQUEST_ID;
    private boolean warnedAboutUnansweredRequests;
    private int rejectionsLogged;

    public ItemStackRequestTracker(final UserConnection user) {
        super(user);
    }

    /**
     * Sends one request and remembers what it was expected to change.
     *
     * @param actions   the steps, in the order the server should apply them
     * @param predicted a description of the resulting state to apply locally, or null to leave the
     *                  local model alone and simply wait for the response
     * @param affected  every container the request touches, so a rejection can put them all back
     */
    public void send(final List<ItemStackRequestAction> actions, final Runnable predicted, final Container... affected) {
        if (actions.isEmpty()) {
            return;
        }

        final int requestId = this.nextRequestId;
        this.nextRequestId += REQUEST_ID_STEP;
        if (this.nextRequestId > 0) { // Wrapped after ~1 billion clicks; start over rather than go positive
            this.nextRequestId = FIRST_REQUEST_ID;
        }

        final PendingRequest request = new PendingRequest(affected);
        this.pending.put(requestId, request);
        // A response that never comes would otherwise pin every snapshot it took. The cap is far
        // above any plausible in-flight count, so reaching it does not mean the player is fast --
        // it means the server is not answering item stack requests at all, and every click is about
        // to look like it half-worked. Say so once, because the symptom on its own points nowhere.
        while (this.pending.size() > MAX_IN_FLIGHT) {
            final Integer oldest = this.pending.keySet().iterator().next();
            this.pending.remove(oldest);
            if (!this.warnedAboutUnansweredRequests) {
                this.warnedAboutUnansweredRequests = true;
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "The server has not answered any of the last "
                        + MAX_IN_FLIGHT + " item stack requests. Inventory changes will not take effect.");
            }
        }

        final PacketWrapper itemStackRequest = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, this.user());
        itemStackRequest.write(BedrockTypes.UNSIGNED_VAR_INT, 1); // request count
        itemStackRequest.write(BedrockTypes.VAR_INT, requestId); // request id
        itemStackRequest.write(BedrockTypes.UNSIGNED_VAR_INT, actions.size()); // action count
        for (ItemStackRequestAction action : actions) {
            // The id goes into the actions as well as the header: a slot naming a stack this request
            // has yet to create refers to it by the request's own id.
            action.write(itemStackRequest, requestId);
        }
        itemStackRequest.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // filter string count
        itemStackRequest.write(BedrockTypes.INT_LE, -1); // filter cause: none
        itemStackRequest.sendToServer(BedrockProtocol.class);

        if (predicted != null) {
            predicted.run();
        }
        // Nothing is sent to the Java client from here. Every change made above went through
        // Container.setItem, which marks the container dirty, and InventoryTracker's tick pushes it
        // on the same tick. Keeping one owner of "tell the client" is what stops the two paths
        // disagreeing -- which is the shape of bug this whole class exists to avoid.
    }

    /**
     * Applies a response. {@code slotUpdates} is keyed by container and holds the new count and
     * stack id for each slot the server touched.
     */
    public void handleResponse(final int requestId, final String result, final boolean success, final List<SlotUpdate> slotUpdates) {
        final PendingRequest request = this.pending.remove(requestId);
        if (request == null) { // A response to a request we no longer hold, or one we never sent
            return;
        }

        if (success) {
            for (SlotUpdate update : slotUpdates) {
                final Container container = update.container();
                if (update.slot() < 0 || update.slot() >= container.size()) {
                    continue;
                }
                final BedrockItem item = container.getItem(update.slot());
                if (update.count() <= 0) {
                    container.setItem(update.slot(), BedrockItem.empty());
                    continue;
                }
                // Identity stays as predicted -- the response never carries it -- but the count and
                // the stack id are the server's, and the stack id is what the next request is
                // validated against. A prediction that got the count wrong is corrected here.
                item.setAmount(update.count());
                item.setNetId(update.stackNetworkId());
                container.setItem(update.slot(), item);
            }
        } else {
            request.restore();
            // Loud, and only for the first few. A rejection is the player watching an item snap back
            // for no visible reason, and the result name is the only thing that says why -- whether
            // the stack ids were stale (InvalidItemNetId), the screen was not open as far as the
            // server was concerned (ActionRequestNotAllowed), or the move itself was refused.
            if (this.rejectionsLogged < MAX_REJECTIONS_LOGGED) {
                this.rejectionsLogged++;
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "The server rejected item stack request "
                        + requestId + ": " + result + ". The affected slots have been rolled back."
                        + (this.rejectionsLogged == MAX_REJECTIONS_LOGGED ? " Further rejections will not be logged." : ""));
            }
        }
    }

    /** One slot the server reported back, already resolved to the container it belongs to. */
    public record SlotUpdate(Container container, int slot, int count, int stackNetworkId) {
    }

    /** The containers a request touched, with the contents they had before it was applied. */
    private record PendingRequest(Container[] containers, BedrockItem[][] snapshots) {

        PendingRequest(final Container[] containers) {
            this(containers, snapshot(containers));
        }

        private static BedrockItem[][] snapshot(final Container[] containers) {
            final BedrockItem[][] snapshots = new BedrockItem[containers.length][];
            for (int i = 0; i < containers.length; i++) {
                if (containers[i] == null) {
                    continue;
                }
                final BedrockItem[] items = containers[i].getItems();
                for (int slot = 0; slot < items.length; slot++) {
                    items[slot] = items[slot].copy();
                }
                snapshots[i] = items;
            }
            return snapshots;
        }

        void restore() {
            for (int i = 0; i < this.containers.length; i++) {
                if (this.containers[i] == null || this.snapshots[i] == null) {
                    continue;
                }
                this.containers[i].setItems(this.snapshots[i]);
            }
        }
    }

}
