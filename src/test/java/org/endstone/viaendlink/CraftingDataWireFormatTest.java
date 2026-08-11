package org.endstone.viaendlink;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.CraftingDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.FurnaceRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.MultiRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The byte layout of {@code CraftingData} at protocol 1001, as ViaBedrock's reader walks it.
 *
 * <p>The reader itself lives in the vendored ViaBedrock and cannot be called from here — it is
 * built into ViaProxy.jar against a different netty — so this mirrors its parse and runs it against
 * bytes the real 1.26.30 serializer produced. That makes the layout the contract, which is what it
 * is on the wire anyway.</p>
 *
 * <p>Getting it wrong has one failure mode and it is total. The recipe list is a flat sequence with
 * no per-entry length, so a field read in the wrong order or a kind skipped rather than parsed
 * leaves the reader mid-entry and everything after it is garbage — not a missing recipe, a hundred
 * wrong ones. Hence the furnace and multi entries in the middle of the fixture below: nothing uses
 * them, and they still have to be read exactly right.</p>
 *
 * @see CraftingDataDowngradeTest
 */
class CraftingDataWireFormatTest {

    private static final UUID RECIPE_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final ItemDefinition OAK_LOG = new SimpleItemDefinition("minecraft:oak_log", 17, false);
    private static final ItemDefinition OAK_PLANKS = new SimpleItemDefinition("minecraft:oak_planks", 5, false);
    private static final ItemDefinition STICK = new SimpleItemDefinition("minecraft:stick", 280, false);
    private static final ItemDefinition CHARCOAL = new SimpleItemDefinition("minecraft:charcoal", 303, false);

    // ItemDescriptorType ordinals
    private static final int DESCRIPTOR_INVALID = 0;
    private static final int DESCRIPTOR_DEFAULT = 1;
    private static final int DESCRIPTOR_ITEM_TAG = 3;

    @Test
    void theRecipeListIsWalkedFieldForField() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getCraftingData().add(ShapedRecipeData.of(CraftingDataType.SHAPED, "minecraft:stick", 1, 2,
                List.of(ingredient(OAK_PLANKS), ingredient(OAK_PLANKS)),
                List.of(ItemData.builder().definition(STICK).count(4).build()),
                RECIPE_UUID, "crafting_table", 0, 501, false));
        // A kind nothing here keeps, in the middle, where mis-reading it would corrupt what follows
        packet.getCraftingData().add(FurnaceRecipeData.of(CraftingDataType.FURNACE, OAK_LOG.getRuntimeId(), -1,
                ItemData.builder().definition(CHARCOAL).count(1).build(), "furnace"));
        packet.getCraftingData().add(MultiRecipeData.of(RECIPE_UUID, 502));
        packet.getCraftingData().add(ShapelessRecipeData.of(CraftingDataType.SHAPELESS, "minecraft:oak_planks",
                List.of(new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:logs"), 1)),
                List.of(ItemData.builder().definition(OAK_PLANKS).count(4).build()),
                RECIPE_UUID, "crafting_table", 1, 503));
        packet.setCleanRecipes(true);

        ByteBuf buffer = encode(packet);
        try {
            Parsed parsed = parse(buffer);
            assertEquals(0, buffer.readableBytes(),
                    "the reader must consume the packet exactly -- leftover bytes mean it and the serializer "
                            + "disagree, and every recipe after the first disagreement is nonsense");
            assertTrue(parsed.cleanRecipes());

            assertEquals(2, parsed.recipes().size(), "only the two crafting-table recipes are kept");

            Recipe stick = parsed.recipes().get(0);
            assertTrue(stick.shaped());
            assertEquals(501, stick.netId());
            assertEquals("crafting_table", stick.tag());
            assertEquals(1, stick.width());
            assertEquals(2, stick.height());
            assertEquals(2, stick.ingredients().size());
            assertEquals(OAK_PLANKS.getRuntimeId(), stick.ingredients().get(0).itemId());
            assertEquals(STICK.getRuntimeId(), stick.resultId());
            assertEquals(4, stick.resultCount());

            Recipe planks = parsed.recipes().get(1);
            assertTrue(!planks.shaped());
            assertEquals(503, planks.netId());
            assertEquals(1, planks.priority());
            assertEquals("minecraft:logs", planks.ingredients().get(0).tag());
            assertEquals(OAK_PLANKS.getRuntimeId(), planks.resultId());
        } finally {
            buffer.release();
        }
    }

    /** An empty cell of a shaped recipe is a descriptor, not an absent field. */
    @Test
    void anEmptyShapedCellIsStillAnIngredient() {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getCraftingData().add(ShapedRecipeData.of(CraftingDataType.SHAPED, "minecraft:wooden_pickaxe", 3, 3,
                List.of(ingredient(OAK_PLANKS), ingredient(OAK_PLANKS), ingredient(OAK_PLANKS),
                        ItemDescriptorWithCount.EMPTY, ingredient(STICK), ItemDescriptorWithCount.EMPTY,
                        ItemDescriptorWithCount.EMPTY, ingredient(STICK), ItemDescriptorWithCount.EMPTY),
                List.of(ItemData.builder().definition(STICK).count(1).build()),
                RECIPE_UUID, "crafting_table", 0, 504, false));

        ByteBuf buffer = encode(packet);
        try {
            Parsed parsed = parse(buffer);
            assertEquals(0, buffer.readableBytes());
            Recipe pickaxe = parsed.recipes().get(0);
            assertEquals(9, pickaxe.ingredients().size(),
                    "a 3x3 recipe writes nine ingredients whatever they are; counting only the filled ones "
                            + "would put every later field one descriptor early");
            assertTrue(pickaxe.ingredients().get(3).empty());
            assertEquals(STICK.getRuntimeId(), pickaxe.ingredients().get(4).itemId());
        } finally {
            buffer.release();
        }
    }

    // --- the parse ViaBedrock's CraftingDataReader performs -------------------------------------

    private record Ingredient(boolean empty, int itemId, int auxValue, String tag, int count) {
    }

    private record Recipe(boolean shaped, int width, int height, List<Ingredient> ingredients,
                          int resultId, int resultCount, String tag, int priority, int netId) {
    }

    private record Parsed(List<Recipe> recipes, boolean cleanRecipes) {
    }

    private static Parsed parse(ByteBuf buffer) {
        List<Recipe> recipes = new ArrayList<>();
        int entryCount = VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < entryCount; i++) {
            int type = VarInts.readInt(buffer);
            switch (type) {
                case 0, 5, 6 -> { // SHAPELESS, SHULKER_BOX, SHAPELESS_CHEMISTRY
                    readString(buffer); // recipe id
                    List<Ingredient> ingredients = new ArrayList<>();
                    int ingredientCount = VarInts.readUnsignedInt(buffer);
                    for (int n = 0; n < ingredientCount; n++) {
                        ingredients.add(readIngredient(buffer));
                    }
                    int[] result = readResults(buffer);
                    readUuid(buffer);
                    String tag = readString(buffer);
                    int priority = VarInts.readInt(buffer);
                    if (type == 0 || type == 5) {
                        readRequirement(buffer);
                    }
                    int netId = VarInts.readUnsignedInt(buffer);
                    if (type == 0) {
                        recipes.add(new Recipe(false, 0, 0, ingredients, result[0], result[1], tag, priority, netId));
                    }
                }
                case 1, 7 -> { // SHAPED, SHAPED_CHEMISTRY
                    readString(buffer); // recipe id
                    int width = VarInts.readInt(buffer);
                    int height = VarInts.readInt(buffer);
                    List<Ingredient> ingredients = new ArrayList<>();
                    for (int n = 0; n < width * height; n++) {
                        ingredients.add(readIngredient(buffer));
                    }
                    int[] result = readResults(buffer);
                    readUuid(buffer);
                    String tag = readString(buffer);
                    int priority = VarInts.readInt(buffer);
                    buffer.readBoolean(); // assume symmetry
                    if (type == 1) {
                        readRequirement(buffer);
                    }
                    int netId = VarInts.readUnsignedInt(buffer);
                    if (type == 1) {
                        recipes.add(new Recipe(true, width, height, ingredients, result[0], result[1], tag, priority, netId));
                    }
                }
                case 2 -> { // FURNACE
                    VarInts.readInt(buffer); // input id
                    readItemInstance(buffer);
                    readString(buffer); // tag
                }
                case 3 -> { // FURNACE_DATA
                    VarInts.readInt(buffer); // input id
                    VarInts.readInt(buffer); // input data
                    readItemInstance(buffer);
                    readString(buffer); // tag
                }
                case 4 -> { // MULTI
                    readUuid(buffer);
                    VarInts.readUnsignedInt(buffer); // net id
                }
                case 8 -> { // SMITHING_TRANSFORM
                    readString(buffer);
                    readIngredient(buffer);
                    readIngredient(buffer);
                    readIngredient(buffer);
                    readItemInstance(buffer);
                    readString(buffer);
                    VarInts.readUnsignedInt(buffer);
                }
                case 9 -> { // SMITHING_TRIM
                    readString(buffer);
                    readIngredient(buffer);
                    readIngredient(buffer);
                    readIngredient(buffer);
                    readString(buffer);
                    VarInts.readUnsignedInt(buffer);
                }
                default -> throw new IllegalStateException("Unknown CraftingDataType: " + type);
            }
        }

        int potionMixes = VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < potionMixes * 6; i++) {
            VarInts.readInt(buffer);
        }
        int containerMixes = VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < containerMixes * 3; i++) {
            VarInts.readInt(buffer);
        }
        int materialReducers = VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < materialReducers; i++) {
            VarInts.readInt(buffer); // input id
            int outputs = VarInts.readUnsignedInt(buffer);
            for (int output = 0; output < outputs * 2; output++) {
                VarInts.readInt(buffer);
            }
        }
        return new Parsed(recipes, buffer.readBoolean());
    }

    private static Ingredient readIngredient(ByteBuf buffer) {
        int descriptorType = buffer.readUnsignedByte();
        Integer itemId = null;
        int auxValue = 0x7FFF;
        String tag = null;
        switch (descriptorType) {
            case DESCRIPTOR_INVALID -> {
            }
            case DESCRIPTOR_DEFAULT -> {
                short id = buffer.readShortLE();
                if (id != 0) {
                    auxValue = buffer.readShortLE();
                    itemId = (int) id;
                }
            }
            case DESCRIPTOR_ITEM_TAG -> tag = readString(buffer);
            default -> throw new IllegalStateException("Unhandled ItemDescriptorType in this fixture: " + descriptorType);
        }
        int count = VarInts.readInt(buffer);
        return new Ingredient(itemId == null && tag == null, itemId == null ? 0 : itemId, auxValue, tag, count);
    }

    private static void readRequirement(ByteBuf buffer) {
        byte context = buffer.readByte();
        if (context != 0) {
            return;
        }
        int ingredientCount = VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < ingredientCount; i++) {
            readIngredient(buffer);
        }
    }

    /** @return {resultId, resultCount} of the first result */
    private static int[] readResults(ByteBuf buffer) {
        int resultCount = VarInts.readUnsignedInt(buffer);
        int[] first = {0, 0};
        for (int i = 0; i < resultCount; i++) {
            int[] result = readItemInstance(buffer);
            if (i == 0) {
                first = result;
            }
        }
        return first;
    }

    /** An ItemInstance: the Item shape with no stack network id field at all. */
    private static int[] readItemInstance(ByteBuf buffer) {
        int id = VarInts.readInt(buffer);
        if (id == 0) {
            return new int[]{0, 0};
        }
        int count = buffer.readUnsignedShortLE();
        VarInts.readUnsignedInt(buffer); // damage
        VarInts.readInt(buffer); // block runtime id
        buffer.skipBytes(VarInts.readUnsignedInt(buffer)); // user data
        return new int[]{id, count};
    }

    private static String readString(ByteBuf buffer) {
        byte[] bytes = new byte[VarInts.readUnsignedInt(buffer)];
        buffer.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void readUuid(ByteBuf buffer) {
        buffer.readLongLE();
        buffer.readLongLE();
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static ItemDescriptorWithCount ingredient(ItemDefinition definition) {
        return new ItemDescriptorWithCount(new DefaultDescriptor(definition, 0), 1);
    }

    private static ByteBuf encode(CraftingDataPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        BedrockPacketDefinition<CraftingDataPacket> definition = Bedrock_v1001.CODEC.getPacketDefinition(CraftingDataPacket.class);
        BedrockCodecHelper helper = Bedrock_v1001.CODEC.createHelper();
        helper.setItemDefinitions(SimpleDefinitionRegistry.<ItemDefinition>builder()
                .add(ItemDefinition.AIR).add(OAK_LOG).add(OAK_PLANKS).add(STICK).add(CHARCOAL).build());
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
        definition.getSerializer().serialize(buffer, helper, packet);
        return buffer;
    }

}
