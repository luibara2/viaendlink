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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;

/**
 * Any block that simply stores items: chests, barrels, shulker boxes, hoppers, droppers, crafters.
 *
 * <p>They differ only in how many slots they have and which of Bedrock's slot names addresses them,
 * so one class covers all of them. Nothing here interprets what the items <em>mean</em> — a furnace
 * or an anvil would, which is why those are not this.</p>
 */
public class ChestContainer extends Container {

    private final ContainerEnumName slotName;

    public ChestContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position, final int size) {
        this(user, containerId, ContainerType.CONTAINER, title, position, size, ContainerEnumName.LevelEntityContainer,
                CustomBlockTags.CHEST, CustomBlockTags.TRAPPED_CHEST);
    }

    public ChestContainer(final UserConnection user, final byte containerId, final ContainerType type, final TextComponent title,
                          final BlockPosition position, final int size, final ContainerEnumName slotName, final String... validBlockTags) {
        super(user, containerId, type, title, position, size, validBlockTags);
        this.slotName = slotName;
    }

    @Override
    protected ContainerEnumName requestContainerName(final int slot) {
        return this.slotName;
    }

}
