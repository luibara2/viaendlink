package org.endstone.viaendlink;

import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.ProtocolBinding;
import org.endstone.proxy.protocol.ProtocolRegistry;
import org.endstone.proxy.protocol.TranslationContext;
import org.endstone.proxy.protocol.ModernClientTo1001Translator;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.math.vector.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The packet that carries terrain across the 2168 &rarr; 1001 hop.
 *
 * <p>1.26.40 sends a sub-chunk's data, its two height maps and its blob id as flagged optionals, so
 * any of them may be absent. 1.26.30 infers presence instead — it writes the payload whenever caching
 * is off, and a height map whenever its type byte says {@code HAS_DATA} — so an absent optional
 * becomes a null dereference, the whole packet fails to encode, and the proxy drops it. A Java player
 * then joins, sees chat and the player list, and stands in an empty world.</p>
 *
 * <p>Found in production, from 236 of these in one session:</p>
 *
 * <pre>DROPPED UNENCODABLE PACKET SubChunkPacket id=174 protocol=1001 ...
 *   Caused by: java.lang.NullPointerException: toWrite</pre>
 */
class SubChunkUpgradeTest {

    /**
     * The bug itself, stated as a test: this is what the relay did before the translator touched the
     * packet. If this ever stops throwing, the codecs have been reconciled and the fix-up can go.
     */
    @Test
    void anUntranslated2168SubChunkCannotBeEncodedFor1001() {
        SubChunkPacket packet = subChunkWithAbsentOptionals();

        assertThrows(Exception.class, () -> encodeWith(Bedrock_v1001.CODEC, packet),
                "a 1.26.40 sub-chunk with absent optionals must be unencodable for 1.26.30 — if it is "
                        + "not, this test is no longer reproducing the production failure");
    }

    @Test
    void theUpgradeEdgeMakesItEncodable() {
        SubChunkPacket packet = subChunkWithAbsentOptionals();

        LegacyClientTo2168Translator.INSTANCE.translateClientbound(packet, context());

        assertDoesNotThrow(() -> encodeWith(Bedrock_v1001.CODEC, packet),
                "after translation a 1.26.30 client must be able to receive the sub-chunk, or terrain "
                        + "never reaches a Java player");
    }

    @Test
    void absentPayloadsBecomeTheirEmptyEquivalents() {
        SubChunkPacket packet = subChunkWithAbsentOptionals();

        LegacyClientTo2168Translator.INSTANCE.translateClientbound(packet, context());

        SubChunkData subChunk = packet.getSubChunks().get(0);
        assertNotNull(subChunk.getData(), "1.26.30 always writes a length-prefixed payload");
        assertEquals(0, subChunk.getData().readableBytes(), "an absent 1.26.40 payload means no blocks");
        assertEquals(HeightMapDataType.NO_DATA, subChunk.getHeightMapType(),
                "a height map claiming HAS_DATA with no data is what makes the serializer dereference null");
        assertEquals(HeightMapDataType.NO_DATA, subChunk.getRenderHeightMapType());
    }

    /**
     * A sub-chunk that already carries everything must come through untouched — the fix-up is for
     * absent optionals only, and must not quietly discard real terrain.
     */
    @Test
    void aFullyPopulatedSubChunkIsLeftAlone() {
        SubChunkPacket packet = new SubChunkPacket();
        packet.setDimension(0);
        packet.setCacheEnabled(false);
        packet.setCenterPosition(Vector3i.ZERO);
        SubChunkData subChunk = new SubChunkData();
        subChunk.setPosition(Vector3i.from(1, 2, 3));
        subChunk.setResult(SubChunkRequestResult.SUCCESS);
        subChunk.setData(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4}));
        subChunk.setHeightMapType(HeightMapDataType.HAS_DATA);
        subChunk.setHeightMapData(Unpooled.wrappedBuffer(new byte[256]));
        subChunk.setRenderHeightMapType(HeightMapDataType.NO_DATA);
        packet.getSubChunks().add(subChunk);

        LegacyClientTo2168Translator.INSTANCE.translateClientbound(packet, context());

        assertEquals(4, subChunk.getData().readableBytes());
        assertEquals(HeightMapDataType.HAS_DATA, subChunk.getHeightMapType());
        assertDoesNotThrow(() -> encodeWith(Bedrock_v1001.CODEC, packet));
    }

    /** Exactly the shape a 1.26.40 backend produces for a sub-chunk it has no blocks for. */
    private static SubChunkPacket subChunkWithAbsentOptionals() {
        SubChunkPacket packet = new SubChunkPacket();
        packet.setDimension(0);
        packet.setCacheEnabled(false);
        packet.setCenterPosition(Vector3i.ZERO);

        SubChunkData subChunk = new SubChunkData();
        subChunk.setPosition(Vector3i.from(0, 1, 0));
        subChunk.setResult(SubChunkRequestResult.SUCCESS_ALL_AIR);
        subChunk.setData(null);
        subChunk.setHeightMapType(HeightMapDataType.HAS_DATA);
        subChunk.setHeightMapData(null);
        subChunk.setRenderHeightMapType(HeightMapDataType.HAS_DATA);
        subChunk.setRenderHeightMapData(null);
        packet.getSubChunks().add(subChunk);
        return packet;
    }

    private static TranslationContext context() {
        return new TranslationContext(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, Bedrock_v2168.CODEC);
    }

    @SuppressWarnings("unchecked")
    private static void encodeWith(BedrockCodec codec, SubChunkPacket packet) {
        BedrockPacketDefinition<SubChunkPacket> definition =
                (BedrockPacketDefinition<SubChunkPacket>) codec.getPacketDefinition(SubChunkPacket.class);
        ByteBuf buffer = Unpooled.buffer();
        try {
            definition.getSerializer().serialize(buffer, codec.createHelper(), packet);
        } finally {
            buffer.release();
        }
    }
}
