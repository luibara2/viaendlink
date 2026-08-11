package org.endstone.viaendlink;


import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs ViaProxy (and with it ViaVersion, ViaBackwards, ViaRewind, ViaLegacy and ViaBedrock) inside
 * this JVM so that one jar serves both editions.
 *
 * <p><b>Why a child classloader and not the application classpath.</b> This proxy is built against
 * netty <b>4.1.101</b> — the version {@code ProxyPass/protocol} pins, and the one the RakNet
 * transport binds internals of — while ViaProxy ships netty <b>4.2.x</b>. The two cannot share a flat
 * classpath, and shading a 47 MB prebuilt fat jar to relocate netty fails at runtime rather than at
 * link time. A child-first {@link URLClassLoader} keeps Via's netty, log4j, guava and gson entirely
 * out of our classpath for free. The two halves then talk over loopback RakNet, which is a
 * process-internal boundary either way.</p>
 *
 * <p>ViaProxy needs no patching to run this way: it self-attaches its instrumentation agent, so its
 * mixins apply to classes loaded by this child loader with no launcher flags. That was verified
 * before the design was built on it — see {@code JAVA-SUPPORT-PLAN.md} §0.3 for the probe and its
 * output.</p>
 *
 * <p><b>Two hazards this class exists to contain.</b> ViaProxy's {@code ConsoleHandler.hookConsole()}
 * replaces {@link System#out} and {@link System#err} process-wide, which would swallow
 * {@code ProxyConsole}'s command prompt; the streams are captured and restored around the boot. And
 * ViaProxy sets {@code user.dir} to whatever it picks as its working directory, so that too is
 * pinned and restored. Neither is a bug in ViaProxy — they are reasonable for a program that owns
 * its process, and this one does not.</p>
 */
public final class JavaBridge {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final String VIAPROXY_MAIN_CLASS = "net.raphimc.viaproxy.ViaProxy";
    private static final String EMBEDDED_JAR_RESOURCE = "/ViaProxy.jar";

    /**
     * Read by the patched {@code ExternalInterface}/{@code EndstoneBridgeAuth} inside the embedded
     * ViaProxy. A system property is the one channel that crosses the classloader boundary without
     * either side needing a class from the other.
     */
    private static final String SECRET_PROPERTY = "endstone.bridge.secret";

    private final ViaEndlinkConfig config;
    private final InetSocketAddress bridgeAddress;
    private final String bridgeSecret;
    private final Path dataFolder;
    private URLClassLoader classLoader;
    private Thread thread;

    public JavaBridge(ViaEndlinkConfig config, String bridgeSecret, Path dataFolder) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        if (dataFolder == null) {
            throw new IllegalArgumentException("dataFolder cannot be null");
        }
        this.config = config;
        this.bridgeAddress = config.bridgeAddress();
        this.bridgeSecret = bridgeSecret;
        this.dataFolder = dataFolder;
    }

    /**
     * Boots the embedded ViaProxy and returns once its Java listener is accepting connections.
     *
     * @throws IOException when ViaProxy.jar cannot be found or the listener never came up. The caller
     *                     is expected to treat this as non-fatal: a Bedrock server must not fail to
     *                     start because an optional translator did not
     */
    public void start() throws IOException {
        Path workDir = workDir();
        Files.createDirectories(workDir);
        Path jar = resolveViaProxyJar(workDir);
        Path viaProxyConfig = workDir.resolve("viaproxy.yml");
        Files.writeString(viaProxyConfig, renderConfig(), StandardCharsets.UTF_8);
        applyViaBedrockConfig(workDir.resolve("viabedrock.yml"), config.interactionFeatures());

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", workDir.toString());
            if (bridgeSecret != null && !bridgeSecret.isEmpty()) {
                System.setProperty(SECRET_PROPERTY, bridgeSecret);
            }
            // Nothing here is fatal to the proxy, but a hard-exit inside ViaProxy would be, so keep
            // the inputs it validates under our control: the config we just wrote and a jar we found.
            // The parent is the *platform* loader, not ours. A URLClassLoader delegates to its parent
            // first, so handing it this class's loader would have ViaProxy resolve netty from our
            // classpath — netty 4.1, against code built for 4.2 — and the failure is a silent one: it
            // links, then never finishes binding. ViaProxy.jar is a fat jar and needs nothing from us,
            // so giving it only the JDK is both the simplest isolation and the most complete.
            classLoader = new URLClassLoader(
                    "viaproxy",
                    new URL[]{jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader()
            );
            Class<?> viaProxy = classLoader.loadClass(VIAPROXY_MAIN_CLASS);
            Method main = viaProxy.getMethod("main", String[].class);

            thread = new Thread(() -> {
                try {
                    main.invoke(null, (Object) new String[]{"config", viaProxyConfig.toString()});
                } catch (Throwable throwable) {
                    originalErr.println("Java bridge: embedded ViaProxy stopped: " + throwable);
                }
            }, "java-bridge-viaproxy");
            thread.setContextClassLoader(classLoader);
            thread.setDaemon(true);
            thread.start();

            if (!awaitListener(config.listenAddress())) {
                throw new IOException("embedded ViaProxy did not bind " + describe(config.listenAddress())
                        + " within " + STARTUP_TIMEOUT.toSeconds() + "s");
            }
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            throw new IOException("ViaProxy.jar at " + jar + " is not a ViaProxy build this bridge understands",
                    exception);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }

    public void stop() {
        try {
            if (classLoader != null) {
                Class<?> viaProxy = classLoader.loadClass(VIAPROXY_MAIN_CLASS);
                viaProxy.getMethod("stopProxy").invoke(null);
            }
        } catch (Throwable ignored) {
            // Best effort: the JVM is going down anyway, and a translator that will not shut down
            // cleanly must not stop the proxy from shutting down.
        } finally {
            if (thread != null) {
                thread.interrupt();
            }
            try {
                if (classLoader != null) {
                    classLoader.close();
                }
            } catch (IOException ignored) {
                // as above
            }
        }
    }

    /**
     * Jar resolution, in order: the configured path, then {@code <workDir>/ViaProxy.jar}, then a copy
     * extracted from this jar's own resources.
     *
     * <p>The embedded copy is what makes "one jar" literally true, but it also adds ~47 MB to a file
     * that gets uploaded to every backend, so an operator who would rather keep the small jar can
     * drop ViaProxy.jar beside the config instead and it wins.</p>
     */
    private Path resolveViaProxyJar(Path workDir) throws IOException {
        if (config.viaProxyJar() != null) {
            if (!Files.isRegularFile(config.viaProxyJar())) {
                throw new IOException("java.viaProxyJar points at " + config.viaProxyJar() + ", which does not exist");
            }
            return config.viaProxyJar();
        }
        Path local = workDir.resolve("ViaProxy.jar");
        if (Files.isRegularFile(local)) {
            return local;
        }
        try (InputStream embedded = JavaBridge.class.getResourceAsStream(EMBEDDED_JAR_RESOURCE)) {
            if (embedded == null) {
                throw new IOException("no ViaProxy.jar: set java.viaProxyJar, or put one at " + local
                        + ", or build the proxy with -PembedViaProxy=<path> to ship it inside the jar");
            }
            Path extracted = workDir.resolve("ViaProxy.jar");
            Files.copy(embedded, extracted, StandardCopyOption.REPLACE_EXISTING);
            return extracted;
        }
    }

    /** Everything this addon writes — the translator jar, its config, its logs and caches — lives here. */
    private Path workDir() {
        return dataFolder;
    }

    /**
     * Sets ViaBedrock's {@code enable-experimental-features} without touching anything else in its file.
     *
     * <p>ViaBedrock keeps <em>block placing, item use and block interaction</em> behind that flag, and
     * it defaults to false. {@code BedrockProtocol.registerPackets} cancels every serverbound packet it
     * has no handler for, and with the flag off {@code USE_ITEM_ON} has no handler — so a Java player's
     * right-click reaches the Bedrock server as nothing at all. No container opens, no block is placed,
     * and there is no error anywhere to say why. It has to be on for the game to be playable.</p>
     *
     * <p>Rewritten in place rather than regenerated, unlike {@code viaproxy.yml}. ViaBedrock owns the
     * rest of this file — the blob cache mode, the resource pack host and port it picked — and writes
     * its own defaults back into it, so replacing the whole file every start would undo both its
     * bookkeeping and any operator edit. The file is flat {@code key: value} YAML, so replacing the one
     * line is exact; if the key is absent it is appended, and if the file is absent it is created with
     * only that key and ViaBedrock fills in the rest.</p>
     */
    static void applyViaBedrockConfig(Path file, boolean interactionFeatures) throws IOException {
        String setting = "enable-experimental-features: " + interactionFeatures;
        List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();

        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("enable-experimental-features:")) {
                lines.set(i, setting);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(setting);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * ViaProxy's config file. Only the keys that matter here are set; ViaProxy fills in every default
     * it does not find, so this stays short instead of tracking their whole schema.
     *
     * <p>Two settings carry the whole security posture.</p>
     *
     * <p>{@code proxy-online-mode} decides whether Java players must own a real Minecraft account.
     * With it on, ViaProxy runs the encryption handshake and checks the session against Mojang's
     * {@code hasJoinedServer}, kicking anything that fails — and, crucially, <em>replaces</em> the
     * profile with the one Mojang returns, so the name that reaches this proxy is the authenticated
     * one rather than whatever the client typed. That is what stops both cracked clients and players
     * impersonating each other, so it defaults on and turning it off is a deliberate act.</p>
     *
     * <p>{@code auth-method: NONE} is about the <em>other</em> end and stays as it is: it means
     * ViaBedrock does not log in to us with an Xbox account. An account-backed login would give every
     * Java player the <em>same</em> XUID and trip the duplicate-login check. Identity instead comes
     * from the Mojang-verified name, via the token the patched ViaProxy mints.</p>
     */
    private String renderConfig() {
        return """
                # Generated by endstone-proxy. Edits are overwritten on every start.
                bind-address: %s
                target-address: %s
                target-version: Bedrock 1.26.30
                connect-timeout: 8000
                proxy-online-mode: %s
                auth-method: NONE
                betacraft-auth: false
                backend-proxy-url: ''
                backend-haproxy: false
                frontend-haproxy: false
                chat-signing: true
                compression-threshold: 256
                allow-beta-pinging: false
                ignore-protocol-translation-errors: false
                suppress-client-protocol-errors: false
                allow-legacy-client-passthrough: false
                bungeecord-player-info-passthrough: false
                rewrite-handshake-packet: true
                rewrite-transfer-packets: true
                custom-motd: ''
                custom-favicon-path: ''
                resource-pack-url: '%s'
                wildcard-domain-handling: NONE
                simple-voice-chat-support: false
                fix-fabric-particle-api: true
                fake-accept-resource-packs: %s
                skip-config-state-packet-queue: false
                log-ips: true
                log-client-status-requests: false
                """.formatted(
                describe(config.listenAddress()),
                describe(bridgeAddress),
                Boolean.toString(config.onlineMode()),
                config.resourcePackUrl(),
                Boolean.toString(config.acceptServerResourcePacks())
        );
    }

    private static boolean awaitListener(InetSocketAddress address) {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        InetSocketAddress probe = address.getAddress() != null && address.getAddress().isAnyLocalAddress()
                ? new InetSocketAddress("127.0.0.1", address.getPort())
                : address;
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(probe, 1000);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static String describe(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
