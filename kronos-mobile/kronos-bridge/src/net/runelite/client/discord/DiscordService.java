package net.runelite.client.discord;
import javax.inject.Singleton;
import net.runelite.discord.DiscordUser;
/** Desktop Discord RPC has no Android native library. Mobile plays without RPC. */
@Singleton
public class DiscordService implements AutoCloseable {
    public DiscordUser getCurrentUser(){return null;}
    public void init(){}
    public void close(){}
    public void updatePresence(DiscordPresence presence){}
    public void clearPresence(){}
    public void respondToRequest(String userId,DiscordReplyType reply){}
}
