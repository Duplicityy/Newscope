package io.ruin.utility;

import io.ruin.Server;
import io.ruin.api.protocol.Protocol;
import io.ruin.api.protocol.Response;
import io.ruin.api.utils.FileUtils;
import io.ruin.api.utils.ListUtils;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.entity.player.PlayerLogin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

public class OfflineMode {

    public static boolean enabled;
    
    public static String path;
    private static LocalAccounts accounts;

    public static void setPath() throws IOException {
        File offlineFolder = FileUtils.get(System.getProperty("kronos.playerData", "runtime/player-data"));
        if(!offlineFolder.exists() && !offlineFolder.mkdirs())
            throw new IOException("Offline mode folder could not be created!");
        path = offlineFolder.getPath();
        String accountFile = System.getProperty("kronos.accounts");
        if (Boolean.getBoolean("kronos.public") && accountFile == null)
            throw new IOException("Public mode requires an account registry");
        if (accountFile != null) {
            try { accounts = new LocalAccounts(java.nio.file.Paths.get(accountFile)); }
            catch (Exception e) { throw new IOException("Unable to load local accounts", e); }
        }
    }

    /**
     * Players
     */

    private static final Object userIdLock = new Object();

    private static final HashMap<String, Integer> tempUserIds = new HashMap<>();

    private static int userIdOffset;

    /** Temporary development IDs are reused after restart and cannot own durable rewards. */
    public static boolean hasStableAccountRegistry() { return enabled && accounts != null; }

    /** Convenience privileges are confined to an unauthenticated local development world. */
    public static boolean allowDevelopmentPrivileges() {
        return enabled && accounts == null && !Boolean.getBoolean("kronos.public");
    }

    /** Explicit registry ownership is independent of development-world defaults. */
    static int accountGroup(LocalAccounts.Account account) {
        return account.owner ? PlayerGroup.OWNER.id : account.admin ? PlayerGroup.ADMINISTRATOR.id : PlayerGroup.REGISTERED.id;
    }

    public static Path playerSavePath(String name) throws IOException {
        if (!enabled || path == null || name == null || name.isEmpty())
            throw new IOException("Local player persistence is unavailable");
        Path folder = Paths.get(path, "players").toAbsolutePath().normalize();
        Path target = folder.resolve(name.toLowerCase(Locale.ROOT) + ".json").normalize();
        if (!folder.equals(target.getParent()) || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IOException("Invalid player save name");
        return target;
    }

    public static void writePlayerSnapshot(Path target, String json) throws IOException {
        AtomicPlayerSave.write(target, json.getBytes(StandardCharsets.UTF_8));
    }
    
    public static boolean savePlayer(Player player, int logoutAttempt, String json) {
        if(!enabled)
            return false;
        try {
            writePlayerSnapshot(playerSavePath(player.getName()), json);
            if(logoutAttempt != -1)
                player.finishLogout(logoutAttempt);
        } catch(IOException e) {
            Server.logError("", e);
        }
        return true;
    }
    
    public static boolean loadPlayer(PlayerLogin login) {
        if(!enabled)
            return false;
        if (io.ruin.model.entity.player.autonomous.Adventurers.reservedName(login.info.name)) { login.deny(Response.INVALID_LOGIN); return true; }
        if(Protocol.method360(login.info.name) == null) {
            login.deny(Response.CHANGE_DISPLAY_NAME);
            return true;
        }
        Integer userId;
        int group = PlayerGroup.ADMINISTRATOR.id;
        if (accounts != null) {
            try {
                LocalAccounts.Account account = accounts.authenticate(login.info.name, login.info.password);
                if (account == null) { login.deny(Response.INVALID_LOGIN); return true; }
                userId = account.id;
                group = accountGroup(account);
            } catch (Exception e) {
                Server.logError("Local account authentication failed", e);
                login.deny(Response.LOGIN_SERVER_OFFLINE);
                return true;
            }
        } else synchronized(userIdLock) {
            String key = login.info.name.toLowerCase();
            userId = tempUserIds.computeIfAbsent(key, k -> ++userIdOffset);
        }
        String saved = "";
        try {
            File savedFile = FileUtils.get(path + "/players/" + login.info.name.toLowerCase() + ".json");
            if(savedFile.exists())
                saved = AtomicPlayerSave.decodeSnapshot(Files.readAllBytes(savedFile.toPath()));
        } catch(Exception e) {
            Server.logError("", e);
            login.deny(Response.ERROR_LOADING_ACCOUNT);
            return true;
        }
        login.info.update(userId, login.info.name, saved, ListUtils.toList(group), 0);
        login.success();
        return true;
    }

}
