package local.kronos.mobile;

/** Shared interpretation of the native engine's published interaction rectangles. */
public final class TouchRegions {
 private TouchRegions(){}
 public static int at(int[] map,int width,int height,float x,float y){
  if(map==null||map.length<8||map[1]!=30)return 1;
  if(map[7]!=0)return 2;
  if(map[5]!=width||map[6]!=height)return 1;
  boolean world=false;
  for(int i=8;i+4<map.length;i+=5){
   int kind=map[i+4];
   if(kind>=100||x<map[i]||y<map[i+1]||x>=map[i]+map[i+2]||y>=map[i+1]+map[i+3])continue;
   if(kind!=0)return kind;
   world=true;
  }
  return world?0:1;
 }
}
