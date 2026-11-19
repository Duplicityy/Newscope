package net.runelite.standalone;

import java.util.LinkedList;

public final class WorldMapConfirmCheck {
    private static final int LEFT = 10, TOP = 20, WIDTH = 450, HEIGHT = 250;
    private static final int PX = 120, PY = 110;
    private static final int[] PIXELS = new int[600 * 350];
    private static final int GOLD = 0xffffcd42;
    private static final int LIMIT_WIDTH = 225, LIMIT_HEIGHT = 125;
    private static WorldMap map;

    public static void main(String[] args) {
        Client.gameState = 30;
        Client.cycle = 100;
        Client.packetWriter.isaacCipher = new IsaacCipher(new int[4]);
        Rasterizer2D.method6409(PIXELS, 600, 350);
        map = new WorldMap();
        map.currentMapArea = new WorldMapArea();
        map.currentMapArea.sections = new LinkedList<>();
        map.currentMapArea.sections.add(new WorldMapSection() {
            public boolean vmethod5843(int p,int x,int y) { return true; }
            public void decode(Buffer b) {}
            public Coord vmethod5846(int x,int y) { return new Coord(0,x,y); }
            public int[] vmethod5845(int p,int x,int y) { return new int[] {x,y}; }
            public boolean vmethod5844(int x,int y) { return true; }
            public void vmethod5850(WorldMapArea a) {}
        });
        map.currentMapArea.regionLowX = 40;
        map.currentMapArea.regionHighX = 60;
        map.currentMapArea.regionLowY = 40;
        map.currentMapArea.regionHighY = 60;
        map.zoom = map.zoomTarget = 2f;
        map.centerTileX = map.centerTileY = 640;
        map.worldMapDisplayX = LEFT;
        map.worldMapDisplayY = TOP;
        map.worldMapDisplayWidth = LIMIT_WIDTH;
        map.worldMapDisplayHeight = LIMIT_HEIGHT;
        draw();

        map.method6054(PX,PY,true,true);
        check(Client.packetWriter.bufferSize==0, "press sent a route");
        map.method6054(PX,PY,false,false);
        Coord selected = map.mouseCoord;
        check(selected!=null, "native map projection");
        draw();
        check(markerAt(PX,PY), "yellow marker near selected tile");
        check(Client.packetWriter.bufferSize==0, "selection sent a route");

        map.method6054(PX+61,PY,false,true);
        draw();
        check(Client.packetWriter.bufferSize==0 && !markerAt(PX,PY), "cancel");

        map.method6054(PX,PY,false,true);
        selected=map.mouseCoord;
        draw();
        map.method6054(PX+25,PY,false,true);
        check(Client.packetWriter.bufferSize==5, "route did not send exactly one packet");
        PacketBufferNode sent = (PacketBufferNode)Client.packetWriter.packetBufferNodes.method5044();
        byte[] packet=sent.packetBuffer.array;
        int coord=(packet[1]&255)<<24|(packet[2]&255)<<16|(packet[3]&255)<<8|packet[4]&255;
        check(coord==selected.method3913(), "routed to button rather than destination");
        check(sent.clientPacket==ClientPacket.field2409, "wrong routing packet");
        map.method6054(PX+25,PY,false,false);
        check(Client.packetWriter.bufferSize==5, "duplicate route");
        Client.packetWriter.bufferSize=0;

        map.method6054(PX,PY,true,true);
        map.method6054(PX+30,PY+18,true,false);
        map.method6054(PX+30,PY+18,false,false);
        draw();
        check(Client.packetWriter.bufferSize==0 && !markerAt(PX,PY), "pan submitted a destination");
        check(map.centerTileX!=640, "native drag lost");

        map.method6054(PX,PY,false,true);
        draw();
        check(markerAt(PX,PY), "marker before hiding map");
        Client.cycle += 10;
        draw();
        check(!markerAt(PX,PY), "stale marker after map reopened");
        System.out.println("PASS world map marker, mouse and collapsed touch taps, exact packet, cancel, drag, reopen");
        System.exit(0);
    }

    private static boolean markerAt(int x,int y) {
        for(int dy=-3;dy<=3;dy++) for(int dx=-3;dx<=3;dx++)
            if (PIXELS[(y+dy)*600+x+dx]==GOLD) return true;
        return false;
    }

    private static void draw() {
        java.util.Arrays.fill(PIXELS,0);
        WorldMapConfirm.draw(map,LEFT,TOP,WIDTH,HEIGHT);
    }

    private static void check(boolean ok,String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}
