# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `Pizza`
- Slug: `pizza`
- Repository: `carmelosantana/minecraft-pizza`
- Owner: `Carmelo Santana`
- Target version: `0.1.0`
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `pizza.jar`
- External services: `none`
- Status: `active`
- Autonomy: `autonomous`

Naming chain, established at gate 1 and verified by `minecraft-plugin-scaffold`'s exit check:
slug `pizza` → repository `carmelosantana/minecraft-pizza` → Maven `artifactId` `pizza` (group
`org.xpfarm`) → shaded JAR `pizza-<version>.jar` → updater destination `pizza.jar` →
`plugin.yml` `name: Pizza`.

Full design: [`docs/superpowers/specs/2026-07-22-pizza-menu-design.md`](superpowers/specs/2026-07-22-pizza-menu-design.md).

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded.
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are defined.
- [x] Known limitations and any intentionally withheld gates are recorded.

**Purpose.** Younger players on `play.xpfarm.org` cannot reliably type commands, and most useful
commands are op-gated anyway. Pizza gives them a touch-friendly menu — opened with `/pizza` — that
performs tasks across the existing xpfarm plugin set on their behalf: claim a starter kit, put its
armour on, get a custom item, travel to another world, and invite a friend along. Staff get a
permission-gated panel for handing out kits and setting world time and weather. Pizza adds no
gameplay of its own; it is a front end over commands that already exist.

**Commands.**

| Command | Arguments | Who |
|---|---|---|
| `/pizza` | none | Any player (`pizza.use`, default true). Aliases `/menu`, `/order`. |
| `/pizza reload` | none | `pizza.reload`, default op |

**Events.** `PlayerQuitEvent` — drop pending invites and cancel their timeout tasks; also the
point at which Floodgate flushes open forms, so handlers must tolerate an offline player.
`InventoryClickEvent` and `InventoryCloseEvent` — chest renderer only, Java players only. No join
listener: the menu is command-only and nothing is pushed at players.

**Permissions.** `pizza.use` (default `true`) opens the menu; `pizza.staff` (default `op`) reveals
the staff panel; `pizza.invite` (default `true`) allows sending travel invites; `pizza.reload`
(default `op`) allows `/pizza reload`. Config buttons may name any permission node, but these four
are what the code itself checks and therefore what `PluginDescriptorTest` asserts.

**Configuration.** `config.yml` holds the entire menu as data — a `menus` map of named menus, each
with `title`, `content`, and a `buttons` list. A button is `label` (string), optional `image`
(`{type: path|url, data: string}`), optional `permission` (string), and exactly one action:
`open` (submenu name), `command` (string), or `invite` (boolean). Command buttons additionally
carry `run-as` (`console` | `player` | `player-elevated`, default `console`), optional `cooldown`
(duration string), for `player-elevated` a `grant` list of permission nodes, and two optional
expansion markers: `worlds: true` (one button per entry in `allowed-worlds`, substituting
`%world%`) and `each-online: true` (dispatch once per online player, substituting `%target%` —
this is what implements give-to-all). Top level also holds `allowed-worlds` (list),
`command-allowlist` (list of permitted command roots), `invite-timeout` (duration), and `messages`
(map of player-facing strings). Validation at load: unknown action keys, a button with zero or more
than one action, a command root absent from `command-allowlist`, a `player-elevated` button with no
`grant`, and the combination of `each-online` with `player-elevated` are each refused, logged, and
omitted from the rendered menu.

**Persistence.** None. Cooldowns and pending invites are in-memory and intentionally do not
survive a restart. Pizza writes no files beyond its own `config.yml`.

**Dependencies.** `softdepend: [floodgate]` for load ordering. Floodgate API
`org.geysermc.floodgate:api:2.2.5-SNAPSHOT` at Maven scope `provided`, from
`https://repo.opencollab.dev/main/` with snapshots enabled; Cumulus `org.geysermc.cumulus:cumulus:1.1.2`
pinned explicitly at `provided` rather than relied on transitively. Neither is shaded. There are no
hard plugin dependencies: Starter Pack, WorldCRUD, Magic Carpet and the rest are reached by command
dispatch, so a missing plugin costs a button, not startup.

*Deviation recorded deliberately:* the ecosystem convention (documented in Farmers Market's
`plugin.yml`) is that Floodgate is reached reflectively and never placed on the compile classpath.
Pizza uses `provided` scope instead. The convention protects against bundling Floodgate and
against a hard runtime requirement; `provided` satisfies both, since it is excluded from
`maven-jar-plugin` output and from `maven-shade-plugin` by default. Reflection is adequate for the
boolean `isBedrockPlayer` check Farmers Market performs and inadequate for form handling, where
every response read becomes another reflective invocation with no compile-time checking against
Cumulus signature churn. Approved by the owner on `2026-07-22`.

**External integrations.** `none`. Pizza makes no outbound network calls, so gate 5's
external-service contract does not apply to this plugin. Umami reporting of button usage was
considered during planning and deferred to a later milestone.

**Acceptance checks.** These are the basis for gate 6 unit tests and gate 7a runtime verification.

1. `/pizza` on a Bedrock client opens a Cumulus `SimpleForm` with the configured buttons.
2. `/pizza` on a Java client opens a chest GUI with equivalent buttons.
3. A Bedrock player is never routed to the chest renderer, and a Bedrock player with a linked Java
   account is still detected as Bedrock (`isFloodgatePlayer`, not the UUID-prefix test).
4. The plugin loads, enables, and serves the chest GUI on a server with Floodgate absent, with no
   `NoClassDefFoundError`.
5. "Claim my kit" gives the starter pack to a player holding no `starterpack.admin` permission.
6. "Put my armour on" equips them via the existing `/starterpack equip <player>`.
7. Pressing a cooled-down button reports the remaining time and dispatches nothing.
8. A button whose command root is absent from `command-allowlist` is refused at config load and
   the menu renders without it.
9. World travel succeeds via `player-elevated`, and the granted permission is gone afterwards —
   verified by confirming the player still cannot run `/worldcrud teleport` directly.
10. An invite that is accepted teleports the recipient; declined, closed, timed-out, and
    superseded invites each leave them in place and produce exactly one outcome.
11. An invitee who disconnects with the form still open produces no exception.
12. `/pizza reload` re-reads configuration without a server restart.

**Known limitations.**

- The chest GUI is a degraded fallback, not a second first-class experience: Geyser renders a
  server-opened container by placing a temporary fake chest block near the player, which fails
  when that position is obstructed or outside world bounds. Bedrock players are never routed to it.
- Cooldowns and pending invites reset on restart, by design — the alternative is persistence
  machinery for a family server's item catalogue.
- Config-driven command dispatch breaks if a wrapped plugin renames a subcommand. Mitigated by a
  startup pass resolving every configured command root against `Bukkit.getCommandMap()` and
  logging failures; mitigated, not eliminated.
- The Floodgate API has no non-SNAPSHOT release published, so CI compiles against a snapshot. It
  never ships inside the JAR, so this is a build-reproducibility concern only.
- Cumulus 1.1.2 has no per-button callbacks — that landed only on the unreleased 2.0 master, which
  has had no commits since 2024-06-26 — so `SimpleForm` response handling is index-based.
- Sending any form force-closes a Bedrock player's open container, because `GeyserSession#doSendForm`
  closes inventories first or the form will not display. Mitigated for unsolicited invites by
  deferring while an inventory is open; inherent to forms otherwise.
- Neither Floodgate nor Geyser snapshots are certified against Paper 26.1.2 specifically; Geyser
  has 26.x support in flight. Worth an explicit smoke test at gate 7a.
- No gates are intentionally withheld. Status is `active`, so gates 2 through 12 all apply.
- Deferred to milestone 2, deliberately out of v1 scope: Umami analytics, a physical clickable
  menu item, first-join auto-open, crafting-recipe display, party travel, and homes/warps.

## 2. Repository

- [x] Repository is `carmelosantana/minecraft-pizza` with an SSH `origin` and `main` branch.
      Created and pushed `2026-07-22`: `origin` is `git@github.com:carmelosantana/minecraft-pizza.git`,
      branch `main` tracking `origin/main`, clean worktree. The first attempt was blocked by the
      Claude Code permission classifier (not by the owner, and not by any failing evidence — autonomy
      is `autonomous`, so the authorization was already granted); the owner elected to retry and the
      commands succeeded when issued individually rather than chained.
- [x] Existing user-owned worktree changes were identified and preserved. The working directory was
      empty at gate 1 preflight; there was nothing to preserve.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation.
      `rg -n 'herobrinesystems' . --hidden -g '!target/**' -g '!.git/**'` returns exactly one hit:
      the text of the checkbox on this line. All ten sibling plugin checklists carry the identical
      line, so this is the template's own label rather than a reference to the obsolete identity.

## 3. Metadata

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent. Full
      661-line AGPL-3.0 text in `LICENSE`; `pom.xml` `<licenses>` names "GNU Affero General Public
      License v3.0 or later" pointing at `https://www.gnu.org/licenses/agpl-3.0.html`.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present. `pom.xml`
      `<url>` and `<developers>`; `plugin.yml` `author` and `website`.
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is
      documented — `README.md`, under "Playing", as the join hostname for both Java and Bedrock.
- [x] New work uses the `org.xpfarm` Maven group. No existing-coordinate carve-out applies; this is
      a new plugin with no prior published coordinates.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are
      consistent. Verified: project `<artifactId>pizza</artifactId>` (the coordinate directly under
      `<project>`; the other `<artifactId>` matches in `pom.xml` are dependency and build-plugin
      coordinates), `plugin.yml` `name: Pizza`, shaded JAR `pizza-0.1.0.jar`, updater destination
      `pizza.jar`.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation. `config.yml`
      holds only menu structure, world names, and player-facing strings; there are no endpoints,
      tokens, or credentials anywhere in the tree.

## 4. Compatibility

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`,
      matching the API compiled against. `mvn clean verify` green with `maven.compiler.release` 25
      against `io.papermc.paper:paper-api:26.1.2.build.74-stable`; gate 7a confirmed Paper loaded the
      plugin (`Loading server plugin Pizza v0.1.0`), so the descriptor and bytecode are runtime-valid,
      not merely compile-valid.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and
      declared. No hard plugin dependencies — every other xpfarm plugin is reached by command
      dispatch, so a missing one costs a button, not startup. `softdepend: [floodgate]` for load
      ordering. Floodgate API + Cumulus are `provided` scope, never shaded (verified: `unzip -l` on
      the shaded JAR returns no `org.geysermc` entry).
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and
      protocol behavior. The plugin is Bedrock-first by design: native Cumulus forms for Bedrock
      (no Java-only chat input), chest GUI for Java, and Bedrock players are never routed to the chest
      renderer. Identity uses `isFloodgatePlayer` (handles linked accounts), never the UUID-prefix
      test. Player-name substitution rejects whitespace/control characters only, permitting the
      non-ASCII and comma characters Floodgate leaves in Bedrock names — so a legitimate Bedrock child
      is never locked out. Gate 7a confirmed Geyser, Floodgate, and ViaVersion all start green
      alongside Pizza on the Legendary stack. What a Bedrock client actually *renders* is gate 12.

## 5. External services

- [x] External integrations are `none`. Pizza makes no outbound network call anywhere; the
      whole-branch review confirmed no network or persistence code exists. Gate 5's contract therefore
      has no surface to apply to.
- [x] Not applicable — no Ollama/Umami or other external endpoint. Recorded as `none` at gate 1.
- [x] Not applicable — no endpoint whose failure could affect startup; there is nothing to redact
      because nothing external is contacted. Startup validation only reads the local command map.

## 6. Tests and build

- [x] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths.
      68 tests across config parsing (fail-closed validation, all seven button rules), the allowlist
      (injection resistance, empty-list fail-closed), placeholder validation (whitespace-reject,
      non-ASCII-permit), cooldowns (injectable clock), the consent race (200-iteration × 5-thread
      single-winner), the Floodgate import quarantine (source scan), a real classloading test proving
      the plugin survives Floodgate's absence, and the menu-visibility filter.
- [x] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts `name`,
      `main`, a `String`-typed `api-version`, a fully-substituted `version`, the `pizza` command, all
      four permission nodes the code checks (`pizza.use/invite/staff/reload`), and the `floodgate`
      soft dependency. It also asserts `command-allowlist` ships populated (fail-closed guard).
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. Run independently in the main
      loop, not only by subagents: `BUILD SUCCESS`, 68/68.
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected. `target/pizza-0.1.0.jar`
      contains only `org/xpfarm`, `META-INF`, `plugin.yml`, `config.yml` — no Paper/Bukkit/Geyser
      classes leaked, no `original-*` shipped. Embedded `plugin.yml` shows the fully-substituted
      version `0.1.0`, correct main class, and `api-version: '26.1'`.

## 7. Matrix

- [ ] Fresh-volume matrix over every updater-managed plugin — **7b, out-of-band, not required for
      this release.** Belongs to `minecraft-plugin-matrix`, triggered by a manifest change or a
      Paper/Geyser/Floodgate/ViaVersion bump. Left unchecked deliberately; a `dev` run does not run it.
- [ ] Per-plugin manifest `enabled`/default-state recording — 7b, same as above.
- [x] **7a — Paper, Geyser, Floodgate, and ViaVersion start successfully together.** Verified on a
      fresh disposable Legendary stack: `plugins` over RCON listed all four green —
      `floodgate`, `Geyser-Spigot`, `Pizza`, `ViaVersion`. Paper logged `Done (21.173s)!`, and the
      Java port answered a real Minecraft protocol handshake (Paper 26.1.2, protocol 775), not just a
      TCP connect.
- [x] **7a — commands, permissions, and configuration reload exercised over RCON with no server-wide
      hot reload.** `/pizza reload` re-parsed config and re-ran the startup command-root validation
      (observed the warnings fire a second time) without a server restart. `/pizza`, `/pizza accept`,
      `/pizza decline` from the console each returned a friendly "only a player can…" message — no
      stack trace. The startup validation pass correctly warned that `carpet`, `starterpack`,
      `supertrash`, `worldcrud` do not resolve on a bare stack (the wrapped plugins are not installed),
      demonstrating the silent-dead-button mitigation; `time`/`weather` (vanilla) resolved silently.
      No exceptions, severe errors, or leaked secrets in startup/action/reload logs.
- [x] Not applicable — no Ollama/Umami endpoint to exercise a negative path against.

**7a re-run for v0.4.0 (2026-07-28):** config-only change (Timber Blast Axe + Sweet Bread catalog
buttons; `timberblast` + `gfbread` allowlist roots; no Java changes; `mvn clean verify` green,
104/104). Fresh disposable Legendary stack: RCON `plugins` listed `floodgate`, `Geyser-Spigot`,
`Pizza (0.4.0)`, `ViaVersion` all green; Paper `Done (18.646s)!`, Java port answered a real handshake
(protocol 775). The startup command-root validation ran and now includes both new roots — logged
`configured command root 'timberblast' does not resolve…` and `…'gfbread' does not resolve…` on the
bare stack (expected silent-dead-button mitigation; both wrapped plugins are installed in production).
No exceptions or leaked secrets in Pizza's startup logs. (`curse` is not in the validation warnings
because it sits on `pick` buttons, not `RunCommand` — the known `validateCommandRoots` gap, unchanged.)

**7a re-run for v0.5.0 (2026-07-28):** Copper Sword (`player-elevated`, grants `copperkingdom.give`)
+ Shop (`run-as: player`, `market`) buttons, and a per-button Java chest icon (`ButtonImage.material`).
Fresh Legendary stack: RCON `plugins` listed `floodgate`, `Geyser-Spigot`, `Pizza`, `ViaVersion` all
green; Paper `Done (20.572s)!`, protocol 775. Startup command-root validation now includes both new
roots — logged `configured command root 'copperkingdom' does not resolve…` and `…'market' does not
resolve…` on the bare stack (expected; both wrapped plugins installed in production). No exceptions in
Pizza's startup logs. Java chest icons and the Copper Sword elevated grant are client-rendered/live-only
— carried to the gate 12 play-test. `mvn clean verify` green, 112/112.

**7a re-run for v0.5.1 (2026-07-28):** Copper Sword button switched from `player-elevated` to plain
`run-as: console` dispatching `copperkingdom give copper_sword %player%` (Copper Kingdom v0.3.0 now
resolves a player target). Fresh stack: `plugins` listed all four green; Paper enabled `Pizza v0.5.1`;
`copperkingdom` root still validates (warns "does not resolve" on the bare stack, expected). No
exceptions. `mvn clean verify` green, 112/112. Deploy note: the console dispatch only functions once
production has pulled Copper Kingdom v0.3.0 (both enrolled → pulled together on recreation).

**Behaviours gate 7a could not reach — carried to gate 12 as a real play-test obligation:**
No client attaches to a headless stack, so none of the following are verified yet, and none block
the release: (1) whether a Bedrock client actually *renders* the Cumulus form and its buttons; (2) a
real button press dispatching a command as a player; (3) the chest GUI as drawn for a Java player;
(4) the invite flow accept/decline over a real client, and a teleport against a live world on an
ACCEPTED invite; (5) the scheduled invite timeout firing; (6) `isFloodgatePlayer` correctly
identifying a real linked Bedrock account. `minecraft-plugin-handoff` records these at gate 12 with a
named owner and date.

## 8. CI/CD

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25
      build, artifact, checksum, and release behavior. `.github/workflows/build.yml` is byte-identical
      to Magic Carpet's, which carries the post-`2026-07-19` checksum remediation (bare filenames via
      `find -printf '%f\0'`, not `target/`-prefixed paths, so downloaded release assets can actually
      pass `sha256sum --check`).
- [x] Successful main Actions run is recorded before tagging. Run `29968906094` on commit `2d01b7e`
      (`main`), `completed / success`. The tag was created on that exact verified commit, never on an
      in-flight or red run.
- [x] Workflow permissions contain no broader access than the documented contract: `contents: write`
      and nothing else.

## 9. Release

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag. `pom.xml` `0.1.0`,
      embedded `plugin.yml` `0.1.0` (Maven-filtered, no hardcoded drift), annotated tag `v0.1.0` on
      commit `2d01b7e`.
- [x] Successful tag Actions run and GitHub release are recorded. Tag run `29968947442`,
      `completed / success`. Release `v0.1.0` is stable — not draft, not prerelease.
- [x] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*`
      JAR. Downloaded assets: `pizza-0.1.0.jar`, `SHA256SUMS.txt` — exactly two, no `original-*`.
- [x] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`. `pizza-0.1.0.jar: OK`
      against a flat download, so the checksum manifest records bare filenames correctly.

**Release addendum — v0.4.0 (2026-07-28):** version consistent across `pom.xml` `0.4.0`, embedded
`plugin.yml` `0.4.0` (Maven-filtered), and annotated tag `v0.4.0` on commit `0a7192f`. Main run
`30370944639` on `0a7192f` = `completed / success` (recorded before tagging; tag created on that exact
commit). Tag run `30371041743` = `completed / success`; release `v0.4.0` is stable (not draft, not
prerelease). Assets exactly `pizza-0.4.0.jar` + `SHA256SUMS.txt` (no `original-*`); `sha256sum --check`
= `pizza-0.4.0.jar: OK`. (Prior v0.2.0 / v0.3.0 releases followed the same path; their per-release
evidence lives in git history and the ecosystem handbook rather than being re-templated into §8/§9.)

**Release addendum — v0.5.0 (2026-07-28):** `pom.xml`/`plugin.yml` `0.5.0`, annotated tag `v0.5.0` on
commit `7674010`. Main run `30374274546` = `completed / success` (recorded before tagging). Tag run
success; release `v0.5.0` stable. Assets exactly `pizza-0.5.0.jar` + `SHA256SUMS.txt` (no `original-*`);
`sha256sum --check` = `pizza-0.5.0.jar: OK`. Updater unchanged (`asset_regex` `^pizza-[0-9].*\.jar$`
follows latest stable) — production picks up 0.5.0 on next recreation, config auto-heals to add the
Shop/Copper Sword buttons and Java icons.

## 10. Updater

- [x] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs,
      enabled state, and optional pin. Entry committed to `carmelosantana/minecraft-plugin-updater`
      at `48a0f22`: `repo` `carmelosantana/minecraft-pizza`, `destination` `pizza.jar` (unique across
      all 15 entries), `asset_regex` `^pizza-[0-9].*\.jar$` (anchored, version-digit-pinned),
      `legacy_globs` `["pizza-[0-9]*.jar"]`, `enabled` absent (defaults true), no `pin` (follows
      latest stable — intentional). `python3 -m json.tool` clean; 11/11 updater unit tests pass.
- [x] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure
      behaviors pass. Pizza-specific: dry-run reported `would install v0.1.0` (asset_regex selected
      exactly one JAR, checksum verified during download); a sandboxed real install reported
      `installed v0.1.0` then `already current (v0.1.0)` on re-run. Manifest-independent behaviors
      (replacement-with-backup, legacy versioned-JAR archival, endpoint/download/checksum failure
      warn-and-continue) are covered by the updater's 11 passing unit tests.
- [x] Updater dry-run uses a disposable directory and never a production plugin directory. All runs
      targeted `/tmp/minecraft-plugin-updater-dry-run` and `/tmp/mpu-sandbox/*`; the real sandboxed
      run overrode all three of `--plugins-dir`, `--state-file`, `--backup-dir` inside the sandbox,
      never the `/minecraft` volume. Both trees discarded afterward.
- [x] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.
      Verified by the updater unit suite (a plugin-level failure warns and continues, leaving any
      installed JAR untouched, without a non-zero exit unless `--strict`). The dry-run over all 15
      entries exited 0.

## 11. Deployment

Not a gate. Deployment is updater pickup: a verified release plus a correct manifest entry is all
this lifecycle owes. Leaving this section entirely unticked is the normal resting state and blocks
nothing — not release, not enrolment, not handoff.

- [x] Enrolment confirmed live and correct: release sound, manifest entry on `origin/main`, gate 10
      genuinely completed. Updater manifest commit `48a0f22` on `origin/main`; release `v0.1.0` stable
      with a checksum-verified asset. This is enrolment confirmation, not deployment confirmation.
- [ ] Deployment evidence recorded, if and only if an operator relayed some. **None relayed** —
      enrolled, not known to be deployed. Deployment is updater pickup on the production server's next
      recreation, outside this toolkit's visibility. Left unticked, which is the normal resting state.

## 12. Handoff

- [x] Current-state documentation refreshed with release, CI, updater, deployment, and local pending
      state. `xpfarm-plugin-toolkit/CURRENT_STATE.md` commit `298d15b` (local-only handbook, never
      pushed): releases table, the runs sentence, Deployment Status, api-version table, Local Pending
      Changes, and a dated "Pizza v0.1.0 Handoff" section all updated.
- [x] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and
      follow-up owner are recorded — in the CURRENT_STATE.md handoff section: the `provided`-scope
      deviation, the intentional in-memory/fallback limitations, the un-run 7b matrix (roster 12→13),
      rollback-by-removal, and owner Carmelo Santana.
- [x] Evidence distinguishes source commit (`c012de3` on `main`, `v0.1.0` tagged at `2d01b7e`),
      published tag/release (tag run `29968947442`, stable release), updater state (manifest `48a0f22`,
      `pizza.jar`, no pin), and deployed state (unknown to this toolkit) — without exposing secrets.
- [x] Client play-test obligation recorded with a named owner and a target date: **Carmelo Santana /
      `2026-07-29`**, in CURRENT_STATE.md's Client Play-Test Obligation section, with seven named
      behaviors in plugin terms (Bedrock form render, chest GUI, button dispatch, the consent flow,
      elevated travel, Bedrock name handling, disconnect-mid-form).
- [ ] Client play-test outcome recorded once performed, covering Java join, Bedrock join, and any
      form, inventory, or rendered item behavior. **Not yet run** — left unchecked with the owner and
      date above. This is Pizza's largest unverified surface: as a Bedrock-first menu plugin, almost
      its entire UX is client-rendered and headlessly unreachable. Not a release blocker; a real
      obligation.
- [ ] Public deployment reachability confirmed during that pass: `play.xpfarm.org` reaches the
      intended Java and Bedrock entry points. **Deferred to the same play-test** — folded into the
      obligation above.
