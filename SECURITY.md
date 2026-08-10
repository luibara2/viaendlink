# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub's
[security advisory form](https://github.com/luibara2/viaendlink/security/advisories/new). That opens a
channel only you and the maintainer can see, and lets a fix exist before the problem is public.

Tell me what you can: what the flaw allows, how to reproduce it, and which versions you tested. A
proof of concept helps but is not required to report.

## Why this addon needs its own policy

ViaEndlink is the only thing in an Endlink network that is allowed to say "this login is genuine"
without an Xbox signature behind it.

The translator has no Xbox account, so it cannot sign the Bedrock login it produces for a Java
player. Endlink therefore exposes a **trusted listener**: a Bedrock listener that accepts a
self-signed login, bound to loopback only, and gated on a secret generated fresh at every proxy
start. Two things and only two things stop that from being an open door onto the whole network —
the listener never binds a routable address, and every login through it must carry that secret.

So a bug here does not merely break Java support. It can hand an attacker an authenticated session
on every backend behind the proxy.

## What is in scope

- **Reaching the trusted listener from off-host**, or getting a login accepted through it without
  the current start's secret. Either one turns the addon into an authentication bypass for the
  entire network.
- **Leaking the shared secret** — into a log line, an error message, a config the translator writes,
  a crash report, or anything reachable by a connected player.
- **Joining without a valid Mojang account** while `onlineMode` is on, or joining under a name the
  session server did not confirm. Name spoofing matters as much as cracked access here: identity,
  and therefore inventory and permissions, is derived from the verified name.
- **Forging the forwarded IP**, so a player appears to the proxy or a backend as some other address
  and defeats a ban, a throttle or an allowlist.
- **Escaping the isolated classloader** the translator runs in, or otherwise reaching proxy internals
  a Java client should not touch.
- **Remote crashes or resource exhaustion** in the addon reachable by an unauthenticated client,
  beyond what the configured limits are meant to allow.

## What is not

- **Bugs in ViaProxy, ViaVersion or ViaBedrock themselves.** Report those to
  [ViaProxy](https://github.com/ViaVersion/ViaProxy) or
  [ViaBedrock](https://github.com/RaphiMC/ViaBedrock) upstream, where a fix helps everyone. `bridge/`
  vendors their sources so local patches are reviewable; that does not make this the right place to
  fix an upstream flaw. If a local patch under `bridge/` is what introduces the problem, that *is* in
  scope — say so.
- **Anything requiring local code execution on the proxy host.** An attacker there has already won:
  the loopback listener trusts local addons by design, and the secret is in the proxy's memory.
- **The known-broken gameplay surface** — containers not opening, movement rubber-banding, unmapped
  custom items and entities, unreliable block entities. These are documented limitations, not
  vulnerabilities. See the README.
- **The older-version translation chain**, which is documented as incomplete and unsupported. Run the
  current Minecraft release on both ends.
- **`onlineMode = false`.** Turning session verification off means anyone can join as anyone; that is
  what the option does, and it says so in the config template. Findings that depend on it are not
  vulnerabilities.

## A note on the upgrade edge

Installing this addon registers a Bedrock 1.26.30 → 1.26.40 upgrade edge in the proxy's protocol
graph, because that is the newest Bedrock version the translator speaks. A side effect is that real
Bedrock 1.26.30 clients can then join 1.26.40 backends. That is expected and documented, not a
vulnerability — but those clients are authenticated normally through Xbox, so it is not an
authentication weakness either. Report anything that suggests otherwise.

## Supported versions

This is early work in progress with no long-term support branches. Fixes land on `main` and go out in
the next release. Only the latest release is supported.
