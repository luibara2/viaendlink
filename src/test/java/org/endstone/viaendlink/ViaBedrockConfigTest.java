package org.endstone.viaendlink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one ViaBedrock setting this addon insists on, and the file it has to share.
 *
 * <p>ViaBedrock keeps block placing, item use and block interaction behind
 * {@code enable-experimental-features}, and it defaults to false. {@code BedrockProtocol} cancels
 * every serverbound packet it has no handler registered for, and with the flag off {@code
 * USE_ITEM_ON} has none — so a Java player's right-click reaches the Bedrock server as nothing at
 * all. No container opens, no block is placed, and nothing is logged to explain it.</p>
 *
 * <p>Unlike {@code viaproxy.yml}, this file cannot simply be regenerated each start: ViaBedrock owns
 * the rest of it and writes its own state back — the resource pack port it picked, the blob cache
 * mode. Hence the surgical edit, and hence these tests.</p>
 */
class ViaBedrockConfigTest {

    @Test
    void theFileIsCreatedWhenItDoesNotExist(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("viabedrock.yml");

        JavaBridge.applyViaBedrockConfig(file, true);

        assertEquals(List.of("enable-experimental-features: true"), Files.readAllLines(file));
    }

    @Test
    void everythingElseInTheFileIsLeftAlone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("viabedrock.yml");
        Files.write(file, List.of(
                "# If true, enables experimental features",
                "enable-experimental-features: false",
                "blob-cache: \"memory\"",
                "resource-pack-port: 41234"
        ), StandardCharsets.UTF_8);

        JavaBridge.applyViaBedrockConfig(file, true);

        List<String> lines = Files.readAllLines(file);
        assertEquals(List.of(
                "# If true, enables experimental features",
                "enable-experimental-features: true",
                "blob-cache: \"memory\"",
                "resource-pack-port: 41234"
        ), lines, "the operator's blob cache choice and the port ViaBedrock picked must survive a restart");
    }

    @Test
    void theKeyIsAppendedWhenTheFileHasSomeOtherContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("viabedrock.yml");
        Files.write(file, List.of("blob-cache: \"disk\""), StandardCharsets.UTF_8);

        JavaBridge.applyViaBedrockConfig(file, true);

        assertEquals(List.of("blob-cache: \"disk\"", "enable-experimental-features: true"), Files.readAllLines(file));
    }

    @Test
    void theSettingCanBeTurnedBackOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("viabedrock.yml");
        Files.write(file, List.of("enable-experimental-features: true"), StandardCharsets.UTF_8);

        JavaBridge.applyViaBedrockConfig(file, false);

        assertEquals(List.of("enable-experimental-features: false"), Files.readAllLines(file));
    }

    /** Rewriting must be idempotent: this runs on every single start. */
    @Test
    void repeatedStartsDoNotAccumulateLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("viabedrock.yml");

        for (int start = 0; start < 5; start++) {
            JavaBridge.applyViaBedrockConfig(file, true);
        }

        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    void interactionFeaturesDefaultToOn() {
        ViaEndlinkConfig config = ViaEndlinkConfig.from(new java.util.Properties(), Path.of("."));

        assertTrue(config.interactionFeatures(),
                "a Java player who cannot right-click anything has no working game, so this is only a "
                        + "setting to give an operator an escape hatch, not a feature to opt into");
    }

}
