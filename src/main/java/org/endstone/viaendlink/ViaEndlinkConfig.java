package org.endstone.viaendlink;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * ViaEndlink's own configuration, in {@code plugins/ViaEndlink/config.properties}.
 *
 * <p>Separate from the proxy's config on purpose: Endlink without this addon has no Java settings to
 * explain, and this addon's settings travel with the jar that reads them. Keys lost the {@code java.}
 * prefix in the move — there is nothing else in this file for them to be confused with.</p>
 *
 * @param enabled     lets an operator keep the jar in place but turned off, without deleting it
 * @param listenAddress where Java clients connect. Public
 * @param bridgePort  the loopback Bedrock port the embedded translator connects back into. Bound to
 *                    127.0.0.1 only and never advertised. Self-signed logins are accepted there and
 *                    nowhere else, so it must not become routable
 * @param onlineMode  require a real, Mojang-authenticated account. Defaults on; see the template
 * @param namePrefix  prepended to Java players' display names. Applied after identity is derived
 * @param viaProxyJar explicit path to the translator jar, or null to use the copy inside this addon
 * @param resourcePackUrl a Java-format resource pack offered to Java players on join. Bedrock packs
 *                        cannot be sent to a Java client, so a converted pack has to be hosted
 *                        somewhere and named here; empty offers none
 * @param acceptServerResourcePacks accept the Bedrock server's own packs on the player's behalf
 *                        instead of prompting. Bedrock servers can require a pack a Java client
 *                        could never load, which otherwise stops the join outright
 * @param interactionFeatures turn on ViaBedrock's {@code enable-experimental-features}, which is
 *                        where upstream keeps block placing, item use and block interaction. Without
 *                        it a Java player cannot right-click anything at all — no container opens,
 *                        no block is placed — because the serverbound handlers are simply not
 *                        registered and unregistered packets are cancelled
 */
public record ViaEndlinkConfig(
        boolean enabled,
        InetSocketAddress listenAddress,
        int bridgePort,
        boolean onlineMode,
        String namePrefix,
        Path viaProxyJar,
        String resourcePackUrl,
        boolean acceptServerResourcePacks,
        boolean interactionFeatures,
        String bridgeSecret
) {
    private static final int DEFAULT_PORT = 25565;
    private static final int DEFAULT_BRIDGE_PORT = 19136;
    private static final int SECRET_BYTES = 32;
    private static final String CONFIG_RESOURCE = "/viaendlink.example.properties";

    public ViaEndlinkConfig {
        if (listenAddress == null) {
            throw new IllegalArgumentException("listenAddress cannot be null");
        }
        if (bridgePort < 1 || bridgePort > 65535) {
            throw new IllegalArgumentException("bridgePort must be a valid port: " + bridgePort);
        }
        if (listenAddress.getPort() == bridgePort) {
            throw new IllegalArgumentException("port and bridgePort cannot be the same port");
        }
        namePrefix = namePrefix == null ? "" : namePrefix;
        resourcePackUrl = resourcePackUrl == null ? "" : resourcePackUrl.trim();
        // It goes verbatim into the generated ViaProxy config, so a quote would end the YAML scalar
        // early and produce a file that fails to parse rather than a pack that fails to download.
        if (resourcePackUrl.contains("'") || resourcePackUrl.contains("\n") || resourcePackUrl.contains("\r")) {
            throw new IllegalArgumentException("resourcePackUrl cannot contain quotes or newlines: " + resourcePackUrl);
        }
        // The prefix becomes part of the name a backend stores player data against, and Bedrock names
        // are short. Cap it here, where the error can still name the setting.
        if (namePrefix.length() > 4) {
            throw new IllegalArgumentException("namePrefix is limited to 4 characters: '" + namePrefix + "'");
        }
        if (namePrefix.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("namePrefix cannot contain whitespace: '" + namePrefix + "'");
        }
    }

    /** Reads {@code config.properties} from the data folder, writing the documented template first if absent. */
    public static ViaEndlinkConfig loadOrCreate(Path dataFolder) throws IOException {
        Path file = dataFolder.resolve("config.properties");
        if (Files.notExists(file)) {
            try (InputStream template = ViaEndlinkConfig.class.getResourceAsStream(CONFIG_RESOURCE)) {
                if (template != null) {
                    Files.copy(template, file);
                }
            }
        }
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
        }
        return from(properties, dataFolder);
    }

    public static ViaEndlinkConfig from(Properties properties, Path dataFolder) {
        String jar = properties.getProperty("viaProxyJar", "").trim();
        return new ViaEndlinkConfig(
                Boolean.parseBoolean(properties.getProperty("enabled", "true").trim()),
                new InetSocketAddress(
                        properties.getProperty("host", "0.0.0.0").trim(),
                        intProperty(properties, "port", DEFAULT_PORT)
                ),
                intProperty(properties, "bridgePort", DEFAULT_BRIDGE_PORT),
                Boolean.parseBoolean(properties.getProperty("onlineMode", "true").trim()),
                properties.getProperty("namePrefix", "").trim(),
                jar.isEmpty() ? null : dataFolder.resolve(jar).toAbsolutePath().normalize(),
                properties.getProperty("resourcePackUrl", "").trim(),
                Boolean.parseBoolean(properties.getProperty("acceptServerResourcePacks", "true").trim()),
                Boolean.parseBoolean(properties.getProperty("interactionFeatures", "true").trim()),
                generateBridgeSecret()
        );
    }

    /**
     * The loopback address the translator dials. Never routable: binding it anywhere else would expose
     * the self-signed login path to the network.
     */
    public InetSocketAddress bridgeAddress() {
        return new InetSocketAddress("127.0.0.1", bridgePort);
    }

    /**
     * A fresh secret per start, handed to the translator and required back in every login it forwards.
     *
     * <p>It is what separates "a login from our own translator" from "a login from anything else that
     * can reach loopback". Regenerated each start on purpose: it never needs to outlive the process
     * that shares it, and nothing can be replayed against the next one.</p>
     */
    private static String generateBridgeSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }
}
