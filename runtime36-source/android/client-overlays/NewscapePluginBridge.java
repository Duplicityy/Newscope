/** Keeps the existing plugin request API while adding the owner automation transport. */
public final class NewscapePluginBridge {
    private NewscapePluginBridge() {}

    public static String request(String json) {
        String automation = net.runelite.standalone.OwnerAutomationClient.request(json);
        return automation == null ? net.runelite.client.config.OwnerPluginBridge.request(json) : automation;
    }
}
