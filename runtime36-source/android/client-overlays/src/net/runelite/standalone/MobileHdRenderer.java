package net.runelite.standalone;

import java.nio.*;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.Entity;
import net.runelite.api.Model;
import net.runelite.api.TilePaint;
import net.runelite.api.TileModel;
import net.runelite.api.Texture;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.GameState;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;

/** Experimental GLES scene renderer: actual animated game meshes, not a framebuffer filter. */
public final class MobileHdRenderer implements DrawCallbacks {
    private static final int FLOATS = 13, LIMIT = 600000, METADATA = UltraSceneLights.METADATA;
    private static final Object LOCK = new Object();
    private static volatile boolean wanted, active;
    private static volatile String status = "Classic";
    private static volatile String fallbackReason;
    private static volatile boolean shadows = true;
    private static volatile boolean ultraRequested;
    private static MobileHdRenderer instance;
    private static int nextGeneration;
    private final Client client;
    private final DrawManager manager;
    private ByteBuffer geometry = direct(1024*1024), snapshot = direct(1024*1024), previousSnapshot=direct(1024*1024);
    private int count, frozenCount;
    private long sequence;
    private boolean frozenActive;
    private long previousSequence;
    private boolean previousActive,captureFailed;
    private int previousCount;
    private final int[] view = new int[METADATA];
    private final UltraSceneLights lights = new UltraSceneLights();
    private boolean ultraFrame;
    private final int[] frozenView = new int[METADATA];
    private final int[] previousView=new int[METADATA];
    private final int[] textureLayers;
    private final BitSet touchedTextures = new BitSet();
    private final List<byte[]> textures = new ArrayList<>();
    private final List<Texture> textureSources = new ArrayList<>();
    private int textureGeneration;
    private final int sceneGeneration=++nextGeneration;
    private final int[] tileX=new int[4],tileY=new int[4],tileZ=new int[4];
    private long[] transparentOrder=new long[256];
    private int[] transparentSlots=new int[256];
    private MobileHdRenderer(Client client) {
        this(client,RuneLite.getInjector().getInstance(DrawManager.class));
    }
    MobileHdRenderer(Client client,DrawManager manager) {
        this.client=client; this.manager=manager;
        textureLayers=new int[client.getTextureProvider().getTextures().length];Arrays.fill(textureLayers,-2);
    }
    private static ByteBuffer direct(int bytes) {return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());}
    public static void request(boolean enabled) { wanted=enabled;fallbackReason=null; status=enabled?"Preparing Mobile HD":"Switching to Classic"; }
    public static String status() {return status;}
    public static boolean requested() {return wanted;}
    /** Presentation preference only; applied with the next coherent captured frame. */
    public static void configureShadows(boolean enabled) {shadows=enabled;}
    public static boolean requestedShadows() {return shadows;}
    public static void configureUltra(boolean enabled) {ultraRequested=enabled;}
    /** Only called on the game render thread, after the completed frame was published. */
    public static void poll(Client client) {
        if(active&&(client.getGameState()==GameState.LOGIN_SCREEN||client.getGameState()==GameState.CONNECTION_LOST))wanted=false;
        if(wanted==active) return;
        if(wanted && client.getGameState()!=GameState.LOGGED_IN) return;
        try {
            if(wanted) {
                if(client.getDrawCallbacks()!=null) throw new IllegalStateException("Another GPU renderer is active");
                instance=new MobileHdRenderer(client);
                client.setDrawCallbacks(instance); client.setGpu(true); client.resizeCanvas();
                active=true; status="Preparing Mobile HD";
            } else {
                client.setDrawCallbacks(null); client.setGpu(false); client.resizeCanvas();
                active=false; status=fallbackReason==null?"Classic":"Classic: HD fallback ("+fallbackReason+")";
            }
        } catch(Throwable failure) {
            wanted=false; active=false; client.setDrawCallbacks(null); client.setGpu(false); client.resizeCanvas();
            status="Classic: HD unavailable ("+failure.getClass().getSimpleName()+")";
        }
    }
    public static void fail(String reason) {wanted=false;fallbackReason=reason; status="Returning to Classic: "+reason;}
    public static void presented() {if(wanted&&active)status="Mobile HD (experimental)";}
    /** Native presentation result: 1=soft shadows, 0=requested off, -1=unavailable. */
    public static void presented(int shadowState) {
        if(wanted&&active)status="Mobile HD (experimental; "+(shadowState>0?"soft shadows":shadowState<0?"shadows unavailable":"shadows off")+")";
    }
    /** Called under the complete-frame publication lock so software UI and geometry share a sequence. */
    public static void commitFrame(long nextSequence) {
        synchronized(LOCK) {
            if(instance==null) return;
            MobileHdRenderer r=instance;
            ByteBuffer recycled=r.previousSnapshot;r.previousSnapshot=r.snapshot;r.snapshot=recycled;
            r.previousSequence=r.sequence;r.previousCount=r.frozenCount;r.previousActive=r.frozenActive;
            System.arraycopy(r.frozenView,0,r.previousView,0,METADATA);
            r.frozenActive=active; r.sequence=nextSequence;
            if(!active||r.captureFailed) {r.frozenCount=0; return;}
            int bytes=r.count*FLOATS*4;
            if(r.snapshot.capacity()<bytes) r.snapshot=direct(bytes);
            ByteBuffer src=r.geometry.duplicate(); src.flip();
            r.snapshot.clear(); r.snapshot.put(src); r.snapshot.flip();
            r.sortTransparency(bytes);
            r.frozenCount=r.count; System.arraycopy(r.view,0,r.frozenView,0,METADATA);
        }
    }
    /** Depth-sort translucent faces without allocating per triangle or changing opaque depth testing. */
    private void sortTransparency(int bytes) {
        int n=0,stride=FLOATS*4,triangleBytes=stride*3;
        for(int triangle=0;triangle<count/3;triangle++) {
            int at=triangle*triangleBytes;float alpha=geometry.getFloat(at+6*4);
            if(alpha>=.99f||alpha<=0)continue;
            if(n==transparentOrder.length){transparentOrder=Arrays.copyOf(transparentOrder,n*2);transparentSlots=Arrays.copyOf(transparentSlots,n*2);}
            float depth=(geometry.getFloat(at+8)+geometry.getFloat(at+stride+8)+geometry.getFloat(at+2*stride+8))/3;
            transparentOrder[n]=((long)(0x7fffffff-Float.floatToIntBits(Math.max(0,depth)))<<32)|(triangle&0xffffffffL);
            transparentSlots[n++]=triangle;
        }
        Arrays.sort(transparentOrder,0,n);
        ByteBuffer src=geometry.duplicate();
        for(int i=0;i<n;i++){
            int from=((int)transparentOrder[i])*triangleBytes;
            src.limit(from+triangleBytes);src.position(from);
            snapshot.position(transparentSlots[i]*triangleBytes);snapshot.put(src);
        }
        snapshot.limit(bytes);snapshot.position(0);
    }
    /** 0=classic, -1=keep previous frame, positive=required/copied geometry bytes. */
    public static int copyGeometry(ByteBuffer target,int[] metadata,long frameSequence) {
        synchronized(LOCK) {
            if(instance==null) return 0;
            MobileHdRenderer r=instance;
            boolean previous=r.sequence!=frameSequence;
            if(previous&&r.previousSequence!=frameSequence) return -1;
            if(!(previous?r.previousActive:r.frozenActive)) return 0;
            int count=previous?r.previousCount:r.frozenCount;
            if(count==0)return -1;
            // Old detached consumers can still request the original 12-word prefix.
            System.arraycopy(previous?r.previousView:r.frozenView,0,metadata,0,Math.min(metadata.length,METADATA));
            metadata[0]=count; metadata[10]=r.textures.size(); metadata[11]=r.textureGeneration;
            int bytes=count*FLOATS*4;
            if(target!=null && target.capacity()>=bytes) {target.clear();target.put((previous?r.previousSnapshot:r.snapshot).duplicate());}
            return bytes==0?-1:bytes;
        }
    }
    /** Texture pixels come from this cache's real TextureProvider, frozen on the game thread. */
    public static int copyTexture(int layer,int expectedGeneration,ByteBuffer target) {
        synchronized(LOCK) {
            if(instance==null||instance.sceneGeneration!=expectedGeneration||layer<0||layer>=instance.textures.size()) return 0;
            byte[] pixels=instance.textures.get(layer);
            if(target!=null&&target.capacity()>=pixels.length){target.clear();target.put(pixels);}
            return pixels.length;
        }
    }
    private void callbackFailure(Throwable failure) {if(!captureFailed)failure.printStackTrace();captureFailed=true;fail("Scene asset could not be rendered: "+failure.getClass().getSimpleName());}
    @Override public void drawScene(int x,int y,int z,int pitch,int yaw,int plane) {
        try{drawSceneUnchecked(x,y,z,pitch,yaw,plane);}catch(RuntimeException|LinkageError failure){callbackFailure(failure);}
    }
    private void drawSceneUnchecked(int x,int y,int z,int pitch,int yaw,int plane) {
        geometry.clear(); count=0;touchedTextures.clear();captureFailed=false;
        view[1]=client.getViewportXOffset();view[2]=client.getViewportYOffset();
        view[3]=client.getViewportWidth();view[4]=client.getViewportHeight();
        view[5]=client.getCenterX();view[6]=client.getCenterY();view[7]=client.get3dZoom();
        view[8]=sceneGeneration;
        // Positions retain the native camera transform/picking. Normals are already
        // model-oriented world-space; this basis reconstructs positions for shadows.
        view[9]=(pitch&2047)|((yaw&2047)<<11);
        view[12]=x;view[13]=y;view[14]=z;view[15]=shadows?1:0;
        ultraFrame = ultraRequested;
        view[UltraSceneLights.COUNT] = 0;
        if (ultraFrame) lights.begin(client, x, y, z, plane);
        client.getScene().setDrawDistance(35);
    }
    @Override public void draw() {
        try{manager.processDrawComplete(()->((MainBufferProvider)client.getBufferProvider()).getImage());}catch(RuntimeException|LinkageError failure){callbackFailure(failure);poll(client);}
    }
    @Override public void animate(Texture texture,int diff) {
        try{animateUnchecked(texture,diff);}catch(RuntimeException|LinkageError failure){callbackFailure(failure);}
    }
    private void animateUnchecked(Texture texture,int diff) {
        float speed=texture.getAnimationSpeed()*diff/128f;
        switch(texture.getAnimationDirection()) {
            case 1:texture.setV(texture.getV()-speed);break;case 2:texture.setU(texture.getU()-speed);break;
            case 3:texture.setV(texture.getV()+speed);break;case 4:texture.setU(texture.getU()+speed);break;
        }
    }
    private int texture(int id) {
        if(id<0||id>=textureLayers.length) return -1;
        int[] pixels=null;
        if(!touchedTextures.get(id)){pixels=client.getTextureProvider().load(id);touchedTextures.set(id);}
        int old=textureLayers[id];if(old>=-1)return old;
        if(textures.size()>=256){captureFailed=true;fail("Experimental texture budget exceeded");return -1;}
        if(pixels==null)pixels=client.getTextureProvider().load(id);if(pixels==null)return -1;
        int size=(int)Math.sqrt(pixels.length); if(size*size!=pixels.length)return -1;
        byte[] rgba=new byte[128*128*4];
        for(int y=0;y<128;y++)for(int x=0;x<128;x++){
            int color=pixels[(y*size/128)*size+x*size/128],p=(y*128+x)*4;
            rgba[p]=(byte)(color>>16);rgba[p+1]=(byte)(color>>8);rgba[p+2]=(byte)color;rgba[p+3]=(byte)(color==0?0:255);
        }
        synchronized(LOCK){int layer=textures.size();textures.add(rgba);textureSources.add(client.getTextureProvider().getTextures()[id]);textureLayers[id]=layer;textureGeneration++;return layer;}
    }
    @Override public void draw(Entity entity,int orientation,int ps,int pc,int ys,int yc,int x,int y,int z,long hash) {
        if(captureFailed)return;
        try{drawEntityUnchecked(entity,orientation,ps,pc,ys,yc,x,y,z,hash);}catch(RuntimeException|LinkageError failure){callbackFailure(failure);}
    }
    private void drawEntityUnchecked(Entity entity,int orientation,int ps,int pc,int ys,int yc,int x,int y,int z,long hash) {
        Model model=entity instanceof Model?(Model)entity:entity.getModel();if(model==null)return;
        if (ultraFrame) lights.observe(view, hash, x, y, z);
        // A negative layer still denotes an untextured face to every existing consumer.
        boolean playerFace = ultraFrame && entity instanceof net.runelite.api.Player;
        model.calculateBoundsCylinder();model.calculateExtreme(orientation);
        if(!visible(model,ps,pc,ys,yc,x,y,z))return;
        client.checkClickbox(model,orientation,ps,pc,ys,yc,x,y,z,hash);
        int[] vx=model.getVerticesX(),vy=model.getVerticesY(),vz=model.getVerticesZ();
        int[] a=model.getTrianglesX(),b=model.getTrianglesY(),c=model.getTrianglesZ();
        int[] ca=model.getFaceColors1(),cb=model.getFaceColors2(),cc=model.getFaceColors3();
        short[] ids=model.getFaceTextures();byte[] alpha=model.getTriangleTransparencies();
        float[][] us=model.getFaceTextureUCoordinates(),vs=model.getFaceTextureVCoordinates();
        int sin=Rasterizer3D.Rasterizer3D_sine[orientation&2047],cos=Rasterizer3D.Rasterizer3D_cosine[orientation&2047];
        for(int i=0;i<model.getTrianglesCount();i++) {
            if(cc[i]==-2)continue;
            int color2=cc[i]==-1?ca[i]:cb[i],color3=cc[i]==-1?ca[i]:cc[i];
            int id=ids==null?-1:ids[i];int layer=texture(id);
            if (playerFace && layer < 0) layer = -2;
            float[] u=us!=null&&i<us.length?us[i]:null,v=vs!=null&&i<vs.length?vs[i]:null;
            float opacity=alpha==null?1:1-(alpha[i]&255)/255f;
            emit(vx,vy,vz,a[i],b[i],c[i],ca[i],color2,color3,layer,u,v,opacity,sin,cos,x,y,z,ps,pc,ys,yc,playerFace);
        }
    }
    /** Same conservative cylinder/frustum test as the existing BSD-licensed RuneLite GPU plugin. */
    private boolean visible(Model model,int ps,int pc,int ys,int yc,int x,int y,int z) {
        int radius=model.getXYZMag(),zoom=client.get3dZoom();
        int forward=(int)(((long)yc*z-(long)ys*x)>>16);
        int depth=(int)(((long)ps*y+(long)pc*forward)>>16);
        int farDepth=depth+(int)(((long)pc*radius)>>16);
        if(farDepth<=50)return false;
        int horizontal=(int)(((long)z*ys+(long)yc*x)>>16);
        if((long)(horizontal-radius)*zoom/farDepth>=client.getRasterizer3D_clipMidX2())return false;
        if((long)(horizontal+radius)*zoom/farDepth<=client.getRasterizer3D_clipNegativeMidX())return false;
        int vertical=(int)(((long)pc*y-(long)forward*ps)>>16),extent=(int)(((long)ps*radius)>>16);
        if((long)(vertical+extent)*zoom/farDepth<=client.getRasterizer3D_clipNegativeMidY())return false;
        int above=(int)(((long)pc*model.getModelHeight())>>16)+extent;
        return (long)(vertical-above)*zoom/farDepth<client.getRasterizer3D_clipMidY2();
    }
    @Override public void drawScenePaint(int orientation,int ps,int pc,int ys,int yc,int x,int y,int z,TilePaint p,int plane,int tx,int ty,int zoom,int cx,int cy) {
        if(captureFailed)return;
        try{drawScenePaintUnchecked(orientation,ps,pc,ys,yc,x,y,z,p,plane,tx,ty,zoom,cx,cy);}catch(RuntimeException|LinkageError failure){callbackFailure(failure);}
    }
    private void drawScenePaintUnchecked(int orientation,int ps,int pc,int ys,int yc,int x,int y,int z,TilePaint p,int plane,int tx,int ty,int zoom,int cx,int cy) {
        int[][] heights=client.getTileHeights()[plane];
        int[] vx=tileX,vy=tileY,vz=tileZ;
        vx[0]=vx[3]=tx*128;vx[1]=vx[2]=(tx+1)*128;vz[0]=vz[1]=ty*128;vz[2]=vz[3]=(ty+1)*128;
        vy[0]=heights[tx][ty];vy[1]=heights[tx+1][ty];vy[2]=heights[tx+1][ty+1];vy[3]=heights[tx][ty+1];
        int layer=texture(p.getTexture());
        if(p.getNeColor()!=12345678)emit(vx,vy,vz,2,3,1,p.getNeColor(),p.getNwColor(),p.getSeColor(),layer,null,null,1,0,65536,x,y,z,ps,pc,ys,yc,false);
        if(p.getSwColor()!=12345678)emit(vx,vy,vz,0,1,3,p.getSwColor(),p.getSeColor(),p.getNwColor(),layer,null,null,1,0,65536,x,y,z,ps,pc,ys,yc,false);
    }
    @Override public void drawSceneModel(int orientation,int ps,int pc,int ys,int yc,int x,int y,int z,TileModel m,int plane,int tx,int ty,int zoom,int cx,int cy) {
        if(captureFailed)return;
        try{drawSceneModelUnchecked(orientation,ps,pc,ys,yc,x,y,z,m,plane,tx,ty,zoom,cx,cy);}catch(RuntimeException|LinkageError failure){callbackFailure(failure);}
    }
    private void drawSceneModelUnchecked(int orientation,int ps,int pc,int ys,int yc,int x,int y,int z,TileModel m,int plane,int tx,int ty,int zoom,int cx,int cy) {
        int[] a=m.getFaceX(),b=m.getFaceY(),c=m.getFaceZ(),vx=m.getVertexX(),vy=m.getVertexY(),vz=m.getVertexZ();
        int[] ids=m.getTriangleTextureId();
        for(int i=0;i<a.length;i++) {
            if(m.getTriangleColorA()[i]==12345678)continue;
            emit(vx,vy,vz,a[i],b[i],c[i],m.getTriangleColorA()[i],m.getTriangleColorB()[i],m.getTriangleColorC()[i],texture(ids==null?-1:ids[i]),null,null,1,0,65536,x,y,z,ps,pc,ys,yc,false);
        }
    }
    private void emit(int[] vx,int[] vy,int[] vz,int a,int b,int c,int ca,int cb,int cc,int tex,float[] u,float[] v,float alpha,int sin,int cos,int x,int y,int z,int ps,int pc,int ys,int yc,boolean playerFace) {
        if(count+3>LIMIT){captureFailed=true;fail("Scene exceeded experimental renderer budget");return;}
        int bytes=(count+3)*FLOATS*4;
        if(geometry.capacity()<bytes){ByteBuffer bigger=direct(Math.min(LIMIT*FLOATS*4,Math.max(bytes,geometry.capacity()*2)));geometry.flip();bigger.put(geometry);geometry=bigger;}
        float ax=vx[b]-vx[a],ay=vy[b]-vy[a],az=vz[b]-vz[a],bx=vx[c]-vx[a],by=vy[c]-vy[a],bz=vz[c]-vz[a];
        float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(len==0)len=1;
        nx/=len;ny/=len;nz/=len;
        float rotatedNx=(nx*cos+nz*sin)/65536f;nz=(nz*cos-nx*sin)/65536f;nx=rotatedNx;
        Texture texture=tex>=0?textureSources.get(tex):null;
        float uOffset=texture==null?0:texture.getU(),vOffset=texture==null?0:texture.getV();
        // GLES array layers round to the nearest integer. The fractional tag preserves the
        // original texture for HD and shadows and identifies cloth lining in Ultra.
        float layerTag = playerFace && tex >= 0 ? tex + 0.25f : tex;
        for(int j=0;j<3;j++){
            int index=j==0?a:j==1?b:c,packedColor=j==0?ca:j==1?cb:cc;
            float wx=((float)vx[index]*cos+(float)vz[index]*sin)/65536f+x,wy=vy[index]+y,wz=((float)vz[index]*cos-(float)vx[index]*sin)/65536f+z;
            float xx=(wx*yc+wz*ys)/65536f,zz=(wz*yc-wx*ys)/65536f;
            float yy=(wy*pc-zz*ps)/65536f;zz=(zz*pc+wy*ps)/65536f;
            int color=Rasterizer3D.Rasterizer3D_colorPalette[packedColor&65535];
            float r=((color>>16)&255)/255f,g=((color>>8)&255)/255f,bl=(color&255)/255f;
            if(tex>=0){float lum=(packedColor&127)/127f;r=g=bl=lum;}
            geometry.putFloat(xx).putFloat(yy).putFloat(zz).putFloat(r).putFloat(g).putFloat(bl).putFloat(alpha)
                .putFloat(nx).putFloat(ny).putFloat(nz).putFloat((u==null?vx[index]/128f:u[j])+uOffset).putFloat((v==null?vz[index]/128f:v[j])+vOffset).putFloat(layerTag);
            count++;
        }
    }
}
