package net.runelite.standalone;
/** Mobile-only endpoint overlay. The desktop jar is never changed. */
public enum CustomWorldType {
    PVP("KronosPK"), ECO("Kronos"), BETA("BETA"), DEV("Development");
    private final String name;
    CustomWorldType(String name) { this.name = name; }
    public String getName() { return name; }
    public String getUrl() { return "http://" + getGameServerAddress(); }
    public String getGameServerAddress() { return System.getProperty("kronos.host", "127.0.0.1"); }
    public String getFileServerAddress() { return getGameServerAddress(); }
}
