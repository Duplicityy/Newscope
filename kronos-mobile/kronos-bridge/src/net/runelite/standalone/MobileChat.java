package net.runelite.standalone;

import java.awt.Rectangle;
import java.util.*;
import net.runelite.api.FontTypeFace;

/** Input regions only. Native chat sprites, text, opacity and colors are untouched. */
final class MobileChat {
 static final int CLICK_THROUGH_VARBIT=2570;
 private MobileChat(){}
 static boolean consumes(boolean bodyVisible,boolean through,Rectangle background,List<Rectangle> controls,int x,int y){
  for(Rectangle control:controls)if(control.contains(x,y))return true;
  return bodyVisible&&!through&&background!=null&&background.contains(x,y);
 }
 static List<Rectangle> controls(Widget[] widgets,Rectangle clip){
  List<Rectangle> out=new ArrayList<>();
  if(widgets!=null)collect(widgets,-1,clip,out,Collections.newSetFromMap(new IdentityHashMap<Widget,Boolean>()));
  return out;
 }
 private static void collect(Widget[] widgets,int parent,Rectangle clip,List<Rectangle> out,Set<Widget> seen){
  for(Widget w:widgets){
   if(w==null||w.parentId!=parent||w.isHidden||!seen.add(w))continue;
   Rectangle full=w.getBounds(),visible=clip.intersection(full);if(visible.isEmpty())continue;
   int child=w.id&65535;
   boolean input=(w.id>>>16)==162&&(child==45||child==57);
   boolean action=w.buttonType!=0||w.clickMask!=0||w.onClick!=null||w.onOp!=null||w.onDrag!=null||w.onDragComplete!=null;
   if(w.actions!=null)for(String value:w.actions)if(value!=null&&!value.trim().isEmpty())action=true;
   // Nonempty chat text consumes only its drawn text span, not the whole blank row.
   if(input)add(out,visible);
   else if(w.type==4&&w.text!=null&&!w.text.isEmpty())add(out,clip.intersection(textBounds(w,full)));
   else if(action)add(out,visible);
   // Native legacy scrollbars are drawn beside the scroll container, not over its body.
   if(!w.isIf3&&w.scrollHeight>w.height)add(out,clip.intersection(new Rectangle(full.x+full.width,full.y,16,full.height)));
   if(w.onScroll!=null&&w.type!=0)add(out,visible);
   if(w.type==0){collect(widgets,w.id,visible,out,seen);if(w.children!=null)collect(w.children,w.id,visible,out,seen);}
  }
 }
 private static void add(List<Rectangle> out,Rectangle r){if(!r.isEmpty())out.add(r);}
 static Rectangle textBounds(Widget w,Rectangle bounds){
  FontTypeFace font=w.getFont();if(font==null)return bounds;
  String text=w.text;int width=font.getTextWidth(text);
  if(text.contains("<br>")||width>bounds.width)return bounds;
  int x=bounds.x+(w.textXAlignment==1?(bounds.width-width)/2:w.textXAlignment==2?bounds.width-width:0);
  int height=Math.min(bounds.height,Math.max(1,font.getBaseline()+3));
  int y=bounds.y+(w.textYAlignment==1?(bounds.height-height)/2:w.textYAlignment==2?bounds.height-height:0);
  return bounds.intersection(new Rectangle(x-1,y,width+2,height));
 }
}
