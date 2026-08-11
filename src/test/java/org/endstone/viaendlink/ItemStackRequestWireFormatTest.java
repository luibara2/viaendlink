package org.endstone.viaendlink;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftResultsDeprecatedAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.RecipeItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bytes ViaBedrock writes for an {@code ItemStackRequest}, read back by the codec that will
 * actually read them.
 *
 * <p>A Java player's inventory click leaves the bridge as a Bedrock 1.26.30 packet, is decoded by
 * this proxy with the v1001 codec, and is re-encoded for the 1.26.40 backend. Both ends of that hop
 * are pinned here, because a mistake in either is invisible until a player reports that their
 * inventory does not work: an item stack request that fails to decode is dropped by the proxy, and
 * one that decodes into the wrong action reaches the server as a different move than the player
 * asked for.</p>
 *
 * <p>The writer under test lives in the vendored ViaBedrock and cannot be called from here — it is
 * built into ViaProxy.jar, against a different netty. So these tests build the same byte sequence
 * with the same primitives and assert the codec's reading of it. That makes the byte layout the
 * contract, which is what it is on the wire anyway; if ViaBedrock's writer and this drift apart,
 * the layout documented in each is the thing to compare.</p>
 *
 * @see org.endstone.viaendlink.LegacyClientTo2168Translator
 */
class ItemStackRequestWireFormatTest {

    // ItemStackRequestActionType ids as protocol 1001 numbers them. 7 and 8 were vacated when
    // PlaceInItemContainer and TakeFromItemContainer were deprecated, and the gap was left in place
    // -- so LAB_TABLE_COMBINE is 9, not 7. 1.26.40 closes the gap, which is exactly why re-encoding
    // through the enum rather than passing the byte through is what makes this hop work.
    private static final int V1001_TAKE = 0;
    private static final int V1001_PLACE = 1;
    private static final int V1001_SWAP = 2;
    private static final int V1001_DROP = 3;
    private static final int V1001_CONSUME = 5;
    private static final int V1001_CRAFT_RECIPE = 12;
    private static final int V1001_CRAFT_RESULTS_DEPRECATED = 19;

    private static final int CONTAINER_HOTBAR = 28;
    private static final int CONTAINER_INVENTORY = 29;
    private static final int CONTAINER_CRAFTING_INPUT = 13;
    private static final int CONTAINER_CURSOR = 59;
    private static final int CONTAINER_CREATED_OUTPUT = 60;

    /** The player UI container's slot numbers, which is how a craft addresses the grid and its output. */
    private static final int UI_SLOT_CRAFTING_INPUT_FIRST = 32;
    private static final int UI_SLOT_CREATED_OUTPUT = 50;

    @Test
    void aTakeIntoTheCursorDecodesAsWritten() {
        ByteBuf buffer = requestBuffer(7, request -> {
            request.writeByte(V1001_TAKE);
            request.writeByte(5); // count
            writeSlot(request, CONTAINER_INVENTORY, 14, 900); // source
            writeSlot(request, CONTAINER_CURSOR, 0, 0); // destination
        });

        ItemStackRequestPacket packet = decode(Bedrock_v1001.CODEC, buffer);
        assertEquals(1, packet.getRequests().size());

        ItemStackRequest request = packet.getRequests().get(0);
        assertEquals(7, request.getRequestId());
        assertEquals(1, request.getActions().length);

        TransferItemStackRequestAction take = assertInstanceOf(TransferItemStackRequestAction.class, request.getActions()[0]);
        assertEquals(ItemStackRequestActionType.TAKE, take.getType());
        assertEquals(5, take.getCount());
        assertEquals(ContainerSlotType.INVENTORY, take.getSource().getContainer());
        assertEquals(14, take.getSource().getSlot());
        assertEquals(900, take.getSource().getStackNetworkId(),
                "the stack network id is what the server validates the move against; losing it means "
                        + "every request is rejected as stale");
        assertEquals(ContainerSlotType.CURSOR, take.getDestination().getContainer());
        assertEquals(0, take.getDestination().getSlot());
    }

    @Test
    void aPlaceOntoAHotbarSlotDecodesAsWritten() {
        ByteBuf buffer = requestBuffer(9, request -> {
            request.writeByte(V1001_PLACE);
            request.writeByte(1); // count
            writeSlot(request, CONTAINER_CURSOR, 0, 12); // source
            writeSlot(request, CONTAINER_HOTBAR, 3, 0); // destination
        });

        TransferItemStackRequestAction place = assertInstanceOf(TransferItemStackRequestAction.class,
                decode(Bedrock_v1001.CODEC, buffer).getRequests().get(0).getActions()[0]);
        assertEquals(ItemStackRequestActionType.PLACE, place.getType());
        assertEquals(1, place.getCount());
        assertEquals(ContainerSlotType.CURSOR, place.getSource().getContainer());
        assertEquals(ContainerSlotType.HOTBAR, place.getDestination().getContainer(),
                "the player's first nine slots are addressed as the hotbar, not as the inventory");
        assertEquals(3, place.getDestination().getSlot());
    }

    @Test
    void aSwapCarriesNoCount() {
        ByteBuf buffer = requestBuffer(11, request -> {
            request.writeByte(V1001_SWAP);
            writeSlot(request, CONTAINER_CURSOR, 0, 4);
            writeSlot(request, CONTAINER_INVENTORY, 20, 77);
        });

        SwapAction swap = assertInstanceOf(SwapAction.class,
                decode(Bedrock_v1001.CODEC, buffer).getRequests().get(0).getActions()[0]);
        assertEquals(ContainerSlotType.CURSOR, swap.getSource().getContainer());
        assertEquals(ContainerSlotType.INVENTORY, swap.getDestination().getContainer());
        assertEquals(77, swap.getDestination().getStackNetworkId());
    }

    @Test
    void aDropCarriesItsRandomlyFlag() {
        ByteBuf buffer = requestBuffer(13, request -> {
            request.writeByte(V1001_DROP);
            request.writeByte(2); // count
            writeSlot(request, CONTAINER_HOTBAR, 0, 55);
            request.writeBoolean(false); // randomly
        });

        ItemStackRequestAction action = decode(Bedrock_v1001.CODEC, buffer).getRequests().get(0).getActions()[0];
        assertEquals(ItemStackRequestActionType.DROP, action.getType());
    }

    @Test
    void severalActionsTravelInOneRequest() {
        // What a shift-click becomes: one stack split across two destinations, applied all or nothing
        ByteBuf buffer = requestBuffer(15, 2, request -> {
            request.writeByte(V1001_PLACE);
            request.writeByte(40);
            writeSlot(request, CONTAINER_INVENTORY, 9, 5);
            writeSlot(request, CONTAINER_INVENTORY, 10, 6);

            request.writeByte(V1001_PLACE);
            request.writeByte(24);
            writeSlot(request, CONTAINER_INVENTORY, 9, 5);
            writeSlot(request, CONTAINER_INVENTORY, 11, 0);
        });

        ItemStackRequest request = decode(Bedrock_v1001.CODEC, buffer).getRequests().get(0);
        assertEquals(2, request.getActions().length);
        assertEquals(40, ((TransferItemStackRequestAction) request.getActions()[0]).getCount());
        assertEquals(24, ((TransferItemStackRequestAction) request.getActions()[1]).getCount());
    }

    /**
     * The whole reason the upgrade edge can be a re-encode rather than a translation: 1.26.40
     * renumbered the action types, and going through the enum renumbers with it.
     *
     * <p>Passing the byte through unchanged would send the backend a different action than the
     * player asked for — a craft request arriving as a creative-mode item duplication, say — which
     * the server would either reject or, worse, honour.</p>
     */
    @Test
    void theActionTypeIsRenumberedForTheBackend() {
        ByteBuf buffer = requestBuffer(17, request -> {
            request.writeByte(V1001_CRAFT_RECIPE);
            VarInts.writeUnsignedInt(request, 42); // recipe network id
            request.writeByte(1); // number of requested crafts
        });

        ItemStackRequestPacket packet = decode(Bedrock_v1001.CODEC, buffer);
        assertEquals(ItemStackRequestActionType.CRAFT_RECIPE, packet.getRequests().get(0).getActions()[0].getType());

        ByteBuf reencoded = encode(Bedrock_v2168.CODEC, packet);
        // request count, request id, action count, then the action type byte
        reencoded.readByte();
        VarInts.readInt(reencoded);
        reencoded.readByte();
        assertEquals(10, reencoded.readByte(),
                "1.26.40 dropped the two deprecated action types, so CRAFT_RECIPE moved from 12 to 10");
    }

    @Test
    void aRequestSurvivesTheFullUpgradeHop() {
        ByteBuf buffer = requestBuffer(19, request -> {
            request.writeByte(V1001_TAKE);
            request.writeByte(3);
            writeSlot(request, CONTAINER_INVENTORY, 14, 900);
            writeSlot(request, CONTAINER_CURSOR, 0, 0);
        });

        ItemStackRequestPacket asRead = decode(Bedrock_v1001.CODEC, buffer);
        ItemStackRequestPacket asBackendReadsIt = decode(Bedrock_v2168.CODEC, encode(Bedrock_v2168.CODEC, asRead));

        TransferItemStackRequestAction take = assertInstanceOf(TransferItemStackRequestAction.class,
                asBackendReadsIt.getRequests().get(0).getActions()[0]);
        assertEquals(19, asBackendReadsIt.getRequests().get(0).getRequestId());
        assertEquals(3, take.getCount());
        assertEquals(ContainerSlotType.INVENTORY, take.getSource().getContainer());
        assertEquals(14, take.getSource().getSlot());
        assertEquals(900, take.getSource().getStackNetworkId());
        assertEquals(ContainerSlotType.CURSOR, take.getDestination().getContainer());
    }

    /**
     * Request ids are <b>negative and odd</b>, and the server enforces it.
     *
     * <p>Sending a positive one does not degrade gracefully — the backend refuses the packet and
     * drops the connection, so the player is kicked the instant they touch their inventory:</p>
     *
     * <pre>
     * packet 147 rejected (terminating connection): expected a valid ItemStackRequestId
     * readNoHeader failed! packetId: 147
     * </pre>
     *
     * <p>The id is a zigzag varint at both ends, so a negative value is a byte long rather than
     * five, and survives the hop unchanged. This test exists to keep anyone from "tidying" the
     * sequence back to counting upwards from one.</p>
     */
    @Test
    void requestIdsAreNegativeOddAndSurviveTheHop() {
        for (int requestId : new int[]{-1, -3, -5, -4097, Integer.MIN_VALUE + 1}) {
            assertTrue(requestId < 0, "request ids must be negative");
            assertTrue(Math.abs(requestId % 2) == 1, "request ids must be odd");

            ByteBuf buffer = requestBuffer(requestId, request -> {
                request.writeByte(V1001_DROP);
                request.writeByte(1);
                writeSlot(request, CONTAINER_HOTBAR, 0, 0);
                request.writeBoolean(false);
            });

            ItemStackRequestPacket asBackendReadsIt = decode(Bedrock_v2168.CODEC,
                    encode(Bedrock_v2168.CODEC, decode(Bedrock_v1001.CODEC, buffer)));
            assertEquals(requestId, asBackendReadsIt.getRequests().get(0).getRequestId());
        }
    }

    /**
     * A whole craft, as a vanilla Bedrock client sends one.
     *
     * <p>This is a transcription of a real capture, not a guess: a 1.26.40 client crafting oak
     * planks from a log in its own 2x2 square, taken through the proxy on 2026-08-11. The values are
     * that request's, which is what makes this the reference for a shape nothing else here documents.
     * The original:</p>
     *
     * <pre>
     * ItemStackRequest id=-35
     *   CRAFT_RECIPE recipeNetworkId=564 crafts=1
     *   CRAFT_RESULTS_DEPRECATED timesCrafted=1 results=[minecraft:oak_planks x4]
     *   CONSUME count=1 from=CRAFTING_INPUT[30] netId=193
     *   PLACE count=4 from=CREATED_OUTPUT[50] netId=-35 to=HOTBAR_AND_INVENTORY[0] netId=0
     * </pre>
     *
     * <p>Three things it pins, each of which was wrong or unknown before the capture:</p>
     *
     * <ul>
     *   <li><b>The order.</b> Name the recipe, state what it produces, hand over the ingredients,
     *       and only then move the result out. Sending just the move — the shape a Java click most
     *       resembles — is refused; as far as the server is concerned nothing was crafted.</li>
     *   <li><b>Where the result comes from.</b> {@code CREATED_OUTPUT}, slot 50 — not the grid, and
     *       not the crafting-output preview slot.</li>
     *   <li><b>Its stack network id: the request's own id.</b> The stack does not exist yet, so it
     *       is named by the request that will make it. Sending 0 gets
     *       {@code FailedToValidateSrcSlot}, which reads like the slot is wrong rather than the
     *       id.</li>
     * </ul>
     *
     * <p>The zero-leftover-bytes assertion covers the fourth: the predicted result is an
     * {@code ItemInstance}, with no stack network id field at all, and writing it as the ordinary
     * {@code Item} shape adds a byte that shifts everything after it — so the server decodes a
     * different request rather than rejecting a malformed one.</p>
     */
    @Test
    void aCraftRequestDecodesAsWritten() {
        final int requestId = -35;
        ByteBuf buffer = requestBuffer(requestId, 4, request -> {
            request.writeByte(V1001_CRAFT_RECIPE);
            VarInts.writeUnsignedInt(request, 564); // recipe network id
            request.writeByte(1); // number of requested crafts

            request.writeByte(V1001_CRAFT_RESULTS_DEPRECATED);
            VarInts.writeUnsignedInt(request, 1); // result count
            writeItemInstance(request, 5, 4); // 4x oak planks
            request.writeByte(1); // times crafted

            request.writeByte(V1001_CONSUME);
            request.writeByte(1); // count
            writeSlot(request, CONTAINER_CRAFTING_INPUT, 30, 193);

            request.writeByte(V1001_PLACE);
            request.writeByte(4); // count
            writeSlot(request, CONTAINER_CREATED_OUTPUT, UI_SLOT_CREATED_OUTPUT, requestId);
            writeSlot(request, CONTAINER_HOTBAR, 0, 0);
        });

        ItemStackRequest request = decode(Bedrock_v1001.CODEC, buffer).getRequests().get(0);
        assertEquals(4, request.getActions().length);

        RecipeItemStackRequestAction craft = assertInstanceOf(RecipeItemStackRequestAction.class, request.getActions()[0]);
        assertEquals(ItemStackRequestActionType.CRAFT_RECIPE, craft.getType());
        assertEquals(564, craft.getRecipeNetworkId(),
                "the recipe network id is how the server knows which craft this is; it comes from CraftingData");
        assertEquals(1, craft.getNumberOfRequestedCrafts());

        CraftResultsDeprecatedAction results = assertInstanceOf(CraftResultsDeprecatedAction.class, request.getActions()[1]);
        assertEquals(1, results.getResultItems().length);
        assertEquals(5, results.getResultItems()[0].getDefinition().getRuntimeId());
        assertEquals(4, results.getResultItems()[0].getCount());
        assertEquals(1, results.getTimesCrafted());

        ItemStackRequestAction consume = request.getActions()[2];
        assertEquals(ItemStackRequestActionType.CONSUME, consume.getType());
        assertEquals(193, ((ConsumeAction) consume).getSource().getStackNetworkId(),
                "an ingredient is a stack that already exists, so it is named by its real id");

        TransferItemStackRequestAction place = assertInstanceOf(TransferItemStackRequestAction.class, request.getActions()[3]);
        assertEquals(ContainerSlotType.CREATED_OUTPUT, place.getSource().getContainer(),
                "the result comes out of the created-output slot, not out of the grid");
        assertEquals(UI_SLOT_CREATED_OUTPUT, place.getSource().getSlot());
        assertEquals(requestId, place.getSource().getStackNetworkId(),
                "the crafted stack has no id of its own yet, so it is named by the request that makes it");
        assertEquals(4, place.getCount());
    }

    /** Every action of a craft has to renumber for the backend, not just the one that names the recipe. */
    @Test
    void aCraftSurvivesTheFullUpgradeHop() {
        ByteBuf buffer = requestBuffer(-23, 3, request -> {
            request.writeByte(V1001_CRAFT_RECIPE);
            VarInts.writeUnsignedInt(request, 77);
            request.writeByte(2);

            request.writeByte(V1001_CRAFT_RESULTS_DEPRECATED);
            VarInts.writeUnsignedInt(request, 1);
            writeItemInstance(request, 5, 8); // 8x oak planks, two crafts of four
            request.writeByte(2);

            request.writeByte(V1001_CONSUME);
            request.writeByte(2);
            writeSlot(request, CONTAINER_CRAFTING_INPUT, UI_SLOT_CRAFTING_INPUT_FIRST, 61);
        });

        ItemStackRequestPacket asRead = decode(Bedrock_v1001.CODEC, buffer);
        ItemStackRequestPacket asBackendReadsIt = decode(Bedrock_v2168.CODEC, encode(Bedrock_v2168.CODEC, asRead));

        ItemStackRequest request = asBackendReadsIt.getRequests().get(0);
        assertEquals(-23, request.getRequestId());
        assertEquals(ItemStackRequestActionType.CRAFT_RECIPE, request.getActions()[0].getType());
        assertEquals(77, ((RecipeItemStackRequestAction) request.getActions()[0]).getRecipeNetworkId());
        assertEquals(2, ((RecipeItemStackRequestAction) request.getActions()[0]).getNumberOfRequestedCrafts());
        assertEquals(ItemStackRequestActionType.CRAFT_RESULTS_DEPRECATED, request.getActions()[1].getType());
        assertEquals(ItemStackRequestActionType.CONSUME, request.getActions()[2].getType());
    }

    /** The common ids have to stay small on the wire, or every click pays for it. */
    @Test
    void aNegativeRequestIdIsStillOneByte() {
        ByteBuf buffer = Unpooled.buffer();
        VarInts.writeInt(buffer, -1);
        assertEquals(1, buffer.readableBytes(), "zigzag encoding is what keeps -1 a single byte");
    }

    // --- the byte layout ViaBedrock writes ---------------------------------------------------

    /**
     * An ItemInstance: the ordinary item shape with the stack network id field left off entirely,
     * because a stack the craft has not produced yet cannot have one.
     */
    private static void writeItemInstance(ByteBuf buffer, int runtimeId, int count) {
        VarInts.writeInt(buffer, runtimeId);
        buffer.writeShortLE(count);
        VarInts.writeUnsignedInt(buffer, 0); // damage
        VarInts.writeInt(buffer, 0); // block runtime id
        ByteBuf userData = Unpooled.buffer();
        userData.writeShortLE(0); // no nbt
        userData.writeIntLE(0); // no canPlace
        userData.writeIntLE(0); // no canBreak
        VarInts.writeUnsignedInt(buffer, userData.readableBytes());
        buffer.writeBytes(userData);
        userData.release();
    }

    /** A slot info: the container name (with its optional dynamic id), the slot, the stack id. */
    private static void writeSlot(ByteBuf buffer, int containerName, int slot, int stackNetworkId) {
        buffer.writeByte(containerName);
        buffer.writeBoolean(false); // no dynamic id -- only bundles have one
        buffer.writeByte(slot);
        VarInts.writeInt(buffer, stackNetworkId);
    }

    private static ByteBuf requestBuffer(int requestId, java.util.function.Consumer<ByteBuf> actions) {
        return requestBuffer(requestId, 1, actions);
    }

    private static ByteBuf requestBuffer(int requestId, int actionCount, java.util.function.Consumer<ByteBuf> actions) {
        ByteBuf buffer = Unpooled.buffer();
        VarInts.writeUnsignedInt(buffer, 1); // request count
        VarInts.writeInt(buffer, requestId);
        VarInts.writeUnsignedInt(buffer, actionCount);
        actions.accept(buffer);
        VarInts.writeUnsignedInt(buffer, 0); // filter string count
        buffer.writeIntLE(-1); // filter cause: none
        return buffer;
    }

    private static ItemStackRequestPacket decode(BedrockCodec codec, ByteBuf buffer) {
        ByteBuf copy = buffer.copy();
        try {
            BedrockPacketDefinition<ItemStackRequestPacket> definition = codec.getPacketDefinition(ItemStackRequestPacket.class);
            assertNotNull(definition, "the codec must know ItemStackRequestPacket");
            ItemStackRequestPacket packet = definition.getFactory().get();
            BedrockCodecHelper helper = helperWithDefinitions(codec);
            definition.getSerializer().deserialize(copy, helper, packet);
            assertTrue(copy.readableBytes() == 0,
                    "the whole packet must be consumed -- " + copy.readableBytes() + " bytes were left over, "
                            + "which means the layout written here and the layout the codec expects disagree");
            return packet;
        } finally {
            copy.release();
        }
    }

    private static ByteBuf encode(BedrockCodec codec, ItemStackRequestPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        BedrockPacketDefinition<ItemStackRequestPacket> definition = codec.getPacketDefinition(ItemStackRequestPacket.class);
        definition.getSerializer().serialize(buffer, helperWithDefinitions(codec), packet);
        return buffer;
    }

    /**
     * The registries the proxy syncs from StartGame, stood in for here.
     *
     * <p>A craft request carries item stacks, so unlike a plain move it cannot be decoded without
     * them: the codec resolves every runtime id through these. The proxy's own fallbacks answer for
     * ids they have never heard of, which is what these do too.
     */
    private static BedrockCodecHelper helperWithDefinitions(BedrockCodec codec) {
        BedrockCodecHelper helper = codec.createHelper();
        helper.setItemDefinitions(new DefinitionRegistry<>() {
            @Override
            public ItemDefinition getDefinition(int runtimeId) {
                return runtimeId == 0 ? ItemDefinition.AIR : new SimpleItemDefinition("minecraft:item_" + runtimeId, runtimeId, false);
            }

            // 1.26.40 names an item stack request's items by identifier where 1.26.30 uses the
            // runtime id, so the backend side of the hop resolves them the other way round.
            @Override
            public ItemDefinition getDefinition(String identifier) {
                return getDefinition(Integer.parseInt(identifier.substring("minecraft:item_".length())));
            }

            @Override
            public boolean isRegistered(ItemDefinition definition) {
                return definition != null;
            }
        });
        helper.setBlockDefinitions(new DefinitionRegistry<>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return () -> runtimeId;
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return definition != null;
            }
        });
        return helper;
    }

}
