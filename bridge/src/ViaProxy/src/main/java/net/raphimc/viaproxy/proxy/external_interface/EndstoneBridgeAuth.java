/*
 * LOCAL ADDITION (endstone-proxy). Not part of upstream ViaProxy.
 *
 * See endstone-proxy/java-bridge/README.md for the full list of local modifications.
 */
package net.raphimc.viaproxy.proxy.external_interface;

import io.jsonwebtoken.Jwts;
import net.raphimc.viabedrock.api.util.FNV1;
import net.raphimc.viabedrock.protocol.storage.AuthData;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Supplies ViaBedrock's login token when ViaProxy is embedded inside endstone-proxy.
 *
 * <p>Left to itself, ViaBedrock mints a self-signed token carrying only the player's name and an XUID
 * derived from it. That is enough to join, but it leaves the proxy two things it cannot get any other
 * way:</p>
 *
 * <ul>
 *   <li><b>The player's real IP.</b> Every Java player reaches the proxy over loopback, so without
 *       this every one of them looks like 127.0.0.1 — to the logs, to the connection throttle, and to
 *       any backend that does its own IP checks.</li>
 *   <li><b>Proof the login came from this ViaProxy.</b> The bridge listener accepts self-signed
 *       logins, which is safe only because it is bound to loopback. The shared secret narrows that
 *       from "any local process" to "the ViaProxy inside this JVM".</li>
 * </ul>
 *
 * <p>The token is otherwise shaped exactly like the one ViaBedrock would have produced — same claim
 * names, same {@code |FNV1(username)|} XUID derivation — so a player's identity is unchanged whether
 * or not this class is in play. The name and UUID come from {@code ProxyConnection.getGameProfile()},
 * which ViaProxy has already replaced with Mojang's authoritative profile when online mode is on;
 * that is what makes the name trustworthy rather than merely claimed.</p>
 *
 * <p>Inert unless {@code endstone.bridge.secret} is set, so this ViaProxy still behaves like upstream
 * when run standalone.</p>
 */
public final class EndstoneBridgeAuth {

    /** Set by the proxy before it starts the embedded ViaProxy. Its presence is what enables this. */
    public static final String SECRET_PROPERTY = "endstone.bridge.secret";

    private EndstoneBridgeAuth() {
    }

    public static boolean isEnabled() {
        final String secret = System.getProperty(SECRET_PROPERTY);
        return secret != null && !secret.isEmpty();
    }

    public static void fill(final ProxyConnection proxyConnection) {
        final String secret = System.getProperty(SECRET_PROPERTY);
        if (secret == null || secret.isEmpty()) {
            return;
        }

        final String username = proxyConnection.getGameProfile().getName();
        final UUID javaUuid = proxyConnection.getGameProfile().getId();
        final long rawXuid = FNV1.fnv1_64(username.getBytes(StandardCharsets.UTF_8));
        final String xuid = String.valueOf(Math.abs(rawXuid));

        final KeyPair sessionKeyPair = generateEcdsa384KeyPair();
        final String encodedPublicKey = Base64.getEncoder().encodeToString(sessionKeyPair.getPublic().getEncoded());
        final Instant now = Instant.now();

        final String multiplayerToken = Jwts.builder()
                .signWith(sessionKeyPair.getPrivate(), Jwts.SIG.ES384)
                .header().add("x5u", encodedPublicKey).and()
                .claim("aud", "api://auth-minecraft-services/multiplayer")
                .claim("cpk", encodedPublicKey)
                .claim("leguuid", UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + xuid).getBytes(StandardCharsets.UTF_8)))
                .claim("mid", Long.toHexString(rawXuid).toUpperCase(Locale.ROOT))
                .claim("nid", "")
                .claim("nname", "")
                .claim("pid", "")
                .claim("pname", "")
                .claim("xid", xuid)
                .claim("xname", username)
                // The endstone-proxy additions. Names are prefixed so they cannot collide with a claim
                // Mojang may add later.
                .claim("ep_secret", secret)
                .claim("ep_ip", clientIp(proxyConnection))
                .claim("ep_port", clientPort(proxyConnection))
                .claim("ep_uuid", javaUuid == null ? "" : javaUuid.toString())
                .claim("ep_edition", "java")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .compact();

        proxyConnection.getUserConnection().put(new AuthData(multiplayerToken, sessionKeyPair));
    }

    private static String clientIp(final ProxyConnection proxyConnection) {
        if (proxyConnection.getC2P() != null
                && proxyConnection.getC2P().remoteAddress() instanceof InetSocketAddress address
                && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "";
    }

    private static int clientPort(final ProxyConnection proxyConnection) {
        if (proxyConnection.getC2P() != null
                && proxyConnection.getC2P().remoteAddress() instanceof InetSocketAddress address) {
            return address.getPort();
        }
        return 0;
    }

    private static KeyPair generateEcdsa384KeyPair() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            return generator.generateKeyPair();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to generate the bridge session key pair", e);
        }
    }
}
