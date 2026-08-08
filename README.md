# ViaEndlink

Minecraft: Java Edition players on an Endlink network.

Drop `ViaEndlink.jar` into Endlink's `plugins/` folder and Java clients from **1.7.2 to 26.2** can
join a Bedrock server. Remove it and the proxy is Bedrock-only again, with nothing left behind in its
config. That is the whole installation procedure.

By analogy: if Endlink is Velocity, ViaEndlink is Geyser.

> ### Status: early work in progress
>
> This is the least finished part of the project, and the honest summary is that a Java player can
> get in and move around, not that the experience is good.
>
> **Working**, tested against a Minecraft 1.26.40 backend: joining, chat, the player list, terrain
> loading, backend switching, Mojang account verification, and the player's real IP reaching the
> backend.
>
> **Not working yet**: containers (chests, crafting tables) do not open; movement rubber-bands,
> because a Java client predicts motion and a Bedrock server does not agree; custom addon items,
> blocks and entities have no Java equivalent and render as unknown; block entities loaded from
> chunks are unreliable.
>
> ViaBedrock, which does the actual translation, is upstream-described as early in development. Much
> of the above is inherited from that rather than added here.

## How it works

The addon embeds ViaProxy — and through it ViaVersion, ViaBackwards, ViaRewind, ViaLegacy and
ViaBedrock — in an isolated classloader inside the proxy's own process. Java clients connect to it,
it translates them to Bedrock, and it speaks that Bedrock to a loopback-only listener inside Endlink.
Java players therefore enter the proxy at exactly the same point Bedrock players do, and get backend
switching, permissions and failover without any of that code knowing Java exists.

Two things it asks the proxy for, through the addon API:

- **A protocol upgrade edge.** The translator speaks Bedrock 1.26.30 while backends run 1.26.40, and
  the proxy's own graph only ever translates newer clients down to older backends.
- **A trusted loopback listener.** The translator has no Xbox account to sign a login with, so that
  listener accepts self-signed logins — safe only because it binds `127.0.0.1` and because every
  login must carry a secret generated fresh each start.

## Identity and security

`onlineMode` defaults to **on**: the translator runs the encryption handshake, checks the session
against Mojang, kicks anything that fails, and takes the player's name and UUID from Mojang's answer
rather than from whatever the client claimed. That is what stops both cracked clients and one player
joining under another's name.

A Java player's identity is derived from their verified name, and their real IP is carried into the
proxy so logs, throttling and backend IP checks see the player rather than `127.0.0.1`.

## Configuration

`plugins/ViaEndlink/config.properties`, written with comments on first start. Port, online mode, a
name prefix to distinguish Java players in chat, and a resource pack URL.

## Building

```
bridge/build.ps1        # builds the vendored translator and verifies the local patches are in it
gradle :viaendlink:build
```

`dist/ViaEndlink.jar` carries the translator inside it, so an operator places exactly one file.

**Do not build the bridge with `gradlew` directly** — see `bridge/README.md` for why that silently
ships a jar without your changes.

## Known limits

- ViaBedrock speaks Bedrock 1.26.30 and nothing newer, so installing this addon also lets real
  1.26.30 Bedrock clients onto 1.26.40 backends.
- ViaBedrock is upstream-described as early in development. Containers, block entities and movement
  are imperfect.
- Java draws titles, subtitles, the action bar and name tags as a single un-wrapped line, so
  multi-line Bedrock text is collapsed there. Signs do get their four lines.
- Custom addon items, blocks and entities are not mapped to Java equivalents yet.

## Third-party code

`bridge/src/` vendors **ViaProxy** and **ViaBedrock** (both GPL-3.0) so the local patches applied to
them are reviewable and rebuildable. Those patches are listed in `bridge/README.md`.
`JAVA-SUPPORT-PLAN.md` records the design and the measurements behind it.

## Licence

ViaEndlink is licensed under the **GNU General Public License v3.0** — see `LICENSE`.

**This is not a free choice.** ViaEndlink embeds and links
[ViaProxy](https://github.com/ViaVersion/ViaProxy) and
[ViaBedrock](https://github.com/RaphiMC/ViaBedrock), both GPL-3.0, and vendors their sources under
`bridge/`. A work built on GPL-3.0 code and distributed must itself be GPL-3.0. Anyone who receives
this addon is entitled to its source, including the local patches — which is why those sources are
vendored here rather than pulled in silently at build time.

Endlink itself is Apache 2.0 and stays that way: it does not contain or link any GPL code. Running a
GPL addon alongside it does not change the proxy's licence.
