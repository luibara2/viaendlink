package org.endstone.viaendlink;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.CraftingRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTransformRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTrimRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.endstone.proxy.protocol.PacketTranslator;
import org.endstone.proxy.protocol.TranslationContext;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;

import java.util.List;

/**
 * Adjacent-version translator for the 1.26.30 (protocol 1001) &rarr; 1.26.40 (protocol 2168) step
 * &mdash; an <em>older</em> client against a <em>newer</em> backend, the opposite of every other edge
 * in the registry.
 *
 * <p>It exists for one caller: the Java Edition bridge. ViaBedrock speaks Bedrock 1001 and nothing
 * else (<code>ProtocolConstants.BEDROCK_PROTOCOL_VERSION = 1001</code> on upstream main as of
 * 2026-08-07), while the backends run 2168, so the bridge needs the one edge the graph has never
 * had. See {@code JAVA-SUPPORT-PLAN.md} &sect;0.1.</p>
 *
 * <p><b>Why this is a mirror of {@code ModernClientTo1001Translator} and not a real translation
 * layer.</b> Crossing this gap costs nothing because the codecs do it: the proxy decodes with one
 * side's codec and re-encodes with the other's through a shared packet model, and
 * {@code Bedrock_v2168 extends Bedrock_v1001} overriding 30 serializers is exactly that mechanism.
 * No packet was added or removed between the two, so the id table is unchanged and there is no
 * id-gap to police in either direction.</p>
 *
 * <p>The single asymmetry is the same one the downgrade edge documents.
 * {@code ClientboundUpdateSoundDataPacket} has no shared field between the two shapes &mdash; v1001
 * writes a handle plus an event string, v2168 writes a handle plus seven optionals and no string
 * &mdash; so it cannot survive a re-encode in <em>either</em> direction and is dropped here too. It
 * is clientbound only; the serverbound override is here for symmetry and to keep the drop in one
 * obvious place if the packet ever gains a serverbound form.</p>
 *
 * @see org.endstone.proxy.protocol.ModernClientTo1001Translator
 */
public final class LegacyClientTo2168Translator implements PacketTranslator {
    public static final LegacyClientTo2168Translator INSTANCE = new LegacyClientTo2168Translator();

    private LegacyClientTo2168Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket) {
            return null;
        }
        if (packet instanceof SubChunkPacket subChunk) {
            normalizeSubChunkForLegacyClient(subChunk);
        }
        if (packet instanceof CraftingDataPacket craftingData) {
            flattenCraftingDataForLegacyClient(craftingData);
        }
        return packet;
    }

    /**
     * Folds 1.26.40's eight per-kind recipe lists back into the single list 1.26.30 reads.
     *
     * <p>This is the second shape of the same trap {@link #normalizeSubChunkForLegacyClient} covers,
     * and it is quieter still. 1.26.40 split {@code CraftingDataPacket} from one {@code craftingData}
     * list into {@code shapedData}, {@code shapelessData}, {@code multiData} and five more; the two
     * serializers therefore read and write <em>different fields of the same packet object</em>. A
     * re-encode across the hop does not fail and does not warn — the 1.26.30 serializer writes
     * {@code craftingData}, which the 1.26.40 deserializer never touched, so it writes a
     * well-formed packet containing zero recipes.</p>
     *
     * <p>Which is why the Java client had no recipes at all rather than some. Each entry already
     * carries its own {@code CraftingDataType}, so putting them back in one list is all that the
     * older layout needs.</p>
     *
     * <p>Recipes whose ingredients cannot survive the older encoding are dropped rather than
     * mangled. 1.26.40 names a {@code DEFAULT} ingredient by <em>identifier</em> and 1.26.30 by
     * runtime id, so one that this session's item registry cannot resolve would be written as
     * runtime id 0 — indistinguishable, to the client, from "this slot must be empty", turning the
     * recipe into a different one that matches an emptier grid. Losing the recipe is recoverable;
     * shipping a wrong one is not.</p>
     */
    private static void flattenCraftingDataForLegacyClient(CraftingDataPacket packet) {
        if (!packet.getCraftingData().isEmpty()) {
            return; // Already the flat shape: a backend older than the split, or an already-folded packet
        }
        final List<RecipeData> flat = packet.getCraftingData();
        addEncodable(flat, packet.getShapedData());
        addEncodable(flat, packet.getShapelessData());
        addEncodable(flat, packet.getMultiData());
        addEncodable(flat, packet.getShapelessUserData());
        addEncodable(flat, packet.getShapelessChemistryData());
        addEncodable(flat, packet.getShapedChemistryData());
        addEncodable(flat, packet.getSmithingTransformData());
        addEncodable(flat, packet.getSmithingTrimData());
    }

    private static void addEncodable(List<RecipeData> flat, List<? extends RecipeData> recipes) {
        for (RecipeData recipe : recipes) {
            if (isEncodableForLegacyClient(recipe)) {
                flat.add(recipe);
            }
        }
    }

    private static boolean isEncodableForLegacyClient(RecipeData recipe) {
        if (recipe instanceof CraftingRecipeData crafting) {
            return allResolved(crafting.getIngredients());
        }
        if (recipe instanceof SmithingTransformRecipeData smithing) {
            return allResolved(List.of(smithing.getTemplate(), smithing.getBase(), smithing.getAddition()));
        }
        if (recipe instanceof SmithingTrimRecipeData trim) {
            return allResolved(List.of(trim.getTemplate(), trim.getBase(), trim.getAddition()));
        }
        return true;
    }

    private static boolean allResolved(List<ItemDescriptorWithCount> ingredients) {
        for (ItemDescriptorWithCount ingredient : ingredients) {
            if (ingredient != null && ingredient.getDescriptor() instanceof DefaultDescriptor descriptor
                    && descriptor.getItemId() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fills in the sub-chunk fields 1.26.30 writes unconditionally but 1.26.40 sends as optionals.
     *
     * <p><b>This is the packet that carries terrain.</b> 1.26.40 encodes a sub-chunk's data, its two
     * height maps and its blob id as flagged optionals, so any of them may legitimately be absent.
     * The 1.26.30 serializer instead infers presence: it writes the data whenever the result is not
     * {@code SUCCESS_ALL_AIR} (or caching is off), and writes a height map whenever its type byte says
     * {@code HAS_DATA}. Handed an absent optional it dereferences null, the whole
     * {@code SubChunkPacket} fails to encode, and the proxy drops it:</p>
     *
     * <pre>
     * DROPPED UNENCODABLE PACKET SubChunkPacket id=174 protocol=1001 ...
     *   Caused by: java.lang.NullPointerException: toWrite
     *     at SubChunkSerializer_v818.serializeSubChunk(SubChunkSerializer_v818.java:20)
     * </pre>
     *
     * <p>That presents as a player who joins fine, sees chat and the player list, and stands in an
     * empty world — every other packet crosses the hop untouched, and only terrain is silently
     * missing. It is the one place where "the codecs handle the gap" stops being true, because the
     * two sides disagree about what makes a field present rather than about its layout.</p>
     *
     * <p>An empty buffer rather than a dropped entry: 1.26.30 expects a length-prefixed payload to be
     * there, and a zero-length one reads back as a sub-chunk with no blocks, which is what an absent
     * 1.26.40 payload meant. Height maps whose data did not arrive are demoted to {@code NO_DATA},
     * which is the honest description of the same state.</p>
     */
    private static void normalizeSubChunkForLegacyClient(SubChunkPacket packet) {
        for (SubChunkData subChunk : packet.getSubChunks()) {
            if (subChunk.getData() == null) {
                subChunk.setData(Unpooled.EMPTY_BUFFER);
            }
            if (subChunk.getHeightMapType() == HeightMapDataType.HAS_DATA && subChunk.getHeightMapData() == null) {
                subChunk.setHeightMapType(HeightMapDataType.NO_DATA);
            }
            if (subChunk.getRenderHeightMapType() == HeightMapDataType.HAS_DATA
                    && subChunk.getRenderHeightMapData() == null) {
                subChunk.setRenderHeightMapType(HeightMapDataType.NO_DATA);
            }
            // Only read when caching is on, but a null Long unboxes to an NPE just as readily.
            if (packet.isCacheEnabled() && subChunk.getBlobId() == null) {
                subChunk.setBlobId(0L);
            }
        }
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }
}
