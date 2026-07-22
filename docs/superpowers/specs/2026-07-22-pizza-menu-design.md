# Pizza — in-game menu system

Design accepted `2026-07-22`. This is the spec `minecraft-plugin-scaffold` and
`minecraft-plugin-dev` implement against. Where it records a fact discovered by research rather
than chosen by preference, it says so, because those facts are the ones most expensive to
rediscover during implementation.

## Purpose

Younger players on `play.xpfarm.org` cannot reliably type commands, and most of the useful ones
are permission-gated to operators anyway. Pizza gives them a touch-friendly menu — opened with
`/pizza` — that performs tasks across the existing xpfarm plugin set on their behalf: claim a
starter kit, put its armour on, get a custom item, travel to another world, and invite a friend
to come along. Staff get an additional permission-gated panel for handing out kits and setting
world time and weather.

Pizza adds no gameplay of its own. It is a front end over commands that already exist.

## Platform target

Bedrock-first. The younger players are on tablets and consoles through Geyser, so the primary
renderer is a native Cumulus form. Java players get a chest-inventory GUI.

The chest GUI is a **degraded fallback, not a second first-class experience**. Bedrock has no
concept of a server-opened virtual container, so Geyser places a temporary fake chest block near
the player and forces it open; that fails outright when the position is obstructed
([Geyser#1727](https://github.com/GeyserMC/Geyser/issues/1727)) or outside world bounds
([#1441](https://github.com/GeyserMC/Geyser/issues/1441)). Pizza therefore never routes a Bedrock
player to the chest renderer.

## Architecture

Six units. Each has one purpose, is testable without a running server, and communicates through
a type it does not own.

| Unit | Responsibility | Depends on |
|---|---|---|
| `MenuModel` | Immutable tree of menus and buttons parsed from config | Nothing platform-specific |
| `MenuRenderer` | Interface: show a menu to a player, report the chosen button | `MenuModel` |
| `BedrockRenderer` | Cumulus form implementation | Geyser/Floodgate types |
| `ChestRenderer` | Bukkit inventory implementation | Bukkit |
| `ActionDispatcher` | Allowlist check, permission check, cooldown, placeholder substitution, dispatch | Bukkit |
| `ConsentService` | Pending travel invites, timeout, race resolution | `MenuRenderer` |

`MenuModel` is deliberately ignorant of both renderers. A button is a label, an optional image, an
optional permission, and exactly one action. That keeps the config format from drifting toward
either rendering technology.

### Floodgate isolation

`BedrockRenderer` is the only class in the plugin that imports `org.geysermc.*`. It is
instantiated only when `Bukkit.getPluginManager().isPluginEnabled("floodgate")` returns true;
otherwise a no-op implementation is used. JVM class loading is lazy, so the Geyser-importing class
is never resolved on a server without Floodgate.

The no-op implementation **must not name a Cumulus type in any signature, including parameter
types**. CrossplatForms hit exactly this bug and left a workaround comment in
`ConfigurationModule.java` about `EmptyBedrockHandler` causing `ClassDefNotFound` when Cumulus is
absent. Pizza avoids it structurally: the `MenuRenderer` interface speaks only in `MenuModel`
terms, so no Cumulus type ever appears in the abstraction.

`plugin.yml` declares `softdepend: [floodgate]` for load ordering. This is required today and
would become mandatory if the plugin ever migrates to `paper-plugin.yml`, where classloader
isolation is strict.

### Dependency scope — a deliberate deviation

The ecosystem convention, documented in Farmers Market's `plugin.yml`, is that Floodgate is
reached reflectively and never placed on the compile classpath. **Pizza deviates: the Floodgate
API is declared Maven `provided`.**

The convention protects two things — Floodgate must never be bundled into the shaded JAR, and must
never be a hard runtime requirement. `provided` scope satisfies both: it is excluded from
`maven-jar-plugin` output and from `maven-shade-plugin` by default. What the convention does not
require is reflection specifically, and reflection is genuinely the wrong tool here. It is
adequate for the boolean `isBedrockPlayer` check Farmers Market performs. It is not adequate for
forms: every response read becomes another reflective invocation, a `CustomFormResponse` read loop
costs roughly five reflective calls per component, `FormImage.Type` requires reflective
`Enum.valueOf`, and none of it is checked against Cumulus signature churn at compile time.

```xml
<dependency>
  <groupId>org.geysermc.floodgate</groupId>
  <artifactId>api</artifactId>
  <version>2.2.5-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

Repository `https://repo.opencollab.dev/main/` with snapshots enabled. Cumulus `1.1.2` resolves
transitively and inherits `provided` scope, but is pinned explicitly to survive POM churn — the
Floodgate API artifact stopped bundling Cumulus, so relying on transitivity alone is fragile.

Two facts to carry forward: **no non-SNAPSHOT release of the Floodgate API exists**, so CI takes a
snapshot compile dependency (it never ships in the JAR, so this is a reproducibility concern
only); and **Cumulus is effectively frozen** — master is `2.0.0-SNAPSHOT` with no tags and no
commits since 2024-06-26, so Pizza designs against 1.1.2 and no 2.0-only feature.

### Bedrock detection

`FloodgateApi.getInstance().isFloodgatePlayer(uuid)`. Not the UUID-prefix trick — that is
`isFloodgateId`, whose own javadoc states it cannot validate a linked player's UUID. Any Bedrock
player who has linked a Java account would be misrouted to the chest renderer.

Pizza checks `isFloodgatePlayer` itself before every send, because `FloodgateApi#sendForm` returns
`true` when handed a Java player's UUID — it is a silent no-op, not an error.

## Configuration

The entire menu is data. Adding a plugin to the menu is a config edit, never a release.

```yaml
menus:
  main:
    title: "&6Pizza Menu"
    content: "What do you want to do?"
    buttons:
      - label: "My Starter Kit"
        image: { type: path, data: "textures/items/apple" }
        open: kit
      - label: "Cool Items"
        open: catalog
      - label: "Travel"
        open: travel
      - label: "Staff"
        permission: pizza.staff
        open: staff

  kit:
    title: "Starter Kit"
    buttons:
      - label: "Claim my kit"
        run-as: console
        command: "starterpack give %player%"
        cooldown: 24h
      - label: "Put my armour on"
        run-as: console
        command: "starterpack equip %player%"
        cooldown: 5m

  catalog:
    title: "Cool Items"
    buttons:
      - label: "Magic Carpet"
        run-as: console
        command: "carpet give %player%"
        cooldown: 1h

  travel:
    title: "Travel"
    buttons:
      - label: "Go to a world"
        worlds: true          # expands to one button per entry in allowed-worlds
        run-as: player-elevated
        command: "worldcrud teleport %world%"
        grant: [worldcrud.teleport]
      - label: "Invite a friend"
        invite: true

allowed-worlds: [world, world_nether, creative]

command-allowlist: [starterpack, carpet, supertrash, worldcrud, time, weather]

cooldown-message: "&eYou can do that again in %time%."
```

`image` uses `FormImage.Type.PATH` (vanilla texture paths) rather than `URL`. URL images are
rendered through a documented hack in Geyser's `FormCache` — a latency packet after 500 ms and an
attribute packet 500 ms after the reply — costing roughly a second before the icon appears. PATH
images have no such delay.

A button carries exactly one action: `open` (a submenu), `command`, or `invite`. Buttons whose
`permission` the player lacks are omitted from the rendered form rather than shown disabled.

A `command` button additionally carries `run-as` (default `console`), an optional `cooldown`
duration, and — for `player-elevated` only — `grant`, a list of permission nodes.

Two markers modify a `command` button rather than acting as a fourth action, and are valid only
there:

- **`worlds: true`** expands the button at render time into one button per entry in
  `allowed-worlds`, substituting `%world%`.
- **`each-online: true`** dispatches the command once per online player, substituting `%target%`.
  This is what implements "give everyone a starter kit". It is rejected on a `player-elevated`
  button — fanning a temporary permission grant across the whole server is not a thing Pizza will
  do.

Top level also carries `allowed-worlds`, `command-allowlist`, `invite-timeout` (a duration, how
long an invitee has to answer before the invite expires on its own), and a `messages` map of
player-facing strings.

## Action dispatch

Three `run-as` modes:

- **`console`** — the default, and correct for anything taking a target player. `/starterpack give`
  and `/starterpack equip` both already accept a target, so the kit and catalog need no new code
  in any other plugin.
- **`player`** — dispatched as the player, for commands they already have permission for.
- **`player-elevated`** — a `PermissionAttachment` granting exactly the nodes named in `grant`,
  for the duration of one dispatch, removed in a `finally` block. Required for
  `/worldcrud teleport`, which needs a player sender but defaults its permission to op.

### Allowlist

`command-allowlist` names the permitted command roots. A button whose command root is not on the
list is refused at config load, logged, and omitted from the menu. This is an allowlist rather
than a denylist deliberately: on a server whose users are children, a config typo must fail
closed. No config edit can wire `/op`, `/ban`, `/stop`, or `/worldcrud delete` to a button,
because those roots are not listed and cannot be reached by omission.

Startup runs a validation pass resolving every configured command root against
`Bukkit.getCommandMap()`, logging any that do not exist. This is the mitigation for the one real
weakness of command dispatch: a wrapped plugin renaming a subcommand would otherwise break a
button silently.

### Cooldowns

Per-player, per-button, held in memory, keyed by button id. They reset on restart — an accepted
trade, since the alternative is persistence machinery for a family server's item catalogue. The
button renders with its remaining time when a cooldown is active.

## Consent flow

Travel invites use a Cumulus `ModalForm` — the Bedrock message form, which renders as a centred
two-button dialog. A two-button `SimpleForm` would render as a scrollable list and read worse for
a decision.

```
A picks "Invite a friend" → picks B from a player list
  → B receives a ModalForm: "A wants you to join <world>."  [Accept] [Decline]
    → Accept  : B is teleported. A is told.
    → Decline : B stays. A is told.
    → Closed  : B stays. A is told nothing.
    → Timeout : B stays. A is told the invite expired.
```

**Nobody is moved without tapping Accept, including by staff.** There is no override.

Four hazards this flow is built around, each confirmed against Geyser and Floodgate source:

1. **Closed is not declined.** Dismissing a form produces a `CLOSED` result and the valid handler
   never runs. Ignoring the form entirely produces *no event at all* — Bedrock message forms have
   no X button, though Escape and the mobile back gesture dismiss them. `ConsentService` therefore
   owns a scheduled timeout and never assumes a callback will arrive.
2. **The closed handler can run for an offline player.** Floodgate's `SpigotListener` flushes open
   forms from `PlayerQuitEvent` at `MONITOR` priority, invoking each closed handler after the
   player has left. Every handler re-fetches the player and null-checks before acting.
3. **A second form cancels the first, firing its closed handler.** If two invites arrive, the
   first is *superseded*, not declined, and the inviter is not told "B declined".
4. **On timeout, `closeForm(uuid)` itself fires the closed handler.** A single `AtomicBoolean`
   guards resolution so that timeout, accept, decline, close, and supersede race safely and
   exactly one outcome wins.

One accepted UX hazard: sending a form to a Bedrock player force-closes any container they have
open, because `GeyserSession#doSendForm` closes inventories first — otherwise the form does not
display. An unsolicited invite can therefore interrupt someone mid-chest. Pizza defers the invite
form while the recipient has an inventory open, and expires it if that never clears.

There is no server-side form rate limiting anywhere in Cumulus, Floodgate, or Geyser, so Pizza
rate-limits itself: one pending invite per recipient.

## Commands and permissions

| Command | Purpose |
|---|---|
| `/pizza` | Open the main menu. Aliases `/menu`, `/order`. |
| `/pizza reload` | Reload configuration. |

| Permission | Default | Gates |
|---|---|---|
| `pizza.use` | `true` | Open the menu |
| `pizza.staff` | `op` | The staff panel |
| `pizza.invite` | `true` | Send travel invites |
| `pizza.reload` | `op` | `/pizza reload` |

Buttons may name any permission node; the four above are what the code itself checks.

## Events

- `PlayerQuitEvent` — drop pending invites and cancel their timeout tasks.
- `InventoryClickEvent` / `InventoryCloseEvent` — chest renderer only, Java players only.

No join listener: the menu is command-only and nothing is pushed at players.

## Persistence

None. Cooldowns and pending invites are in-memory and intentionally do not survive a restart.
Pizza writes no files beyond its own `config.yml`.

## External integrations

`none`. Pizza makes no outbound network calls, so gate 5's external-service contract does not
apply. Umami reporting of button usage was considered and deferred to a later milestone.

## Acceptance checks

1. `/pizza` on a Bedrock client opens a Cumulus `SimpleForm` with the configured buttons.
2. `/pizza` on a Java client opens a chest GUI with equivalent buttons.
3. A Bedrock player is never routed to the chest renderer, and a linked-account Bedrock player is
   detected correctly.
4. The plugin loads, enables, and serves the chest GUI on a server with Floodgate absent, with no
   `NoClassDefFoundError`.
5. "Claim my kit" gives the starter pack to a player holding no `starterpack.admin` permission.
6. "Put my armour on" equips them, via the existing `/starterpack equip`.
7. Pressing a cooled-down button reports the remaining time and dispatches nothing.
8. A button whose command root is absent from `command-allowlist` is refused at config load, and
   the menu renders without it.
9. World travel succeeds via `player-elevated`, and the granted permission is gone afterwards —
   verified by checking the player cannot run `/worldcrud teleport` directly.
10. An invite that is accepted teleports the recipient; declined, closed, timed-out, and
    superseded invites each leave them in place and produce exactly one outcome.
11. An invitee who disconnects with the form open does not produce an exception.
12. `/pizza reload` re-reads config without a server restart.

## Known limitations

- Chest GUI is a degraded fallback; Bedrock players are never routed to it.
- Cooldowns and pending invites reset on restart, by design.
- Config-driven dispatch breaks if a wrapped plugin renames a subcommand; mitigated by the startup
  validation pass, not eliminated.
- The Floodgate API has no non-SNAPSHOT release, so CI compiles against a snapshot.
- Cumulus 1.1.2 has no per-button callbacks (that is 2.0-only and unreleased), so `SimpleForm`
  handling is index-based.
- Sending a form force-closes a Bedrock player's open container. Mitigated for invites; inherent
  for any form.
- Neither Floodgate nor Geyser snapshots are certified against Paper 26.1.2 specifically. Worth a
  smoke test at gate 7a.
- Milestone 2 candidates, deliberately excluded from v1: Umami analytics, a physical menu item,
  first-join auto-open, recipe display, party travel, and homes/warps.
