/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.xpfarm.pizza.config.ConfigHashes;
import org.xpfarm.pizza.config.ConfigParser;
import org.xpfarm.pizza.config.ConfigRefreshDecision;
import org.xpfarm.pizza.config.PizzaConfig;
import org.xpfarm.pizza.consent.ConsentService;
import org.xpfarm.pizza.dispatch.ActionDispatcher;
import org.xpfarm.pizza.dispatch.CommandAllowlist;
import org.xpfarm.pizza.dispatch.CooldownService;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.Menu;
import org.xpfarm.pizza.menuitem.MenuItemListener;
import org.xpfarm.pizza.menuitem.MenuItemService;
import org.xpfarm.pizza.render.BedrockBridge;
import org.xpfarm.pizza.render.BedrockRendererFactory;
import org.xpfarm.pizza.render.ChestRenderer;
import org.xpfarm.pizza.render.MenuRenderer;
import org.yaml.snakeyaml.Yaml;

/**
 * Bootstraps every Task 1-5 component and wires them into a working plugin.
 *
 * <h2>Wiring order</h2>
 *
 * <p>{@code config load -> ConfigParser.parse -> CommandAllowlist -> CooldownService ->
 * ActionDispatcher -> BedrockBridge.create -> ChestRenderer + BedrockRenderer -> ConsentService ->
 * MenuService -> register PizzaCommand and the listeners}. {@link CooldownService} is built on
 * {@link Clock#systemUTC()}; nothing here ever substitutes a fake clock in production.
 *
 * <h2>Why {@code config.yml} is read with SnakeYAML, not {@code getConfig()}</h2>
 *
 * <p>{@link ConfigParser} is deliberately free of any Bukkit dependency — it takes a plain {@code
 * Map<String, Object>}, not a {@code ConfigurationSection}, so its entire validation surface is
 * unit-testable without a running server. Bukkit's {@code FileConfiguration} does not hand back
 * that shape: nested sections stay {@code ConfigurationSection} instances unless flattened into
 * dotted keys, neither of which is what {@link ConfigParser} expects. Reading {@code config.yml}
 * directly with the same SnakeYAML {@code PluginDescriptorTest} already parses it with (in {@code
 * src/test}) keeps runtime and test parsing identical and sidesteps that conversion entirely.
 *
 * <h2>Startup command-root validation</h2>
 *
 * <p>After every parse (at boot and again on {@code /pizza reload}), every configured command
 * button's root is resolved against {@link Bukkit#getCommandMap()} and logged if missing. This is
 * the mitigation for command dispatch's one real weakness — a wrapped plugin renaming a subcommand
 * — and turns what would otherwise be a silently dead button into a startup warning. It only
 * warns: a missing root never disables the plugin or fails {@code onEnable}, since the menu still
 * works for every other button.
 */
public final class PizzaPlugin extends JavaPlugin {

    private MenuService menuService;

    @Override
    public void onEnable() {
        maybeAutoRefreshConfig();
        PizzaConfig config = parseConfig();
        validateCommandRoots(config);
        validateMenuItemMaterial(config);

        CommandAllowlist allowlist = new CommandAllowlist(config.commandAllowlist());
        CooldownService cooldowns = new CooldownService(Clock.systemUTC());
        ActionDispatcher dispatcher = new ActionDispatcher(this, allowlist);
        BedrockBridge bridge = BedrockBridge.create(this);
        ConsentService consent = new ConsentService(this, config.inviteTimeout(), bridge);

        // MenuService needs both renderers to route to, and each renderer needs MenuService as
        // its ButtonSink — a constructor cycle. MenuService is built first without them and
        // wired in afterward via setRenderers; see its class-level javadoc.
        menuService = new MenuService(this, config, bridge, cooldowns, dispatcher, consent);
        ChestRenderer chestRenderer = new ChestRenderer(this, menuService);
        // Guarded: BedrockRenderer (and therefore Cumulus) is only ever linked when Floodgate is
        // present. See BedrockRendererFactory's javadoc for why this must never be a bare
        // `new BedrockRenderer(...)` here again.
        MenuRenderer bedrockRenderer = BedrockRendererFactory.create(this, menuService, bridge);
        menuService.setRenderers(chestRenderer, bedrockRenderer);

        PizzaCommand pizzaCommand = new PizzaCommand(this, menuService, consent);
        PluginCommand command = getCommand("pizza");
        if (command == null) {
            getLogger().severe("plugin.yml does not declare the 'pizza' command; /pizza will not work");
        } else {
            command.setExecutor(pizzaCommand);
            command.setTabCompleter(pizzaCommand);
        }

        // ChestRenderer implements Listener but does not self-register; BedrockRenderer never
        // needs to (it has no @EventHandler methods — Floodgate calls it directly). MenuService
        // is also a Listener, for the PlayerQuitEvent cleanup below.
        Bukkit.getPluginManager().registerEvents(chestRenderer, this);
        Bukkit.getPluginManager().registerEvents(menuService, this);

        MenuItemService menuItems = new MenuItemService(this);
        MenuItemListener menuItemListener = new MenuItemListener(this, menuService, menuItems);
        Bukkit.getPluginManager().registerEvents(menuItemListener, this);
    }

    /**
     * Re-reads {@code config.yml} from disk, re-parses it, re-runs the startup command-root
     * validation, and swaps the live model in {@link MenuService} — all without restarting the
     * server. Called by {@code /pizza reload} ({@link PizzaCommand}) only. {@link #onEnable}
     * inlines the same parse-then-validate sequence instead of calling this method, because at
     * that point {@link #menuService} does not exist yet — it is constructed *from* the parsed
     * {@link PizzaConfig} this produces, so nothing can call {@code menuService.reload(...)} until
     * after that construction completes.
     */
    PizzaConfig reloadPizzaConfig() {
        PizzaConfig config = parseConfig();
        validateCommandRoots(config);
        validateMenuItemMaterial(config);
        menuService.reload(config);
        return config;
    }

    private PizzaConfig parseConfig() {
        Map<String, Object> raw = loadRawConfig();
        return ConfigParser.parse(raw, warning -> getLogger().warning(warning));
    }

    /**
     * Before the first parse, decide whether the on-disk config.yml is an unmodified older default
     * that can be safely replaced with this version's default. Only replaces on a REFRESH verdict;
     * a customized config is left untouched with a single INFO line. See {@link ConfigRefreshDecision}.
     */
    private void maybeAutoRefreshConfig() {
        saveDefaultConfig(); // writes the bundled default only if config.yml is absent
        Path path = getDataFolder().toPath().resolve("config.yml");
        try {
            byte[] onDisk = Files.readAllBytes(path);
            byte[] current = ConfigHashes.currentDefaultBytes();
            switch (ConfigRefreshDecision.decide(onDisk, current, ConfigHashes.knownHashes())) {
                case UP_TO_DATE -> { /* nothing to do */ }
                case REFRESH -> {
                    Path backup = writeBackup(path, onDisk);
                    Files.write(path, current);
                    getLogger().info("config.yml was an unmodified older default; refreshed it to the "
                            + "v" + getPluginMeta().getVersion() + " default. Your old file is at "
                            + backup.getFileName());
                }
                case CUSTOMIZED -> getLogger().info("config.yml looks customized; leaving it as-is. New "
                        + "default features may be missing — run /pizza config refresh to reset it "
                        + "(your file is backed up first).");
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "could not auto-refresh config.yml; using it as-is", e);
        }
    }

    /**
     * Unconditionally back up config.yml and overwrite it with this version's bundled default.
     * Called by {@code /pizza config refresh}. Returns the backup path so the command can report it.
     */
    Path refreshConfigToDefault() throws IOException {
        Path path = getDataFolder().toPath().resolve("config.yml");
        byte[] onDisk = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
        Path backup = writeBackup(path, onDisk);
        Files.write(path, ConfigHashes.currentDefaultBytes());
        reloadPizzaConfig();
        return backup;
    }

    private Path writeBackup(Path configPath, byte[] contents) throws IOException {
        // Live server runtime — System.currentTimeMillis is available and fine here.
        Path backup = configPath.resolveSibling("config.yml.backup-" + System.currentTimeMillis());
        Files.write(backup, contents);
        return backup;
    }

    /** See the class-level javadoc for why this reads the file directly instead of {@link #getConfig()}. */
    private Map<String, Object> loadRawConfig() {
        saveDefaultConfig();
        Path path = getDataFolder().toPath().resolve("config.yml");
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            if (loaded == null) {
                getLogger().warning("config.yml is empty; using an empty config (everything fails closed)");
                return Map.of();
            }
            if (loaded instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }
            getLogger()
                    .severe("config.yml did not parse to a map; using an empty config (everything "
                            + "fails closed)");
            return Map.of();
        } catch (IOException e) {
            getLogger()
                    .log(Level.SEVERE,
                            "could not read config.yml; using an empty config (everything fails closed)",
                            e);
            return Map.of();
        }
    }

    private void validateCommandRoots(PizzaConfig config) {
        CommandMap commandMap = Bukkit.getCommandMap();
        Set<String> roots = new TreeSet<>();
        for (Menu menu : config.menus().values()) {
            for (Button button : menu.buttons()) {
                if (button.action() instanceof Action.RunCommand runCommand) {
                    String root = CommandAllowlist.rootOf(runCommand.command());
                    if (!root.isEmpty()) {
                        roots.add(root);
                    }
                }
            }
        }
        for (String root : roots) {
            if (commandMap.getCommand(root) == null) {
                getLogger()
                        .warning("configured command root '" + root + "' does not resolve to a "
                                + "registered command; any button that dispatches it will silently "
                                + "do nothing until the wrapped plugin is installed (or a renamed "
                                + "subcommand is fixed)");
            }
        }
    }

    /**
     * Warns once, at boot and again on {@code /pizza reload}, if the configured menu-item
     * material does not resolve to a real item while the feature is enabled. Mirrors {@link
     * #validateCommandRoots} in shape and intent: turns a silently-dead feature (a misconfigured
     * material makes {@link MenuItemService#create} return {@code null} and {@code giveIfMissing}
     * give nothing, with no diagnostic) into a startup/reload warning instead.
     */
    private void validateMenuItemMaterial(PizzaConfig config) {
        if (!config.menuItem().enabled()) {
            return;
        }
        String material = config.menuItem().material();
        if (MenuItemService.resolveMaterial(material).isEmpty()) {
            getLogger()
                    .warning("menu-item material '" + material + "' does not resolve to a valid "
                            + "item; the menu item will not be given");
        }
    }
}
