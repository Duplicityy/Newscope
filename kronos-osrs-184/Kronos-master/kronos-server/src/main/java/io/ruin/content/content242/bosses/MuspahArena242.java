package io.ruin.content.content242.bosses;

import java.util.*;
import java.util.function.BiPredicate;

/** Captured lair shockwave footprint from pinned TSPS phantom-muspah.json (BSD notice). */
final class MuspahArena242 {
    static final int CENTRE_X=2847,CENTRE_Y=4258;
    static final String[] ROWS={
        "                 .....   ","  .. ....ooooooo.......  ","  ........ooooo........  ",
        " .........ooooo......... "," .......ooooooooo....... "," ......oooo...oooo...... ",
        " .....oooo.....oooo..... ","   o..ooooo...ooooo..o.. ","  .ooooo.ooo.ooo.ooooo...",
        "  .oooo...o   o...oooo...","  .oooo....   ....oooo...","  .oooo...o   o...oooo...",
        "  .ooooo.ooo.ooo.ooooo...","  .o..ooooo...ooooo..o.. ","  ....oooo.....oooo....  ",
        " ......oooo...oooo...    ","........ooooooooo....    ","..........ooooo......    ",
        "..........ooooo.......   "," ........ooooooo......   ","   .................     ",
        "   ..            .       "};
    static boolean floor(int x,int y){int dx=x-CENTRE_X,dy=y-CENTRE_Y;if(Math.abs(dx)<=1&&Math.abs(dy)<=1)return true;return kind(x,y)!=' ';}
    static char kind(int x,int y){int row=10-(y-CENTRE_Y),col=x-CENTRE_X+12;return row>=0&&row<ROWS.length&&col>=0&&col<ROWS[row].length()?ROWS[row].charAt(col):' ';}
    static List<int[]> tiles(){List<int[]> result=new ArrayList<>();for(int y=4247;y<=4268;y++)for(int x=2835;x<=2859;x++)if(floor(x,y))result.add(new int[]{x,y});return result;}
    static boolean sheltered(int x,int y,BiPredicate<Integer,Integer> blocked){
        int dx=x-CENTRE_X,dy=y-CENTRE_Y,steps=Math.max(Math.abs(dx),Math.abs(dy))*4;
        for(int i=1;i<steps;i++){int px=(int)Math.round(CENTRE_X+dx*i/(double)steps),py=(int)Math.round(CENTRE_Y+dy*i/(double)steps);if(px==x&&py==y)break;if(blocked.test(px,py))return true;}return false;
    }
}
