package org.endstone.viaendlink;

import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.config.ProxyConfig;
import org.endstone.proxy.listener.BedrockProxyListener;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole chain, end to end, in one process: a Java client connects, the embedded ViaProxy and
 * ViaBedrock translate it to Bedrock 1001, the loopback bridge listener accepts the self-signed login,
 * and the player appears in the connected-player registry under their Java username.
 *
 * <p>No backend is involved and none is needed. Everything this test exists to prove happens before
 * the proxy dials one: authentication is the milestone, and a login that reaches the registry has
 * already crossed every new boundary — the classloader, the translator, the loopback listener and the
 * self-signed login path. The backend dial then fails, which is the correct place for this to stop.</p>
 *
 * <p><b>Known limitation: this harness is not currently reliable.</b> The hand-rolled client below has
 * driven a real join exactly once; most runs end with ViaBedrock closing its Bedrock connection
 * immediately after ViaProxy's "Filling player data" step, before any Bedrock packet reaches the
 * session layer. Real Minecraft clients join fine, so the suspect is this synthetic client rather than
 * the bridge — but that is a belief, not a measurement, so treat a failure here as "the harness is
 * still unreliable" until someone proves otherwise. {@code SelfSignedBridgeLoginTest} is what actually
 * pins the identity, secret, prefix and real-IP rules, and it does so without needing any of this.</p>
 *
 * <p><b>Requires ViaProxy.jar</b>, which is 47 MB and not vendored, so the test skips unless one is
 * pointed at:</p>
 *
 * <pre>gradle test -Djavabridge.viaProxyJar=D:/git/endstone-proxy/endstone-proxy/viabedrock/ViaProxy.jar</pre>
 */
class JavaPlayerJoinEndToEndTest {

    private static final String JAVA_USERNAME = "BridgeTester";
    /** Java 1.20.1 (763): the last version whose login has no configuration phase, so a client that stops after login-start does not leave the handshake half-finished. Any version ViaVersion knows works; a fixed one keeps the handshake bytes simple. */
    private static final int JAVA_PROTOCOL = 763;
    private static final long JOIN_TIMEOUT_MILLIS = 30_000;

    @Test
    void aJavaClientReachesTheProxyAsAPlayer() throws Exception {
        Path viaProxyJar = configuredViaProxyJar();
        assumeTrue(viaProxyJar != null && Files.isRegularFile(viaProxyJar),
                "set -Djavabridge.viaProxyJar=<path to ViaProxy.jar> to run the end-to-end Java join test");

        int bedrockPort = freeUdpPort();
        int javaPort = freePort();
        int bridgePort = freeUdpPort();
        Path workDir = Files.createTempDirectory("endstone-java-bridge-test");
        java.net.DatagramSocket blackHole = new java.net.DatagramSocket(0);

        Properties properties = new Properties();
        properties.setProperty("listener.host", "127.0.0.1");
        properties.setProperty("listener.port", Integer.toString(bedrockPort));
        // Pin the backend to 1.26.40 so the join has to resolve the 1001 -> 2168 upgrade edge rather
        // than falling back to the client's own protocol the way auto-detection does with no backend.
        properties.setProperty("backend.protocol", "1.26.40");
        properties.setProperty("backend.host", "127.0.0.1");
        // A backend that accepts datagrams and answers nothing, rather than a closed port. A closed
        // loopback port answers ICMP unreachable, the backend dial fails in microseconds and the
        // player is unregistered again before any poll can see them — the join is real either way, but
        // only this version is observable. RakNet just retries against a silent socket, which holds the
        // session open long enough to assert on.
        properties.setProperty("backend.port", Integer.toString(blackHole.getLocalPort()));
        properties.setProperty("backendVerification.enabled", "false");
        properties.setProperty("security.sendConnectionCookie", "false");
        properties.setProperty("java.enabled", "true");
        properties.setProperty("java.host", "127.0.0.1");
        properties.setProperty("java.port", Integer.toString(javaPort));
        properties.setProperty("java.bridgePort", Integer.toString(bridgePort));
        properties.setProperty("java.viaProxyJar", viaProxyJar.toString());
        properties.setProperty("java.workDir", workDir.toString());
        // The harness has no Minecraft account, so it cannot pass Mojang verification. Production
        // leaves java.onlineMode at its default of true; SelfSignedBridgeLoginTest covers the identity
        // rules, and this test covers the transport and the bridge contract.
        properties.setProperty("java.onlineMode", "false");
        properties.setProperty("java.namePrefix", "*");

        ProxyConfig config = ProxyConfig.from(properties, Path.of("."));
        BedrockProxyListener listener = new BedrockProxyListener(config);
        listener.start();
        try {
            // Not an assumption: the jar was supplied, so a bridge that will not come up is the
            // failure this test is for. Skipping here once hid exactly that — a parent-first
            // classloader handing ViaProxy our netty 4.1 — behind a green build.
            assertTrue(isListening("127.0.0.1", javaPort),
                    "the embedded ViaProxy never bound its Java listener; the proxy output above says why");

            // Sample from before the client connects. The join is real but does not last: with no
            // backend to finish the login against, ViaBedrock gives up and the session closes again,
            // so what is asserted is that the player *arrived*, not that they stayed. Staying needs a
            // real Bedrock backend and belongs to the live test.
            AtomicReference<ClientLogin> seen = new AtomicReference<>();
            Thread sampler = new Thread(() -> {
                long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MILLIS;
                while (System.currentTimeMillis() < deadline && seen.get() == null) {
                    listener.connectedPlayers().findByName("*" + JAVA_USERNAME)
                            .ifPresent(connection -> seen.set(connection.clientLogin()));
                    Thread.onSpinWait();
                }
            }, "join-sampler");
            sampler.setDaemon(true);
            sampler.start();

            int javaClientPort = connectAsJavaClient("127.0.0.1", javaPort, JAVA_USERNAME);
            sampler.join(JOIN_TIMEOUT_MILLIS + 5_000);

            ClientLogin joined = seen.get();
            assertTrue(joined != null,
                    "a Java client that connected to the bridge never arrived as a player. The chain is "
                            + "Java client -> ViaProxy -> ViaBedrock (Bedrock 1001) -> loopback listener "
                            + "-> self-signed login; check the proxy output for where it stopped");
            assertEquals("*" + JAVA_USERNAME, joined.authData().displayName(),
                    "the Java username must survive the whole chain, wearing the configured prefix");
            assertEquals(javaClientPort, joined.bridgeClientAddress().getPort(),
                    "the player's address must be the one the bridge stamped into the login (their real "
                            + "Java socket), not the loopback address ViaBedrock dialled the bridge from — "
                            + "otherwise every Java player looks identical to a backend doing IP checks");
            assertEquals(
                    UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + joined.authData().xuid()).getBytes(StandardCharsets.UTF_8)),
                    joined.authData().identity(),
                    "a Java player's identity must be the derivation the rest of the proxy keys on"
            );
        } finally {
            listener.stop();
            blackHole.close();
        }
    }

    /**
     * A Java handshake and login-start, hand-rolled. That is all it takes: ViaBedrock opens its Bedrock
     * connection on login-start, so nothing past this point is needed to exercise the bridge, and
     * hand-rolling two packets avoids a client library dependency for two packets' worth of work.
     */
    private static int connectAsJavaClient(String host, int port, String username) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        // Deliberately left open: closing it would tear the session down before the assertion runs.
        OutputStream out = socket.getOutputStream();

        byte[] handshake = packet(0x00, body -> {
            writeVarInt(body, JAVA_PROTOCOL);
            writeString(body, host);
            body.writeShort(port);
            writeVarInt(body, 2); // next state: login
        });
        byte[] loginStart = packet(0x00, body -> {
            writeString(body, username);
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            body.writeLong(uuid.getMostSignificantBits());
            body.writeLong(uuid.getLeastSignificantBits());
        });
        out.write(handshake);
        out.write(loginStart);
        out.flush();
        return socket.getLocalPort();
    }

    private static Path configuredViaProxyJar() {
        String configured = System.getProperty("javabridge.viaProxyJar", System.getenv("VIAPROXY_JAR"));
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private static boolean isListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /** For the Java listener, which is TCP. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * For the Bedrock listeners, which are UDP. A free TCP port is not a free UDP one, and picking the
     * wrong family here fails the run with "Failed to bind Bedrock proxy listener" rather than anything
     * to do with what is being tested.
     */
    private static int freeUdpPort() throws IOException {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private interface BodyWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] packet(int packetId, BodyWriter writer) throws IOException {
        var body = new java.io.ByteArrayOutputStream();
        DataOutputStream bodyStream = new DataOutputStream(body);
        writeVarInt(bodyStream, packetId);
        writer.write(bodyStream);
        bodyStream.flush();

        var framed = new java.io.ByteArrayOutputStream();
        DataOutputStream framedStream = new DataOutputStream(framed);
        writeVarInt(framedStream, body.size());
        framedStream.write(body.toByteArray());
        framedStream.flush();
        return framed.toByteArray();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
