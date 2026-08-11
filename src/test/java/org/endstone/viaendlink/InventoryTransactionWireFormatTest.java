package org.endstone.viaendlink;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clientbound inventory transaction, as the bridge will read it.
 *
 * <p>This is the only packet the backend uses to say "your inventory now holds this" when nothing
 * asked it to — picking an item up off the ground being the case that happens constantly. There is
 * no item stack response for that, and no inventory slot packet either, so misreading this one does
 * not degrade anything visibly: the player's count simply stays at the old value until something
 * unrelated forces a resync.</p>
 *
 * <p>Which is exactly what a missing presence boolean did. The action list is preceded by one, the
 * same as the transaction type before it, and the vendored ViaBedrock read the list without it —
 * taking the boolean itself as the list length and every field after it one byte early. The source
 * type then came out of the real action count and named a source the handler ignores, so the update
 * was dropped in silence.</p>
 *
 * <p>These assertions walk the buffer in the order that reader walks it. If a future protocol moves
 * one of these booleans, this fails here rather than as an inventory that lags a click behind.</p>
 */
class InventoryTransactionWireFormatTest {

    @Test
    void aServerSentSlotChangeHasTheLayoutTheBridgeReads() {
        InventoryTransactionPacket packet = new InventoryTransactionPacket();
        packet.setLegacyRequestId(0);
        packet.setTransactionType(InventoryTransactionType.NORMAL);
        packet.getActions().add(new InventoryActionData(
                InventorySource.fromContainerWindowId(0), 4, ItemData.AIR, ItemData.AIR));

        ByteBuf buffer = encode(Bedrock_v1001.CODEC, packet);

        assertEquals(0, VarInts.readInt(buffer), "legacy request id");
        assertFalse(buffer.readBoolean(), "no legacy slots for request id 0");
        assertTrue(buffer.readBoolean(), "the transaction type is present");
        assertEquals(InventoryTransactionType.NORMAL.ordinal(), VarInts.readUnsignedInt(buffer), "transaction type");
        assertTrue(buffer.readBoolean(),
                "the action list is preceded by its own presence boolean, and reading the list without it "
                        + "consumes this byte as the list length");
        assertEquals(1, VarInts.readUnsignedInt(buffer), "action count");

        assertEquals(InventorySource.Type.CONTAINER.id(), VarInts.readUnsignedInt(buffer), "source type");
        assertTrue(buffer.readBoolean(), "the container id is present");
        assertTrue(buffer.readBoolean(), "and set");
        assertEquals(0, buffer.readByte(), "container id, which is the player's own inventory");
        assertTrue(buffer.readBoolean(), "the flag field is present");
        assertFalse(buffer.readBoolean(), "but unset, because flags belong to world interactions");
        assertEquals(4, VarInts.readUnsignedInt(buffer), "slot");

        readAirItem(buffer, "from item");
        readAirItem(buffer, "to item");
        assertFalse(buffer.isReadable(), "a normal transaction ends with its actions");
    }

    /**
     * The slot list belongs to a narrow range of request ids, and the reader only looks for it
     * under those. Announcing it for any other id writes bytes nothing reads back, which puts every
     * field after them at the wrong offset.
     */
    @Test
    void theLegacySlotListOnlyExistsForTheIdsThatCarryIt() {
        assertFalse(hasLegacySlots(0), "0 means the transaction answers nothing");
        assertFalse(hasLegacySlots(-1), "-1 is the id used when there is no legacy request");
        assertFalse(hasLegacySlots(-3), "odd ids carry no slots");
        assertTrue(hasLegacySlots(-2), "negative and even is the shape that does");
        assertTrue(hasLegacySlots(-4), "and so is the next one");
    }

    private static boolean hasLegacySlots(int legacyRequestId) {
        InventoryTransactionPacket packet = new InventoryTransactionPacket();
        packet.setLegacyRequestId(legacyRequestId);
        packet.setTransactionType(InventoryTransactionType.NORMAL);

        ByteBuf buffer = encode(Bedrock_v1001.CODEC, packet);
        VarInts.readInt(buffer); // legacy request id
        return buffer.readBoolean();
    }

    /** Air is the full descriptor with everything zeroed, not a short form. */
    private static void readAirItem(ByteBuf buffer, String what) {
        assertEquals(0, buffer.readShortLE(), what + ": runtime id");
        assertEquals(0, buffer.readShortLE(), what + ": count");
        assertEquals(0, VarInts.readUnsignedInt(buffer), what + ": damage");
        assertFalse(buffer.readBoolean(), what + ": no stack network id");
        assertEquals(0, VarInts.readUnsignedInt(buffer), what + ": block runtime id");
        assertEquals(0, VarInts.readUnsignedInt(buffer), what + ": no extra data, not even an empty blob");
    }

    private static ByteBuf encode(BedrockCodec codec, InventoryTransactionPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        BedrockPacketDefinition<InventoryTransactionPacket> definition = codec.getPacketDefinition(InventoryTransactionPacket.class);
        definition.getSerializer().serialize(buffer, codec.createHelper(), packet);
        return buffer;
    }

}
