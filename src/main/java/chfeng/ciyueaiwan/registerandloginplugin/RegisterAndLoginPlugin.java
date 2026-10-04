package chfeng.ciyueaiwan.registerandloginplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RegisterAndLoginPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    public static final String VERSION = "1.3.0";
    public static final String MODRINTH_SLUG = "register-and-login-plugin";
    private final Map<UUID, String> accountPassword = new HashMap<>();
    private final Map<UUID, Boolean> loggedIn = new HashMap<>();
    private final Map<UUID, BukkitRunnable> kickTasks = new HashMap<>();
    private static final long LOGIN_TIMEOUT_TICKS = 20 * 60;
    private File accountsFolder;
    private File langFile;
    private FileConfiguration langConfig;

    private boolean cfg(String key, boolean def) {
        return getConfig().getBoolean(key, def);
    }

    private boolean isPreventDamageEnabled() { return cfg("prevent-damage", true); }
    private boolean isBlockCommandsEnabled() { return cfg("block-commands", true); }
    private boolean isBlockInteractionsEnabled() { return cfg("block-interactions", true); }
    private boolean isShowTitleEnabled() { return cfg("show-title", true); }
    private boolean isAutoLoginByIpEnabled() { return cfg("auto-login-by-ip", true); }

    private int getMaxAccountsPerIp() {
        return getConfig().getInt("max-accounts-for-ip", 1);
    }

    private String sha256Hash(String rawText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append("0");
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            getLogger().severe("Hash failed!");
            return null;
        }
    }

    private void loadLang() {
        langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) saveResource("lang.yml", false);
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    private String getLang(String key, String... replace) {
        String msg = langConfig.getString(key, key);
        for (int i = 0; i < replace.length; i += 2) {
            if (i + 1 >= replace.length) break;
            msg = msg.replace(replace[i], replace[i + 1]);
        }
        return msg;
    }

    private String getPlayerIp(Player player) {
        try {
            return player.getAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private Path getPlayerFile(UUID uuid) {
        return new File(accountsFolder, uuid + ".txt").toPath();
    }

    private String[] loadAccountData(UUID uuid) {
        Path file = getPlayerFile(uuid);
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file).trim();
            String[] parts = content.split("\\|", 2);
            if (parts.length == 0 || parts[0].isEmpty()) return null;
            String hash = parts[0];
            String ip = parts.length > 1 ? parts[1] : "";
            return new String[]{hash, ip};
        } catch (IOException e) {
            getLogger().severe(getLang("plugin.read_err", "%uuid%", uuid.toString()));
            return null;
        }
    }

    private String loadPlayerPassword(UUID uuid) {
        String[] data = loadAccountData(uuid);
        return data == null ? null : data[0];
    }

    private void savePlayerPassword(UUID uuid, String hash, String ip) {
        try {
            Files.writeString(getPlayerFile(uuid), hash + "|" + (ip == null ? "" : ip));
        } catch (IOException e) {
            getLogger().severe(getLang("plugin.save_err", "%uuid%", uuid.toString()));
        }
    }

    private int countAccountsForIp(String ip) {
        if (ip == null || ip.isEmpty() || accountsFolder == null) return 0;
        File[] files = accountsFolder.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            try {
                String content = Files.readString(f.toPath()).trim();
                String[] parts = content.split("\\|", 2);
                if (parts.length > 1 && ip.equals(parts[1])) {
                    count++;
                }
            } catch (IOException ignored) {}
        }
        return count;
    }

    private boolean isLoggedIn(Player player) {
        return loggedIn.getOrDefault(player.getUniqueId(), false);
    }

    private void applyLoginRestrictions(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS,
                Integer.MAX_VALUE,
                0,
                false,
                false,
                false
        ));
    }

    private void removeLoginRestrictions(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.sendTitle("", "", 0, 1, 0);
    }

    private void startTimeoutKick(Player player) {
        UUID uuid = player.getUniqueId();
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!loggedIn.getOrDefault(uuid, false)) {
                    player.kickPlayer(getLang("timeout.kick_msg"));
                }
                kickTasks.remove(uuid);
            }
        };
        task.runTaskLater(this, LOGIN_TIMEOUT_TICKS);
        kickTasks.put(uuid, task);
    }

    private void cancelTimeout(UUID uuid) {
        if (kickTasks.containsKey(uuid)) {
            kickTasks.get(uuid).cancel();
            kickTasks.remove(uuid);
        }
    }

    private void showAuthTitle(Player player, boolean hasAccount) {
        if (!isShowTitleEnabled()) return;
        String title = hasAccount
                ? getLang("title.login")
                : getLang("title.reg");
        player.sendTitle(title, "", 10, 100, 10);
    }

    private void checkModrinthUpdate(CommandSender sender) {
        CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                URI uri = URI.create("https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version");
                URL url = uri.toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", "RegisterAndLoginPlugin/" + VERSION + " (chfeng)");
                try (InputStream is = conn.getInputStream()) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String searchKey = "\"version_number\":\"";
                    int start = json.indexOf(searchKey) + searchKey.length();
                    int end = json.indexOf("\"", start);
                    return json.substring(start, end);
                }
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).thenAccept(latestVer -> Bukkit.getScheduler().runTask(this, () -> {
            if (latestVer == null) {
                sender.sendMessage(getLang("update.check_failed"));
                getLogger().warning("Modrinth update check failed.");
                return;
            }
            if (!VERSION.equals(latestVer)) {
                sender.sendMessage(getLang("update.found", "%local%", VERSION, "%latest%", latestVer));
                getLogger().info("==============================================");
                getLogger().info("New version found! Local: v" + VERSION + " Latest: v" + latestVer);
                getLogger().info("https://modrinth.com/plugin/" + MODRINTH_SLUG);
                getLogger().info("==============================================");
            } else {
                sender.sendMessage(getLang("update.latest"));
                getLogger().info("You are on the latest version.");
            }
        }));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLang();
        getLogger().info(getLang("plugin.enable", "%version%", VERSION));
        accountsFolder = new File(getDataFolder(), "accounts");
        if (!accountsFolder.exists()) accountsFolder.mkdirs();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("register") != null) {
            getCommand("register").setExecutor(this);
            getCommand("register").setTabCompleter(this);
        } else getLogger().warning(getLang("plugin.cmd_register"));
        if (getCommand("login") != null) {
            getCommand("login").setExecutor(this);
            getCommand("login").setTabCompleter(this);
        } else getLogger().warning(getLang("plugin.cmd_login"));
        if (getCommand("changepassword") != null) {
            getCommand("changepassword").setExecutor(this);
            getCommand("changepassword").setTabCompleter(this);
        }
        checkModrinthUpdate(getServer().getConsoleSender());
    }

    @Override
    public void onDisable() {
        getLogger().info(getLang("plugin.disable"));
        kickTasks.values().forEach(BukkitRunnable::cancel);
        kickTasks.clear();
        loggedIn.clear();
        accountPassword.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("login")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("update")) {
                if (!sender.isOp()) {
                    sender.sendMessage(getLang("update.no_perm"));
                    return true;
                }
                sender.sendMessage(getLang("update.checking"));
                checkModrinthUpdate(sender);
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(getLang("only_player"));
                return true;
            }
            UUID uuid = player.getUniqueId();
            String storedHash = loadPlayerPassword(uuid);
            if (storedHash == null) {
                player.sendMessage(getLang("login.no_account"));
                return true;
            }
            if (loggedIn.getOrDefault(uuid, false)) {
                player.sendMessage(getLang("login.already"));
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(getLang("login.usage"));
                return true;
            }
            String inputHash = sha256Hash(args[0]);
            if (inputHash == null) {
                player.sendMessage(getLang("login.hash_fail"));
                return true;
            }
            if (storedHash.equals(inputHash)) {
                String ip = getPlayerIp(player);
                savePlayerPassword(uuid, storedHash, ip);
                loggedIn.put(uuid, true);
                cancelTimeout(uuid);
                removeLoginRestrictions(player);
                player.sendMessage(getLang("login.success"));
                getLogger().info("Player " + player.getName() + " logged in.");
            } else {
                player.sendMessage(getLang("login.wrong"));
                getLogger().warning(getLang("login.wrong_log", "%player%", player.getName()));
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("register")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(getLang("only_player"));
                return true;
            }
            UUID uuid = player.getUniqueId();
            String storedHash = loadPlayerPassword(uuid);
            if (storedHash != null) {
                player.sendMessage(getLang("register.exists"));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(getLang("register.usage"));
                return true;
            }
            if (args[0].length() < 4) {
                player.sendMessage(getLang("register.short"));
                return true;
            }
            if (!args[0].equals(args[1])) {
                player.sendMessage(getLang("register.mismatch"));
                return true;
            }

            String ip = getPlayerIp(player);
            int maxAccounts = getMaxAccountsPerIp();
            if (maxAccounts > 0 && countAccountsForIp(ip) >= maxAccounts) {
                player.sendMessage(getLang("register.ip_used"));
                return true;
            }

            String hash = sha256Hash(args[0]);
            if (hash == null) {
                player.sendMessage(getLang("register.hash_fail"));
                return true;
            }
            savePlayerPassword(uuid, hash, ip);
            accountPassword.put(uuid, hash);
            loggedIn.put(uuid, true);
            cancelTimeout(uuid);
            removeLoginRestrictions(player);
            player.sendMessage(getLang("register.success"));
            getLogger().info(getLang("register.log", "%player%", player.getName(), "%ip%", ip == null ? "?" : ip));
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("changepassword")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(getLang("only_player"));
                return true;
            }
            UUID uuid = player.getUniqueId();
            String storedHash = loadPlayerPassword(uuid);
            if (storedHash == null) {
                player.sendMessage(getLang("cp.no_account"));
                return true;
            }
            if (!loggedIn.getOrDefault(uuid, false)) {
                player.sendMessage(getLang("cp.must_login"));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(getLang("cp.usage"));
                return true;
            }
            String oldHash = sha256Hash(args[0]);
            if (!storedHash.equals(oldHash)) {
                player.sendMessage(getLang("cp.old_wrong"));
                return true;
            }
            String newHash = sha256Hash(args[1]);
            String ip = getPlayerIp(player);
            savePlayerPassword(uuid, newHash, ip);
            player.sendMessage(getLang("cp.success"));
            getLogger().info("Player " + player.getName() + " changed password.");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> suggest = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("login")) {
            if (args.length == 1 && sender.isOp()) {
                suggest.add("update");
            }
        }
        return suggest;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        loggedIn.put(uuid, false);
        cancelTimeout(uuid);

        String[] data = loadAccountData(uuid);
        boolean hasAccount = data != null;
        String currentIp = getPlayerIp(player);

        if (hasAccount && isAutoLoginByIpEnabled()) {
            String savedIp = data[1];
            if (savedIp != null && !savedIp.isEmpty() && savedIp.equals(currentIp)) {
                loggedIn.put(uuid, true);
                player.sendMessage(getLang("auto_login.success"));
                getLogger().info("Auto-login: " + player.getName() + " (IP match)");
                return;
            }
        }

        startTimeoutKick(player);
        applyLoginRestrictions(player);
        showAuthTitle(player, hasAccount);

        if (!hasAccount) {
            player.sendMessage(getLang("join.new"));
        } else {
            player.sendMessage(getLang("join.old"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        cancelTimeout(uuid);
        loggedIn.put(uuid, false);
        accountPassword.remove(uuid);
        removeLoginRestrictions(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!isPreventDamageEnabled()) return;
        if (!(e.getEntity() instanceof Player player)) return;
        if (!isLoggedIn(player)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isBlockCommandsEnabled()) return;
        Player player = e.getPlayer();
        if (isLoggedIn(player)) return;

        String msg = e.getMessage().toLowerCase();
        if (msg.startsWith("/login") || msg.startsWith("/l ")
                || msg.startsWith("/register") || msg.startsWith("/reg ")
                || msg.startsWith("/changepassword") || msg.startsWith("/cp ")) {
            return;
        }
        e.setCancelled(true);
        player.sendMessage(getLang("command.blocked"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!isBlockInteractionsEnabled()) return;
        if (!isLoggedIn(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(getLang("interact.blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!isBlockInteractionsEnabled()) return;
        if (!isLoggedIn(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(getLang("interact.blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (!isBlockInteractionsEnabled()) return;
        if (!isLoggedIn(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!isBlockInteractionsEnabled()) return;
        if (e.getDamager() instanceof Player player && !isLoggedIn(player)) {
            e.setCancelled(true);
            player.sendMessage(getLang("interact.blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (!isBlockInteractionsEnabled()) return;
        if (!isLoggedIn(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(getLang("interact.blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!isBlockInteractionsEnabled()) return;
        if (!isLoggedIn(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(getLang("interact.blocked"));
        }
    }

    @EventHandler
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (isLoggedIn(p)) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        e.setCancelled(true);
        UUID uuid = p.getUniqueId();
        if (loadPlayerPassword(uuid) == null) {
            p.sendMessage(getLang("move.new"));
        } else {
            p.sendMessage(getLang("move.old"));
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (isLoggedIn(p)) return;
        e.setCancelled(true);
        UUID uuid = p.getUniqueId();
        if (loadPlayerPassword(uuid) == null) {
            p.sendMessage(getLang("chat.new"));
        } else {
            p.sendMessage(getLang("chat.old"));
        }
    }
}