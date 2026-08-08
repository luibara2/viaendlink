/*
 * LOCAL ADDITION (endstone-proxy / ViaEndlink). Not part of upstream ViaBedrock.
 *
 * See endstone-proxy/viaendlink/bridge/README.md for the full list of local modifications.
 */
package net.raphimc.viabedrock.api.util;

import java.util.logging.Level;
import net.raphimc.viabedrock.ViaBedrock;

/**
 * Normalises the several things a Bedrock server can mean by "new line" into one thing Java code can
 * split on, and collapses them where Java cannot render them at all.
 *
 * <p><b>Why this is not just {@code split("\n")}.</b> ViaBedrock already splits sign text on {@code
 * \n}, and signs still arrived on a single line, which means the text reaching it did not contain a
 * real newline. Bedrock text reaches a server through several pipelines that each encode a break
 * differently:</p>
 *
 * <ul>
 *   <li>A player typing into a sign produces a real {@code U+000A}.</li>
 *   <li>Anything that came through JSON rawtext — {@code /titleraw}, {@code /tellraw}, most addon and
 *       script APIs — can arrive with the <em>two characters</em> {@code \} and {@code n} still
 *       escaped, because the Bedrock client unescapes them at render time rather than on the wire.</li>
 *   <li>Text edited on Windows tooling can carry {@code \r\n} or a bare {@code \r}.</li>
 * </ul>
 *
 * <p>All three look identical to a player on Bedrock and only the first survives a naive split, which
 * is exactly the reported symptom: the text is all there, all on line one.</p>
 *
 * <p>Set {@code -Dendstone.bridge.traceText=true} to log the raw string with its escapes visible the
 * moment it is normalised. That is the only way to tell these three cases apart from the outside.</p>
 */
public final class BedrockLineBreaks {

    private static final boolean TRACE = Boolean.getBoolean("endstone.bridge.traceText");

    private BedrockLineBreaks() {
    }

    /**
     * Turns every encoding of a line break into a real {@code \n}, so a caller can split on that alone.
     *
     * <p>The literal {@code \\n} case is deliberately handled <em>before</em> {@code \r} so that a
     * literal {@code \\r\\n} collapses to one break rather than two.</p>
     */
    public static String normalize(final String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String normalized = text;
        if (normalized.indexOf('\\') >= 0) {
            normalized = normalized.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
        }
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        if (TRACE && !normalized.equals(text)) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO,
                    "Normalised Bedrock line breaks: " + visible(text) + " -> " + visible(normalized));
        } else if (TRACE) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Bedrock text (unchanged): " + visible(text));
        }
        return normalized;
    }

    /**
     * Normalises, then replaces the breaks with {@code separator} for the Java surfaces that are drawn
     * as a single line.
     *
     * <p>Java titles, subtitles and the action bar are rendered with one call and no wrapping, so a
     * newline in them is not a line break — it is at best nothing and at worst a missing glyph. Bedrock
     * allows multi-line text in all three. Nothing can make Java draw two lines there, so the honest
     * translation is to keep the words readable and separated rather than run together.</p>
     */
    public static String flatten(final String text, final String separator) {
        final String normalized = normalize(text);
        if (normalized.indexOf('\n') < 0) {
            return normalized;
        }
        // A trailing or leading break would otherwise become a stray separator.
        return normalized.replaceAll("\n+", java.util.regex.Matcher.quoteReplacement(separator)).strip();
    }

    /** Renders control characters visibly, so a log line shows which of the three cases arrived. */
    private static String visible(final String text) {
        return '"' + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") + '"';
    }
}
