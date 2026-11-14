package net.runelite.standalone;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Orthographic engineering preview of actual cache triangles; not a gameplay capture. */
public final class RenderNativeMesh {
    public static void main(String[] args) throws Exception {
        Set<Integer> selected = new HashSet<>();
        for (int i=2;i<args.length;i++) selected.add(Integer.parseInt(args[i]));
        Rasterizer3D.method2949(0.8);
        try (DataInputStream input = new DataInputStream(new FileInputStream(args[0]))) {
            for (;;) {
                int id; try { id=input.readInt(); } catch (EOFException end) { break; }
                byte[] raw=new byte[input.readInt()]; input.readFully(raw);
                if (!selected.contains(id)) continue;
                ModelData model=new ModelData(raw);
                BufferedImage image=new BufferedImage(512,420,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();g.setColor(new Color(34,35,39));g.fillRect(0,0,512,420);
                g.setColor(new Color(75,70,60));g.fillPolygon(new int[]{256,448,256,64},new int[]{220,316,412,316},4);
                Integer[] faces=new Integer[model.faceCount];for(int f=0;f<faces.length;f++)faces[f]=f;
                Arrays.sort(faces,Comparator.comparingDouble(f->{double depth=0;for(int v:new int[]{model.indices1[f],model.indices2[f],model.indices3[f]})depth+=model.verticesX[v]+model.verticesZ[v]-model.verticesY[v]*0.7;return depth;}));
                int textures=0,transparent=0;
                for(int f:faces) {
                    if(model.faceTextures!=null&&model.faceTextures[f]!=-1)textures++;
                    if(model.faceAlphas!=null&&(model.faceAlphas[f]&255)!=0)transparent++;
                    int[] xs=new int[3],ys=new int[3],vs={model.indices1[f],model.indices2[f],model.indices3[f]};
                    for(int i=0;i<3;i++){int v=vs[i];xs[i]=256+(int)((model.verticesX[v]-model.verticesZ[v])*1.5);ys[i]=316+(int)((model.verticesX[v]+model.verticesZ[v])*0.75+model.verticesY[v]*1.5);}
                    g.setColor(new Color(Rasterizer3D.Rasterizer3D_colorPalette[model.faceColors[f]&65535]));g.fillPolygon(xs,ys,3);
                    g.setColor(new Color(0,0,0,25));g.drawPolygon(xs,ys,3);
                }
                g.setColor(Color.WHITE);g.drawString("Native 184 mesh "+id+" - engineering projection",15,22);
                g.drawString("Faces: "+model.faceCount+"; textured: "+textures+"; translucent: "+transparent,15,42);
                g.dispose();ImageIO.write(image,"png",new File(args[1],"mesh-"+id+".png"));
                System.out.println(id+" faces="+model.faceCount+" textured="+textures+" translucent="+transparent);
            }
        }
    }
}
