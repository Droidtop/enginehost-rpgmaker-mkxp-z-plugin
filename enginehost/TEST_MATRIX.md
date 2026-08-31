# RPG Maker RGSS acceptance matrix

A green build proves packaging, not game compatibility. Every release line is
tested in place through Enginehost, with the game folder unmodified except for
an explicit user-authored `enginehost.json` when one is needed.

## Required checks per tested game

1. Resolve the correct `engineContext`, full engine version, capability, and
   Ruby runtime from the effective folder-authoritative configuration.
2. Reach the game's own title/menu screen with audio and rendered text.
3. Navigate the menu using the engine-specific controller mapping.
4. Begin a game, create a save, exit the runtime, relaunch it, and load that
   save. Record whether the title uses its game folder or Enginehost's shared
   save root; do not redirect a game-folder save merely for uniformity.
5. Exercise at least one encrypted archive/resource and any configured RTP
   lookup. Record missing Win32API/MiniFFI calls rather than masking them.
6. Repeat from a path containing spaces and non-ASCII text when a suitable
   title is available.

## On-device library available 2026-08-31

| Context | Title | Detected runtime | Ruby request | Current result |
|---|---|---|---|---|
| VX Ace | MGQ Paradox 3.06 | RGSS 3.01 | 1.9.2 | Pending first bundle launch |

No XP or VX title was found in the current on-device RPGMaker folder, so those
contexts may be build-verified but cannot be called game-verified yet. The same
folder contains four MV titles and one MZ title; those belong to the separate
MV/MZ repository's test matrix and must not be used to claim RGSS coverage.

## Release evidence

For each row, record the exact bundle ID/hash, plugin commit, CI run, game path
class (ordinary/spaced/non-ASCII), title-screen screenshot, controller result,
save path, and successful post-relaunch load. A regression in any one of those
dimensions keeps that game/context incomplete even if the process stays alive.
