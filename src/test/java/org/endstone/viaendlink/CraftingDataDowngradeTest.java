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
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.CraftingDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipe list surviving the 1.26.40 &rarr; 1.26.30 hop.
 *
 * <p>Everything else the proxy carries across this gap is a re-encode: decode with one side's codec,
 * encode with the other's, and the shared packet model absorbs the difference. {@code CraftingData}
 * is where that stops working <em>silently</em>. 1.26.40 split one {@code craftingData} list into
 * eight per-kind ones, so the two serializers read and write different fields of the same object:
 * the re-encode succeeds, emits a well-formed packet, and the packet contains no recipes at all.</p>
 *
 * <p>A Java player then sees a crafting table whose result slot is empty for every recipe, with
 * nothing in any log to say why — which is exactly what these tests exist to stop happening again.
 *
 * @see LegacyClientTo2168Translator
 */
class CraftingDataDowngradeTest {

    private static final UUID RECIPE_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static final ItemDefinition OAK_PLANKS = new SimpleItemDefinition("minecraft:oak_planks", 5, false);
    private static final ItemDefinition STICK = new SimpleItemDefinition("minecraft:stick", 280, false);
    private static final ItemDefinition CRAFTING_TABLE = new SimpleItemDefinition("minecraft:crafting_table", 58, false);

    @Test
    void aReEncodeWithoutTheFoldLosesEveryRecipe() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapedData().add(craftingTableRecipe());

        ByteBuf encoded = encode(Bedrock_v1001.CODEC, packet);
        try {
            CraftingDataPacket asClientReadsIt = decode(Bedrock_v1001.CODEC, encoded);
            assertTrue(asClientReadsIt.getCraftingData().isEmpty(),
                    "this is the bug, pinned: without the fold the older serializer writes the list it knows "
                            + "about, which the newer deserializer never filled in");
        } finally {
            encoded.release();
        }
    }

    @Test
    void theFoldPutsEveryKindIntoTheListTheOlderClientReads() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapedData().add(craftingTableRecipe());
        packet.getShapelessData().add(planksRecipe());
        packet.setCleanRecipes(true);

        CraftingDataPacket folded = translate(packet);
        assertEquals(2, folded.getCraftingData().size());

        ByteBuf encoded = encode(Bedrock_v1001.CODEC, folded);
        try {
            CraftingDataPacket asClientReadsIt = decode(Bedrock_v1001.CODEC, encoded);
            assertEquals(2, asClientReadsIt.getCraftingData().size());
            assertTrue(asClientReadsIt.isCleanRecipes());

            ShapedRecipeData shaped = assertInstanceOf(ShapedRecipeData.class, asClientReadsIt.getCraftingData().get(0));
            assertEquals(CraftingDataType.SHAPED, shaped.getType());
            assertEquals("minecraft:crafting_table", shaped.getId());
            assertEquals(2, shaped.getWidth());
            assertEquals(2, shaped.getHeight());
            assertEquals(4, shaped.getIngredients().size());
            assertEquals(77, shaped.getNetId(),
                    "the recipe network id is the whole point: a craft request names the recipe by it");
            assertEquals("crafting_table", shaped.getTag());
            assertEquals(CRAFTING_TABLE.getRuntimeId(), shaped.getResults().get(0).getDefinition().getRuntimeId());

            ShapelessRecipeData shapeless = assertInstanceOf(ShapelessRecipeData.class, asClientReadsIt.getCraftingData().get(1));
            assertEquals(CraftingDataType.SHAPELESS, shapeless.getType());
            assertEquals(78, shapeless.getNetId());
            assertEquals(4, shapeless.getResults().get(0).getCount());
        } finally {
            encoded.release();
        }
    }

    /** A tag ingredient — "any plank" — has to keep its tag, not collapse into a specific item. */
    @Test
    void anItemTagIngredientSurvivesTheHop() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapelessData().add(ShapelessRecipeData.of(CraftingDataType.SHAPELESS, "minecraft:stick_from_planks",
                List.of(new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:planks"), 2)),
                List.of(ItemData.builder().definition(STICK).count(4).build()),
                RECIPE_UUID, "crafting_table", 0, 90));

        CraftingDataPacket asClientReadsIt = roundTrip(translate(packet));
        ShapelessRecipeData shapeless = assertInstanceOf(ShapelessRecipeData.class, asClientReadsIt.getCraftingData().get(0));
        ItemTagDescriptor descriptor = assertInstanceOf(ItemTagDescriptor.class, shapeless.getIngredients().get(0).getDescriptor());
        assertEquals("minecraft:planks", descriptor.getItemTag());
        assertEquals(2, shapeless.getIngredients().get(0).getCount());
    }

    /**
     * 1.26.40 names an ingredient's item by identifier and 1.26.30 by runtime id, so one this
     * session cannot resolve has no number to write. The older format's "no item" and "this slot
     * must be empty" are the same bytes, so writing it anyway would not lose the recipe — it would
     * silently replace it with a different, emptier one that the server has never heard of.
     */
    @Test
    void aRecipeWithAnUnresolvableIngredientIsDroppedRatherThanEmptied() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapelessData().add(ShapelessRecipeData.of(CraftingDataType.SHAPELESS, "example:mystery",
                List.of(new ItemDescriptorWithCount(new DefaultDescriptor(null, 0), 1)),
                List.of(ItemData.builder().definition(STICK).count(1).build()),
                RECIPE_UUID, "crafting_table", 0, 91));
        packet.getShapelessData().add(planksRecipe());

        CraftingDataPacket folded = translate(packet);
        assertEquals(1, folded.getCraftingData().size(), "only the resolvable recipe is kept");
        assertEquals(78, ((ShapelessRecipeData) folded.getCraftingData().get(0)).getNetId());
    }

    /** A packet that is already flat — an older backend, or one folded once already — is left alone. */
    @Test
    void anAlreadyFlatPacketIsNotDoubled() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getCraftingData().add(planksRecipe());

        assertEquals(1, translate(packet).getCraftingData().size());
        assertEquals(1, translate(translate(packet)).getCraftingData().size());
    }

    @Test
    void theOtherKindsAreCarriedToo() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getMultiData().add(org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.MultiRecipeData.of(RECIPE_UUID, 12));
        packet.getShapelessUserData().add(ShapelessRecipeData.of(CraftingDataType.SHULKER_BOX, "minecraft:shulker_box",
                List.of(new ItemDescriptorWithCount(new DefaultDescriptor(OAK_PLANKS, 0), 1)),
                List.of(ItemData.builder().definition(STICK).count(1).build()),
                RECIPE_UUID, "crafting_table", 0, 13));

        CraftingDataPacket asClientReadsIt = roundTrip(translate(packet));
        assertEquals(2, asClientReadsIt.getCraftingData().size());
        assertFalse(asClientReadsIt.getCraftingData().stream().map(RecipeData::getType).toList().contains(null));
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static ShapedRecipeData craftingTableRecipe() {
        ItemDescriptorWithCount plank = new ItemDescriptorWithCount(new DefaultDescriptor(OAK_PLANKS, 0), 1);
        return ShapedRecipeData.of(CraftingDataType.SHAPED, "minecraft:crafting_table", 2, 2,
                List.of(plank, plank, plank, plank),
                List.of(ItemData.builder().definition(CRAFTING_TABLE).count(1).build()),
                RECIPE_UUID, "crafting_table", 0, 77, true);
    }

    private static ShapelessRecipeData planksRecipe() {
        return ShapelessRecipeData.of(CraftingDataType.SHAPELESS, "minecraft:oak_planks",
                List.of(new ItemDescriptorWithCount(new DefaultDescriptor(OAK_PLANKS, 0), 1)),
                List.of(ItemData.builder().definition(OAK_PLANKS).count(4).build()),
                RECIPE_UUID, "crafting_table", 0, 78);
    }

    private static CraftingDataPacket translate(CraftingDataPacket packet) {
        return (CraftingDataPacket) LegacyClientTo2168Translator.INSTANCE.translateClientbound(packet, null);
    }

    private static CraftingDataPacket roundTrip(CraftingDataPacket packet) {
        ByteBuf encoded = encode(Bedrock_v1001.CODEC, packet);
        try {
            return decode(Bedrock_v1001.CODEC, encoded);
        } finally {
            encoded.release();
        }
    }

    private static DefinitionRegistry<ItemDefinition> itemDefinitions() {
        return SimpleDefinitionRegistry.<ItemDefinition>builder()
                .add(ItemDefinition.AIR)
                .add(OAK_PLANKS)
                .add(STICK)
                .add(CRAFTING_TABLE)
                .build();
    }

    /** What the proxy installs as a fallback: every runtime id resolves, none of them means anything. */
    private static DefinitionRegistry<BlockDefinition> blockDefinitions() {
        return new DefinitionRegistry<>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return () -> runtimeId;
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return definition != null;
            }
        };
    }

    private static ByteBuf encode(BedrockCodec codec, CraftingDataPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        BedrockPacketDefinition<CraftingDataPacket> definition = codec.getPacketDefinition(CraftingDataPacket.class);
        assertNotNull(definition, "the codec must know CraftingDataPacket");
        BedrockCodecHelper helper = codec.createHelper();
        helper.setItemDefinitions(itemDefinitions());
        helper.setBlockDefinitions(blockDefinitions());
        definition.getSerializer().serialize(buffer, helper, packet);
        return buffer;
    }

    private static CraftingDataPacket decode(BedrockCodec codec, ByteBuf buffer) {
        ByteBuf copy = buffer.copy();
        try {
            BedrockPacketDefinition<CraftingDataPacket> definition = codec.getPacketDefinition(CraftingDataPacket.class);
            CraftingDataPacket packet = definition.getFactory().get();
            BedrockCodecHelper helper = codec.createHelper();
            helper.setItemDefinitions(itemDefinitions());
        helper.setBlockDefinitions(blockDefinitions());
            definition.getSerializer().deserialize(copy, helper, packet);
            assertEquals(0, copy.readableBytes(),
                    "the whole packet must be consumed -- leftover bytes mean the layout written and the "
                            + "layout expected disagree, and every entry after the first mismatch is nonsense");
            return packet;
        } finally {
            copy.release();
        }
    }

    /** Kept honest: the 1.26.40 codec must still round-trip its own split layout. */
    @Test
    void theNewerLayoutStillRoundTripsOnItsOwnSide() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapedData().add(craftingTableRecipe());

        ByteBuf encoded = encode(Bedrock_v2168.CODEC, packet);
        try {
            CraftingDataPacket asBackendWroteIt = decode(Bedrock_v2168.CODEC, encoded);
            assertEquals(1, asBackendWroteIt.getShapedData().size());
            assertTrue(asBackendWroteIt.getCraftingData().isEmpty(),
                    "the newer codec never touches the flat list, which is why the fold has to exist");
        } finally {
            encoded.release();
        }
    }

}
