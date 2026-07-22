# Pizza

Touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.

Most of the useful commands on a Minecraft server are gated behind operator permissions, and
younger players cannot reliably type them anyway. Pizza puts those tasks behind buttons: claim a
starter kit, put its armour on, get a custom item, travel to another world, and invite a friend to
come along. Staff get an extra panel for handing out kits and setting the time and weather.

Pizza adds no gameplay of its own. It is a front end over commands that already exist.

## Playing

Join at **`play.xpfarm.org`** — the same hostname for Java and Bedrock Edition.

Type `/pizza` (or `/menu`, or `/order`) to open the menu.

## Bedrock and Java

Pizza is Bedrock-first. Bedrock players get a native Cumulus form: real touch buttons, readable
text, nothing to type. Java players get a chest-inventory menu with the same buttons.

The chest menu is a deliberate fallback rather than a second first-class experience, and Bedrock
players are never routed to it. Bedrock has no concept of a server-opened container, so Geyser
renders one by placing a temporary fake chest block near the player — which fails outright when
that position happens to be obstructed.

Floodgate is optional. When it is absent, Pizza still enables and serves the chest menu to
everyone.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/pizza` | Open the menu. Aliases `/menu`, `/order` | `pizza.use` (default: everyone) |
| `/pizza reload` | Reload the configuration | `pizza.reload` (default: op) |

## Permissions

| Node | Default | Gates |
|---|---|---|
| `pizza.use` | everyone | Opening the menu |
| `pizza.invite` | everyone | Sending travel invites |
| `pizza.staff` | op | The staff panel |
| `pizza.reload` | op | `/pizza reload` |

Individual buttons may name any permission node. A button whose permission a player lacks is
omitted from their menu rather than shown greyed out.

## Configuration

The entire menu lives in `config.yml` as data — menus, buttons, labels, icons, cooldowns, and the
command each button runs. Adding another plugin to the menu is a config edit, not a release.

Two things worth understanding before editing it:

**`command-allowlist`** names the command roots any button is permitted to dispatch. A button whose
root is missing from that list is refused when the config loads, logged, and left out of the menu.
It is an allowlist rather than a denylist on purpose: on a server whose users are children, a typo
in the config must fail closed rather than reach something destructive. Nothing in the file can
wire `/op`, `/ban`, `/stop`, or a world deletion to a button, because those roots are simply not
listed.

**`run-as`** decides who a button's command runs as. `console` is the default and covers most
cases. `player-elevated` grants the permission nodes in `grant` for exactly the duration of one
dispatch and removes them in a `finally` block — needed for commands that require a player sender
but default their permission to op, such as world travel.

## Travelling together

A player can invite someone else to join them in another world. The invitee gets a Yes/No prompt
and **nobody is ever moved without tapping Accept**, including by staff. There is no override.

An invite that is ignored expires on its own; Pizza never assumes an answer is coming.

## Building

Requires Java 25.

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
