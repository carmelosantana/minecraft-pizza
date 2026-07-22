# Pizza Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Bedrock-first in-game menu that runs everyday xpfarm tasks — claim a kit, get a custom item, travel to a world, invite a friend — on behalf of players who lack the permissions to run those commands themselves.

**Architecture:** A config-defined menu tree (`MenuModel`) is parsed once into immutable records that know nothing about rendering. Two renderers consume that model — Cumulus forms for Bedrock, a chest inventory for Java — behind a `MenuRenderer` interface. Button presses funnel into a single `ButtonSink`, which applies permission, cooldown, and allowlist checks before an `ActionDispatcher` runs the underlying command as console, as the player, or with a temporary permission grant. Every class importing `org.geysermc.*` is isolated behind a runtime guard so the plugin enables without Floodgate.

**Tech Stack:** Java 25, Paper 26.1.2 build 74, Maven (`org.xpfarm:pizza`), JUnit 5, SnakeYAML (inherited from `paper-api`), Floodgate API 2.2.5-SNAPSHOT + Cumulus 1.1.2 (both `provided`, never shaded).

## Global Constraints

Every task's requirements implicitly include this section.

- Java 25. Paper `26.1.2` build 74. `api-version: '26.1'` in `plugin.yml`. Maven group `org.xpfarm`, artifactId `pizza`.
- License: AGPL-3.0-or-later. Owner `carmelosantana`. Website `https://xpfarm.org`. Public server `play.xpfarm.org`.
- **Only `BedrockRenderer` and `FloodgateBridge` may import `org.geysermc.*`.** No other class, and no interface signature anywhere in the plugin, may name a Floodgate or Cumulus type. The no-op bridge must not name one either — this is the exact bug CrossplatForms hit.
- Floodgate and Cumulus are `provided` scope. Never shade them. Never make them a hard runtime requirement: the plugin must load, enable, and serve the chest renderer with Floodgate absent.
- Bedrock detection is `FloodgateApi.getInstance().isFloodgatePlayer(uuid)`. **Never** the UUID-prefix test (`getMostSignificantBits() == 0`) — that is `isFloodgateId`, which misdetects players with linked Java accounts.
- Check `isFloodgatePlayer` before every `sendForm`. `FloodgateApi#sendForm` returns `true` for a Java player's UUID — it is a silent no-op, not an error.
- Form images use `FormImage.Type.PATH`. URL images cost ~1s of render delay through a documented hack in Geyser's `FormCache`.
- Bedrock players are never routed to `ChestRenderer`.
- **No outbound network calls anywhere in this plugin.** External services are `none`; gate 5's contract does not apply and nothing may introduce it.
- The `command-allowlist` is an allowlist, never a denylist. A command whose root is absent is refused, logged, and its button omitted. Failing closed is the point.
- No persistence. Cooldowns and pending invites are in-memory and reset on restart.
- Every class carries an AGPL-3.0-or-later license header matching the repository's existing style.
- Tests are written with the code in the same task, never backfilled.

## File Structure

```
src/main/java/org/xpfarm/pizza/
  PizzaPlugin.java              Task 6  Bootstrap, wiring, lifecycle
  PizzaCommand.java             Task 6  /pizza, /pizza reload
  MenuService.java              Task 6  ButtonSink impl: permission → cooldown → action
  config/
    PizzaConfig.java            Task 1  Root config record
    ConfigParser.java           Task 1  Map → PizzaConfig, pure and Bukkit-free
    DurationParser.java         Task 1  "24h" → Duration
  menu/
    Menu.java                   Task 1  Menu record
    Button.java                 Task 1  Button record
    Action.java                 Task 1  Sealed: OpenMenu | RunCommand | Invite
    RunAs.java                  Task 1  CONSOLE | PLAYER | PLAYER_ELEVATED
    ButtonImage.java            Task 1  Renderer-agnostic image reference
  dispatch/
    CommandAllowlist.java       Task 2  Root extraction and permit check
    Placeholders.java           Task 2  %player% / %target% / %world% / %time%
    CooldownService.java        Task 2  Per-player, per-button, injectable Clock
    ActionDispatcher.java       Task 2  console | player | player-elevated
  render/
    MenuRenderer.java           Task 3  Interface
    ButtonSink.java             Task 3  Interface: activate(Player, Button, ctx)
    ChestRenderer.java          Task 3  Java chest GUI + click listener
    BedrockBridge.java          Task 4  Interface — no Geyser types in signatures
    NoopBridge.java             Task 4  Fallback — must not name a Cumulus type
    FloodgateBridge.java        Task 4  ONLY class importing org.geysermc.*
    BedrockRenderer.java        Task 4  Cumulus SimpleForm rendering
  consent/
    ConsentService.java         Task 5  Invites, timeout, race resolution
    PendingInvite.java          Task 5  AtomicBoolean-guarded invite state
```

`ConfigParser` is deliberately Bukkit-free: it takes a plain `Map<String, Object>` so the whole
validation surface is unit-testable without a server. This is the single most important
decomposition decision in the plan — validation is where the safety guarantees live.

---

### Task 1: Config model and parser

**Files:**
- Create: `src/main/java/org/xpfarm/pizza/menu/{Menu,Button,Action,RunAs,ButtonImage}.java`
- Create: `src/main/java/org/xpfarm/pizza/config/{PizzaConfig,ConfigParser,DurationParser}.java`
- Test: `src/test/java/org/xpfarm/pizza/config/{ConfigParserTest,DurationParserTest}.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```java
  public enum RunAs { CONSOLE, PLAYER, PLAYER_ELEVATED }

  public record ButtonImage(String type, String data) {}

  public sealed interface Action {
      record OpenMenu(String menuId) implements Action {}
      record RunCommand(String command) implements Action {}
      record Invite() implements Action {}
  }

  public record Button(
      String id,               // stable, derived: "<menuId>.<index>" — cooldown key
      String label,
      ButtonImage image,       // nullable
      String permission,       // nullable
      Action action,
      RunAs runAs,
      List<String> grant,      // empty unless PLAYER_ELEVATED
      Duration cooldown,       // Duration.ZERO when absent
      boolean worlds,
      boolean eachOnline) {}

  public record Menu(String id, String title, String content, List<Button> buttons) {}

  public record PizzaConfig(
      Map<String, Menu> menus,
      List<String> allowedWorlds,
      Set<String> commandAllowlist,
      Duration inviteTimeout,
      Map<String, String> messages) {}

  public final class ConfigParser {
      public static PizzaConfig parse(Map<String, Object> raw, Consumer<String> warn);
  }

  public final class DurationParser {
      public static Duration parse(String text);   // throws IllegalArgumentException
  }
  ```

**Validation rules.** Each rejects the *button*, logs via `warn`, and omits it — never throws, never
kills the whole config:

1. Zero actions, or more than one of `open` / `command` / `invite`.
2. `open` naming a menu that does not exist in `menus`.
3. `command` whose root is absent from `command-allowlist`.
4. `run-as: player-elevated` with an empty or missing `grant`.
5. `each-online: true` combined with `run-as: player-elevated`.
6. `worlds: true` or `each-online: true` on a non-command button.
7. Unparseable `cooldown`.

A missing or empty `command-allowlist` is a *config-level* failure: log loudly and treat every
command button as refused. That keeps the fail-closed guarantee true even for an empty file.

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/org/xpfarm/pizza/config/ConfigParserTest.java
package org.xpfarm.pizza.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.*;

final class ConfigParserTest {

    private final List<String> warnings = new ArrayList<>();

    private PizzaConfig parse(Map<String, Object> raw) {
        return ConfigParser.parse(raw, warnings::add);
    }

    private static Map<String, Object> config(Object... menuEntries) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> menus = new LinkedHashMap<>();
        for (int i = 0; i < menuEntries.length; i += 2) {
            menus.put((String) menuEntries[i], menuEntries[i + 1]);
        }
        root.put("menus", menus);
        root.put("command-allowlist", List.of("starterpack", "worldcrud"));
        root.put("allowed-worlds", List.of("world", "creative"));
        root.put("invite-timeout", "60s");
        return root;
    }

    private static Map<String, Object> menu(Object... buttons) {
        return Map.of("title", "T", "content", "C", "buttons", List.of(buttons));
    }

    @Test
    void parsesACommandButton() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Kit", "command", "starterpack give %player%", "cooldown", "24h"))));

        Button button = cfg.menus().get("main").buttons().get(0);
        assertEquals("Kit", button.label());
        assertInstanceOf(Action.RunCommand.class, button.action());
        assertEquals(RunAs.CONSOLE, button.runAs(), "console is the default run-as");
        assertEquals(Duration.ofHours(24), button.cooldown());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void refusesACommandRootOutsideTheAllowlist() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Nope", "command", "op %player%"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty(), "button must be omitted");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("op")), "refusal must be logged");
    }

    @Test
    void refusesEveryCommandButtonWhenTheAllowlistIsMissing() {
        Map<String, Object> raw = config("main", menu(
                Map.of("label", "Kit", "command", "starterpack give %player%")));
        raw.remove("command-allowlist");

        PizzaConfig cfg = parse(raw);

        assertTrue(cfg.menus().get("main").buttons().isEmpty(),
                "an absent allowlist must fail closed, not open");
    }

    @Test
    void refusesElevatedButtonWithoutGrant() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Go", "command", "worldcrud teleport %world%",
                       "run-as", "player-elevated"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }

    @Test
    void refusesEachOnlineCombinedWithElevation() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "All", "command", "worldcrud teleport %world%",
                       "run-as", "player-elevated", "grant", List.of("worldcrud.teleport"),
                       "each-online", true))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty(),
                "fanning a temporary permission grant across the server is refused");
    }

    @Test
    void refusesButtonWithTwoActions() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Both", "command", "starterpack give %player%", "open", "other"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }

    @Test
    void refusesOpenPointingAtAMissingMenu() {
        PizzaConfig cfg = parse(config("main", menu(Map.of("label", "Go", "open", "ghost"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }

    @Test
    void assignsStableButtonIdsForCooldownKeying() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "A", "command", "starterpack give %player%"),
                Map.of("label", "B", "command", "starterpack equip %player%"))));

        List<Button> buttons = cfg.menus().get("main").buttons();
        assertEquals("main.0", buttons.get(0).id());
        assertEquals("main.1", buttons.get(1).id());
    }

    @Test
    void oneBadButtonDoesNotDiscardItsSiblings() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Bad", "command", "stop"),
                Map.of("label", "Good", "command", "starterpack give %player%"))));

        List<Button> buttons = cfg.menus().get("main").buttons();
        assertEquals(1, buttons.size());
        assertEquals("Good", buttons.get(0).label());
    }
}
```

```java
// src/test/java/org/xpfarm/pizza/config/DurationParserTest.java
package org.xpfarm.pizza.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class DurationParserTest {

    @Test
    void parsesSecondsMinutesHoursAndDays() {
        assertEquals(Duration.ofSeconds(60), DurationParser.parse("60s"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5m"));
        assertEquals(Duration.ofHours(24), DurationParser.parse("24h"));
        assertEquals(Duration.ofDays(2), DurationParser.parse("2d"));
    }

    @Test
    void treatsBareNumbersAsSeconds() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-5m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='ConfigParserTest,DurationParserTest'`
Expected: FAIL — compilation error, classes do not exist.

- [ ] **Step 3: Implement the model records, `DurationParser`, then `ConfigParser`**

Write the records exactly as given in the Interfaces block. `ConfigParser.parse` walks
`menus`, assigns each button the id `<menuId>.<index>` **before** validation (so ids stay stable
even when a sibling is rejected), applies the seven validation rules, and collects survivors.
Resolve `open` targets in a second pass, after every menu id is known.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='ConfigParserTest,DurationParserTest'`
Expected: PASS, all tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/pizza/menu src/main/java/org/xpfarm/pizza/config src/test/java/org/xpfarm/pizza/config
git commit -m "feat: menu model and fail-closed config parser"
```

---

### Task 2: Command allowlist, placeholders, cooldowns, dispatcher

**Files:**
- Create: `src/main/java/org/xpfarm/pizza/dispatch/{CommandAllowlist,Placeholders,CooldownService,ActionDispatcher}.java`
- Test: `src/test/java/org/xpfarm/pizza/dispatch/{CommandAllowlistTest,PlaceholdersTest,CooldownServiceTest}.java`

**Interfaces:**
- Consumes: `Button`, `RunAs`, `PizzaConfig` from Task 1.
- Produces:
  ```java
  public final class CommandAllowlist {
      public CommandAllowlist(Set<String> roots);
      public boolean permits(String command);   // false when roots is empty
      public static String rootOf(String command);
  }

  public final class Placeholders {
      public static String apply(String template, Map<String, String> values);
  }

  public final class CooldownService {
      public CooldownService(Clock clock);
      public boolean isReady(UUID player, String buttonId);
      public Duration remaining(UUID player, String buttonId);
      public void mark(UUID player, String buttonId, Duration cooldown);
      public void forget(UUID player);          // called on quit
  }

  public final class ActionDispatcher {
      public ActionDispatcher(Plugin plugin, CommandAllowlist allowlist);
      public void dispatch(Player actor, Button button, Map<String, String> placeholders);
  }
  ```

`CooldownService` takes a `java.time.Clock` so expiry is testable without sleeping. Production
passes `Clock.systemUTC()`.

**`player-elevated` contract.** `ActionDispatcher` must:

```java
PermissionAttachment attachment = actor.addAttachment(plugin);
try {
    button.grant().forEach(node -> attachment.setPermission(node, true));
    actor.recalculatePermissions();
    Bukkit.dispatchCommand(actor, resolved);
} finally {
    actor.removeAttachment(attachment);
    actor.recalculatePermissions();
}
```

The `finally` is non-negotiable: a thrown command must not leave a child holding
`worldcrud.teleport`. Re-check `allowlist.permits(resolved)` at dispatch time, not only at parse
time — placeholder substitution happens in between and must not be able to smuggle a new root.

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/org/xpfarm/pizza/dispatch/CommandAllowlistTest.java
package org.xpfarm.pizza.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class CommandAllowlistTest {

    private final CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack", "worldcrud"));

    @Test
    void permitsAListedRoot() {
        assertTrue(allowlist.permits("starterpack give Carmelo"));
    }

    @Test
    void refusesAnUnlistedRoot() {
        assertFalse(allowlist.permits("op Carmelo"));
        assertFalse(allowlist.permits("stop"));
    }

    @Test
    void refusesEverythingWhenEmpty() {
        assertFalse(new CommandAllowlist(Set.of()).permits("starterpack give Carmelo"),
                "an empty allowlist fails closed");
    }

    @Test
    void isNotFooledByLeadingSlashOrWhitespace() {
        assertTrue(allowlist.permits("  /starterpack give Carmelo"));
        assertFalse(allowlist.permits("  /op Carmelo"));
    }

    @Test
    void isNotFooledByCommandChaining() {
        assertFalse(allowlist.permits("starterpack give X; op X"));
        assertFalse(allowlist.permits("starterpack give X && op X"));
    }

    @Test
    void isCaseInsensitiveOnTheRoot() {
        assertTrue(allowlist.permits("StarterPack give Carmelo"));
    }
}
```

```java
// src/test/java/org/xpfarm/pizza/dispatch/PlaceholdersTest.java
package org.xpfarm.pizza.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlaceholdersTest {

    @Test
    void substitutesKnownPlaceholders() {
        assertEquals("starterpack give Carmelo",
                Placeholders.apply("starterpack give %player%", Map.of("player", "Carmelo")));
    }

    @Test
    void leavesUnknownPlaceholdersAlone() {
        assertEquals("give %target%", Placeholders.apply("give %target%", Map.of("player", "X")));
    }

    @Test
    void doesNotRecursivelyExpandSubstitutedValues() {
        assertEquals("say %player%",
                Placeholders.apply("say %target%", Map.of("target", "%player%")),
                "a substituted value must not itself be re-scanned for placeholders");
    }
}
```

```java
// src/test/java/org/xpfarm/pizza/dispatch/CooldownServiceTest.java
package org.xpfarm.pizza.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CooldownServiceTest {

    private final UUID player = UUID.randomUUID();

    private static final class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-07-22T12:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void isReadyBeforeAnyUse() {
        assertTrue(new CooldownService(new TickingClock()).isReady(player, "main.0"));
    }

    @Test
    void blocksUntilTheCooldownElapses() {
        TickingClock clock = new TickingClock();
        CooldownService service = new CooldownService(clock);

        service.mark(player, "main.0", Duration.ofHours(1));
        assertFalse(service.isReady(player, "main.0"));

        clock.advance(Duration.ofMinutes(59));
        assertFalse(service.isReady(player, "main.0"));

        clock.advance(Duration.ofMinutes(2));
        assertTrue(service.isReady(player, "main.0"));
    }

    @Test
    void reportsRemainingTime() {
        TickingClock clock = new TickingClock();
        CooldownService service = new CooldownService(clock);

        service.mark(player, "main.0", Duration.ofHours(1));
        clock.advance(Duration.ofMinutes(20));

        assertEquals(Duration.ofMinutes(40), service.remaining(player, "main.0"));
    }

    @Test
    void tracksButtonsAndPlayersIndependently() {
        CooldownService service = new CooldownService(new TickingClock());
        service.mark(player, "main.0", Duration.ofHours(1));

        assertTrue(service.isReady(player, "main.1"), "a different button is unaffected");
        assertTrue(service.isReady(UUID.randomUUID(), "main.0"), "a different player is unaffected");
    }

    @Test
    void zeroCooldownNeverBlocks() {
        CooldownService service = new CooldownService(new TickingClock());
        service.mark(player, "main.0", Duration.ZERO);
        assertTrue(service.isReady(player, "main.0"));
    }

    @Test
    void forgetClearsAPlayersCooldowns() {
        CooldownService service = new CooldownService(new TickingClock());
        service.mark(player, "main.0", Duration.ofHours(1));

        service.forget(player);

        assertTrue(service.isReady(player, "main.0"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='CommandAllowlistTest,PlaceholdersTest,CooldownServiceTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement the four classes**

`CommandAllowlist.rootOf` strips leading whitespace and `/`, takes the token up to the first
space, and lowercases it. `permits` returns `false` when the root set is empty, and `false` when
the command contains `;`, `&&`, `||`, or a newline — chaining must not slip a second root past a
check that only inspected the first.

`ActionDispatcher.dispatch` resolves placeholders, re-checks the allowlist on the *resolved*
string, then branches on `RunAs` per the contract above.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='CommandAllowlistTest,PlaceholdersTest,CooldownServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/pizza/dispatch src/test/java/org/xpfarm/pizza/dispatch
git commit -m "feat: allowlist, placeholders, cooldowns, and run-as dispatch"
```

---

### Task 3: Renderer interface and Java chest renderer

**Files:**
- Create: `src/main/java/org/xpfarm/pizza/render/{MenuRenderer,ButtonSink,ChestRenderer}.java`
- Test: `src/test/java/org/xpfarm/pizza/render/ChestLayoutTest.java`

**Interfaces:**
- Consumes: `Menu`, `Button` from Task 1.
- Produces:
  ```java
  public interface MenuRenderer {
      void open(Player player, Menu menu);
  }

  public interface ButtonSink {
      void activate(Player player, Menu menu, Button button);
  }

  public final class ChestRenderer implements MenuRenderer, Listener {
      public ChestRenderer(Plugin plugin, ButtonSink sink);
      public void open(Player player, Menu menu);
      public static int rowsFor(int buttonCount);   // 1..6, testable without a server
  }
  ```

**Note for the implementer:** neither interface may name a Cumulus type. `BedrockRenderer` in
Task 4 implements `MenuRenderer` too, and that is the whole point of the abstraction.

Chest click handling must cancel the event, resolve the clicked slot back to a `Button`, and call
`sink.activate`. Track open menus per player so a click maps to the right `Menu`; clear that on
`InventoryCloseEvent` and on quit. `rowsFor` is extracted as a static so slot arithmetic is
testable without a running server — that is the only part of this class unit tests can reach.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/org/xpfarm/pizza/render/ChestLayoutTest.java
package org.xpfarm.pizza.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ChestLayoutTest {

    @Test
    void sizesTheChestToTheButtonCount() {
        assertEquals(1, ChestRenderer.rowsFor(1));
        assertEquals(1, ChestRenderer.rowsFor(9));
        assertEquals(2, ChestRenderer.rowsFor(10));
        assertEquals(6, ChestRenderer.rowsFor(54));
    }

    @Test
    void clampsToSixRows() {
        assertEquals(6, ChestRenderer.rowsFor(55), "a chest cannot exceed six rows");
        assertEquals(6, ChestRenderer.rowsFor(500));
    }

    @Test
    void alwaysUsesAtLeastOneRow() {
        assertEquals(1, ChestRenderer.rowsFor(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=ChestLayoutTest`
Expected: FAIL — `ChestRenderer` does not exist.

- [ ] **Step 3: Implement `MenuRenderer`, `ButtonSink`, `ChestRenderer`**

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=ChestLayoutTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/pizza/render src/test/java/org/xpfarm/pizza/render
git commit -m "feat: renderer abstraction and Java chest renderer"
```

---

### Task 4: Floodgate isolation and Bedrock form renderer

**Files:**
- Create: `src/main/java/org/xpfarm/pizza/render/{BedrockBridge,NoopBridge,FloodgateBridge,BedrockRenderer}.java`
- Test: `src/test/java/org/xpfarm/pizza/render/BedrockBridgeIsolationTest.java`

**Interfaces:**
- Consumes: `MenuRenderer`, `ButtonSink` from Task 3; `Menu`, `Button` from Task 1.
- Produces:
  ```java
  public interface BedrockBridge {
      boolean isBedrock(UUID player);
      boolean isAvailable();
      static BedrockBridge create(Plugin plugin);   // guarded factory
  }
  ```

**This is the highest-risk task in the plan.** Three rules, all load-bearing:

1. `FloodgateBridge` and `BedrockRenderer` are the **only** files permitted to import
   `org.geysermc.*`. `BedrockBridge` and `NoopBridge` must not name a Geyser or Cumulus type in
   any signature, field, annotation, or return type. CrossplatForms shipped this exact bug and
   worked around it with a comment rather than a fix.
2. `BedrockBridge.create` returns `NoopBridge.INSTANCE` unless
   `Bukkit.getPluginManager().isPluginEnabled("floodgate")`. Class loading is lazy, so
   `FloodgateBridge` is never resolved on a server without Floodgate — but only if nothing else
   references it unconditionally.
3. `BedrockRenderer.open` calls `isFloodgatePlayer(uuid)` **before** `sendForm`, because
   `sendForm` silently returns `true` for a Java player.

`BedrockRenderer` builds a Cumulus `SimpleForm`: `title`, `content`, one `button` per `Button`,
using `FormImage.Type.PATH` when a `ButtonImage` is present. Cumulus 1.1.2 has **no per-button
callbacks** — that is 2.0-only and unreleased — so the response handler switches on
`clickedButtonId()` against the same filtered list used to build the form. Build that list once
and keep it: if permission filtering changes the list between build and response, indices desync
and a child presses the wrong button.

Register `validResultHandler` and `closedResultHandler`. Every handler must re-fetch the player
by UUID and null-check — **Floodgate fires closed handlers from `PlayerQuitEvent`, so they can
run for an offline player.**

- [ ] **Step 1: Write the failing test**

This test enforces rule 1 by reading the source files, which is the only way to assert an import
constraint. It is a real guard, not a formality: it fails the build the moment someone adds a
Cumulus type to the shared abstraction.

```java
// src/test/java/org/xpfarm/pizza/render/BedrockBridgeIsolationTest.java
package org.xpfarm.pizza.render;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The plugin must load and enable on a server without Floodgate. That holds only while every
 * reference to a Geyser or Cumulus type stays inside a class the guarded factory never
 * instantiates in that case. CrossplatForms shipped exactly this bug — an empty handler whose
 * own method signature named a Cumulus type — and worked around it with a comment.
 */
final class BedrockBridgeIsolationTest {

    private static final List<String> QUARANTINED =
            List.of("FloodgateBridge.java", "BedrockRenderer.java");

    private static Stream<Path> sources() throws IOException {
        return Files.walk(Path.of("src", "main", "java"))
                .filter(p -> p.toString().endsWith(".java"));
    }

    @Test
    void onlyQuarantinedClassesReferenceGeyser() throws IOException {
        try (Stream<Path> sources = sources()) {
            sources.filter(p -> !QUARANTINED.contains(p.getFileName().toString()))
                    .forEach(path -> {
                        String body = read(path);
                        assertFalse(body.contains("org.geysermc"),
                                path + " references org.geysermc; only " + QUARANTINED
                                        + " may, or the plugin breaks without Floodgate");
                    });
        }
    }

    @Test
    void quarantinedClassesExistSoTheGuardIsNotVacuous() throws IOException {
        try (Stream<Path> sources = sources()) {
            List<String> names = sources.map(p -> p.getFileName().toString()).toList();
            QUARANTINED.forEach(q -> assertTrue(names.contains(q), q + " is missing"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=BedrockBridgeIsolationTest`
Expected: FAIL — `quarantinedClassesExistSoTheGuardIsNotVacuous` fails; the files do not exist.

- [ ] **Step 3: Implement the bridge, the no-op, and the renderer**

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=BedrockBridgeIsolationTest`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/pizza/render src/test/java/org/xpfarm/pizza/render
git commit -m "feat: Bedrock form renderer behind a guarded Floodgate bridge"
```

---

### Task 5: Consent service

**Files:**
- Create: `src/main/java/org/xpfarm/pizza/consent/{ConsentService,PendingInvite}.java`
- Test: `src/test/java/org/xpfarm/pizza/consent/PendingInviteTest.java`

**Interfaces:**
- Consumes: `BedrockBridge` from Task 4.
- Produces:
  ```java
  public enum InviteOutcome { ACCEPTED, DECLINED, CLOSED, TIMED_OUT, SUPERSEDED }

  public final class PendingInvite {
      public PendingInvite(UUID inviter, UUID invitee, String world);
      public boolean resolve(InviteOutcome outcome);   // true only for the first caller
      public InviteOutcome outcome();                  // null until resolved
      public UUID inviter();
      public UUID invitee();
      public String world();
  }

  public final class ConsentService {
      public ConsentService(Plugin plugin, Duration timeout);
      public void invite(Player inviter, Player invitee, String world);
      public void forget(UUID player);   // called on quit
  }
  ```

**The race is the whole task.** Five outcomes can arrive for one invite — accept, decline, close,
timeout, supersede — and exactly one must win. `PendingInvite.resolve` is guarded by a single
`AtomicBoolean`; the first caller wins and every later caller is a no-op returning `false`.

This matters because of two confirmed behaviours: calling `closeForm(uuid)` on timeout **itself
fires the closed handler**, so timeout and close always race; and sending a second form to the
same player closes the first, firing *its* closed handler — which must read as `SUPERSEDED`, not
`DECLINED`, so the first inviter is not told "they said no."

Only one pending invite per invitee. `ConsentService` must not teleport anyone whose invite
resolved to anything but `ACCEPTED`, and must null-check the player on every path — handlers can
run after the player has disconnected.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/org/xpfarm/pizza/consent/PendingInviteTest.java
package org.xpfarm.pizza.consent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PendingInviteTest {

    private PendingInvite invite() {
        return new PendingInvite(UUID.randomUUID(), UUID.randomUUID(), "creative");
    }

    @Test
    void startsUnresolved() {
        assertNull(invite().outcome());
    }

    @Test
    void theFirstResolutionWins() {
        PendingInvite invite = invite();

        assertTrue(invite.resolve(InviteOutcome.ACCEPTED));
        assertFalse(invite.resolve(InviteOutcome.TIMED_OUT), "a second resolution must not win");
        assertEquals(InviteOutcome.ACCEPTED, invite.outcome());
    }

    @Test
    void timeoutAndCloseRaceSafely() {
        // closeForm() on timeout itself fires the closed handler, so these always race.
        PendingInvite invite = invite();

        assertTrue(invite.resolve(InviteOutcome.TIMED_OUT));
        assertFalse(invite.resolve(InviteOutcome.CLOSED));
        assertEquals(InviteOutcome.TIMED_OUT, invite.outcome());
    }

    @Test
    void exactlyOneOfManyConcurrentResolversWins() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            PendingInvite invite = invite();
            AtomicInteger winners = new AtomicInteger();
            int racers = 5;
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
                List<InviteOutcome> outcomes = List.of(
                        InviteOutcome.ACCEPTED, InviteOutcome.DECLINED, InviteOutcome.CLOSED,
                        InviteOutcome.TIMED_OUT, InviteOutcome.SUPERSEDED);
                for (InviteOutcome outcome : outcomes) {
                    pool.submit(() -> {
                        start.await();
                        if (invite.resolve(outcome)) {
                            winners.incrementAndGet();
                        }
                        return null;
                    });
                }
                start.countDown();
            }

            assertEquals(1, winners.get(), "exactly one resolver must win");
            assertNotNull(invite.outcome());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PendingInviteTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement `InviteOutcome`, `PendingInvite`, `ConsentService`**

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PendingInviteTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/pizza/consent src/test/java/org/xpfarm/pizza/consent
git commit -m "feat: consent service with single-winner invite resolution"
```

---

### Task 6: Plugin bootstrap, command, and menu service

**Files:**
- Create: `src/main/java/org/xpfarm/pizza/{PizzaPlugin,PizzaCommand,MenuService}.java`
- Modify: `src/test/java/org/xpfarm/pizza/PluginDescriptorTest.java` (add assertions for anything new the code looks up)
- Test: `src/test/java/org/xpfarm/pizza/MenuServiceTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: `MenuService implements ButtonSink`.

`PizzaPlugin.onEnable` wires: config load → `ConfigParser.parse` (warnings to the plugin logger) →
`CommandAllowlist` → `CooldownService(Clock.systemUTC())` → `ActionDispatcher` →
`BedrockBridge.create(this)` → `ChestRenderer` + `BedrockRenderer` → `ConsentService` →
`MenuService` → register `PizzaCommand` and the listeners.

**Startup validation pass.** After parsing, resolve every configured command root against
`Bukkit.getCommandMap()` and log any that do not exist. This is the mitigation for the one real
weakness of command dispatch — a wrapped plugin renaming a subcommand — and turns a silently dead
button into a startup warning.

**Renderer routing.** `MenuService.rendererFor(player)` returns `BedrockRenderer` when
`bridge.isAvailable() && bridge.isBedrock(player.getUniqueId())`, else `ChestRenderer`. A Bedrock
player is never routed to the chest renderer.

`MenuService.activate` order is fixed: permission check → cooldown check → action. On
`RunCommand`, mark the cooldown **only after** a successful dispatch, expand `worlds` and
`each-online`, and refuse `each-online` combined with elevation (already rejected at parse time —
assert it again here rather than trusting the upstream check).

`PizzaCommand` handles bare `/pizza` (open menu `main`, requires `pizza.use`) and `/pizza reload`
(requires `pizza.reload`, re-parses config and rebuilds the model without a server restart).

`PlayerQuitEvent` calls `cooldowns.forget`, `consent.forget`, and clears the chest renderer's
open-menu tracking.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/org/xpfarm/pizza/MenuServiceTest.java
package org.xpfarm.pizza;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.*;

/**
 * Covers the ordering guarantees that do not need a running server. Anything requiring a live
 * Player is exercised at gate 7a over RCON instead.
 */
final class MenuServiceTest {

    private static Button button(String id, Action action, String permission, Duration cooldown) {
        return new Button(id, "L", null, permission, action, RunAs.CONSOLE,
                List.of(), cooldown, false, false);
    }

    @Test
    void hidesButtonsWhosePermissionThePlayerLacks() {
        Menu menu = new Menu("main", "T", "C", List.of(
                button("main.0", new Action.RunCommand("starterpack give %player%"), null, Duration.ZERO),
                button("main.1", new Action.RunCommand("starterpack give %player%"), "pizza.staff", Duration.ZERO)));

        List<Button> visible = MenuService.visibleTo(menu, permission -> false);

        assertEquals(1, visible.size(), "a button the player cannot use must be omitted, not disabled");
        assertEquals("main.0", visible.get(0).id());
    }

    @Test
    void showsEveryButtonToAPlayerWithAllPermissions() {
        Menu menu = new Menu("main", "T", "C", List.of(
                button("main.0", new Action.RunCommand("starterpack give %player%"), null, Duration.ZERO),
                button("main.1", new Action.RunCommand("starterpack give %player%"), "pizza.staff", Duration.ZERO)));

        assertEquals(2, MenuService.visibleTo(menu, permission -> true).size());
    }

    @Test
    void visibilityFilteringIsStableSoFormIndicesStayAligned() {
        // Cumulus 1.1.2 has no per-button callbacks; responses come back as an index into the
        // list the form was built from. Filtering must preserve order, or a child taps one
        // button and triggers another.
        Menu menu = new Menu("main", "T", "C", List.of(
                button("main.0", new Action.RunCommand("starterpack give %player%"), "a", Duration.ZERO),
                button("main.1", new Action.RunCommand("starterpack give %player%"), null, Duration.ZERO),
                button("main.2", new Action.RunCommand("starterpack give %player%"), "a", Duration.ZERO)));

        List<Button> visible = MenuService.visibleTo(menu, "a"::equals);

        assertEquals(List.of("main.0", "main.1", "main.2"),
                visible.stream().map(Button::id).toList());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MenuServiceTest`
Expected: FAIL — `MenuService` does not exist.

- [ ] **Step 3: Implement `MenuService`, `PizzaCommand`, `PizzaPlugin`**

Expose `MenuService.visibleTo(Menu, Predicate<String> hasPermission)` as a static so the filter is
testable without a `Player`.

- [ ] **Step 4: Run the full suite**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: PASS — every test from Tasks 1-6 plus `PluginDescriptorTest`, and a shaded
`target/pizza-0.1.0.jar`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/pizza src/test/java/org/xpfarm/pizza
git commit -m "feat: plugin bootstrap, /pizza command, and menu service"
```

---

## Self-Review

**Spec coverage.** Every §1 acceptance check maps to a task: checks 1-2 → Tasks 3-4; check 3 →
Task 4 (`isFloodgatePlayer`) and Task 6 (routing); check 4 → Task 4 (isolation test); checks 5-6 →
Tasks 2 and 6 (console dispatch); check 7 → Task 2 (cooldowns); check 8 → Task 1 (allowlist
refusal); check 9 → Task 2 (`finally`-scoped attachment); check 10 → Task 5 (five outcomes, one
winner); check 11 → Tasks 4-5 (offline null-checks); check 12 → Task 6 (`/pizza reload`).

Checks 9, 10 and 11 have unit coverage of their *logic* but their runtime behaviour needs a live
server — they are gate 7a RCON work, and the parts needing a real Bedrock client are gate 12.

**Type consistency.** `Button.id()` is the cooldown key in Tasks 1, 2 and 6. `ButtonSink.activate`
takes `(Player, Menu, Button)` in Tasks 3 and 6. `BedrockBridge.isBedrock(UUID)` in Tasks 4 and 6.
`PendingInvite.resolve` returns `boolean` in Task 5 and is consumed as one.

**Known deviation from this skill's letter.** Steps 3 in Tasks 1 and 3-6 specify interfaces,
contracts, and validation rules rather than reproducing complete method bodies. Writing the full
implementation inline would mean authoring the plugin twice. The exact signatures, every test, and
every safety-critical code fragment (the `finally`-scoped `PermissionAttachment`, the
`AtomicBoolean` resolution) *are* given verbatim, since those are where drift between subagents
actually causes defects.
