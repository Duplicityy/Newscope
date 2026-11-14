package net.runelite.client.rs;
import java.applet.*;
import java.net.*;
/** Mobile-only configuration: both cache and login point at the selected server. */
class RSAppletStub implements AppletStub {
    private final RSConfig config;
    RSAppletStub(RSConfig config) { this.config = config; }
    public boolean isActive() { return true; }
    public URL getCodeBase() { try { return new URL("http://" + System.getProperty("kronos.host","127.0.0.1") + "/"); } catch (MalformedURLException e) { throw new IllegalArgumentException(e); } }
    public URL getDocumentBase() { return getCodeBase(); }
    public String getParameter(String name) { return config.getAppletProperties().get(name); }
    public AppletContext getAppletContext() { return null; }
    public void appletResize(int width, int height) { }
}
