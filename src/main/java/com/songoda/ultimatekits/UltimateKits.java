package com.songoda.ultimatekits;

import com.songoda.core.SongodaCore;
import com.songoda.core.SongodaPlugin;
import com.songoda.core.commands.CommandManager;
import com.songoda.core.compatibility.CompatibleMaterial;
import com.songoda.core.configuration.Config;
import com.songoda.core.database.DataMigrationManager;
import com.songoda.core.database.DatabaseConnector;
import com.songoda.core.database.MySQLConnector;
import com.songoda.core.database.SQLiteConnector;
import com.songoda.core.gui.GuiManager;
import com.songoda.core.hooks.EconomyManager;
import com.songoda.core.hooks.HologramManager;
import com.songoda.core.utils.TextUtils;
import com.songoda.ultimatekits.category.Category;
import com.songoda.ultimatekits.category.CategoryManager;
import com.songoda.ultimatekits.commands.CommandCategories;
import com.songoda.ultimatekits.commands.CommandCrate;
import com.songoda.ultimatekits.commands.CommandCreatekit;
import com.songoda.ultimatekits.commands.CommandEdit;
import com.songoda.ultimatekits.commands.CommandKey;
import com.songoda.ultimatekits.commands.CommandKit;
import com.songoda.ultimatekits.commands.CommandPreviewKit;
import com.songoda.ultimatekits.commands.CommandReload;
import com.songoda.ultimatekits.commands.CommandRemove;
import com.songoda.ultimatekits.commands.CommandSet;
import com.songoda.ultimatekits.commands.CommandSettings;
import com.songoda.ultimatekits.conversion.Convert;
import com.songoda.ultimatekits.crate.Crate;
import com.songoda.ultimatekits.crate.CrateManager;
import com.songoda.ultimatekits.database.DataManager;
import com.songoda.ultimatekits.database.migrations._1_InitialMigration;
import com.songoda.ultimatekits.database.migrations._2_DuplicateMigration;
import com.songoda.ultimatekits.handlers.DisplayItemHandler;
import com.songoda.ultimatekits.handlers.ParticleHandler;
import com.songoda.ultimatekits.key.Key;
import com.songoda.ultimatekits.key.KeyManager;
import com.songoda.ultimatekits.kit.Kit;
import com.songoda.ultimatekits.kit.KitAnimation;
import com.songoda.ultimatekits.kit.KitBlockData;
import com.songoda.ultimatekits.kit.KitItem;
import com.songoda.ultimatekits.kit.KitManager;
import com.songoda.ultimatekits.kit.KitType;
import com.songoda.ultimatekits.listeners.BlockListeners;
import com.songoda.ultimatekits.listeners.ChatListeners;
import com.songoda.ultimatekits.listeners.ChunkListeners;
import com.songoda.ultimatekits.listeners.EntityListeners;
import com.songoda.ultimatekits.listeners.InteractListeners;
import com.songoda.ultimatekits.listeners.PlayerListeners;
import com.songoda.ultimatekits.settings.Settings;
import com.songoda.ultimatekits.utils.ItemSerializer;
import com.songoda.ultimatekits.utils.Methods;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class UltimateKits extends SongodaPlugin {
    private static UltimateKits INSTANCE;

    private final Config kitConfig = new Config(this, "kit.yml");
    private final Config categoryConfig = new Config(this, "category.yml");
    private final Config dataFile = new Config(this, "data.yml");
    private final Config keyFile = new Config(this, "keys.yml");
    private final Config crateFile = new Config(this, "crates.yml");

    private final GuiManager guiManager = new GuiManager(this);
    private final ParticleHandler particleHandler = new ParticleHandler(this);
    private final DisplayItemHandler displayItemHandler = new DisplayItemHandler(this);

    private KitManager kitManager;
    private CommandManager commandManager;
    private KeyManager keyManager;
    private CrateManager crateManager;
    private CategoryManager categoryManager;

    private DatabaseConnector databaseConnector;
    private DataManager dataManager;

    private boolean loaded = false;

    public static UltimateKits getInstance() {
        return INSTANCE;
    }

    @Override
    public void onPluginLoad() {
        INSTANCE = this;
    }

    @Override
    public void onPluginEnable() {
        SongodaCore.registerPlugin(this, 14, CompatibleMaterial.BEACON);

        // Load Economy
        EconomyManager.load();
        // Register Hologram Plugin
        HologramManager.load(this);

        // Setup Config
        Settings.setupConfig();
        this.setLocale(Settings.LANGUGE_MODE.getString(), false);

        // Set economy preference
        EconomyManager.getManager().setPreferredHook(Settings.ECONOMY_PLUGIN.getString());

        this.kitManager = new KitManager();
        this.keyManager = new KeyManager();
        this.crateManager = new CrateManager();
        this.categoryManager = new CategoryManager(this);

        this.kitConfig.load();
        Convert.runKitConversions();

        this.categoryConfig.load();

        // load kits
        this.dataFile.load();
        this.keyFile.load();
        this.crateFile.load();
        checkKeyDefaults();
        checkCrateDefaults();
        this.keyFile.saveChanges();
        this.crateFile.saveChanges();

        // setup commands
        this.commandManager = new CommandManager(this);
        this.commandManager.addCommand(new CommandKit(this, this.guiManager));
        this.commandManager.addCommand(new CommandPreviewKit(this, this.guiManager));
        this.commandManager.addMainCommand("KitAdmin")
                .addSubCommand(new CommandReload(this))
                .addSubCommand(new CommandSettings(this, this.guiManager))
                .addSubCommand(new CommandCreatekit(this, this.guiManager))
                .addSubCommand(new CommandCategories(this, this.guiManager))
                .addSubCommand(new CommandEdit(this, this.guiManager))
                .addSubCommand(new CommandKey(this))
                .addSubCommand(new CommandSet(this))
                .addSubCommand(new CommandRemove(this))

                .addSubCommand(new CommandCrate());


        // Event registration
        this.guiManager.init();
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new BlockListeners(this), this);
        pluginManager.registerEvents(new ChunkListeners(this), this);
        pluginManager.registerEvents(new ChatListeners(), this);
        pluginManager.registerEvents(new EntityListeners(this), this);
        pluginManager.registerEvents(new InteractListeners(this, this.guiManager), this);
        pluginManager.registerEvents(new PlayerListeners(), this);

        try {
            if (Settings.MYSQL_ENABLED.getBoolean()) {
                String hostname = Settings.MYSQL_HOSTNAME.getString();
                int port = Settings.MYSQL_PORT.getInt();
                String database = Settings.MYSQL_DATABASE.getString();
                String username = Settings.MYSQL_USERNAME.getString();
                String password = Settings.MYSQL_PASSWORD.getString();
                boolean useSSL = Settings.MYSQL_USE_SSL.getBoolean();
                int poolSize = Settings.MYSQL_POOL_SIZE.getInt();

                this.databaseConnector = new MySQLConnector(this, hostname, port, database, username, password, useSSL, poolSize);
                this.getLogger().info("Data handler connected using MySQL.");
            } else {
                this.databaseConnector = new SQLiteConnector(this);
                this.getLogger().info("Data handler connected using SQLite.");
            }

            this.dataManager = new DataManager(this.databaseConnector, this);
            DataMigrationManager dataMigrationManager = new DataMigrationManager(this.databaseConnector, this.dataManager,
                    new _1_InitialMigration(),
                    new _2_DuplicateMigration(this.databaseConnector instanceof SQLiteConnector));
            dataMigrationManager.runMigrations();
        } catch (Exception ex) {
            this.getLogger().severe("Fatal error trying to connect to database. " +
                    "Please make sure all your connection settings are correct and try again. Plugin has been disabled. (" + ex.getMessage() + ")");
            emergencyStop();
            return;
        }

        this.displayItemHandler.start();
        this.particleHandler.start();
    }

    @Override
    public void onDataLoad() {
        // Empty categories from manager
        categoryManager.clearCategories();

        /*
         * Register categories into CategoryManager from Configuration
         */
        if (categoryConfig.getConfigurationSection("Categories") != null) {
            for (String key : categoryConfig.getConfigurationSection("Categories").getKeys(false)) {
                ConfigurationSection section = categoryConfig.getConfigurationSection("Categories." + key);
                if (section == null) {
                    continue;
                }

                Category category = categoryManager.addCategory(key, section.getString("name"));
                if (section.contains("material")) {
                    category.setMaterial(CompatibleMaterial.getMaterial(section.getString("material")).getMaterial());
                }
            }
        }

        // Empty kits from manager.
        kitManager.clearKits();

        /*
         * Register kits into KitManager from Configuration
         */
        if (kitConfig.getConfigurationSection("Kits") != null) {
            for (String kitName : kitConfig.getConfigurationSection("Kits").getKeys(false)) {
                ConfigurationSection section = kitConfig.getConfigurationSection("Kits." + kitName);
                if (section == null) {
                    continue;
                }

                String itemString = section.getString("displayItem");

                ItemStack item = null;

                if (itemString != null) {
                    if (itemString.contains("{")) {
                        item = ItemSerializer.deserializeItemStackFromJson(itemString);
                    } else {
                        item = CompatibleMaterial.getMaterial(itemString).getItem();
                    }
                }

                kitManager.addKit(new Kit(kitName)
                        .setTitle(section.getString("title"))
                        .setDelay(section.getLong("delay"))
                        .setLink(section.getString("link"))
                        .setDisplayItem(item)
                        .setCategory(categoryManager.getCategory(section.getString("category")))
                        .setHidden(section.getBoolean("hidden"))
                        .setPrice(section.getDouble("price"))
                        .setContents(section.getStringList("items").stream().map(KitItem::new).collect(Collectors.toList()))
                        .setKitAnimation(KitAnimation.valueOf(section.getString("animation", KitAnimation.NONE.name())))
                );
            }
        }

        /*
         * Register legacy kit locations into KitManager from Configuration
         */
        if (dataFile.contains("BlockData")) {
            for (String key : dataFile.getConfigurationSection("BlockData").getKeys(false)) {
                Location location = Methods.unserializeLocation(key);
                Kit kit = kitManager.getKit(dataFile.getString("BlockData." + key + ".kit"));
                KitType type = KitType.valueOf(dataFile.getString("BlockData." + key + ".type", "PREVIEW"));
                boolean holograms = dataFile.getBoolean("BlockData." + key + ".holograms");
                boolean displayItems = dataFile.getBoolean("BlockData." + key + ".displayItems");
                boolean particles = dataFile.getBoolean("BlockData." + key + ".particles");
                boolean itemOverride = dataFile.getBoolean("BlockData." + key + ".itemOverride");

                if (kit == null) {
                    dataFile.set("BlockData." + key, null);
                } else {
                    updateHologram(kitManager.addKitToLocation(kit, location, type, holograms, particles, displayItems, itemOverride));
                }
            }
        }

        /*
         * Register kit locations into KitManager from Configuration
         */
        Bukkit.getScheduler().runTaskLater(this, () ->
                this.dataManager.getBlockData((blockData) -> {
                    this.kitManager.setKitLocations(blockData);

                    Collection<KitBlockData> kitBlocks = getKitManager().getKitLocations().values();
                    for (KitBlockData data : kitBlocks) {
                        updateHologram(data);
                    }
                }), 20L);

        // Apply default keys
        checkKeyDefaults();
        checkCrateDefaults();

        // Empty keys from manager
        keyManager.clear();
        crateManager.clear();

        /*
         * Register keys into KitManager from Configuration
         */
        if (keyFile.contains("Keys")) {
            for (String keyName : keyFile.getConfigurationSection("Keys").getKeys(false)) {
                int amt = keyFile.getInt("Keys." + keyName + ".Item Amount");
                int kitAmount = keyFile.getInt("Keys." + keyName + ".Amount of kit received");
                boolean enchanted = keyFile.getBoolean("Keys." + keyName + ".Enchanted");

                Key key = new Key(keyName, amt, kitAmount, enchanted);
                keyManager.addKey(key);
            }
        }

        /*
         * Register Crates
         */
        if (crateFile.contains("Crates")) {
            for (String crateName : crateFile.getConfigurationSection("Crates").getKeys(false)) {
                int amt = crateFile.getInt("Crates." + crateName + ".Item Amount");
                int kitAmount = crateFile.getInt("Crates." + crateName + ".Amount of kit received");

                Crate crate = new Crate(crateName, amt, kitAmount);
                crateManager.addCrate(crate);
            }
        }
        this.loaded = true;
    }

    @Override
    public void onPluginDisable() {
        saveKits(false);
        this.dataFile.save();
        this.dataManager.bulkUpdateBlockData(this.getKitManager().getKitLocations());
        this.kitManager.clearKits();
        HologramManager.removeAllHolograms();
    }

    @Override
    public List<Config> getExtraConfig() {
        return Arrays.asList(this.kitConfig, this.keyFile, this.categoryConfig, this.crateFile);
    }

    @Override
    public void onConfigReload() {
        setLocale(Settings.LANGUGE_MODE.getString(), true);

        this.dataManager.bulkUpdateBlockData(getKitManager().getKitLocations());
        this.kitConfig.load();
        this.categoryConfig.load();
        this.keyFile.load();
        this.crateFile.load();
        onDataLoad();
    }

    public void removeHologram(KitBlockData data) {
        HologramManager.removeHologram(data.getHologramId());
    }

    public void updateHologram(Kit kit) {
        for (KitBlockData data : getKitManager().getKitLocations().values()) {
            if (data.getKit() != kit) {
                continue;
            }

            updateHologram(data);
        }
    }

    private void createHologram(KitBlockData data) {
        List<String> lines = formatHologram(data);
        Location location = getKitLocation(data, lines.size());
        HologramManager.createHologram(data.getHologramId(), location, lines);
    }

    private Location getKitLocation(KitBlockData data, int lines) {
        Location location = data.getLocation();
        double multi = .1 * lines;
        if (data.isDisplayingItems()) {
            multi += .25;
        }

        Material type = location.getBlock().getType();
        if (type == Material.TRAPPED_CHEST
                || type == Material.CHEST
                || type.name().contains("SIGN")
                || type == Material.ENDER_CHEST) {
            multi -= .10;
        }

        location.add(0, multi, 0);
        return location;
    }

    public void updateHologram(KitBlockData data) {
        if (data == null || !data.isInLoadedChunk() || !HologramManager.isEnabled()) {
            return;
        }

        List<String> lines = formatHologram(data);
        if (lines.isEmpty() || !data.showHologram()) {
            HologramManager.removeHologram(data.getHologramId());
            return;
        }

        if (!HologramManager.isHologramLoaded(data.getHologramId())) {
            createHologram(data);
            return;
        }

        HologramManager.updateHologram(data.getHologramId(), lines);
    }

    private List<String> formatHologram(KitBlockData data) {
        getDataManager().updateBlockData(data);

        List<String> lines = new ArrayList<>();

        Kit kit = data.getKit();
        KitType kitType = data.getType();
        for (String o : Settings.HOLOGRAM_LAYOUT.getStringList()) {
            switch (o.toUpperCase()) {
                case "{TITLE}":
                    String title = kit.getTitle();
                    if (title == null) {
                        lines.add(ChatColor.DARK_PURPLE + TextUtils.formatText(kit.getKey(), true));
                    } else {
                        lines.add(ChatColor.DARK_PURPLE + TextUtils.formatText(title));
                    }
                    break;

                case "{RIGHT-CLICK}":
                    if (kitType == KitType.CRATE) {
                        lines.add(getLocale().getMessage("interface.hologram.crate").getMessage());
                        break;
                    }
                    if (kit.getLink() != null) {
                        lines.add(getLocale().getMessage("interface.hologram.buylink").getMessage());
                        break;
                    }
                    if (kit.getPrice() != 0) {
                        lines.add(getLocale().getMessage("interface.hologram.buyeco")
                                .processPlaceholder("price", kit.getPrice() != 0
                                        ? Methods.formatEconomy(kit.getPrice())
                                        : getLocale().getMessage("general.type.free").getMessage())
                                .getMessage());
                    }
                    break;

                case "{LEFT-CLICK}":
                    if (kitType == KitType.CLAIM) {
                        lines.add(getLocale().getMessage("interface.hologram.daily").getMessage());
                        break;
                    }
                    if (kit.getLink() == null && kit.getPrice() == 0) {
                        lines.add(getLocale().getMessage("interface.hologram.previewonly").getMessage());
                    } else {
                        lines.add(getLocale().getMessage("interface.hologram.preview").getMessage());
                    }
                    break;

                default:
                    lines.add(ChatColor.translateAlternateColorCodes('&', o));
                    break;
            }
        }

        return lines;
    }

    /**
     * Saves registered kits to file
     */
    public void saveKits(boolean force) {
        if (!loaded && !force) return;

        // If we're changing the order the file needs to be wiped
        if (kitManager.hasOrderChanged()) {
            kitConfig.clearConfig(true);
            kitManager.savedOrderChange();
        }

        // Hot fix for kit file resets
        if (kitConfig.contains("Kits"))
            for (String kitName : kitConfig.getConfigurationSection("Kits").getKeys(false)) {
                if (kitManager.getKits().stream().noneMatch(kit -> kit.getKey().equals(kitName)))
                    kitConfig.set("Kits." + kitName, null);
            }

        // Hot fix for category file resets
        if (categoryConfig.contains("Categories"))
            for (String key : categoryConfig.getConfigurationSection("Categories").getKeys(false)) {
                if (categoryManager.getCategories().stream().noneMatch(category -> category.getKey().equals(key)))
                    categoryConfig.set("Categories." + key, null);
            }

        /*
         * Save kits from KitManager to Configuration
         */
        for (Kit kit : kitManager.getKits()) {
            kitConfig.set("Kits." + kit.getKey() + ".delay", kit.getDelay());
            kitConfig.set("Kits." + kit.getKey() + ".title", kit.getTitle());
            kitConfig.set("Kits." + kit.getKey() + ".link", kit.getLink());
            kitConfig.set("Kits." + kit.getKey() + ".price", kit.getPrice());
            kitConfig.set("Kits." + kit.getKey() + ".hidden", kit.isHidden());
            kitConfig.set("Kits." + kit.getKey() + ".animation", kit.getKitAnimation().name());
            if (kit.getCategory() != null)
                kitConfig.set("Kits." + kit.getKey() + ".category", kit.getCategory().getKey());
            if (kit.getDisplayItem() != null)
                kitConfig.set("Kits." + kit.getKey() + ".displayItem", ItemSerializer.serializeItemStackToJson(kit.getDisplayItem()));
            else
                kitConfig.set("Kits." + kit.getKey() + ".displayItem", null);

            List<KitItem> contents = kit.getContents();
            List<String> strContents = new ArrayList<>();

            for (KitItem item : contents) strContents.add(item.getSerialized());

            kitConfig.set("Kits." + kit.getKey() + ".items", strContents);
        }

        /*
         * Save categories from CategoryManager to Configuration
         */
        for (Category category : categoryManager.getCategories()) {
            categoryConfig.set("Categories." + category.getKey() + ".name", category.getName());
            categoryConfig.set("Categories." + category.getKey() + ".material", category.getMaterial().name());
        }

        // Save to file
        kitConfig.saveChanges();
        categoryConfig.saveChanges();
    }

    /**
     * Insert default key list into config
     */
    private void checkKeyDefaults() {
        if (this.keyFile.contains("Keys")) {
            return;
        }

        this.keyFile.set("Keys.Regular.Item Amount", 3);
        this.keyFile.set("Keys.Regular.Amount overrides", Collections.singletonList("Tools:2"));
        this.keyFile.set("Keys.Regular.Amount of kit received", 1);
        this.keyFile.set("Keys.Ultra.Item Amount", -1);
        this.keyFile.set("Keys.Ultra.Amount of kit received", 1);
        this.keyFile.set("Keys.Insane.Item Amount", -1);
        this.keyFile.set("Keys.Insane.Amount of kit received", 2);
        this.keyFile.set("Keys.Insane.Enchanted", true);
    }

    private void checkCrateDefaults() {
        if (this.crateFile.contains("Crates")) {
            return;
        }

        this.crateFile.set("Crates.Regular.Item Amount", 3);
        this.crateFile.set("Crates.Regular.Amount overrides", Collections.singletonList("Tools:2"));
        this.crateFile.set("Crates.Regular.Amount of kit received", 1);
        this.crateFile.set("Crates.Ultra.Item Amount", -1);
        this.crateFile.set("Crates.Ultra.Amount of kit received", 1);
        this.crateFile.set("Crates.Insane.Item Amount", -1);
        this.crateFile.set("Crates.Insane.Amount of kit received", 2);
    }

    public KitManager getKitManager() {
        return this.kitManager;
    }

    public KeyManager getKeyManager() {
        return this.keyManager;
    }

    public CrateManager getCrateManager() {
        return this.crateManager;
    }

    public Config getKitConfig() {
        return this.kitConfig;
    }

    public Config getDataFile() {
        return this.dataFile;
    }

    /**
     * @deprecated Will be made private or removed completely in the future.
     */
    @Deprecated
    public CommandManager getCommandManager() {
        return this.commandManager;
    }

    public GuiManager getGuiManager() {
        return this.guiManager;
    }

    public DisplayItemHandler getDisplayItemHandler() {
        return this.displayItemHandler;
    }

    /**
     * @deprecated Will be made private or removed completely in the future.
     */
    @Deprecated
    public DatabaseConnector getDatabaseConnector() {
        return this.databaseConnector;
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public CategoryManager getCategoryManager() {
        return this.categoryManager;
    }
}
