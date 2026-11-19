package net.runelite.standalone;

import net.runelite.api.Point;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;

import java.awt.Rectangle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Displays server-authorized raid-bot clicks through the native yellow crosshair. */
final class RaidClickIndicator {
    private static final Pattern MESSAGE = Pattern.compile(
            "NewscapeRaidClick ([wonib]) ([0-9]{1,5}) ([0-9]{1,5}) ([0-9]{1,5}) ([0-3])");
    private static Click pending;

    private RaidClickIndicator() {}

    static boolean receive(int type, String sender, String text) {
        if (type != 0 || sender != null && !sender.isEmpty() || text == null
                || !text.startsWith("NewscapeRaidClick ")) return false;
        Matcher match = MESSAGE.matcher(text);
        if (!match.matches()) return false;
        Click click = new Click(match.group(1).charAt(0), Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)), Integer.parseInt(match.group(5)));
        if (click.index > 65535 || click.x > 65535 || click.y > 65535) return false;
        if (pending == null || click.priority() >= pending.priority()) pending = click;
        return true;
    }

    static String command(String text) {
        String action = text.trim().replaceAll(" +", " ");
        return action.equalsIgnoreCase("raid auto") || action.equalsIgnoreCase("raid auto start")
                ? "raid auto view" : text;
    }

    static void pump() {
        Click click = pending;
        pending = null;
        Client client = ViewportMouse.client;
        if (click == null || client == null || Client.gameState != 30 || Client.isLoading || Client.mouseCrossColor == 2) return;
        Point point = click.locate(client);
        if (point == null || point.getX() < 0 || point.getY() < 0
                || point.getX() >= client.getCanvasWidth() || point.getY() >= client.getCanvasHeight()) return;
        Client.mouseCrossX = point.getX();
        Client.mouseCrossY = point.getY();
        Client.mouseCrossState = 0;
        Client.mouseCrossColor = 1;
    }

    private static final class Click {
        final char kind;
        final int index, x, y, plane;

        Click(char kind, int index, int x, int y, int plane) {
            this.kind = kind;
            this.index = index;
            this.x = x;
            this.y = y;
            this.plane = plane;
        }

        int priority() {
            return kind == 'n' || kind == 'o' ? 2 : kind == 'w' ? 0 : 1;
        }

        Point locate(Client client) {
            if (kind == 'i' || kind == 'b') return locateWidget(client);
            if (client.getPlane() != plane) return null;
            LocalPoint tile = LocalPoint.fromWorld(client, x, y);
            if (tile == null) return null;
            int height = kind == 'o' ? 40 : 0;
            if (kind == 'n' && index < Client.npcs.length) {
                NPC npc = Client.npcs[index];
                if (npc != null && npc.getLocalLocation().distanceTo(tile) <= 640) {
                    tile = npc.getLocalLocation();
                    height = npc.getLogicalHeight() / 2;
                }
            }
            Point point = Perspective.localToCanvas(client, tile, plane, height);
            Rectangle viewport = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
                    client.getViewportWidth(), client.getViewportHeight());
            return point != null && viewport.contains(point.getX(), point.getY()) ? point : null;
        }

        private Point locateWidget(Client client) {
            Widget widget = client.getWidget(x, y);
            if (widget == null || widget.isHidden()) return null;
            if (kind == 'b') return middle(widget.getBounds());
            if (index >= 28) return null;
            try {
                WidgetItem item = widget.getWidgetItem(index);
                return item == null ? null : middle(item.getCanvasBounds());
            } catch (IndexOutOfBoundsException missingSlot) {
                return null;
            }
        }

        private Point middle(Rectangle bounds) {
            return bounds == null || bounds.isEmpty() ? null
                    : new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
        }
    }
}
