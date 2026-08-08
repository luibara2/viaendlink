package org.endstone.viaendlink;

import org.endstone.proxy.plugin.EndlinkPlugin;
import org.endstone.proxy.plugin.PluginContext;
import org.endstone.proxy.plugin.TrustedListenerSpec;
import org.endstone.proxy.protocol.CanonicalProtocol;

/**
 * ViaEndlink: Minecraft: Java Edition players on a Bedrock network.
 *
 * <p>Drop {@code ViaEndlink.jar} into {@code plugins/} and Java clients can join. Take it out and
 * Endlink is a plain Bedrock proxy again, with nothing left behind in its config. That is the whole
 * installation procedure, and it is why this class is so short: the work is in {@link JavaBridge},
 * and everything the proxy needs to know is expressed as two contributions.</p>
 *
 * <p><b>The two contributions.</b> A translator that speaks Bedrock 1.26.30 needs the proxy to accept
 * a 1001 client against a 2168 backend, which is an <em>upgrade</em> edge the proxy's own graph never
 * has. And it needs somewhere to connect that will accept its self-signed logins — a loopback
 * listener, carrying this run's secret so nothing else on the host can use it.</p>
 *
 * <p><b>Operators should know</b> that the upgrade edge is a property of the protocol graph rather
 * than of one connection, so installing this addon also lets real Bedrock 1.26.30 clients onto
 * 1.26.40 backends.</p>
 */
public final class ViaEndlinkPlugin implements EndlinkPlugin {

    private ViaEndlinkConfig config;
    private JavaBridge bridge;
    private PluginContext context;

    @Override
    public void onEnable(PluginContext context) throws Exception {
        this.context = context;
        this.config = ViaEndlinkConfig.loadOrCreate(context.dataFolder());

        if (!config.enabled()) {
            context.info("Disabled by config; Java players cannot join.");
            return;
        }

        context.addProtocolUpgrade(
                CanonicalProtocol.V1_26_30,
                CanonicalProtocol.V1_26_40,
                LegacyClientTo2168Translator.INSTANCE
        );
        context.addTrustedListener(new TrustedListenerSpec(
                config.bridgeAddress(),
                CanonicalProtocol.V1_26_30.codec(),
                config.bridgeSecret(),
                config.namePrefix()
        ));
    }

    @Override
    public void onProxyReady() throws Exception {
        if (!config.enabled()) {
            return;
        }
        // Started here rather than in onEnable because the first thing it does is dial the trusted
        // listener, which does not exist until the proxy has bound everything.
        bridge = new JavaBridge(config, config.bridgeSecret(), context.dataFolder());
        bridge.start();
        context.info(String.format(
                "Java Edition players on %s:%d (Java 1.7.2-26.2), bridged as Bedrock 1.26.30 into 127.0.0.1:%d. "
                        + "Account verification: %s. Name prefix: %s.",
                config.listenAddress().getHostString(),
                config.listenAddress().getPort(),
                config.bridgePort(),
                config.onlineMode() ? "on (Mojang-authenticated accounts only)" : "OFF",
                config.namePrefix().isEmpty() ? "none" : "'" + config.namePrefix() + "'"
        ));
        if (!config.onlineMode()) {
            context.info("WARNING: onlineMode=false. Java players are NOT checked against Mojang, so anyone "
                    + "can join under any name, including an existing player's. Their identity is derived "
                    + "from that name, so they also inherit that player's permissions and backend data.");
        }
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
    }
}
