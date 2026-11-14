package net.runelite.standalone;

import java.io.*;

/** Actual shipped 184 model decoder: inspect proposed native wall and scenery dimensions. */
public final class InspectNativeModels {
    public static void main(String[] args) throws Exception {
        try (DataInputStream input = new DataInputStream(new FileInputStream(args[0]))) {
            for (;;) {
                int id;
                try { id = input.readInt(); } catch (EOFException done) { break; }
                byte[] data = new byte[input.readInt()]; input.readFully(data);
                ModelData model = new ModelData(data);
                int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minY=minX,maxY=maxX,minZ=minX,maxZ=maxX;
                for (int i=0;i<model.verticesCount;i++) {
                    minX=Math.min(minX,model.verticesX[i]);maxX=Math.max(maxX,model.verticesX[i]);
                    minY=Math.min(minY,model.verticesY[i]);maxY=Math.max(maxY,model.verticesY[i]);
                    minZ=Math.min(minZ,model.verticesZ[i]);maxZ=Math.max(maxZ,model.verticesZ[i]);
                }
                model.copy$toModel(64,768,-50,-10,-50);
                System.out.println(id+"\t"+model.verticesCount+"\t"+model.faceCount+"\t"+minX+","+maxX+"\t"+minY+","+maxY+"\t"+minZ+","+maxZ);
            }
        }
    }
}
