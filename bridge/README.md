# ViaEndlink bridge (vendored ViaProxy + ViaBedrock)

The Java Edition half of the proxy: a vendored ViaProxy and ViaBedrock, built here and embedded by
`proxy/` so that one process serves both editions.

```
viaendlink/bridge/
  src/ViaProxy/     upstream ViaProxy, plus the local patches listed below
  src/ViaBedrock/   upstream ViaBedrock, currently unpatched
  dist/ViaProxy.jar the built bridge — this is the file the proxy loads
  build/            Gradle output (inside src/ViaProxy/build)
```

## Why the source is vendored

Two reasons, and the second is the one that will matter most.

ViaBedrock speaks **Bedrock 1.26.30 (protocol 1001) and nothing newer**, while the fleet runs 1.26.40
(2168). Every Java player therefore arrives as a 1001 client, and the proxy carries a dedicated
upgrade edge for them. When ViaBedrock eventually follows Mojang, that edge and the
`target-version` in `JavaBridge` both move together — and having the source here is what makes it
possible to move first if upstream is slow.

The second reason is that the bridge has to tell the proxy things upstream has no reason to send:
who the player really is, and where they really came from.

## Local patches

Keep this list current. Everything else is upstream and should stay that way, so a future
`git pull` in `src/ViaProxy` is a merge rather than an archaeology exercise.

| File | Change |
| --- | --- |
| `src/ViaProxy/settings.gradle` | `includeBuild("../ViaBedrock")` so the build uses the ViaBedrock clone beside it instead of the published snapshot. Gradle substitutes `net.raphimc:ViaBedrock` automatically. |
| `src/ViaProxy/.../external_interface/EndstoneBridgeAuth.java` | **New file.** Mints ViaBedrock's login token instead of letting it self-sign a bare one, adding the player's real IP and port, their Mojang UUID, and a per-start shared secret. |
| `src/ViaProxy/.../external_interface/ExternalInterface.java` | One call to `EndstoneBridgeAuth.fill(...)` in `fillPlayerData`, for Bedrock targets with no account configured. |
| `src/ViaProxy/build.gradle` | Drops the webrtc-java natives and the Swing look-and-feel from the fat jar. See below. |
| `src/ViaBedrock/.../api/util/BedrockLineBreaks.java` | **New file.** Normalises real `
`, `
` and the *escaped* two-character `
` that JSON rawtext pipelines leave behind, so text that looks multi-line on Bedrock splits correctly. `-Dendstone.bridge.traceText=true` logs the raw string with escapes visible. |
| `src/ViaBedrock/.../rewriter/blockentity/SignBlockEntityRewriter.java` | Normalises sign text before splitting it into Java's four lines. |
| `src/ViaBedrock/.../protocol/packet/HudPackets.java` | Collapses breaks in titles, subtitles and the action bar to a separator (`endstone.bridge.titleLineSeparator`, default a space) — Java draws all three as one un-wrapped line. |
| `src/ViaBedrock/.../protocol/packet/JoinPackets.java` | Tab list header/footer are configurable and no longer advertise ViaBedrock. `endstone.bridge.tabListHeader` defaults to the level name, `endstone.bridge.tabListFooter` to empty. |
| `src/ViaBedrock/.../protocol/packet/ResourcePackPackets.java`, `.../storage/ResourcePackLoadStateTracker.java` | A "stack finished" reply is held until the server's pack stack actually arrives, instead of being sent the moment the Java client says the pack loaded. See below. |
| `src/ViaBedrock/.../protocol/model/ItemStackRequestSlot.java`, `.../model/ItemStackRequestAction.java` | **New files.** The `ItemStackRequest` wire model — slot addressing and the take/place/swap/drop/destroy/craft actions. See "Inventory" below. |
| `src/ViaBedrock/.../protocol/storage/ItemStackRequestTracker.java` | **New file.** Sends requests, predicts the result locally, and reconciles or rolls back when the server answers. |
| `src/ViaBedrock/.../api/model/container/ContainerClickTranslator.java` | **New file.** Turns a Java `ContainerClick` into the Bedrock item movements that mean the same thing, including the destination search Java's shift-click needs. |
| `src/ViaBedrock/.../api/model/container/Container.java` and its subclasses | Slot addressing (`requestSlot`, `resolveJavaSlot`) and a real `handleClick`, which upstream leaves returning false. Empty `validBlockTags` now means "do not check" so a container on a block with no block entity is not closed on its first tick. |
| `src/ViaBedrock/.../api/model/container/ChestContainer.java` | Takes a size and a slot name, so one class covers chests, barrels, shulkers, hoppers, droppers and crafters. |
| `src/ViaBedrock/.../protocol/packet/InventoryPackets.java` | Handles `ITEM_STACK_RESPONSE`; opens the container types that are plain storage; handles a click on the player's own inventory before the server has acknowledged the screen. |
| `src/ViaBedrock/.../protocol/packet/ClientPlayerPackets.java` | Dropping an item is an `ItemStackRequest`, not the legacy inventory transaction. |
| `src/ViaBedrock/.../protocol/packet/JoinPackets.java` | Pushes the tracked inventory to the Java client at `PlayerSpawn`. |
| `src/ViaBedrock/.../protocol/data/BedrockMappingData.java` | `getJavaMenuId(identifier)`, so the double chest can reach `generic_9x6` — one Bedrock container type covers both chest sizes. |
| `src/ViaBedrock/.../experimental/types/inventory/InventoryTransactionPacketType.java` | Reads the presence boolean that precedes the action list, which upstream omits. Without it the boolean is taken as the list length and every field after it is read one byte early, so a server-sent slot change — an item picked up off the ground — parses as a source nothing handles and is dropped in silence. The write side now announces the legacy slot list only for the request ids the reader looks for it under. |
| `src/ViaBedrock/.../experimental/ExperimentalFeatures.java` | A server-sent inventory transaction is applied whether or not it carries a legacy request id, and reaches the Java client through the tracker's tick like every other change rather than pushing its own packet. |
| `src/ViaBedrock/.../protocol/storage/InventoryTracker.java` | Announces the player's own inventory screen (`INTERACT OpenInventory`), and gives up waiting for the server to acknowledge it after a second instead of holding the click forever. |
| `src/ViaBedrock/.../protocol/storage/ChunkTracker.java` | Names any block entity a loaded chunk carried that could not be placed, once per type. Silence here looks like an invisible chest. |
| `src/ViaBedrock/.../api/model/container/ContainerClickTranslator.java` (`translateCreativeSlot`) | A creative Java client never sends a container click for its own inventory — its screen applies the click locally and reports the resulting slot contents. The movement is inferred from the difference and sent as the same take/place/swap as any other click. Taking a stack straight out of the creative menu still is not covered: that needs a `CraftCreative` id from `CreativeContent`, which nothing reads yet, and it is logged rather than silently dropped. |

Both patches are **inert unless `endstone.bridge.secret` is set**, which only the proxy does. This
jar still behaves exactly like upstream ViaProxy when run standalone.

### The resource pack handshake has an order, and fake-accept broke it

Bedrock's pack negotiation is three strictly ordered steps: the client answers `ResourcePacksInfo`
with `DownloadingFinished`, the server replies with the pack stack, and only then does the client
send `ResourcePackStackFinished`. ViaBedrock drives the first from the Java client's ACCEPTED — but
completes it on a worker thread, so the packet leaves a few milliseconds later — and the last
directly from the client's SUCCESSFULLY_LOADED.

A real Java client has to download the pack in between, which is far longer than that gap. ViaProxy's
`fake-accept-resource-packs` does not: it answers the push with ACCEPTED **and** SUCCESSFULLY_LOADED
in the same breath, so `ResourcePackStackFinished` overtakes the `DownloadingFinished` still in
flight. The server sees the handshake end before it began — it never sends a pack stack at all — and
kicks the `DownloadingFinished` that lands afterwards as an out-of-state packet.

On BDS that kick is `UNEXPECTED_PACKET`, and it arrives **after** the whole join sequence has been
sent, so the log shows a player who fully joined and was then dropped for no visible reason, ~3s in.
The proxy is holding `acceptServerResourcePacks=true` on purpose (a Java client cannot load a Bedrock
pack, and ViaBedrock's converted-pack server is on loopback where a remote player cannot reach it),
so the spoofer stays and ViaBedrock holds the reply until the stack arrives instead.

### Inventory: upstream stops at "read only"

Upstream ViaBedrock shows a Java player their inventory and lets them do nothing with it.
`Container.handleClick` returns false unconditionally, so every click was answered by re-sending the
window — the item snapped back — and `ITEM_STACK_RESPONSE` had no handler at all, which means the
fallback in `BedrockProtocol.registerPackets` cancelled it. Dropping an item and right-clicking a
block were implemented only inside `enable-experimental-features`, which is off by default, and
`registerPackets` cancels every serverbound packet with no handler: a Java player's right-click
reached the server as nothing whatsoever, so no container ever opened.

The backends run with `inventoriesServerAuth=true`, which decides the shape of the fix. Under
server-authoritative inventory the client does not report clicks; it sends an `ItemStackRequest`
naming the movements it wants, and the server applies the list whole or rejects it whole. Three
things must agree with the server or the request is refused: the slot's *kind* (the player's own 36
slots are addressed under two different names, and the offhand's only slot is addressed as slot 1),
the slot index within that kind (the 2x2 crafting grid is 28-31), and the stack network id, which is
the server's handle on the exact stack and is what makes a stale request safe to refuse.

The response says only how many items ended up where — never *which* item — so it cannot rebuild
the inventory on its own, only confirm or deny what we already believed. Hence the cycle in
`ItemStackRequestTracker`: snapshot, predict and show the player the result at once, then reconcile
counts and stack ids from the response, or restore the snapshot if the server said no. Rolling back
is the half that matters; without it a rejected click leaves the player building on an item that
does not exist.

**The request id must be negative and odd** — -1, -3, -5, … This is validated, and failing it is not
a soft failure. BDS refuses the packet and drops the connection:

```
packet 147 rejected (terminating connection): expected a valid ItemStackRequestId
readNoHeader failed! packetId: 147
```

which the player experiences as being kicked the instant they pick anything up in their inventory.
The sign is what distinguishes a client's id from a server-generated one. (This also settled an open
question: BDS *does* process the standalone `ItemStackRequestPacket`. Vanilla clients embed the
request in `PlayerAuthInput` instead and ViaBedrock never sets that flag, so it was worth confirming
the standalone route is honoured — it is.)

**One owner tells the Java client.** `Container.setItem` marks its container dirty and
`InventoryTracker.tick` sends the contents of anything dirty, once per tick. Nothing else pushes
inventory state. That is deliberate: while each packet handler forwarded its own change, whether the
player saw a change depended on which packet the server happened to use to report it — picking an
item up left the inventory looking unchanged until something unrelated forced a refresh, while
dropping one worked, because only some paths remembered to send. Converging on the tracked state
each tick makes that class of bug impossible and coalesces bursts of slot updates into one send.

**Not implemented.** Crafting tables, anvils, enchanting tables, brewing stands, grindstones and
looms are still refused at `CONTAINER_OPEN`. Their result slot is not a move but a craft, carrying
the recipe network id the client matched, and nothing here parses `CRAFTING_DATA` — so opening them
would show a result the player could never take. Creative middle-click, the click-drag and the
double-click gather resync instead of translating, for the same reason: no single request expresses
them.

### What the extra claims are for

`ep_ip` / `ep_port`
: Every Java player reaches the proxy over loopback, because the embedded ViaProxy is what dials it.
  Without these, the proxy — and any backend doing its own IP checks, and the connection throttle —
  sees `127.0.0.1` for every Java player on the server. The proxy prefers this address everywhere it
  reports or verifies a player's origin, including the backend verification handshake.

`ep_secret`
: The bridge listener accepts self-signed logins, which is only safe because it is bound to
  loopback. The secret narrows that from *any process on this host* to *the ViaProxy this proxy
  started*. It is regenerated every start and never written to disk.

`ep_uuid`
: The player's Mojang UUID, for future use. Identity today is derived from the name.

## What was cut from the jar, and what was not

**47.1 MB → 28.6 MB.** Two things came out, both measured with `unzip -l` rather than guessed at:

- **webrtc-java natives (~35 MB)** — six platform builds of the NetherNet transport's native library.
  NetherNet is how a client reaches a Bedrock server over Xbox networking; this bridge dials one
  server, over RakNet, on `127.0.0.1`, so that code never runs. Now `compileOnly`.
- **FlatLaf (~2 MB)** — the Swing look-and-feel. The proxy always starts ViaProxy as
  `config <file>`, which takes the CLI branch of `injectedMain`, so no UI class is ever loaded.

The NetherNet *transport* itself had to stay even though its natives did not: ViaProxy's config
parser resolves `NetherNetAddress` while reading `target-address`, so removing it fails at startup
with `Failed to load config … ClassNotFoundException: NetherNetAddress` before anything binds. That
was caught by booting the slimmed jar, which is worth doing after any change here — a fat jar that is
missing something loaded reflectively still builds perfectly.

**Nothing protocol-related was removed.** ViaVersion, ViaBackwards, ViaRewind, ViaLegacy and
ViaAprilFools are all registered during `ProtocolTranslator.init`, and between them are what makes
"any Java version" true. The slimmed jar still reports `1.7.2 (4)` through `26.2 (776)`.

> **Size is not speed.** Classes that are never loaded cost nothing at runtime, so this makes the
> upload smaller and nothing else. If Java players are lagging, look at heap first — the whole JVM
> runs the proxy, ViaVersion's mappings and ViaBedrock's chunk translation together.

## Building

**Use `build.ps1`. Do not run `gradlew build` by hand.**

```powershell
.\build.ps1 -TrustStore <truststore>    # -TrustStore only where TLS is intercepted
```

It builds both projects, stages `dist/ViaProxy.jar`, and then reads the finished jar back to confirm
each local patch is actually in it, refusing to stage the jar if any is missing.

That check is not paranoia. **ViaBedrock's `class-token-replacer` task does not rerun when its input
classes change.** Edit a ViaBedrock source file and Gradle recompiles it, reports BUILD SUCCESSFUL,
and then packages the *previous* build's classes — so the jar is silently missing your change and
nothing says so. ViaProxy's fat jar then sees an unchanged ViaBedrock artifact and stays up to date
too, which is how a tab-list patch got written, built, boot-tested and shipped with the branding
still in it. `build.ps1` deletes `build/classTokenReplacer` and `build/libs` first, which is the only
thing that reliably forces the stage to rerun.

If you must do it by hand, delete those two directories before building, and check the result:

```powershell
python -c "import zipfile; z=zipfile.ZipFile('dist/ViaProxy.jar'); print(z.read('net/raphimc/viabedrock/protocol/packet/JoinPackets.class').count(b'endstone.bridge.tabList'))"
```

The first build takes ~4 minutes and needs network access; later ones are about a minute. Take the
plain `-SNAPSHOT.jar`, not `+java8.jar` — the latter is the downgraded build for Java 8 runtimes.

## How the proxy finds this jar

`JavaBridge` resolves, in order: `java.viaProxyJar` from the config, then `ViaProxy.jar` in
`java.workDir`, then a copy embedded in `endstone-proxy.jar`. On a server, point `java.viaProxyJar`
at the deployed copy; the jar is ~47 MB and deliberately not embedded by default, because
`endstone-proxy.jar` is uploaded to every backend.

## Upstream

Pinned at the commits below. Neither is a release; both projects ship from `main`.

| Project | Commit | Version |
| --- | --- | --- |
| ViaProxy | `dae070d` | 3.4.13-SNAPSHOT |
| ViaBedrock | `9998f227` | 0.0.29-SNAPSHOT |

ViaBedrock is upstream-described as early in development. Java players will be second-class for a
while, and that is inherent to the approach rather than to this integration.
