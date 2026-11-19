package net.runelite.standalone;

import com.google.gson.Gson;
import net.runelite.api.widgets.JavaScriptCallback;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Existing cache frames, textures, fonts, item icons and NPC geometry. No generated artwork. */
public final class CustomLootCompendiumInterface {
    private static final int GROUP=383;
    private static final String PREFIX="@LC1:";
    private static final int SEARCH_PANEL=0x332e25, DETAILS_PANEL=0x3c3429, PANEL_BORDER=0x655741;
    private static final Gson GSON=new Gson();
    private static Widget[] original;
    private static View view;
    private static View renderedView;
    private static final class Result{int id,action;String name,sub;boolean npc;}
    private static final class Row{int id=-1,npc=-1,action;String title,location,rate,quantity,note;}
    private static final class Section{String key,title;int icon,action,count;boolean collapsed;List<Row> rows;}
    private static final class View{
        int v,item=-1,npc=-1,selected=-1;boolean monsters;String query,title,description,meta,notice,resultLabel,detailLabel,note;
        int itemsAction,monstersAction,searchAction,closeAction,previousAction,nextAction,detailPreviousAction,detailNextAction;
        List<Result> results;List<Section> sections;
    }
    private CustomLootCompendiumInterface(){}
    public static boolean accept(String name){
        if(CustomDungeoneeringExpedition.matches(name)){restore();return CustomDungeoneeringExpedition.accept(name);}
        CustomDungeoneeringExpedition.restore();
        if(name==null||!name.startsWith(PREFIX)){restore();return false;}
        try{
            byte[] bytes=Base64.getDecoder().decode(name.substring(PREFIX.length()));
            if(bytes.length>48000)throw new IllegalArgumentException("Compendium page too large");
            View next=GSON.fromJson(new String(bytes,StandardCharsets.UTF_8),View.class);
            if(next==null||next.v!=1)throw new IllegalArgumentException("Compendium version");
            if(next.results!=null&&next.results.size()>30)throw new IllegalArgumentException("Compendium result count");
            int rows=0;if(next.sections!=null){if(next.sections.size()>100)throw new IllegalArgumentException("Compendium source count");for(Section section:next.sections)if(section!=null&&section.rows!=null)rows+=section.rows.size();}
            if(rows>60)throw new IllegalArgumentException("Compendium row count");
            if(original==null)original=UserComparator5.Widget_interfaceComponents[GROUP];
            if(original==null||original.length<4)throw new IllegalStateException("Original cache interface not loaded");
            view=next;build();return true;
        }catch(RuntimeException invalid){System.err.println("Loot compendium page rejected: "+invalid.getMessage());restore();return true;}
    }
    private static void restore(){if(original!=null){UserComparator5.Widget_interfaceComponents[GROUP]=original;original=null;view=null;renderedView=null;}}
    private static Widget copy(Widget original){
        if(original==null)return null;Widget clone=new Widget();
        try{for(Field field:Widget.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!Modifier.isFinal(field.getModifiers())){field.setAccessible(true);field.set(clone,field.get(original));}}
        catch(ReflectiveOperationException failed){throw new IllegalStateException("Cannot copy native cache frame",failed);}
        if(original.children!=null){clone.children=new Widget[original.children.length];for(int i=0;i<clone.children.length;i++)clone.children[i]=copy(original.children[i]);}
        return clone;
    }
    private static Widget root(int child,int parent,int type,int x,int y,int width,int height){
        Widget widget=new Widget();widget.id=(GROUP<<16)|child;widget.parentId=parent;widget.type=type;widget.isIf3=true;
        widget.rawX=widget.x=x;widget.rawY=widget.y=y;widget.rawWidth=widget.width=width;widget.rawHeight=widget.height=height;
        widget.noClickThrough=true;return widget;
    }
    private static void hideFrameControls(Widget widget){
        if(widget==null)return;
        if(widget.type==4||widget.buttonType>0||widget.actions!=null||widget.onOp!=null||widget.onClick!=null)widget.isHidden=true;
        if(widget.children!=null)for(Widget child:widget.children)hideFrameControls(child);
    }
    private static Widget child(Widget parent,int type,int x,int y,int width,int height){
        int id=parent.children==null?0:parent.children.length;Widget widget=Widget.addChild(parent.id,type,id);
        widget.rawX=widget.x=x;widget.rawY=widget.y=y;widget.rawWidth=widget.width=width;widget.rawHeight=widget.height=height;return widget;
    }
    private static void text(Widget parent,String text,int x,int y,int width,int height,int font,int colour){
        Widget w=child(parent,4,x,y,width,height);w.fontId=font;w.text=text==null?"":text;w.color=colour;w.textShadowed=true;w.textYAlignment=1;
    }
    private static Widget rectangle(Widget parent,int x,int y,int width,int height,int colour,boolean filled) {
        Widget shape=child(parent,3,x,y,width,height);
        shape.color=colour;
        shape.fill=filled;
        return shape;
    }
    private static String fitLabel(Font font,String value,int width) {
        if(value==null)return "";
        String line=value.replace('\r',' ').replace('\n',' ');
        if(font==null||font.method5324(line)<=width)return line;
        String suffix="...";
        if(font.method5324(suffix)>width)return "";
        int low=0,high=line.length();
        while(low<high) {
            int middle=(low+high+1)/2;
            if(font.method5324(line.substring(0,middle)+suffix)<=width)low=middle;
            else high=middle-1;
        }
        if(low>0&&Character.isHighSurrogate(line.charAt(low-1)))low--;
        return line.substring(0,low)+suffix;
    }
    private static Widget tile(Widget parent,int x,int y,int width,int height,int alpha){
        Widget w=child(parent,5,x,y,width,height);w.spriteId=w.spriteId2=1040;w.spriteTiling=true;w.transparencyTop=alpha;return w;
    }
    private static Widget button(Widget parent,String label,int token,int x,int y,int width,int height,boolean selected){
        tile(parent,x,y,width,height,selected?0:80);
        // Invisible native hit rectangle carries the server token. A sprite widget's itemId
        // would replace its cache texture with an unrelated item icon, so tokens stay type3.
        Widget hit=null;if(token>0){hit=child(parent,3,x,y,width,height);hit.fill=true;hit.transparencyTop=255;hit.itemId=token;hit.itemQuantity=1;hit.clickMask|=2;hit.setAction(0,"Select");hit.dataText=label;hit.hasListener=true;}
        text(parent,label,x+5,y,width-10,height,495,selected?0xffd84a:0xd1c7ab);
        return hit;
    }
    private static void icon(Widget parent,int item,int npc,int x,int y,int width,int height){
        if(npc>=0){Widget w=child(parent,6,x,y,width,height);w.modelType=7;w.modelId=npc;w.modelAngleX=300;w.modelAngleY=180;w.modelZoom=2200;return;}
        if(item<0)return;Widget w=child(parent,5,x,y,width,height);Widget.setItem(w,item,1,0);w.outline=1;w.spriteShadow=0x222222;
    }
    private static Widget nativeHost(){
        Widget[] widgets=UserComparator5.Widget_interfaceComponents[GROUP];
        return widgets==null||widgets.length==0||widgets[0]==null?null:(Widget)widgets[0].getParent();
    }
    private static int parentWidth(){Widget host=nativeHost();return host!=null&&host.width>0?host.width:FloorDecoration.canvasWidth;}
    private static int parentHeight(){Widget host=nativeHost();return host!=null&&host.height>0?host.height:WallDecoration.canvasHeight;}
    private static int modalWidth(){return Client.isResizable?Math.max(512,Math.min(960,parentWidth()-30)):512;}
    private static int modalHeight(){return Client.isResizable?Math.max(334,Math.min(650,parentHeight()-42)):334;}
    private static void alignToNativeHost(Widget base){
        // Generic client revalidation treats parentId=-1 as the whole canvas. Hosted
        // group383 must instead use its actual main-interface parent's clip area.
        Widget host=nativeHost();if(host==null)return;
        // Store offsets derived from the actual host, so a later generic canvas-based
        // revalidation cannot recenter the hosted root underneath sidebars or chat.
        base.xAlignment=base.yAlignment=0;
        base.rawX=Client.isResizable?Math.max(0,(host.width-base.rawWidth)/2):0;
        base.rawY=Client.isResizable?Math.max(0,(host.height-base.rawHeight)/2):0;
        TileItem.method1607(base,host.width,host.height,false);
        class33.method678(base,host.width,host.height);
    }
    private static Font font(int id){Widget widget=new Widget();widget.fontId=id;return widget.method3972();}
    private static int textHeight(Font font,String text,int width){
        if(text==null||text.isEmpty())return 0;
        width=Math.max(1,width);
        int spacing=font==null?13:Math.max(1,font.ascent);
        int lines=font==null?1+(text.length()*7)/width:
                font.method5325(text,new int[]{width},new String[text.length()+1]);
        int glyphs=font==null?spacing:Math.max(spacing,font.maxAscent+font.maxDescent);
        return glyphs+Math.max(0,lines-1)*spacing+2;
    }
    private static int[] rowMetrics(Font font,Row row,boolean monsters,int first,int second,int third){
        int title=textHeight(font,row.title,first),quantity=monsters?0:textHeight(font,row.quantity,first);
        int content=Math.max(title+(quantity>0?quantity+2:0),
                Math.max(textHeight(font,row.location,second),textHeight(font,row.rate,third)));
        return new int[]{Math.max(37,content+6),title,quantity};
    }
    /** Native widget dragging suppresses a release action once the threshold is crossed.
     * Only invisible compendium hit rectangles are draggable; world/inventory controls stay native. */
    private static void setScroll(Widget pane,Widget scrollbar,Widget dragParent,int value){
        int next=Math.max(0,Math.min(Math.max(0,pane.scrollHeight-pane.height),value));
        boolean changed=pane.scrollY!=next||scrollbar.scrollY!=next;
        pane.scrollY=scrollbar.scrollY=next;
        dragParent.rawY=dragParent.y=pane.y-next;
        if(changed){WorldMapSectionType.method116(pane);WorldMapSectionType.method116(scrollbar);}
    }
    private static Widget scrollbar(int child,Widget base,Widget pane){
        // IF1 owns the existing cache scrollbar drawing/dragging only. Interactive
        // rows stay inside IF3: the IF1 traversal skips children during widget drags.
        Widget bar=root(child,base.id,0,pane.x+pane.width,pane.y,0,pane.height);
        bar.isIf3=false;bar.scrollHeight=pane.scrollHeight;bar.scrollY=pane.scrollY;
        bar.isHidden=pane.scrollHeight<=pane.height;return bar;
    }
    private static Widget dragParent(int child,Widget base,Widget pane){
        // Native dragging clamps to this rectangle. Cover the full content rather
        // than the modal viewport, including partially visible and tall rows.
        Widget parent=root(child,base.id,0,pane.x,pane.y-pane.scrollY,pane.width,pane.scrollHeight);
        parent.noClickThrough=false;parent.isHidden=pane.scrollHeight<=pane.height;return parent;
    }
    private static void swipeScroll(Widget pane,Widget scrollbar,Widget dragParent){
        if(pane.children==null||pane.scrollHeight<=pane.rawHeight)return;
        pane.hasListener=true;
        pane.onScroll=new Object[]{(JavaScriptCallback)event->setScroll(pane,scrollbar,dragParent,pane.scrollY+((ScriptEvent)event).mouseY*45)};
        for(Widget hit:pane.children)if(hit.type==3&&hit.actions!=null){
            hit.parent=dragParent;hit.dragThreshold=0;hit.dragZoneSize=6;hit.isScrollBar=true;
            final long[] press={Long.MIN_VALUE};final int[] start={0};
            hit.onDrag=new Object[]{(JavaScriptCallback)event->{
                if(press[0]!=MouseHandler.MouseHandler_lastPressedTimeMillis){press[0]=MouseHandler.MouseHandler_lastPressedTimeMillis;start[0]=pane.scrollY;}
                setScroll(pane,scrollbar,dragParent,start[0]+MouseHandler.MouseHandler_lastPressedY-MouseHandler.MouseHandler_y);
            }};
            hit.onDragComplete=new Object[]{(JavaScriptCallback)event->press[0]=Long.MIN_VALUE};
            hit.hasListener=true;
        }
    }
    private static void build(){
        int width=modalWidth(),height=modalHeight(),hostWidth=parentWidth(),hostHeight=parentHeight();
        boolean resizable=Client.isResizable;
        Widget[] previous=UserComparator5.Widget_interfaceComponents[GROUP];
        boolean sameResults=renderedView!=null&&renderedView.monsters==view.monsters&&Objects.equals(renderedView.query,view.query)&&Objects.equals(renderedView.resultLabel,view.resultLabel);
        boolean sameDetails=sameResults&&renderedView.selected==view.selected&&Objects.equals(renderedView.detailLabel,view.detailLabel)&&Objects.equals(renderedView.note,view.note);
        int resultScroll=sameResults&&previous!=null&&previous.length>2&&previous[2]!=null?previous[2].scrollY:0;
        int detailScroll=sameDetails&&previous!=null&&previous.length>3&&previous[3]!=null?previous[3].scrollY:0;
        int left=Math.max(154,width*32/100),rightX=left+24,rightWidth=width-rightX-24;
        Widget[] widgets=new Widget[Math.max(8,original.length)];
        Widget base=root(0,-1,0,0,0,width,height);widgets[0]=base;
        base.hasListener=true;base.onTimer=new Object[]{(JavaScriptCallback)event->{
            if(view==null)return;
            if(resizable!=Client.isResizable||hostWidth!=parentWidth()||hostHeight!=parentHeight()||base.rawWidth!=modalWidth()||base.rawHeight!=modalHeight())build();
            else{setScroll(widgets[2],widgets[4],widgets[6],widgets[4].scrollY);setScroll(widgets[3],widgets[5],widgets[7],widgets[5].scrollY);}
        }};
        // Preserve the actual cache's decorative frame and its sprite-based stone borders.
        Widget frame=copy(original[1]);
        if(frame!=null){frame.parentId=base.id;frame.rawX=frame.x=0;frame.rawY=frame.y=0;frame.rawWidth=frame.width=width;frame.rawHeight=frame.height=height;frame.xAlignment=frame.yAlignment=frame.widthAlignment=frame.heightAlignment=0;frame.onLoad=null;
            if(frame.children!=null)for(Widget w:frame.children)hideFrameControls(w);
            widgets[1]=frame;
        }else widgets[1]=root(1,base.id,0,0,0,width,height);
        int top=125,bottom=height-36;
        Widget results=root(2,base.id,0,14,top,left-12,bottom-top);
        Widget details=root(3,base.id,0,rightX,top,rightWidth,bottom-top);
        widgets[2]=results;widgets[3]=details;
        // Remaining old cache components are explicitly hidden; there is no old Exchange button.
        for(int i=4;i<widgets.length;i++){widgets[i]=root(i,base.id,0,0,0,0,0);widgets[i].isHidden=true;}
        UserComparator5.Widget_interfaceComponents[GROUP]=widgets;
        if(widgets[1].children==null)tile(widgets[1],8,8,width-16,height-16,0);
        // The scroll content gets a solid backing in both Classic and GPU UI composition.
        // Fill the full scroll extent so the backing covers the pane at every offset.
        Widget searchBacking=rectangle(results,0,0,results.rawWidth,results.rawHeight,SEARCH_PANEL,true);
        Widget detailBacking=rectangle(details,0,0,details.rawWidth,details.rawHeight,DETAILS_PANEL,true);
        rectangle(base,results.rawX-1,top-1,results.rawWidth+18,results.rawHeight+2,PANEL_BORDER,false);
        rectangle(base,details.rawX-1,top-1,details.rawWidth+18,details.rawHeight+2,PANEL_BORDER,false);
        Font bodyFont=font(494),headerFont=font(495);
        text(base,"<img=109> Loot Compendium",18,7,width-64,27,496,0xffd84a);
        button(base,"X",view.closeAction,width-39,11,24,23,false);
        text(base,"Search live item sources, monster drops and reward tables.",18,34,width-36,16,494,0xd1c7ab);
        button(base,(view.monsters?"":"<col=ffd84a>")+"Items",view.itemsAction,14,55,left/2-8,24,!view.monsters);
        button(base,(view.monsters?"<col=ffd84a>":"")+"Monsters",view.monstersAction,14+left/2,55,left/2-8,24,view.monsters);
        String searchLabel=view.query==null||view.query.isEmpty()?"Search...":fitLabel(headerFont,view.query,left-22);
        button(base,searchLabel,view.searchAction,14,84,left-12,25,false);
        text(base,fitLabel(bodyFont,view.resultLabel,left-12),16,110,left-12,14,494,0xe5cf8c);
        icon(base,view.item,view.npc,rightX,55,48,48);
        text(base,fitLabel(headerFont,view.title,rightWidth-54),rightX+54,53,rightWidth-54,24,495,0xffd84a);
        text(base,fitLabel(bodyFont,view.description,rightWidth-54),rightX+54,77,rightWidth-54,21,494,0xd6c7ad);
        text(base,fitLabel(bodyFont,view.meta,rightWidth-4),rightX+4,101,rightWidth-4,20,494,0xd1c7ab);
        int y=0;
        if(view.results!=null)for(Result result:view.results){
            button(results,"",result.action,0,y,results.rawWidth-3,36,result.id==view.selected);
            icon(results,result.npc?-1:result.id,result.npc?result.id:-1,2,y+3,29,29);
            int labelWidth=results.rawWidth-41;
            text(results,fitLabel(headerFont,result.name,labelWidth),35,y+1,labelWidth,20,495,result.id==view.selected?0xffdc51:0xe0d2b8);
            text(results,fitLabel(bodyFont,result.sub,labelWidth),35,y+20,labelWidth,13,494,0xa99e85);y+=37;
        }
        if(y==0)text(results,"No matching entries.",4,4,results.rawWidth-8,34,494,0xd1c7ab);
        results.scrollHeight=Math.max(results.rawHeight,y);results.scrollY=Math.min(resultScroll,results.scrollHeight-results.rawHeight);
        y=0;
        if(view.note!=null&&!view.note.isEmpty()){
            int h=Math.max(26,textHeight(bodyFont,view.note,details.rawWidth-13)+6);
            tile(details,0,0,details.rawWidth-3,h,30);text(details,view.note,5,3,details.rawWidth-13,h-6,494,0xe4d1aa);y=h+7;
        }
        if(view.sections!=null)for(Section section:view.sections){
            String heading="    "+(section.collapsed?"+ ":"- ")+section.title+" ("+section.count+")";
            int headingHeight=Math.max(23,textHeight(headerFont,heading,details.rawWidth-13)+6);
            button(details,heading,section.action,0,y,details.rawWidth-3,headingHeight,true);
            icon(details,section.icon,-1,2,y+3,18,18);y+=headingHeight+2;
            if(section.collapsed)continue;
            int first=(details.rawWidth-32)*43/100,second=(details.rawWidth-32)*30/100,third=details.rawWidth-32-first-second;
            text(details,view.monsters?"Item":"Source",29,y,first,15,494,0xb8aa83);
            text(details,view.monsters?"Quantity":"Location",29+first,y,second,15,494,0xb8aa83);
            text(details,"Base rate",29+first+second,y,third,15,494,0xb8aa83);y+=17;
            if(section.rows!=null)for(Row row:section.rows){
                int[] metrics=rowMetrics(bodyFont,row,view.monsters,first-3,second-3,third-2);int rowHeight=metrics[0];
                Widget hit=button(details,"",row.action,0,y,details.rawWidth-3,rowHeight-2,false);if(hit!=null){hit.clickMask|=4;hit.setAction(1,"Details");}
                icon(details,row.id,row.npc,1,y+4,25,25);
                text(details,row.title,29,y+2,first-3,metrics[1],494,0xe5d7bd);
                if(!view.monsters)text(details,row.quantity,29,y+metrics[1]+4,first-3,metrics[2],494,0xa99e85);
                text(details,row.location,29+first,y+2,second-3,rowHeight-4,494,0xc4b99f);
                text(details,row.rate,29+first+second,y+2,third-2,rowHeight-4,494,0xe5d7bd);y+=rowHeight;
            }
            y+=5;
        }
        if(y==0)text(details,"No indexed source for this selection.<br>Scripted or conditional rewards may require a registered provider.",5,5,details.rawWidth-10,80,494,0xd1c7ab);
        details.scrollHeight=Math.max(details.rawHeight,y);details.scrollY=Math.min(detailScroll,details.scrollHeight-details.rawHeight);
        searchBacking.rawHeight=searchBacking.height=results.scrollHeight;
        detailBacking.rawHeight=detailBacking.height=details.scrollHeight;
        widgets[4]=scrollbar(4,base,results);widgets[5]=scrollbar(5,base,details);
        widgets[6]=dragParent(6,base,results);widgets[7]=dragParent(7,base,details);
        swipeScroll(results,widgets[4],widgets[6]);swipeScroll(details,widgets[5],widgets[7]);
        button(base,"<",view.previousAction,14,height-32,24,17,false);button(base,">",view.nextAction,left-22,height-32,24,17,false);
        button(base,"<",view.detailPreviousAction,rightX,height-32,24,17,false);text(base,view.detailLabel,rightX+28,height-32,rightWidth-56,17,494,0xb8aa83);button(base,">",view.detailNextAction,width-47,height-32,24,17,false);
        text(base,fitLabel(bodyFont,view.notice,width-28),14,height-15,width-28,12,494,0xb8aa83);
        for(Widget w:widgets)if(w!=null){WorldMapSectionType.method116(w);ViewportMouse.client.revalidateWidget(w);if(w.children!=null)for(Widget c:w.children){WorldMapSectionType.method116(c);ViewportMouse.client.revalidateWidget(c);}}
        alignToNativeHost(base);
        CustomInterfaceEdits.unlock(base.id,0,base.children.length,0);CustomInterfaceEdits.unlock(results.id,0,results.children==null?0:results.children.length,0);CustomInterfaceEdits.unlock(details.id,0,details.children==null?0:details.children.length,0,1);
        renderedView=view;
    }
}
