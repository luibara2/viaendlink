# ViaEndlink

[![Build](https://github.com/luibara2/viaendlink/actions/workflows/build.yml/badge.svg)](https://github.com/luibara2/viaendlink/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/luibara2/viaendlink?include_prereleases&sort=semver)](https://github.com/luibara2/viaendlink/releases)
[![Licence](https://img.shields.io/github/license/luibara2/viaendlink)](LICENSE)
[![Status](https://img.shields.io/badge/status-beta-orange)](#status-beta-and-genuinely-so)
[![Java clients](https://img.shields.io/badge/Minecraft%20Java-1.7.2%20to%2026.2-brightgreen)](#how-it-works)
[![Bedrock backend](https://img.shields.io/badge/Bedrock%20backend-1.26.40-brightgreen)](#known-limits)

An addon for **[Endlink](https://github.com/luibara2/endlink)** that lets Minecraft: Java Edition
players join a Bedrock server.

Drop `ViaEndlink.jar` into Endlink's `plugins/` folder and Java clients from **1.7.2 to 26.2** can
connect. Remove it and the proxy is Bedrock-only again, with nothing left behind in its config. That
is the whole installation procedure.

It is not a standalone program: it needs a running
[Endlink](https://github.com/luibara2/endlink) proxy, which is what actually talks to your backend
servers. By analogy — if Endlink is Velocity, ViaEndlink is Geyser.

> ### Status: beta, and genuinely so
>
> This is the least finished part of the project, and the honest summary is that a Java player can
> get in and move around, not that the experience is good. **You cannot open a chest.** Read the
> whole of this box before installing it anywhere you care about.
>
> **Working**, tested against a Minecraft 1.26.40 backend: joining, chat, the player list, terrain
> loading, backend switching, Mojang account verification, and the player's real IP reaching the
> backend. Also: moving, dropping and swapping items, and storage containers — chests (single and
> double), barrels, shulker boxes, hoppers, droppers, dispensers and crafters.
>
> **Not working yet**: crafting tables, anvils, enchanting tables and brewing stands do not open,
> because taking their result is a craft carrying a recipe id and nothing here reads the recipe list
> yet; movement rubber-bands, because a Java client predicts motion and a Bedrock server does not
> agree; custom addon items, blocks and entities have no Java equivalent and render as unknown;
> block entities loaded from chunks are unreliable.
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

ViaEndlink does not build on its own. It compiles against the proxy's addon API, and Endlink picks
addons up as included builds from **sibling directories**, so clone both side by side and drive the
build from `endlink/`:

```
git clone https://github.com/luibara2/endlink.git
git clone https://github.com/luibara2/viaendlink.git

cd viaendlink/bridge
./build.ps1                        # builds the vendored translator, verifies the patches are in it

cd ../../endlink
./gradlew :viaendlink:build        # -> viaendlink/dist/ViaEndlink.jar
```

Needs a JDK 21+. `dist/ViaEndlink.jar` carries the translator inside it, so an operator places
exactly one file.

**Do not build the bridge with `gradlew` directly** — see `bridge/README.md` for why that silently
ships a jar without your changes, and why `build.ps1` reads the finished jar back to check.

`build.ps1` currently needs Windows: it calls `gradlew.bat` and uses Windows path separators. Only
the bridge step does — the addon itself builds anywhere. The CI workflow in `.github/workflows/`
runs both steps on every push, so it is a working reference if these instructions ever drift.

## Known limits

- ViaBedrock speaks Bedrock 1.26.30 and nothing newer, so installing this addon also lets real
  1.26.30 Bedrock clients onto 1.26.40 backends.
- ViaBedrock is upstream-described as early in development. Block entities and movement are
  imperfect.
- Containers that *craft* — crafting table, anvil, enchanting table, brewing stand, grindstone,
  loom — are refused rather than opened. Taking their result is not a move but a craft request
  naming the recipe, and the recipe list the server sends is not parsed yet, so the screen would
  show a result the player could never take. Storage containers work.
- Creative middle-click, click-dragging a stack across slots, and double-clicking to gather are not
  translated: no single Bedrock request expresses them, so the click is refused and the window is
  resynced.
- Inventory interaction needs `interactionFeatures=true` (the default), which turns on ViaBedrock's
  own `enable-experimental-features`. With it off, right-clicking does nothing at all.
- Java draws titles, subtitles, the action bar and name tags as a single un-wrapped line, so
  multi-line Bedrock text is collapsed there. Signs do get their four lines.
- Custom addon items, blocks and entities are not mapped to Java equivalents yet.

## Related

| | |
| --- | --- |
| [Endlink](https://github.com/luibara2/endlink) | **Required.** The Bedrock proxy this plugs into — without it there is nothing to install ViaEndlink onto |
| [EndlinkGuard](https://github.com/luibara2/endlinkguard) | The backend plugin. Verifies proxy joins and rejects direct ones — install it on every backend |
| [Endstone](https://github.com/EndstoneMC/endstone) | The recommended backend server: plugin-capable Bedrock Dedicated Server |
| [ViaProxy](https://github.com/ViaVersion/ViaProxy) / [ViaBedrock](https://github.com/RaphiMC/ViaBedrock) | The translators doing the real work here. If Java-to-Bedrock translation is broken, the fix usually belongs upstream |

## Third-party code

`bridge/src/` vendors **ViaProxy** and **ViaBedrock** (both GPL-3.0) so the local patches applied to
them are reviewable and rebuildable. Those patches are listed in `bridge/README.md`, which with the
upstream `.git` directories removed is the only record of what differs from upstream.

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
