package org.endstone.viaendlink;

import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.ProtocolBinding;
import org.endstone.proxy.protocol.ProtocolRegistry;
import org.endstone.proxy.protocol.TranslationContext;
import org.endstone.proxy.protocol.ModernClientTo1001Translator;


import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1001 &rarr; 2168 upgrade edge, which exists so Java players can reach a 1.26.40 backend:
 * ViaBedrock speaks Bedrock 1001 and nothing newer.
 *
 * <p>That the packets themselves survive the hop is proved by {@link CrossProtocolPacketSweepTest},
 * which already sweeps this direction ("every 1.26.30 packet re-encodes for a 1.26.40 client"). What
 * is tested here is the routing: that the edge exists when asked for, that it is <em>absent</em>
 * otherwise, and that registering one backwards still fails loudly.</p>
 */
class LegacyClientTo2168TranslatorTest {

    @Test
    void aJavaBridgeClientOn1001ReachesA2168Backend() {
        ProtocolRegistry registry = registryWithAddon();

        Optional<ProtocolBinding> binding = registry.findBinding(1001, 2168);

        assertTrue(binding.isPresent(),
                "with Java support on, a Bedrock 1001 client (all ViaBedrock speaks) must be able to "
                        + "reach a 2168 backend, or every Java player is rejected at login");
        assertEquals(1001, binding.get().clientCodec().getProtocolVersion());
        assertEquals(2168, binding.get().backendCodec().getProtocolVersion());
        assertSame(LegacyClientTo2168Translator.INSTANCE, binding.get().translator());
    }

    /**
     * The edge also lets a real 1.26.30 <em>Bedrock</em> client onto a 1.26.40 backend, which reverses
     * a documented support decision. That is the operator's call, so it must not appear merely because
     * the default registry was built.
     */
    @Test
    void theUpgradeEdgeIsAbsentWithoutTheAddon() {
        assertTrue(ProtocolRegistry.createDefault().findBinding(1001, 2168).isEmpty(),
                "a proxy without this addon must not let older clients onto newer backends");
    }

    @Test
    void theDowngradeEdgeStillWorksWithTheUpgradeEdgeRegistered() {
        Optional<ProtocolBinding> binding = registryWithAddon().findBinding(2168, 1001);

        assertTrue(binding.isPresent(), "adding an upgrade edge must not disturb the path that runs in production");
        assertSame(ModernClientTo1001Translator.INSTANCE, binding.get().translator());
    }

    /**
     * Both edges live on the same pair of nodes in opposite directions, so a BFS bug would show up as
     * a path that walks 1001 -&gt; 2168 -&gt; 1001 and back. Anything beyond one hop is that bug.
     */
    @Test
    void theUpgradeEdgeDoesNotCreateALongerPath() {
        assertEquals(1, registryWithAddon().findPath(1001, 2168).orElseThrow().size());
        assertEquals(1, registryWithAddon().findPath(2168, 1001).orElseThrow().size());
    }

    /** What {@code ViaEndlinkPlugin.onEnable} contributes, built directly so the test does not need a proxy. */
    private static ProtocolRegistry registryWithAddon() {
        return ProtocolRegistry.defaultBuilder()
                .upgradeEdge(CanonicalProtocol.V1_26_30, CanonicalProtocol.V1_26_40,
                        LegacyClientTo2168Translator.INSTANCE)
                .build();
    }

    @Test
    void anUpgradeEdgeRegisteredBackwardsIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ProtocolRegistry.builder().upgradeEdge(
                        CanonicalProtocol.V1_26_40,
                        CanonicalProtocol.V1_26_30,
                        LegacyClientTo2168Translator.INSTANCE
                ));

        assertTrue(exception.getMessage().contains("older protocol to a newer one"), exception.getMessage());
    }

    /**
     * The one packet whose two shapes share no field. It is dropped on the downgrade edge for exactly
     * the same reason, and re-encoding it here would emit seven absent optionals to a 1.26.40 backend.
     */
    @Test
    void updateSoundDataIsDroppedRatherThanRelayed() {
        TranslationContext context = new TranslationContext(
                org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001.CODEC,
                org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168.CODEC,
                org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168.CODEC);

        assertNull(LegacyClientTo2168Translator.INSTANCE.translateClientbound(
                new ClientboundUpdateSoundDataPacket(), context));
    }

    @Test
    void everyOtherPacketIsPassedThroughUntouched() {
        TranslationContext context = new TranslationContext(
                org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001.CODEC,
                org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168.CODEC,
                org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168.CODEC);
        TextPacket packet = new TextPacket();
        AvailableCommandsPacket commands = new AvailableCommandsPacket();

        assertSame(packet, LegacyClientTo2168Translator.INSTANCE.translateClientbound(packet, context));
        assertSame(packet, LegacyClientTo2168Translator.INSTANCE.translateServerbound(packet, context));
        assertSame(commands, LegacyClientTo2168Translator.INSTANCE.translateCommandTree(commands, context));
    }
}
