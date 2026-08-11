package org.endstone.viaendlink;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The reply to an item stack request, as the bridge will read it.
 *
 * <p>Server-authoritative inventory is a request/response protocol, so this packet is the only place
 * the backend says whether a player's click actually happened. Misreading it is not a cosmetic
 * failure: a response parsed as a rejection rolls back a move the server did make, and one parsed as
 * a success leaves the player looking at items the server does not think they have.</p>
 *
 * <p>1.26.40 and 1.26.30 shape it differently — 2168 writes two presence booleans between the
 * request id and the containers, and 1001 writes the containers only when the result was OK — so
 * this really is translated across the hop rather than passed through. These tests pin the 1001 side
 * byte for byte, because that is what the vendored ViaBedrock reads and it reads it by hand.</p>
 */
class ItemStackResponseWireFormatTest {

    @Test
    void aSuccessfulResponseHasTheLayoutTheBridgeReads() {
        ItemStackResponsePacket packet = response(ItemStackResponseStatus.OK, 7,
                new ItemStackResponseContainer(ContainerSlotType.INVENTORY,
                        List.of(new ItemStackResponseSlot(14, 0, 3, 901, "", 0, "")),
                        new FullContainerName(ContainerSlotType.INVENTORY, null)));

        ByteBuf buffer = encode(Bedrock_v1001.CODEC, packet);

        assertEquals(1, VarInts.readUnsignedInt(buffer), "response count");
        assertEquals(ItemStackResponseStatus.OK.ordinal(), buffer.readByte(), "result");
        assertEquals(7, VarInts.readInt(buffer), "request id");
        assertEquals(1, VarInts.readUnsignedInt(buffer), "container count");
        assertEquals(ContainerSlotType.INVENTORY.ordinal(), readContainerName(buffer), "container name");
        assertEquals(1, VarInts.readUnsignedInt(buffer), "slot count");
        assertEquals(14, buffer.readUnsignedByte(), "slot");
        assertEquals(0, buffer.readUnsignedByte(), "hotbar slot");
        assertEquals(3, buffer.readUnsignedByte(), "count");
        assertEquals(901, VarInts.readInt(buffer),
                "the stack network id, which the next request for this slot has to quote back");
        assertEquals("", readString(buffer), "custom name");
        assertEquals("", readString(buffer), "filtered custom name");
        assertEquals(0, VarInts.readInt(buffer), "durability correction");
        assertFalse(buffer.isReadable(), "nothing may be left over: the bridge reads these fields in this order");
    }

    /**
     * A rejection stops after the request id. Reading on as though containers followed would consume
     * the next response in the same packet and desync the rest of it.
     */
    @Test
    void aRejectionCarriesNoContainers() {
        ItemStackResponsePacket packet = response(ItemStackResponseStatus.ERROR, 9);

        ByteBuf buffer = encode(Bedrock_v1001.CODEC, packet);

        assertEquals(1, VarInts.readUnsignedInt(buffer), "response count");
        assertEquals(ItemStackResponseStatus.ERROR.ordinal(), buffer.readByte(), "result");
        assertEquals(9, VarInts.readInt(buffer), "request id");
        assertFalse(buffer.isReadable(), "a failed response ends at the request id on protocol 1001");
    }

    /**
     * The reason this packet is translated rather than forwarded, in bytes.
     *
     * <p>1.26.40 differs in two places: two presence booleans after the request id, and two more
     * wrapping every slot's stack network id. So the gap is not a constant — it grows with the
     * number of slots reported. Forwarding the backend's bytes to the bridge unchanged would have it
     * read the first of those booleans as a container count and go wrong from there.</p>
     */
    @Test
    void theBackendsShapeIsNotTheBridgesShape() {
        int oneSlot = shapeDifference(new ItemStackResponseSlot(0, 0, 1, 55, "", 0, ""));
        int twoSlots = shapeDifference(
                new ItemStackResponseSlot(0, 0, 1, 55, "", 0, ""),
                new ItemStackResponseSlot(1, 0, 2, 56, "", 0, ""));

        assertEquals(4, oneSlot, "two booleans at the response level, and two around the slot's stack id");
        assertEquals(6, twoSlots, "the per-slot pair is per slot, so a second slot adds two more");
    }

    private static int shapeDifference(ItemStackResponseSlot... slots) {
        ItemStackResponsePacket packet = response(ItemStackResponseStatus.OK, 11,
                new ItemStackResponseContainer(ContainerSlotType.CURSOR, List.of(slots),
                        new FullContainerName(ContainerSlotType.CURSOR, null)));
        return encode(Bedrock_v2168.CODEC, packet).readableBytes() - encode(Bedrock_v1001.CODEC, packet).readableBytes();
    }

    @Test
    void aResponseSurvivesTheHopIntact() {
        ItemStackResponsePacket fromBackend = response(ItemStackResponseStatus.OK, 13,
                new ItemStackResponseContainer(ContainerSlotType.HOTBAR,
                        List.of(new ItemStackResponseSlot(2, 2, 17, 1234, "", 0, "")),
                        new FullContainerName(ContainerSlotType.HOTBAR, null)));

        ItemStackResponsePacket asBridgeSeesIt = decode(Bedrock_v1001.CODEC,
                encode(Bedrock_v1001.CODEC, decode(Bedrock_v2168.CODEC, encode(Bedrock_v2168.CODEC, fromBackend))));

        ItemStackResponse response = asBridgeSeesIt.getEntries().get(0);
        assertEquals(ItemStackResponseStatus.OK, response.getResult());
        assertEquals(13, response.getRequestId());
        ItemStackResponseSlot slot = response.getContainers().get(0).getItems().get(0);
        assertEquals(2, slot.getSlot());
        assertEquals(17, slot.getCount());
        assertEquals(1234, slot.getStackNetworkId());
    }

    private static ItemStackResponsePacket response(ItemStackResponseStatus status, int requestId, ItemStackResponseContainer... containers) {
        ItemStackResponsePacket packet = new ItemStackResponsePacket();
        packet.getEntries().add(new ItemStackResponse(status, requestId, List.of(containers)));
        return packet;
    }

    /** Protocol 1001 writes a container name as the slot type plus an optional little-endian id. */
    private static int readContainerName(ByteBuf buffer) {
        int slotType = buffer.readByte();
        if (buffer.readBoolean()) {
            buffer.readIntLE();
        }
        return slotType;
    }

    private static String readString(ByteBuf buffer) {
        byte[] bytes = new byte[VarInts.readUnsignedInt(buffer)];
        buffer.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuf encode(BedrockCodec codec, ItemStackResponsePacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        BedrockPacketDefinition<ItemStackResponsePacket> definition = codec.getPacketDefinition(ItemStackResponsePacket.class);
        definition.getSerializer().serialize(buffer, codec.createHelper(), packet);
        return buffer;
    }

    private static ItemStackResponsePacket decode(BedrockCodec codec, ByteBuf buffer) {
        BedrockPacketDefinition<ItemStackResponsePacket> definition = codec.getPacketDefinition(ItemStackResponsePacket.class);
        ItemStackResponsePacket packet = definition.getFactory().get();
        definition.getSerializer().deserialize(buffer, codec.createHelper(), packet);
        return packet;
    }

}
