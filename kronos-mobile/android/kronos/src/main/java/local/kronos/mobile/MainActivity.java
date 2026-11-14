package local.kronos.mobile;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;

/** Landscape touch shell around the on-device Kronos 184 engine. */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    static { System.loadLibrary("kronosmobile"); }
    private static native void start(String root,String host);
    private static native void surface(Surface surface);
    private static native void event(int kind,int x,int y,int value,String text);
    private static native String status();
    private static native void foreground(boolean active);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView statusView;
    private static native String editor();
    private static native int[] frameSize();
    private static native int[] interactions();
    private int[] regions=new int[0];
    private int lastUiRequest;
    private Button menuButton;
    private boolean editingLayout;
    private int placingControl=-1;
    private LayoutOverlay layoutOverlay;
    private LinearLayout layoutToolbar;
    private Button appearanceButton;
    private TextView layoutSelection;
    private final LayoutDrag layoutDrag=new LayoutDrag((id,x,y)->event(16,x,y,id,""));
    private static final String[] TAB_NAMES={"Combat","Skills","Quests / RSPS","Inventory","Equipment","Prayer","Spellbook","Clan chat","Account","Friends","Logout","Settings","Emotes","Music"};
    private SurfaceView game;
    private FrameLayout viewport;
    private LinearLayout editorBar;
    private EditText editorInput;
    private Button editorAction;
    private TouchControls controls;
    private int frameWidth=765,frameHeight=503,requestedWidth,requestedHeight,cameraKey,editorTarget;
    private String editorSerial="";
    private boolean running=false,settingEditor=false,imeVisible=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try(PrintWriter writer=new PrintWriter(new File(getFilesDir(),"android-crash.log"))){error.printStackTrace(writer);}catch(Exception ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if(Build.VERSION.SDK_INT>=28) {WindowManager.LayoutParams p=getWindow().getAttributes();p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(p);}
        showGame();playConnection(getPreferences(0).getString("host","127.0.0.1"),statusView);
    }
    private int dp(int n) {return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String value,int size) {TextView t=new TextView(this);t.setText(value);t.setTextColor(0xffeee2c5);t.setTextSize(size);return t;}
    private Button button(String value,Runnable action) {Button b=new Button(this);b.setText(value);b.setTextColor(0xffffd77b);b.setOnClickListener(v->action.run());return b;}
    private void showSetup() {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(28),dp(20),dp(28),dp(16));box.setBackgroundColor(0xff211e18);
        box.addView(label("KRONOS MOBILE",26));
        box.addView(label("Revision 184 · RuneMobile runtime",14));
        EditText host=new EditText(this);host.setSingleLine(true);host.setTextColor(Color.WHITE);host.setHintTextColor(Color.LTGRAY);host.setHint("Server IP or hostname");
        host.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(getPreferences(0).getString("host","127.0.0.1"));box.addView(host);
        TextView hint=label("Use your server address. On a phone, 127.0.0.1 refers to the phone itself.\nCache: 7304   Game: 13302",14);box.addView(hint);
        Button play=button("Connect",()->{
            String selected=host.getText().toString().trim();
            if(!selected.matches("[A-Za-z0-9.-]+") || selected.length()>253) {host.setError("Enter a hostname or IPv4 address, without a URL or port");return;}
            getPreferences(0).edit().putString("host",selected).apply();
            showGame();playConnection(selected,statusView);
        });box.addView(play); box.addView(button("Crash report",this::showCrashReport));
        box.addView(label("Tap to interact · Hold for options · Drag to move items\nTwo fingers to rotate/zoom · Type for chat and login",14));
        setContentView(box);
    }
    private boolean connecting=false;
    private void playConnection(String host,TextView hint) {
        if(connecting)return;connecting=true;hint.setText("Checking server…");
        new Thread(()->{
            try {
                for(int port:new int[]{7304,13302})try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(host,port),5000);}
                runOnUiThread(()->statusView.setText("Preparing game."));
                File root=getFilesDir();new File(root,"tmp").mkdirs();
                String tag=readAsset("jvm/runtime.tag").trim();File saved=new File(root,"runtime.tag");
                String current=saved.isFile()?new String(java.nio.file.Files.readAllBytes(saved.toPath()),"UTF-8").trim():"";
                if(!tag.equals(current)) {copyAssets("jvm",new File(root,"jvm"));java.nio.file.Files.write(saved.toPath(),tag.getBytes("UTF-8"));}
                running=true;start(root.getAbsolutePath(),host);
                runOnUiThread(this::poll);
            } catch(Throwable e) {try(PrintWriter w=new PrintWriter(new File(getFilesDir(),"android-crash.log"))){e.printStackTrace(w);}catch(Exception ignored){} runOnUiThread(()->{connecting=false;new AlertDialog.Builder(this).setTitle("Could not connect").setMessage(e.getMessage()+"\n\nCheck the server address and that both ports are reachable.").setPositiveButton("OK",(d,w)->showSetup()).show();});}
        },"kronos-start").start();
    }
    private String readAsset(String path)throws IOException {try(InputStream in=getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private void copyAssets(String path,File dest)throws IOException {
        String[] children=getAssets().list(path);
        if(children!=null && children.length>0) {if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create "+dest);for(String child:children)copyAssets(path+"/"+child,new File(dest,child));}
        else {try(InputStream in=getAssets().open(path);OutputStream out=new FileOutputStream(dest)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);} }
    }
    private void showGame() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        editorBar=new LinearLayout(this);editorBar.setOrientation(LinearLayout.HORIZONTAL);editorBar.setBackgroundColor(0xff211e18);editorBar.setVisibility(View.GONE);
        editorInput=new EditText(this);editorInput.setSingleLine(true);editorInput.setTextColor(Color.WHITE);editorInput.setHintTextColor(Color.LTGRAY);
        editorInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editorInput.setSaveEnabled(false);
        editorBar.addView(editorInput,new LinearLayout.LayoutParams(0,dp(52),1));
        editorAction=button("Done",this::submitEditor);editorBar.addView(editorAction,new LinearLayout.LayoutParams(dp(80),dp(52)));
        editorBar.addView(button("Hide",()->closeEditor(true)),new LinearLayout.LayoutParams(dp(72),dp(52)));
        root.addView(editorBar);
        viewport=new FrameLayout(this);
        game=new SurfaceView(this);game.getHolder().addCallback(this);game.setOnTouchListener((v,e)->touch(e));
        viewport.addView(game,new FrameLayout.LayoutParams(765,503,Gravity.CENTER));
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutGame());
        Button menu=button("\u2261",this::showMenu);menuButton=menu;menu.setContentDescription("Game menu and diagnostics");menu.setAlpha(0.65f);
        viewport.addView(menu,new FrameLayout.LayoutParams(dp(48),dp(44),Gravity.TOP|Gravity.LEFT));
        statusView=label("Starting.",14);statusView.setBackgroundColor(0xaa211e18);
        FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        statusParams.topMargin=dp(8);viewport.addView(statusView,statusParams);
        layoutOverlay=new LayoutOverlay();layoutOverlay.setVisibility(View.GONE);
        viewport.addView(layoutOverlay,new FrameLayout.LayoutParams(765,503,Gravity.CENTER));
        layoutToolbar=new LinearLayout(this);layoutToolbar.setOrientation(LinearLayout.HORIZONTAL);layoutToolbar.setBackgroundColor(0xee211e18);layoutToolbar.setVisibility(View.GONE);
        layoutSelection=label("Drag any outlined control",12);layoutSelection.setPadding(dp(8),0,dp(8),0);layoutToolbar.addView(layoutSelection,new LinearLayout.LayoutParams(dp(140),dp(48)));
        appearanceButton=button("Size / opacity",this::buttonAppearance);layoutToolbar.addView(appearanceButton);
        layoutToolbar.addView(button("Move",this::chooseControlToMove));
        layoutToolbar.addView(button("Reset",()->new AlertDialog.Builder(this).setTitle("Reset layout?").setMessage("Restore default button positions, sizes and opacity.").setPositiveButton("Reset",(d,w)->{
            placingControl=-1;
            event(15,2,0,3,"");
            layoutSelection.setText("Drag an icon or panel");
        }).setNegativeButton("Cancel",null).show()));
        layoutToolbar.addView(button("Cancel",()->finishLayout(false)));
        layoutToolbar.addView(button("Save",()->finishLayout(true)));
        HorizontalScrollView toolbarScroll=new HorizontalScrollView(this);toolbarScroll.setFillViewport(false);toolbarScroll.addView(layoutToolbar);
        viewport.addView(toolbarScroll,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        controls=new TouchControls(new TouchControls.Sink(){
            public void tap(int button,float x,float y){event(18,(int)x,(int)y,button,"");}
            public void drag(int phase,float x,float y){event(phase,(int)x,(int)y,phase==2?0:1,"");}
            public void wheel(float x,float y,int steps){event(3,(int)x,(int)y,steps,"");}
            public int region(float x,float y){return touchRegion(x,y);}
            public void camera(float dx,float dy){event(13,Math.round(dx*1000),Math.round(dy*1000),0,"");}
            public void zoom(float scale){event(14,0,0,Math.round(scale*1000),"");}
        });
        applyTouchSettings();
        editorInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                if(!settingEditor && editorTarget!=0)event(9,0,0,editorTarget,s.toString());
            }
            public void afterTextChanged(android.text.Editable s){}
        });
        editorInput.setOnEditorActionListener((v,action,e)->{
            if(e==null || e.getAction()==KeyEvent.ACTION_DOWN){submitEditor();return true;}return false;
        });
        editorInput.setOnFocusChangeListener((v,focus)->{if(focus)keyboardManager().showSoftInput(editorInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
                boolean visible=insets.isVisible(WindowInsets.Type.ime());
                root.setPadding(safe.left,safe.top,safe.right,Math.max(safe.bottom,ime.bottom));
                if(imeVisible && !visible && editorTarget!=0)handler.post(()->closeEditor(false));
                imeVisible=visible;return insets;
            });
        }else root.setFitsSystemWindows(true);
        setContentView(root);root.requestApplyInsets();game.post(this::layoutGame);
    }
    private void layoutGame(){
        if(viewport==null || viewport.getWidth()<1 || viewport.getHeight()<1)return;
        if(editorTarget==0 && !imeVisible){
            int[] desired=ScreenLayout.canvas(viewport.getWidth(),viewport.getHeight(),getPreferences(0).getInt("uiScale",100)/100f);
            if(desired[0]!=requestedWidth || desired[1]!=requestedHeight){
                requestedWidth=desired[0];requestedHeight=desired[1];
                event(7,requestedWidth,requestedHeight,0,"");
            }
        }
        int[] size=ScreenLayout.fit(viewport.getWidth(),viewport.getHeight(),frameWidth,frameHeight);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)game.getLayoutParams();
        if(p.width!=size[0] || p.height!=size[1]){p.width=size[0];p.height=size[1];game.setLayoutParams(p);}
        if(layoutOverlay!=null){
            FrameLayout.LayoutParams overlay=(FrameLayout.LayoutParams)layoutOverlay.getLayoutParams();
            if(overlay.width!=size[0]||overlay.height!=size[1]){
                overlay.width=size[0];overlay.height=size[1];layoutOverlay.setLayoutParams(overlay);layoutDrag.cancel();
            }
        }
        if(controls!=null)controls.configure(frameWidth,frameHeight,dp(getPreferences(0).getInt("touchSlop",8))*(float)frameWidth/Math.max(1,size[0]));
    }
    private void poll(){
        if(isFinishing())return;
        String s=status();statusView.setText(s);
        statusView.setVisibility(s.equals("LOGIN_SCREEN")||s.equals("LOGGED_IN")?View.GONE:View.VISIBLE);
        regions=interactions();if(editingLayout)layoutOverlay.invalidate();
        if(regions!=null&&regions.length>=8){
            menuButton.setVisibility(regions[0]==1?View.GONE:View.VISIBLE);
            if(regions[2]!=lastUiRequest){lastUiRequest=regions[2];if(regions[3]==1)showMenu();else if(regions[3]==2)chooseHotkey(regions[4]);}
        }
        int[] size=frameSize();
        if(size!=null&&size.length==2&&(frameWidth!=size[0]||frameHeight!=size[1])){
            if(controls!=null)controls.cancel();
            event(19,0,0,0,"");
            frameWidth=size[0];frameHeight=size[1];layoutGame();
        }
        String info=editor();
        if(info!=null){
            String[] parts=info.split("\n",3);
            if(parts.length==3 && !parts[0].equals(editorSerial)){
                editorSerial=parts[0];int field=Integer.parseInt(parts[1]);
                if(field==0)closeEditor(false);else openEditor(field,parts[2]);
            }
        }
        if(controls!=null)controls.tick(SystemClock.uptimeMillis());
        handler.postDelayed(this::poll,50);
    }
    private void key(int code){event(10,0,0,code,"");}
    private int gx(float x){return Math.max(0,Math.min(frameWidth-1,(int)(x*frameWidth/Math.max(1,game.getWidth()))));}
    private int gy(float y){return Math.max(0,Math.min(frameHeight-1,(int)(y*frameHeight/Math.max(1,game.getHeight()))));}
    private void setCamera(int code){
        if(code==cameraKey)return;
        if(cameraKey!=0)event(6,0,0,cameraKey,"");
        cameraKey=code;if(code!=0)event(5,0,0,code,"");
    }
    private boolean touch(MotionEvent e){
        if(editingLayout||controls==null)return true;
        int action=e.getActionMasked(),index=e.getActionIndex();long time=e.getEventTime();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)
            controls.down(e.getPointerId(index),gx(e.getX(index)),gy(e.getY(index)),time);
        else if(action==MotionEvent.ACTION_MOVE){
            for(int i=0;i<e.getPointerCount();i++)controls.position(e.getPointerId(i),gx(e.getX(i)),gy(e.getY(i)));
            controls.moved(time);
        }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)
            controls.up(e.getPointerId(index),gx(e.getX(index)),gy(e.getY(index)),time);
        else if(action==MotionEvent.ACTION_CANCEL)controls.cancel();
        return true;
    }
    private android.view.inputmethod.InputMethodManager keyboardManager(){return (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);}
    private void openEditor(int field,String text){
        settingEditor=true;editorTarget=field;
        editorInput.setInputType(InputType.TYPE_CLASS_TEXT|(field==2?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        editorInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(field==1?320:field==2?20:80),
            (source,start,end,dest,dstart,dend)->{
                StringBuilder out=new StringBuilder();for(int i=start;i<end;i++){char c=source.charAt(i);if(c>=32&&c<=126)out.append(c);}
                return out.length()==end-start?null:out.toString();
            }});
        editorInput.setImeOptions((field==1?android.view.inputmethod.EditorInfo.IME_ACTION_NEXT:field==2?android.view.inputmethod.EditorInfo.IME_ACTION_GO:android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            |android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        editorInput.setHint(field==1?"Username":field==2?"Password":field==3?"Chat":"Enter text");
        editorInput.setText(text);editorInput.setSelection(editorInput.length());
        editorAction.setText(field==1?"Next":field==2?"Log in":"Send");
        editorBar.setVisibility(View.VISIBLE);settingEditor=false;
        editorInput.requestFocus();keyboardManager().restartInput(editorInput);
        editorInput.postDelayed(()->{if(editorTarget!=0)keyboardManager().showSoftInput(editorInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);},100);
    }
    private void submitEditor(){
        if(editorTarget==0)return;
        event(9,0,0,editorTarget,editorInput.getText().toString());
        if(editorTarget==1){event(12,0,0,2,"");return;}
        key(10);closeEditor(true);
    }
    private void closeEditor(boolean notify){
        if(editorBar==null || editorTarget==0)return;
        if(notify)event(11,0,0,0,"");
        settingEditor=true;editorTarget=0;
        keyboardManager().hideSoftInputFromWindow(editorInput.getWindowToken(),0);
        editorInput.clearFocus();editorInput.setText("");editorBar.setVisibility(View.GONE);settingEditor=false;
        game.requestFocus();game.postDelayed(this::layoutGame,250);
    }
    private void showMenu(){
        if(controls!=null)controls.cancel();
        new AlertDialog.Builder(this).setTitle("Kronos Mobile").setItems(new String[]{"Touch controls","Customize hotkeys","Camera gestures","Interface size","Mobile / classic layout","Keyboard","Escape","Diagnostics","Server settings","Close game","Edit layout"},(d,which)->{
            if(which==0)new AlertDialog.Builder(this).setTitle("Touch controls").setMessage("Tap normally to interact.\n\nIn the game world, hold briefly then drag one finger to rotate and tilt the camera. Pinch two fingers to zoom. Camera gestures never send a click.\n\nHold still longer for context options. Once a menu opens, lift your finger and tap its entry.\n\nTabs and inventory keep their own touch handling. Tap a selected tab again to close its panel. The arrow collapses the extra tab column.\n\nUse the left hotkeys for shortcuts. Chat and keyboard buttons are at the upper left.").setPositiveButton("OK",null).show();
            if(which==1)new AlertDialog.Builder(this).setTitle("Hotkey slot").setItems(new String[]{"1 (top)","2","3","4","5 (bottom)"},(dialog,slot)->chooseHotkey(slot)).show();
            if(which==2)gestureSettings();
            if(which==3)new AlertDialog.Builder(this).setTitle("Interface size").setSingleChoiceItems(new String[]{"Large (100%)","Medium (90%)","Compact (80%)"},getPreferences(0).getInt("uiScale",100)==100?0:getPreferences(0).getInt("uiScale",100)==90?1:2,(dialog,index)->{
                getPreferences(0).edit().putInt("uiScale",100-index*10).apply();if(controls!=null)controls.cancel();layoutGame();dialog.dismiss();
            }).show();
            if(which==4)new AlertDialog.Builder(this).setTitle("Layout").setItems(new String[]{"OSRS mobile layout","Classic client layout"},(dialog,index)->event(15,0,0,index==0?1:0,"")).show();
            if(which==5)event(12,0,0,status().equals("LOGIN_SCREEN")?1:3,"");
            if(which==6)key(27);
            if(which==7)showCrashReport();
            if(which==8)serverSetting();
            if(which==9)confirmClose();
            if(which==10)beginLayout();
        }).show();
    }
    private String layoutName(int id){
        if(id>=0&&id<14)return TAB_NAMES[id]+" button";
        if(id>=14&&id<=18)return "Hotkey "+(id-13);
        if(id>=40&&id<54)return TAB_NAMES[id-40]+" panel";
        switch(id){case 20:return "Minimap";case 21:return "Chat panel";case 22:return "Tab collapse";case 23:return "Menu";case 24:return "Chat button";case 25:return "Keyboard";case 26:return "Logout button";default:return "Drag any outlined control";}
    }
    private boolean editableButton(int id){return id>=0&&id<=18||id>=22&&id<=26;}
    private void chooseControlToMove(){
        java.util.List<Integer> ids=new java.util.ArrayList<>();
        java.util.List<String> names=new java.util.ArrayList<>();
        for(int id=0;id<TAB_NAMES.length;id++){
            int button=id==10?26:id;
            ids.add(button);
            names.add(layoutName(button));
            ids.add(40+id);
            names.add(layoutName(40+id));
        }
        new AlertDialog.Builder(this).setTitle("Control to move")
            .setItems(names.toArray(new String[0]),(dialog,index)->{
                placingControl=ids.get(index);
                if(placingControl>=40)event(15,3,0,placingControl-40,"");
                layoutDrag.cancel();
                layoutSelection.setText("Tap a new spot");
                appearanceButton.setEnabled(false);
                Toast.makeText(this,"Tap where you want the "+layoutName(placingControl).toLowerCase(java.util.Locale.ROOT),Toast.LENGTH_LONG).show();
            }).show();
    }
    private void beginLayout(){
        if(regions==null||regions.length<8||regions[0]!=1){Toast.makeText(this,"Log in and select the mobile layout first",Toast.LENGTH_LONG).show();return;}
        closeEditor(true);controls.cancel();event(19,0,0,0,"");editingLayout=true;
        event(15,2,0,1,"");layoutOverlay.setVisibility(View.VISIBLE);layoutToolbar.setVisibility(View.VISIBLE);appearanceButton.setEnabled(false);
        layoutSelection.setText("Drag an icon or panel");
    }
    private void finishLayout(boolean save){
        placingControl=-1;layoutDrag.cancel();event(15,2,0,save?0:2,"");editingLayout=false;
        layoutOverlay.setVisibility(View.GONE);layoutToolbar.setVisibility(View.GONE);controls.cancel();
        Toast.makeText(this,save?"Layout saved on this device":"Layout changes cancelled",Toast.LENGTH_SHORT).show();
    }
    private void buttonAppearance(){
        int id=layoutDrag.selected();if(!editableButton(id))return;
        int size=100,opacity=100;
        for(int i=8;regions!=null&&i+4<regions.length;i+=5)if(regions[i+4]==200+id){size=regions[i];opacity=regions[i+1];break;}
        final int oldSize=size,oldOpacity=opacity;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),0,dp(16),0);
        TextView sizeLabel=label("",14),opacityLabel=label("",14);SeekBar a=new SeekBar(this),b=new SeekBar(this);
        a.setMax(120);a.setProgress(size-60);b.setMax(90);b.setProgress(opacity-10);
        Runnable update=()->{sizeLabel.setText("Size: "+(60+a.getProgress())+"%");opacityLabel.setText("Opacity: "+(10+b.getProgress())+"%");};
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}public void onProgressChanged(SeekBar bar,int value,boolean user){update.run();if(user)event(17,60+a.getProgress(),10+b.getProgress(),id,"");}};
        a.setOnSeekBarChangeListener(listener);b.setOnSeekBarChangeListener(listener);update.run();
        panel.addView(sizeLabel);panel.addView(a);panel.addView(opacityLabel);panel.addView(b);
        new AlertDialog.Builder(this).setTitle(layoutName(id)).setView(panel).setPositiveButton("Done",(d,w)->event(17,60+a.getProgress(),10+b.getProgress(),id,""))
            .setNegativeButton("Cancel",(d,w)->event(17,oldSize,oldOpacity,id,""))
            .setOnCancelListener(d->event(17,oldSize,oldOpacity,id,"")).show();
    }
    private final class LayoutOverlay extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(3);
        LayoutOverlay(){super(MainActivity.this);setContentDescription("Drag controls to customize layout");}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            super.onDraw(canvas);if(regions==null)return;canvas.save();canvas.scale(getWidth()/(float)frameWidth,getHeight()/(float)frameHeight);
            for(int i=8;i+4<regions.length;i+=5)if(regions[i+4]>=100&&regions[i+4]<200){
                int id=regions[i+4]-100;float x=regions[i],y=regions[i+1],w=regions[i+2],h=regions[i+3];
                paint.setColor(id==layoutDrag.selected()?0xffffd369:0xff72e0dc);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(2);
                canvas.drawRect(x+1,y+1,x+w-1,y+h-1,paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);paint.setTextSize(11);
                if(id>=40||id==20||id==21)canvas.drawText(layoutName(id),x+4,y+14,paint);
            }canvas.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            int action=e.getActionMasked();float x=e.getX()*frameWidth/Math.max(1,getWidth()),y=e.getY()*frameHeight/Math.max(1,getHeight());
            if(action==MotionEvent.ACTION_DOWN){
                if(placingControl>=0){
                    if(layoutDrag.place(placingControl,x,y,regions)){
                        layoutSelection.setText(layoutName(placingControl));
                        appearanceButton.setEnabled(editableButton(placingControl));
                        placingControl=-1;
                    }
                }else{
                    int id=layoutDrag.down(e.getPointerId(0),x,y,regions);
                    layoutSelection.setText(layoutName(id));appearanceButton.setEnabled(editableButton(id));
                }
            }
            else if(action==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1)layoutDrag.move(e.getPointerId(0),x,y);
            else if(action==MotionEvent.ACTION_UP){layoutDrag.move(e.getPointerId(0),x,y);layoutDrag.cancel();}
            else if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL)layoutDrag.cancel();
            invalidate();return true;
        }
    }
    private void chooseHotkey(int slot){
        String[] names={"Combat","Skills","Quests / RSPS","Inventory","Equipment","Prayer","Spellbook","Clan chat","Account","Friends","Logout","Settings","Emotes","Music"};
        new AlertDialog.Builder(this).setTitle("Hotkey "+(slot+1)).setItems(names,(d,index)->event(15,10+slot,0,index,"")).setNegativeButton("Cancel",null).show();
    }
    private int touchRegion(float x,float y){
        return TouchRegions.at(regions,frameWidth,frameHeight,x,y);
    }
    private void applyTouchSettings(){
        if(controls!=null)controls.settings(getPreferences(0).getInt("cameraHold",180),getPreferences(0).getInt("contextHold",650),getPreferences(0).getFloat("cameraSensitivity",2f));
    }
    private void gestureSettings(){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),0,dp(16),0);
        TextView hold=label("",14),context=label("",14),sensitivity=label("",14),slop=label("",14);
        SeekBar a=new SeekBar(this),b=new SeekBar(this),c=new SeekBar(this),d=new SeekBar(this);
        a.setMax(300);a.setProgress(getPreferences(0).getInt("cameraHold",180)-80);
        b.setMax(700);b.setProgress(getPreferences(0).getInt("contextHold",650)-500);
        c.setMax(35);c.setProgress(Math.round(getPreferences(0).getFloat("cameraSensitivity",2f)*10)-5);
        d.setMax(12);d.setProgress(getPreferences(0).getInt("touchSlop",8)-4);
        Runnable labels=()->{hold.setText("Camera hold: "+(80+a.getProgress())+" ms");context.setText("Context hold: "+(500+b.getProgress())+" ms");sensitivity.setText("Camera sensitivity: "+((5+c.getProgress())/10f));slop.setText("Movement threshold: "+(4+d.getProgress())+" dp");};
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){labels.run();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}};
        for(SeekBar bar:new SeekBar[]{a,b,c,d})bar.setOnSeekBarChangeListener(listener);
        panel.addView(hold);panel.addView(a);panel.addView(context);panel.addView(b);panel.addView(sensitivity);panel.addView(c);panel.addView(slop);panel.addView(d);labels.run();
        new AlertDialog.Builder(this).setTitle("Camera gestures").setView(panel).setPositiveButton("Save",(dialog,which)->{
            getPreferences(0).edit().putInt("cameraHold",80+a.getProgress()).putInt("contextHold",500+b.getProgress()).putFloat("cameraSensitivity",(5+c.getProgress())/10f).putInt("touchSlop",4+d.getProgress()).apply();applyTouchSettings();layoutGame();
        }).setNegativeButton("Cancel",null).show();
    }
    private void serverSetting(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(getPreferences(0).getString("host","127.0.0.1"));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Server for next launch").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String host=input.getText().toString().trim();
            if(!host.matches("[A-Za-z0-9.-]+")||host.length()>253){input.setError("Enter a hostname or IP address");return;}
            getPreferences(0).edit().putString("host",host).apply();dialog.dismiss();
        }));dialog.show();
    }
    private String crashReport(){
        StringBuilder report=new StringBuilder("Kronos Mobile ").append(BuildConfig.VERSION_NAME).append("\nDevice: ").append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE).append("\nState: ").append(status()).append('\n');
        if(Build.VERSION.SDK_INT>=30){
            try{
                ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
                for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(getPackageName(),0,3)){
                    report.append("\nPrevious exit: reason=").append(exit.getReason()).append(" status=").append(exit.getStatus()).append(" at ").append(new java.util.Date(exit.getTimestamp())).append("\n").append(exit.getDescription()).append('\n');
                }
            }catch(Exception e){report.append("Exit history: ").append(e).append('\n');}
        }
        File[] files=getFilesDir().listFiles((dir,name)->name.equals("client.log")||name.equals("client-error.log")||name.equals("android-crash.log")||name.equals("boot-stages.log")||name.startsWith("hs_err_pid"));
        if(files!=null)for(File f:files)try(RandomAccessFile in=new RandomAccessFile(f,"r")){
            long length=in.length();int count=(int)Math.min(20000,length);
            if(f.getName().startsWith("hs_err_pid")&&length>20000){byte[] header=new byte[12000];in.readFully(header);report.append("\n--- ").append(f.getName()).append(" crash header ---\n").append(new String(header,"UTF-8"));count=8000;}
            byte[] bytes=new byte[count];in.seek(Math.max(0,length-count));in.readFully(bytes);
            report.append("\n--- ").append(f.getName()).append(" ---\n").append(new String(bytes,"UTF-8"));
        }catch(IOException e){report.append(e.toString());}
        return report.toString();
    }
    private void showCrashReport(){
        String report=crashReport();TextView text=label(report,12);text.setTextIsSelectable(true);text.setPadding(dp(12),dp(12),dp(12),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Kronos crash report").setView(scroll).setPositiveButton("Copy",(d,w)->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Kronos crash report",report));Toast.makeText(this,"Report copied; paste it into our chat",Toast.LENGTH_LONG).show();
        }).setNeutralButton("Share",(d,w)->shareLogs()).setNegativeButton("Close",null).show();
    }
    private void shareLogs(){
        android.content.Intent send=new android.content.Intent(android.content.Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(android.content.Intent.EXTRA_SUBJECT,"Kronos Mobile diagnostics");send.putExtra(android.content.Intent.EXTRA_TEXT,crashReport());startActivity(android.content.Intent.createChooser(send,"Share diagnostics"));
    }
    @Override public void surfaceCreated(SurfaceHolder h){surface(h.getSurface());}
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int height){surface(h.getSurface());}
    @Override public void surfaceDestroyed(SurfaceHolder h){surface(null);}
    @Override protected void onPause(){foreground(false);if(controls!=null)controls.cancel();layoutDrag.cancel();event(19,0,0,0,"");super.onPause();}
    @Override protected void onResume(){super.onResume();foreground(true);}
    @Override public void onBackPressed(){if(editingLayout){finishLayout(false);return;}if(editorTarget!=0){closeEditor(true);return;}if(!running){super.onBackPressed();return;}showMenu();} private void confirmClose(){new AlertDialog.Builder(this).setTitle("Close Kronos?").setMessage("Log out in-game first to save your progress.").setNegativeButton("Keep playing",null).setPositiveButton("Close",(d,w)->{finishAndRemoveTask();android.os.Process.killProcess(android.os.Process.myPid());}).show();}
}

