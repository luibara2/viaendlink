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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * One end of an item movement, as the server-authoritative inventory addresses it.
 *
 * <p>Three things identify a slot on Bedrock, and all three have to agree with what the server
 * believes or it rejects the whole request:</p>
 *
 * <ul>
 *   <li>the {@link FullContainerName} — <em>which kind of slot</em> this is, which is not the same
 *       thing as which container it lives in. The player's own inventory is addressed as two
 *       different names, {@code HotbarContainer} for 0-8 and {@code InventoryContainer} for 9-35,
 *       even though both are container id 0;</li>
 *   <li>the slot index <em>within that name</em>, which is not always the index within the
 *       container. The 2x2 crafting grid is slots 28-31 of the player UI container and is addressed
 *       as {@code CraftingInputContainer} slots 28-31, while the offhand is slot 0 of its own
 *       container and is addressed as slot <em>1</em>;</li>
 *   <li>the stack network id, the server's handle for the exact item stack sitting there. It is
 *       what makes the request safe to apply out of order: if the slot has changed since we last
 *       heard about it, the id no longer matches and the server refuses rather than acting on a
 *       stale view.</li>
 * </ul>
 *
 * @param containerName which kind of slot, and for dynamic containers (bundles) which instance
 * @param slot          index within that name
 * @param stackNetworkId the server's id for the stack currently there, or 0 when it never gave one
 */
public record ItemStackRequestSlot(FullContainerName containerName, int slot, int stackNetworkId) {

    public ItemStackRequestSlot(final ContainerEnumName containerName, final int slot, final int stackNetworkId) {
        this(new FullContainerName(containerName, null), slot, stackNetworkId);
    }

    public void write(final PacketWrapper wrapper) {
        wrapper.write(BedrockTypes.FULL_CONTAINER_NAME, this.containerName); // container name
        wrapper.write(Types.BYTE, (byte) this.slot); // slot
        wrapper.write(BedrockTypes.VAR_INT, this.stackNetworkId); // stack network id
    }

}
