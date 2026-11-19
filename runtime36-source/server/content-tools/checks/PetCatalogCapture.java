package net.runelite.standalone;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.filestore.IndexFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Offline captures through the existing client model decoder and software rasterizer. */
public final class PetCatalogCapture {
    static final int EDGE = 96;
    static final class StoreArchive extends AbstractArchive {
        private final IndexFile index;
        StoreArchive(IndexFile index) {
            super(false, false);
            this.index = index;
        }
        @Override public byte[] method4020(int group,int file,short ignored){return index.getFile(group,file);}
        @Override public byte[] method4029(int group){return index.getFile(group,0);}
        @Override public byte[] method4027(int group){return index.getFile(group,0);}
        @Override public int fileCount(int group){return index.getLastFileId(group)+1;}
        @Override public int[] method4042(int group){
            int[] present = new int[index.getValidFilesCount(group)];
            int count = 0;
            for(int i=0;i<fileCount(group);i++) if(index.getFile(group,i)!=null) present[count++]=i;
            return Arrays.copyOf(present,count);
        }
    }
    static final class Entry {
        String name, preview; boolean tokenPreview, legacy; int meta=-1; String formName;
        int id, item, follower, appearance, divisor, widthScale, heightScale, attack;
        boolean half;
    }
    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch(Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        java.lang.reflect.Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        Client client=(Client)unsafe.allocateInstance(Client.class);
        client.callbacks=(net.runelite.api.hooks.Callbacks)java.lang.reflect.Proxy.newProxyInstance(
                Client.class.getClassLoader(), new Class<?>[]{net.runelite.api.hooks.Callbacks.class},
                (target,method,values)->method.getReturnType()==boolean.class?false:null);
        ViewportMouse.client=client;
        Path candidate=Paths.get(args[0]);
        FileStore store=new FileStore(candidate.resolve("Cache").toString(),8*1024*1024);
        IndexFile configs=store.get(2), models=store.get(7);
        Gson gson=new Gson();
        JsonObject manifest=gson.fromJson(new String(Files.readAllBytes(candidate.resolve("data/pet-definitions.json")), StandardCharsets.UTF_8), JsonObject.class);
        int divisor=manifest.get("defaultRate").getAsInt();
        Map<String,Entry> records=new LinkedHashMap<>();
        boolean render=args.length>1;
        if(render){
            Rasterizer3D.method2949(.82);
            Rasterizer3D.method2948(new TextureProvider(new StoreArchive(store.get(9)),new StoreArchive(store.get(8)),64,.82,128));
        }
        for(Map.Entry<String,JsonElement> pair:manifest.getAsJsonObject("definitions").entrySet()){
            Entry row=new Entry();
            row.id=Integer.parseInt(pair.getKey());
            JsonObject def=pair.getValue().getAsJsonObject();
            row.item=def.get("item").getAsInt();
            row.follower=def.get("npc").getAsInt();
            row.appearance=def.get("appearance").getAsInt();
            row.divisor=def.has("rate")&&def.get("rate").getAsInt()>0?def.get("rate").getAsInt():divisor;
            row.attack=def.has("attack")?def.get("attack").getAsInt():-1;
            NPCDefinition pet=definition(configs,row.follower), original=definition(configs,row.appearance);
            ItemDefinition item=new ItemDefinition();
            item.method4527(new Buffer(configs.getFile(10,row.item)));
            row.name=pet.name;
            if(row.id!=row.appearance)row.formName=original.name;
            row.widthScale=pet.widthScale;
            row.heightScale=pet.heightScale;
            row.half=pet.widthScale!=original.widthScale||pet.heightScale!=original.heightScale;
            require(Arrays.equals(pet.models,original.models) && pet.models.length>0
                    && Arrays.equals(pet.colors,original.colors)&&Arrays.equals(pet.modifiedColors,original.modifiedColors)
                    && Arrays.equals(pet.retextureFrom,original.retextureFrom)&&Arrays.equals(pet.retextureTo,original.retextureTo)
                    && pet.walkingAnimation==original.walkingAnimation&&pet.standingAnimation==original.standingAnimation,
                    "Cache asset reference mismatch for "+row.id);
            require(row.name.equals(item.name) && "Drop".equals(item.inventoryActions[4]) && item.model>0
                    && "Pick-up".equals(pet.actions[0]) && "Show attack".equals(pet.actions[1])
                    && (row.half ? pet.widthScale==Math.max(1,original.widthScale/2)
                        && pet.heightScale==Math.max(1,original.heightScale/2) : true),
                    "Client definition controls or scale for "+row.id);
            if(render){
                row.preview = capture(pet,models);
                if(row.preview==null){row.tokenPreview=true;row.preview=capture(new ModelData(models.getFile(item.model,0)).copy$toModel(64,768,-50,-10,-50));}
                require(row.preview!=null,"Empty capture "+row.id+" "+row.name);
            }
            records.put(pair.getKey(),row);
        }
        if(render){
            int fallback=0;
            if(args.length>2){
                Entry[] legacy=gson.fromJson(new String(Files.readAllBytes(Paths.get(args[2])),StandardCharsets.UTF_8),Entry[].class);
                for(Entry row:legacy){
                    NPCDefinition pet=definition(configs,row.follower);
                    ItemDefinition item=new ItemDefinition();
                    item.method4527(new Buffer(configs.getFile(10,row.item)));
                    row.name=item.name;
                    row.widthScale=pet.widthScale;
                    row.heightScale=pet.heightScale;
                    if(row.meta>=0&&configs.getFile(9,row.meta)!=null)row.formName=definition(configs,row.meta).name;
                    row.preview=pet.models==null||pet.models.length==0?null:capture(pet,models);
                    if(row.preview==null){
                        row.tokenPreview=true;
                        row.preview=capture(new ModelData(models.getFile(item.model,0)).copy$toModel(64,768,-50,-10,-50));
                    }
                    require(row.preview!=null,"Missing legacy preview "+row.item);
                    records.put("legacy:"+row.item,row);
                }
            }
            for(Entry row:records.values())if(row.tokenPreview)fallback++;
            Files.write(Paths.get(args[1]),gson.toJson(records.values()).getBytes(StandardCharsets.UTF_8));
            System.out.println("Catalog images "+records.size()+", inventory model fallback "+fallback);
        }
        System.out.println("PASS "+records.size()+" native NPC and item decoder pairs"+(render?" and captured model previews":""));
    }
    private static NPCDefinition definition(IndexFile configs,int id){
        NPCDefinition npc=new NPCDefinition();
        npc.method4429(new Buffer(configs.getFile(9,id)));
        return npc;
    }
    private static String capture(NPCDefinition npc,IndexFile models) throws Exception{
        ModelData[] parts=new ModelData[npc.models.length];
        for(int i=0;i<parts.length;i++){
            int id=npc.models[i];
            byte[] raw=id<65536?models.getFile(id,0):models.getFile(65408+(id-65536)/512,(id-65536)%512);
            parts[i]=new ModelData(raw);
        }
        ModelData data=parts.length==1?parts[0]:new ModelData(parts,parts.length);
        if(npc.colors!=null)for(int i=0;i<npc.colors.length;i++)data.method2770(npc.colors[i],npc.modifiedColors[i]);
        if(npc.retextureFrom!=null)for(int i=0;i<npc.retextureFrom.length;i++)data.method2831(npc.retextureFrom[i],npc.retextureTo[i]);
        Model model=data.copy$toModel(npc.ambient+64,npc.contrast+850,-30,-50,-30);
        model.method2402(npc.widthScale,npc.heightScale,npc.widthScale);
        return capture(model);
    }
    private static String capture(Model model) throws Exception{
        int[] x=model.getVerticesX(),y=model.getVerticesY(),z=model.getVerticesZ();
        int yaw=1560, pitch=180;
        double yr=yaw*Math.PI/1024, pr=pitch*Math.PI/1024;
        double sx=Math.sin(yr),cx=Math.cos(yr),sp=Math.sin(pr),cp=Math.cos(pr);
        double lx=Double.POSITIVE_INFINITY, hx=-lx, ly=lx, hy=hx;
        for(int i=0;i<model.getVerticesCount();i++){
            double nx=z[i]*sx+x[i]*cx;
            double nz=z[i]*cx-x[i]*sx;
            double ny=y[i]*cp-nz*sp;
            lx=Math.min(lx,nx);hx=Math.max(hx,nx);ly=Math.min(ly,ny);hy=Math.max(hy,ny);
        }
        int[] pixels=new int[EDGE*EDGE];
        Rasterizer2D.method6409(pixels,EDGE,EDGE);
        Rasterizer3D.method2944();
        int divisor=Math.max(1,(int)Math.ceil(Rasterizer3D.Rasterizer3D_zoom*Math.max(hx-lx,hy-ly)/(EDGE-12)));
        int distance=4096;
        int offsetX=(int)Math.round(-(lx+hx)/2);
        int offsetY=(int)Math.round((distance*sp-(ly+hy)/2)/cp);
        model.method2373(0,yaw,0,pitch,offsetX,offsetY,distance,divisor);
        BufferedImage capture=new BufferedImage(EDGE,EDGE,BufferedImage.TYPE_INT_ARGB);
        int count=0;
        for(int i=0;i<pixels.length;i++)if(pixels[i]!=0){
            capture.setRGB(i%EDGE,i/EDGE,0xff000000|pixels[i]);
            count++;
        }
        if(count<4)return null;
        ByteArrayOutputStream png=new ByteArrayOutputStream();
        ImageIO.write(capture,"png",png);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}