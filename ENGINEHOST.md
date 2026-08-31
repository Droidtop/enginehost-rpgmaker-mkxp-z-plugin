# enginehost RPG Maker plugin

This repository is the native RGSS part of Enginehost's `rpgmaker` family:
RPG Maker XP, VX, and VX Ace. RPG Maker 2000/2003 (EasyRPG) and MV/MZ (the
deployed JavaScript runtime) live in separate upstream-oriented repositories;
all advertise the same Enginehost family with different contexts.

The upstream-facing `dev` branch tracks mkxp-z without Enginehost packaging.
`plugin-core` begins at a recorded `dev` revision and contains only the
reusable Android port, Enginehost activity contract, dual-Ruby plumbing,
bundle metadata, signing workflow, and platform fixes needed by every RGSS
release. A canonical `plugin/<release-line>` branch begins at its chosen
mkxp-z revision and merges `plugin-core`. After that:

- reusable wrapper or Android changes are made on `plugin-core`, then merged;
- ordinary upstream improvements are merged into the applicable release line;
- a newer incompatible engine baseline gets a new release line;
- a narrowly required newer upstream fix may be cherry-picked with its source
  revision recorded, without silently advancing the entire engine.

Do not implement an engine/game compatibility fix only on a release branch if
the same change belongs in every future plugin. Conversely, do not turn
`plugin-core` into a rolling copy of all upstream mkxp-z development.

The Android scaffold is derived from the GPL-2.0-or-later
`thehatkid/mkxp-z-android` project at commit
`468fe8128a40cc7d2cba4ac7fbe21a82787de255`. It builds this branch's mkxp-z
source directly rather than carrying a second copy of the engine. Upstream
copyright, `COPYING`, and dependency licenses remain intact.

The in-process `MainActivity` accepts the host's canonical resolved session
extras: game path, selected capability, engine context/version, runtime
requirements, save root, executable, and JSON options. RGSS games run in place
through mkxp-z. No game is copied and the bundle does not request storage
permissions of its own.

`options` is an engine-owned configuration object. The native runtime overlays
it on mkxp-z configuration in memory after the host has applied the
authoritative game-folder configuration. A version branch may further restrict
or translate settings for its Ruby ABI and compatibility profiles.

Ruby is selection-critical rather than an ordinary option. RGSS capabilities
advertise either `runtimeComponents: { "ruby": "3.1.3" }` or
`runtimeComponents: { "ruby": "1.9.2" }`; a game that needs the original VX
Ace ABI requests
`runtimeRequirements: { "ruby": "1.9.2" }` and will resolve only to the
matching capability. The plugin bundles both internally namespaced Ruby/mkxp
native builds in one Enginehost archive and routes by capability ID; it does
not require a separate installed plugin for each Ruby. The APK inside the
archive is a resource container, not a separately installed Android package.
Native libraries are never loaded loose from game storage.

Android payloads and signed `.enginehost.tar.xz` archives are produced in CI.
Both runtime selections retain mkxp-z's MiniFFI-backed
Win32API compatibility surface. This is still an incomplete implementation:
MGQ Paradox may expose additional Windows API or RGSS compatibility gaps.
