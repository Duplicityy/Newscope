package net.runelite.standalone;

import java.awt.*;
import javax.swing.*;
import net.runelite.api.GameState;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;

/** Mobile UI operations run on the game thread; window operations run on the EDT. */
public final class MobileUi {
    private static volatile String editor = "0\n0\n";
    private static int serial;
    private static int target;
    private static int inputMode;
    private static boolean initialSizeApplied;
    private MobileUi() {}
    private static net.runelite.api.Client client() { return RuneLite.getInjector().getInstance(net.runelite.api.Client.class); }
    private static void game(Runnable action) { RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(action); }
    public static String editor() { return editor; }
    private static int previousLogin=-1,previousInput;
    public static void onFrame() {
        net.runelite.api.Client c=client();
        int login=c.getGameState()==GameState.LOGIN_SCREEN?Login.loginIndex:-1;
        int mode=c.getGameState()==GameState.LOGGED_IN?c.getVar(VarClientInt.INPUT_TYPE):0;
        if(login==2 && previousLogin!=2)open(Login.currentLoginField==1?2:1);
        else if(target>0 && ((target<=2 && login!=2)||(target>=3 && c.getGameState()!=GameState.LOGGED_IN)||(target==4 && mode!=inputMode)||(target==3&&!MobileLayout.chatEditorVisible())))open(0);
        if(mode>0 && mode!=previousInput)open(4);
        previousLogin=login;previousInput=mode;
    }

    public static void resize(int w,int h) {
        if(w<765 || h<503 || w>2048 || h>1536)return;
        game(() -> {
            if(!initialSizeApplied){
                initialSizeApplied=true;
                AbstractArchive.clientPreferences.windowMode=2;
                if(!Client.isResizable)UserComparator8.method2878(2);
            }
            final Component applet=(Component)client();
            EventQueue.invokeLater(() -> {
                Dimension size=new Dimension(w,h);
                applet.setPreferredSize(size);applet.setSize(size);
                Container parent=applet.getParent();
                if(parent!=null){parent.setPreferredSize(size);parent.setSize(size);}
                Window window=SwingUtilities.getWindowAncestor(applet);
                if(window!=null)window.pack();
                applet.validate();
            });
        });
    }
    public static void request(int x,int y) {
        game(() -> {
            net.runelite.api.Client c=client();
            int chosen=0;
            if(c.getGameState()==GameState.LOGIN_SCREEN && Login.loginIndex==2) {
                int center=GrandExchangeOfferUnitPriceComparator.loginBoxCenter;
                if(Math.abs(x-center)<165 && y>=237 && y<=278)chosen=y<258?1:2;
                else if(Math.abs(x-(center-80))<75 && y>=301 && y<=341 && (Login.Login_username.isEmpty()||Login.Login_password.isEmpty()))chosen=Login.Login_username.isEmpty()?1:2;
                else return;
            } else if(c.getGameState()==GameState.LOGGED_IN) {
                int mode=c.getVar(VarClientInt.INPUT_TYPE);
                Widget full=c.getWidget(WidgetInfo.CHATBOX_FULL_INPUT);
                Widget chat=c.getWidget(WidgetInfo.CHATBOX_INPUT);
                if(mode>0 && MobileLayout.acceptsEditor(full,x,y))chosen=4;
                else if(MobileLayout.acceptsEditor(chat,x,y))chosen=3;
            }
            open(chosen);
        });
    }
    public static void openField(int field) { game(() -> open(field)); }
    private static void open(int field) {
        net.runelite.api.Client c=client();
        if((field==1||field==2) && (c.getGameState()!=GameState.LOGIN_SCREEN || Login.loginIndex!=2))field=0;
        if(field>=3 && c.getGameState()!=GameState.LOGGED_IN)field=0;
        target=field;
        String value="";
        if(field==1){Login.currentLoginField=0;value=Login.Login_username;}
        if(field==2){Login.currentLoginField=1;value=Login.Login_password;}
        if(field==3)value=c.getVar(VarClientStr.CHATBOX_TYPED_TEXT);
        if(field==4){inputMode=c.getVar(VarClientInt.INPUT_TYPE);value=c.getVar(VarClientStr.INPUT_TEXT);}
        editor=(++serial)+"\n"+field+"\n"+(value==null?"":value);
    }
    public static void replace(int field,String value) {
        game(() -> {
            if(field!=target || field==0)return;
            net.runelite.api.Client c=client();
            int limit=field==1?320:field==2?20:80;
            StringBuilder clean=new StringBuilder();
            for(int i=0;i<value.length() && clean.length()<limit;i++){
                char ch=value.charAt(i);
                if(ch>=32 && ch<=126)clean.append(ch);
            }
            String text=clean.toString();
            if(field==1 && c.getGameState()==GameState.LOGIN_SCREEN){Login.Login_username=text;Client.onUsernameChanged(-1);}
            if(field==2 && c.getGameState()==GameState.LOGIN_SCREEN)Login.Login_password=text;
            if(field==3 && c.getGameState()==GameState.LOGGED_IN)c.setVar(VarClientStr.CHATBOX_TYPED_TEXT,text);
            if(field==4 && c.getGameState()==GameState.LOGGED_IN && c.getVar(VarClientInt.INPUT_TYPE)==inputMode)c.setVar(VarClientStr.INPUT_TEXT,text);
        });
    }
    public static void afterEdits(Runnable action) { game(action); }
    public static void close() { game(() -> open(0)); }
}
