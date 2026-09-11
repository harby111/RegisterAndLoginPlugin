package chfeng.ciyueaiwan.registerAndLoginPlugin121;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegisterAndLoginPlugin121 extends JavaPlugin implements Listener, CommandExecutor {
    public static final String VERSION = "2.0.0";
    private final Map<UUID, String> accountPassword = new HashMap<>();
    private final Map<UUID, Boolean> isLoggedIn = new HashMap<>();
    private File accountFolder;

    private File langFile;
    private FileConfiguration langConfig;

    // SHA256哈希加密
    private String sha256Encrypt(String rawStr) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexSb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexSb.append("0");
                hexSb.append(hex);
            }
            return hexSb.toString();
        } catch (NoSuchAlgorithmException e) {
            getLogger().severe("Hash encrypt failed");
            return null;
        }
    }

    // 加载语言文件
    private void loadLang() {
        langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    // 读取语言+占位符替换
    private String getLang(String key, String... replace) {
        String msg = langConfig.getString(key, key);
        for (int i = 0; i < replace.length; i += 2) {
            if (i + 1 >= replace.length) break;
            msg = msg.replace(replace[i], replace[i+1]);
        }
        return msg;
    }

    @Override
    public void onEnable() {
        loadLang();
        getLogger().info(getLang("plugin.enable", "%version%", VERSION));
        accountFolder = new File(getDataFolder(), "accounts");
        if (!accountFolder.exists()) {
            if (!accountFolder.mkdirs()) {
                getLogger().severe(getLang("plugin.create_folder_fail"));
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("register") != null) {
            getCommand("register").setExecutor(this);
        } else {
            getLogger().warning(getLang("plugin.cmd_register_not_found"));
        }
        if (getCommand("login") != null) {
            getCommand("login").setExecutor(this);
        } else {
            getLogger().warning(getLang("plugin.cmd_login_not_found"));
        }
    }

    @Override
    public void onDisable() {
        getLogger().info(getLang("plugin.disable"));
        accountPassword.clear();
        isLoggedIn.clear();
    }

    private Path getPlayerFile(UUID uuid) {
        return new File(accountFolder, uuid + ".txt").toPath();
    }

    private String loadPassword(UUID uuid) {
        Path file = getPlayerFile(uuid);
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file).trim();
            String[] splitData = content.split("\\|");
            return splitData[0];
        } catch (IOException e) {
            getLogger().severe(getLang("plugin.read_account_err", "%uuid%", uuid.toString()));
            return null;
        }
    }

    private void savePassword(UUID uuid, String passHash, String ip) {
        try {
            String saveText = passHash + "|" + ip;
            Files.writeString(getPlayerFile(uuid), saveText);
        } catch (IOException e) {
            getLogger().severe(getLang("plugin.save_account_err", "%uuid%", uuid.toString()));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(getLang("register.only_player"));
            return true;
        }
        UUID uuid = player.getUniqueId();
        String savedPassHash = loadPassword(uuid);

        if (cmd.getName().equalsIgnoreCase("register")) {
            if (savedPassHash != null) {
                player.sendMessage(getLang("register.already_registered"));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(getLang("register.usage"));
                return true;
            }
            if (args[0].length() < 4) {
                player.sendMessage(getLang("register.password_too_short"));
                return true;
            }
            String p1 = args[0];
            String p2 = args[1];
            if (!p1.equals(p2)) {
                player.sendMessage(getLang("register.password_not_match"));
                return true;
            }
            String hash = sha256Encrypt(p1);
            if (hash == null) {
                player.sendMessage(getLang("register.encrypt_fail"));
                return true;
            }
            String playerIp = player.getAddress().getAddress().getHostAddress();
            savePassword(uuid, hash, playerIp);
            getLogger().info(getLang("join.log_online", "%player%", player.getName(), "%ip%", playerIp));
            accountPassword.put(uuid, hash);
            isLoggedIn.put(uuid, true);
            player.sendMessage(getLang("register.success"));
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("login")) {
            if (savedPassHash == null) {
                player.sendMessage(getLang("login.not_registered"));
                return true;
            }
            if (isLoggedIn.getOrDefault(uuid, false)) {
                player.sendMessage(getLang("login.already_logged"));
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(getLang("login.usage"));
                return true;
            }
            String inputHash = sha256Encrypt(args[0]);
            if (inputHash == null) {
                player.sendMessage(getLang("login.verify_fail"));
                return true;
            }
            if (savedPassHash.equals(inputHash)) {
                isLoggedIn.put(uuid, true);
                player.sendMessage(getLang("login.success"));
                getLogger().info("Player " + player.getName() + " login success");
            } else {
                player.sendMessage(getLang("login.wrong_password"));
                getLogger().warning(getLang("login.wrong_password_log", "%player%", player.getName()));
            }
            return true;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        isLoggedIn.put(uuid, false);
        String playerIp = null;
        try {
            playerIp = player.getAddress().getAddress().getHostAddress();
        } catch (Exception ex) {
            getLogger().warning(getLang("plugin.get_ip_fail", "%player%", player.getName()));
        }
        if(playerIp != null) {
            getLogger().info(getLang("join.log_online", "%player%", player.getName(), "%ip%", playerIp));
        }
        String pass = loadPassword(uuid);
        if (pass == null) {
            player.sendMessage(getLang("join.welcome_new"));
        } else {
            player.sendMessage(getLang("join.need_login"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        isLoggedIn.put(uuid, false);
        accountPassword.remove(uuid);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (!isLoggedIn.getOrDefault(uuid, false)) {
            if (e.getFrom().getBlockX() == e.getTo().getBlockX() &&
                    e.getFrom().getBlockY() == e.getTo().getBlockY() &&
                    e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
                return;
            }
            e.setCancelled(true);
            String pass = loadPassword(uuid);
            if (pass == null) {
                p.sendMessage(getLang("move.not_logged_register"));
            } else {
                p.sendMessage(getLang("move.not_logged_login"));
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (!isLoggedIn.getOrDefault(uuid, false)) {
            e.setCancelled(true);
            String pass = loadPassword(uuid);
            if (pass == null) {
                p.sendMessage(getLang("chat.not_logged_register"));
            } else {
                p.sendMessage(getLang("chat.not_logged_login"));
            }
        }
    }
}
