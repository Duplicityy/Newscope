package net.runelite.standalone;
import java.io.*;
import java.util.*;
import net.runelite.client.RuneLite;
public final class MobileLayoutProbe {
 public static boolean localMapShown() {return !MobileMap.atlasInteractive();}
 public static boolean walkCrossAt(int x, int y) {
  return Client.showMouseCross && Client.mouseCrossColor == 1
      && Client.mouseCrossX == x && Client.mouseCrossY == y;
 }
 public static String walkCross() {
  return Client.mouseCrossColor + " at " + Client.mouseCrossX + "," + Client.mouseCrossY;
 }
 public static void bankCamera() {
  if(!Boolean.getBoolean("kronos.testDialogs")||Integer.getInteger("kronos.testGamePort",0)!=14302)
   throw new IllegalStateException("Isolated test only");
  AbstractArchive.clientPreferences.roofsHidden=true;
  Client.camAngleY=512;
  Client.camAngleX=345;
  Client.camAngleDX=Client.camAngleDY=0;
 }
 public static void fixed(boolean fixed) {
  if(!Boolean.getBoolean("kronos.testDialogs")||Integer.getInteger("kronos.testGamePort",0)!=14302)
   throw new IllegalStateException("Isolated test only");
  UserComparator8.method2878(fixed?1:2);
 }
 public static void dump(String path) {
  try(PrintWriter out=new PrintWriter(path)){
   Client c=(Client)RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
   out.println("ROOT "+c.getWidgetRoot());
   for(int group:new int[]{c.getWidgetRoot(),160,162,149,261,387,541,218,593}){
    net.runelite.rs.api.RSWidget[][] all=c.getWidgets();if(group>=all.length||all[group]==null)continue;
    for(net.runelite.rs.api.RSWidget rw:all[group])if(rw!=null){
     Widget w=(Widget)rw;
     out.println(group+":"+(w.id&65535)+" parent="+w.parentId+" type="+w.type+" content="+w.contentType+" hidden="+w.isHidden()+"/"+w.isSelfHidden()+" xy="+w.x+","+w.y+" size="+w.width+","+w.height+" bounds="+w.getBounds()+" sprite="+w.spriteId2+" text="+w.text+" ops="+Arrays.toString(w.getActions())+" onOp="+Arrays.toString(w.getOnOp()));
    }
   }
  }catch(Exception e){throw new RuntimeException(e);}
 }
}
