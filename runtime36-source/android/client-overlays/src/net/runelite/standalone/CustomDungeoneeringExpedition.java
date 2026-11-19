package net.runelite.standalone;

import com.google.gson.Gson;
import net.runelite.api.widgets.JavaScriptCallback;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** A responsive Newscape ledger made from the existing game widget system. */
final class CustomDungeoneeringExpedition {
    private static final int GROUP = 383;
    private static final int BG = 0x16292d, RAIL = 0x20383d, CARD = 0x28474b;
    private static final int TITLE = 0xe9c891, TEXT = 0xe5eee5, SECONDARY = 0xb3cac7;
    private static final int[] CELLS = { BG, 0x3c5156, 0x507c76, 0xc3a56f, 0x93645c, 0x6ed7b5 };
    private static final Gson GSON = new Gson();
    private static Widget[] original;
    private static View view;

    private static final class Row {
        String title, detail;
        int item, token;
    }
    private static final class View {
        int version, back, next, close, mapWidth, mapHeight;
        int[] map;
        String title, subtitle, pagination;
        List<Row> tabs, rows;
    }

    private CustomDungeoneeringExpedition() {}

    private static long lastOffer = Long.MIN_VALUE / 2;

    static boolean isOffer(Object[] args) {
        return args != null && args.length >= 2 && Integer.valueOf(217).equals(args[0]) &&
            args[1] instanceof String && ((String)args[1]).startsWith("Expedition Ledger / ");
    }

    static void offer(ScriptEvent event) {
        if (Client.gameState != 30 || Client.packetWriter == null ||
            Client.packetWriter.isaacCipher == null || !isOffer(event.getArguments())) return;
        long now = System.nanoTime();
        if (now - lastOffer < 500_000_000L) return;
        lastOffer = now;
        WorldMapSection0.method3906("dungui");
    }
    static boolean matches(String envelope) { return envelope != null && envelope.startsWith("@DG1:"); }

    static boolean accept(String envelope) {
        try {
            if (envelope.length() > 12000) throw new IllegalArgumentException("Expedition page too large");
            byte[] bytes = Base64.getDecoder().decode(envelope.substring(5));
            View next = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), View.class);
            if (!valid(next)) throw new IllegalArgumentException("Invalid expedition page");
            if (original == null) original = UserComparator5.Widget_interfaceComponents[GROUP];
            if (original == null || original.length < 4) throw new IllegalStateException("Expedition frame unavailable");
            view = next;
            build();
        } catch (RuntimeException invalid) {
            restore();
            System.err.println("Expedition page rejected: " + invalid.getMessage());
        }
        return true;
    }

    private static boolean valid(View page) {
        if (page == null || page.version != 1 || page.tabs == null || page.tabs.size() != 7 ||
            page.rows == null || page.rows.size() > 6 || page.title == null || page.close <= 0) return false;
        for (Row row : page.tabs) if (row == null || row.token <= 0) return false;
        for (Row row : page.rows) if (row == null || row.token < 0 || row.item < -1 || row.item >= 65535) return false;
        if (page.map == null) return page.mapWidth == 0 && page.mapHeight == 0;
        if (page.mapWidth < 1 || page.mapWidth > 8 || page.mapHeight < 1 || page.mapHeight > 8 ||
            page.map.length != page.mapWidth * page.mapHeight) return false;
        for (int cell : page.map) if (cell < 0 || cell >= CELLS.length) return false;
        return true;
    }

    static void restore() {
        if (original == null) return;
        UserComparator5.Widget_interfaceComponents[GROUP] = original;
        original = null;
        view = null;
    }

    private static Widget root(int id, int width, int height) {
        Widget widget = new Widget();
        widget.id = (GROUP << 16) | id;
        widget.type = 0;
        widget.isIf3 = true;
        widget.parentId = -1;
        widget.rawWidth = widget.width = width;
        widget.rawHeight = widget.height = height;
        widget.noClickThrough = true;
        return widget;
    }

    private static Widget child(Widget parent, int type, int x, int y, int width, int height) {
        int index = parent.children == null ? 0 : parent.children.length;
        Widget widget = Widget.addChild(parent.id, type, index);
        widget.rawX = widget.x = x;
        widget.rawY = widget.y = y;
        widget.rawWidth = widget.width = width;
        widget.rawHeight = widget.height = height;
        return widget;
    }

    private static Widget block(Widget parent, int x, int y, int width, int height, int colour) {
        Widget shape = child(parent, 3, x, y, width, height);
        shape.color = colour;
        shape.fill = true;
        return shape;
    }

    private static void text(Widget parent, String label, int x, int y, int width, int height, int fontId, int colour) {
        Widget widget = child(parent, 4, x, y, width, height);
        widget.fontId = fontId;
        widget.color = colour;
        widget.textYAlignment = 1;
        widget.textShadowed = false;
        Font font = widget.method3972();
        String value = label == null ? "" : label.replace('<', ' ').replace('>', ' ');
        if (font != null && width > 0 && font.method5324(value) > width) {
            while (!value.isEmpty() && font.method5324(value + "...") > width)
                value = value.substring(0, value.length() - 1);
            value += "...";
        }
        widget.text = value;
    }

    private static void action(Widget parent, String label, int token, int x, int y, int width, int height) {
        if (token <= 0) return;
        Widget hit = block(parent, x, y, width, height, 0);
        hit.transparencyTop = 255;
        hit.itemId = token;
        hit.itemQuantity = 1;
        hit.clickMask |= 2;
        hit.setAction(0, "Select");
        hit.dataText = label;
        hit.hasListener = true;
    }

    private static Widget nativeHost() {
        Widget[] groups = UserComparator5.Widget_interfaceComponents[GROUP];
        return groups != null && groups[0] != null ? (Widget) groups[0].getParent() : null;
    }

    private static int width(Widget host) {
        return Client.isResizable && host != null ? Math.max(512, Math.min(850, host.width - 16)) : 512;
    }

    private static int height(Widget host) {
        return Client.isResizable && host != null ? Math.max(334, Math.min(520, host.height - 12)) : 334;
    }

    private static void place(Widget base, Widget host) {
        if (host == null) return;
        base.rawX = Client.isResizable ? Math.max(0, (host.width - base.rawWidth) / 2) : 0;
        base.rawY = Client.isResizable ? Math.max(0, (host.height - base.rawHeight) / 2) : 0;
        TileItem.method1607(base, host.width, host.height, false);
        class33.method678(base, host.width, host.height);
    }

    private static void build() {
        Widget host = nativeHost();
        int width = width(host), height = height(host);
        Widget[] frame = new Widget[original.length];
        Widget base = frame[0] = root(0, width, height);
        for (int id = 1; id < frame.length; id++) {
            frame[id] = root(id, 0, 0);
            frame[id].isHidden = true;
        }
        UserComparator5.Widget_interfaceComponents[GROUP] = frame;
        place(base, host);
        base.hasListener = true;
        base.onTimer = new Object[]{(JavaScriptCallback) event -> {
            Widget current = nativeHost();
            if (view != null && (base.rawWidth != width(current) || base.rawHeight != height(current))) build();
        }};

        block(base, 0, 0, width, height, BG);
        block(base, 0, 0, width, 3, TITLE);
        block(base, 0, 57, 112, height - 57, RAIL);
        text(base, "Expedition Ledger", 16, 9, width - 60, 25, 496, TITLE);
        text(base, view.subtitle, 16, 34, width - 62, 16, 494, SECONDARY);
        button(base, "Close", view.close, width - 56, 13, 45, false);
        text(base, view.title, 126, 57, width - 144, 25, 495, TITLE);

        for (int i = 0; i < view.tabs.size(); i++) {
            Row row = view.tabs.get(i);
            button(base, row.title, row.token, 9, 65 + i * 34, 94, row.title.equals(view.title));
        }
        int map = view.map == null ? 0 : Math.min(166, (width - 128) * 2 / 5);
        int rowWidth = width - 138 - map;
        int rowHeight = Math.min(55, (height - 120) / 6);
        for (int i = 0; i < view.rows.size(); i++)
            row(base, view.rows.get(i), 126, 84 + i * rowHeight, rowWidth, rowHeight - 3);
        if (map > 0) drawMap(base, width - map - 8, 86, map - 8);
        button(base, "Back", view.back, 126, height - 34, 58, false);
        button(base, "Next", view.next, width - 79, height - 34, 62, false);
        text(base, view.pagination, width / 2 + 40, height - 33, 60, 24, 494, SECONDARY);
        for (Widget widget : frame) {
            WorldMapSectionType.method116(widget);
            ViewportMouse.client.revalidateWidget(widget);
        }
        for (Widget widget : base.children) {
            WorldMapSectionType.method116(widget);
            ViewportMouse.client.revalidateWidget(widget);
        }
        place(base, host);
        CustomInterfaceEdits.unlock(base.id, 0, base.children.length, 0);
    }

    private static void button(Widget base, String label, int token, int x, int y, int width, boolean selected) {
        block(base, x, y, width, 28, selected ? 0x3b6a65 : CARD);
        text(base, label, x + 5, y, width - 10, 28, 495, token > 0 ? TEXT : SECONDARY);
        action(base, label, token, x, y, width, 28);
    }

    private static void row(Widget base, Row row, int x, int y, int width, int height) {
        block(base, x, y, width, height, CARD);
        int start = x + 8;
        if (row.item >= 0) {
            Widget item = child(base, 5, x + 4, y + 2, 28, 27);
            Widget.setItem(item, row.item, 1, 0);
            item.outline = 1;
            start = x + 35;
        }
        int available = width - (start - x) - 7;
        text(base, row.title, start, y + 1, available, 15, 495, TEXT);
        text(base, row.detail, start, y + 16, available, 15, 494, SECONDARY);
        action(base, row.title, row.token, x, y, width, height);
    }

    private static void drawMap(Widget base, int x, int y, int space) {
        int unit = Math.min(22, space / Math.max(view.mapWidth, view.mapHeight));
        block(base, x, y, space, view.mapHeight * unit + 44, RAIL);
        for (int row = 0; row < view.mapHeight; row++) for (int col = 0; col < view.mapWidth; col++) {
            int value = view.map[row * view.mapWidth + col];
            block(base, x + col * unit, y + row * unit, unit - 2, unit - 2, CELLS[value]);
        }
        int legend = y + view.mapHeight * unit + 3;
        text(base, "Mint  You", x + 4, legend, space - 8, 15, 494, 0x6ed7b5);
        text(base, "Gold  Entry / Red  Boss", x + 4, legend + 15, space - 8, 15, 494, SECONDARY);
    }
}