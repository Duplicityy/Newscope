import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
import net.runelite.api.BufferProvider;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;

/** Kronos 184 adapter for RuneMobile's embedded JVM/Cacio architecture. */
public final class KronosBoot {
    private static volatile Client client;
    // Header width/height plus a complete ARGB frame. Published after DrawManager completes drawing.
    private static final Object frameLock = new Object();
    private static int[] latest;
    private static long frameSequence, copiedSequence;
    private static ByteBuffer lastDestination;
    private static IntBuffer destinationPixels;
    private static volatile String status = "Starting Kronos";
    public static void boot() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,e) -> { status = "Client error: " + e; e.printStackTrace(); });
        if (!Boolean.getBoolean("kronos.desktopTest")) UnsafePokeActivation.activate();
        // Desktop Substance chrome queries OS fonts and native window decorations.
        // The Android shell supplies chrome; Cacio only needs a plain Swing host.
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
                java.lang.reflect.Field configured=net.runelite.client.util.SwingUtil.class.getDeclaredField("lookAndFeelIsSet");
                configured.setAccessible(true); configured.setBoolean(null,true);
            } catch(Exception e) {throw new RuntimeException(e);}
        });
        File settings = new File(System.getProperty("user.home"), ".kronos");
        settings.mkdirs();
        File config = new File(settings, "runeliteplus.properties");
        java.util.Properties prefs=new java.util.Properties();
        if(config.exists()) try(java.io.InputStream in=new java.io.FileInputStream(config)){prefs.load(in);}
        prefs.setProperty("runelite.gameSize","765x503");
        prefs.setProperty("runelite.uiEnableCustomChrome","false");
        prefs.setProperty("runelite.lockWindowSize","true");
        prefs.setProperty("runelite.gpuplugin","false");
        try(java.io.OutputStream out=new java.io.FileOutputStream(config)){prefs.store(out,"Kronos mobile host");}
        RuneLite.main(new String[]{"--no-splash"});
        client = RuneLite.getInjector().getInstance(Client.class);
        RuneLite.getInjector().getInstance(DrawManager.class).registerEveryFrameListener(() -> {
            BufferProvider b = client.getBufferProvider();
            if (b == null || b.getPixels() == null || b.getWidth() < 1 || b.getHeight() < 1) return;
            int n = b.getWidth() * b.getHeight();
            if (n > b.getPixels().length) return;
            synchronized (frameLock) {
                if (latest == null || latest.length != n + 2) latest = new int[n + 2];
                latest[0] = b.getWidth();
                latest[1] = b.getHeight();
                System.arraycopy(b.getPixels(), 0, latest, 2, n);
                frameSequence++;
            }
            status = client.getGameState().toString();
            net.runelite.standalone.MobileTap.frameDrawn();
            net.runelite.standalone.MobileUi.onFrame();
        });
        EventQueue.invokeLater(() -> { client.getCanvas().requestFocusInWindow(); });
    }
    public static int[] frame() {
        synchronized (frameLock) {
            return latest == null ? null : latest.clone();
        }
    }

    /** Android supplies one reusable buffer. A zero result means no new frame. */
    public static int copyFrame(ByteBuffer destination, int[] dimensions, boolean refresh) {
        synchronized (frameLock) {
            if (latest == null || !refresh && copiedSequence == frameSequence) return 0;
            int count = latest.length - 2;
            int bytes = count * Integer.BYTES;
            dimensions[0] = latest[0];
            dimensions[1] = latest[1];
            if (destination == null || destination.capacity() < bytes) return -bytes;
            if (destination != lastDestination) {
                lastDestination = destination;
                destinationPixels = destination.order(ByteOrder.nativeOrder()).asIntBuffer();
            }
            destinationPixels.clear();
            destinationPixels.put(latest, 2, count);
            copiedSequence = frameSequence;
            return bytes;
        }
    }
    public static String status() { return status; }
    public static int[] interactions() { return net.runelite.standalone.MobileLayout.interactions(); }
    public static String editor() { return net.runelite.standalone.MobileUi.editor(); }
    // Mouse listeners receive input in AWT's event thread; the client consumes it on its own tick.
    public static void input(int kind, int x, int y, int value, String text) {
        if(kind==18){net.runelite.standalone.MobileTap.enqueue(x,y,value);return;}
        if(kind==19){net.runelite.standalone.MobileTap.cancel();return;}
        if(kind==17){net.runelite.standalone.MobileUi.afterEdits(()->net.runelite.standalone.MobileLayout.style(value,x,y));return;}
        if(kind==16){net.runelite.standalone.MobileUi.afterEdits(()->net.runelite.standalone.MobileLayout.move(value,x,y));return;}
        if(kind==13){net.runelite.standalone.MobileTap.cancel();net.runelite.standalone.MobileUi.afterEdits(()->net.runelite.standalone.MobileLayout.camera(x,y));return;}
        if(kind==14){net.runelite.standalone.MobileTap.cancel();net.runelite.standalone.MobileUi.afterEdits(()->net.runelite.standalone.MobileLayout.zoom(value));return;}
        if(kind==15){net.runelite.standalone.MobileUi.afterEdits(()->net.runelite.standalone.MobileLayout.configure(x,value));return;}
        if(kind==7){net.runelite.standalone.MobileTap.cancel();net.runelite.standalone.MobileUi.resize(x,y);return;}
        if(kind==8){net.runelite.standalone.MobileUi.request(x,y);return;}
        if(kind==9){net.runelite.standalone.MobileUi.replace(value,text);return;}
        if(kind==11){net.runelite.standalone.MobileUi.close();return;}
        if(kind==12){net.runelite.standalone.MobileUi.openField(value);return;}
        if(kind==10){
            net.runelite.standalone.MobileUi.afterEdits(() -> {
                input(5,0,0,value,"");
                java.util.Timer timer=new java.util.Timer(true);
                timer.schedule(new java.util.TimerTask(){public void run(){input(6,0,0,value,"");timer.cancel();}},60);
            });return;
        }
        if(net.runelite.standalone.MobileLayout.editing()&&kind<=6)return;
        Client c = client;
        if (c == null || c.getCanvas() == null) return;
        Canvas canvas = c.getCanvas();
        EventQueue.invokeLater(() -> {
            long now = System.currentTimeMillis();
            if (kind == 0 || kind == 1 || kind == 2) {
                int button = value == 3 ? MouseEvent.BUTTON3 : (value == 2 ? MouseEvent.BUTTON2 : MouseEvent.BUTTON1);
                int id = kind == 0 ? MouseEvent.MOUSE_PRESSED : kind == 1 ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_MOVED;
                int modifiers = kind == 0 ? InputEvent.getMaskForButton(button) : 0;
                MouseEvent e = new MouseEvent(canvas, id, now, modifiers, x, y, kind == 2 ? 0 : 1, button == 3, kind == 2 ? 0 : button);
                if (kind == 2) for (MouseMotionListener l : canvas.getMouseMotionListeners()) l.mouseMoved(e);
                else for (MouseListener l : canvas.getMouseListeners()) { if (kind == 0) l.mousePressed(e); else l.mouseReleased(e); }
            } else if (kind == 3) {
                MouseWheelEvent e = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, now, 0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, value);
                for (MouseWheelListener l : canvas.getMouseWheelListeners()) l.mouseWheelMoved(e);
            } else if (kind == 4) {
                for (char ch : text.toCharArray()) {
                    KeyEvent e = new KeyEvent(canvas, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch);
                    for (KeyListener l : canvas.getKeyListeners()) l.keyTyped(e);
                }
            } else if (kind == 5 || kind == 6) {
                char ch = value == KeyEvent.VK_ENTER ? '\n' : value == KeyEvent.VK_BACK_SPACE ? '\b' : KeyEvent.CHAR_UNDEFINED;
                KeyEvent e = new KeyEvent(canvas, kind == 5 ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, now, 0, value, ch);
                for (KeyListener l : canvas.getKeyListeners()) { if (kind == 5) l.keyPressed(e); else l.keyReleased(e); }
            }
        });
    }
    public static void main(String[] args) throws Exception {
        if(args.length > 0) { java.util.Timer watchdog = new java.util.Timer(true); watchdog.schedule(new java.util.TimerTask(){ public void run(){System.err.println("MOBILE-HARNESS TIMEOUT");System.exit(2);} }, Boolean.getBoolean("kronos.testMobileUi")||Boolean.getBoolean("kronos.testDialogs")?300000:150000); }
        try { boot(); } catch(Throwable e) {e.printStackTrace();System.exit(1);return;}
        if (args.length == 0) return;
        long deadline = System.currentTimeMillis() + 120000;
        int samples=0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            int[] p = frame();
            System.out.println("MOBILE-HARNESS status=" + status + " frame=" + (p == null ? 0 : p.length));
            if(p!=null && samples++%10==0) {
                BufferedImage progress=new BufferedImage(p[0],p[1],BufferedImage.TYPE_INT_RGB);
                progress.setRGB(0,0,p[0],p[1],p,2,p[0]);ImageIO.write(progress,"png",new File(args[0]+".progress.png"));
            }
            if (p != null && "LOGIN_SCREEN".equals(status)) {
                BufferedImage img = new BufferedImage(p[0], p[1], BufferedImage.TYPE_INT_RGB);
                img.setRGB(0,0,p[0],p[1],p,2,p[0]);
                ImageIO.write(img,"png",new File(args[0]));
                System.out.println("MOBILE-HARNESS PASS: revision 184 login screen rendered");
                if(Boolean.getBoolean("kronos.testControls")) controlsTest(args[0]);
                if(Boolean.getBoolean("kronos.testInput")) inputTest(args[0]+".ingame.png");
                if(Boolean.getBoolean("kronos.testDialogs"))try{
                    input(7,1090,503,0,"");Thread.sleep(800);
                    inputTest(args[0]+".ingame.png");
                    MobileLayoutTest.dialogs(new File(args[0]).getParent());
                }catch(Throwable e){e.printStackTrace();System.exit(1);}
                if(Boolean.getBoolean("kronos.testRegistration"))try{registrationTest(args[0]+".registration.png");}catch(Throwable e){e.printStackTrace();System.exit(1);}
                System.exit(0);
            }
        }
        throw new IllegalStateException("Login screen was not reached: " + status);
    }
    private static void controlsTest(String screenshot) throws Exception {
        int[][] shapes={{894,503},{1090,503},{1230,503},{765,574}};
        for(int[] shape:shapes){
            input(7,shape[0],shape[1],0,"");
            long deadline=System.currentTimeMillis()+12000;
            while(System.currentTimeMillis()<deadline){
                int[] p=frame();if(p!=null&&p[0]==shape[0]&&p[1]==shape[1]&&client.isResized())break;
                Thread.sleep(100);
            }
            int[] p=frame();
            if(p==null||p[0]!=shape[0]||p[1]!=shape[1]||!client.isResized())
                throw new IllegalStateException("Adaptive resize failed: "+shape[0]+"x"+shape[1]+" actual="+(p==null?"none":p[0]+"x"+p[1]));
            Thread.sleep(300);
            BufferedImage img=new BufferedImage(p[0],p[1],BufferedImage.TYPE_INT_RGB);img.setRGB(0,0,p[0],p[1],p,2,p[0]);
            ImageIO.write(img,"png",new File(screenshot+"."+shape[0]+"x"+shape[1]+".png"));
        }
        int center=frame()[0]/2;
        input(0,center-80,291,1,"");Thread.sleep(60);input(1,center-80,291,1,"");Thread.sleep(500);
        if(client.getLoginIndex()!=2)throw new IllegalStateException("New User did not open login fields");
        input(8,center,249,0,"");Thread.sleep(150);
        input(9,0,0,1,"mobile_test");Thread.sleep(100);
        input(12,0,0,2,"");Thread.sleep(100);
        input(9,0,0,2,"wrong_letterX");Thread.sleep(100);
        input(9,0,0,2,"wrong_letter");Thread.sleep(100); // Delete last character
        input(9,0,0,2,"corrected");Thread.sleep(100); // Replace selection
        input(11,0,0,0,"");Thread.sleep(100);
        input(8,center,265,0,"");Thread.sleep(150);
        String[] editor=editor().split("\n",3);
        if(editor.length!=3||!editor[1].equals("2")||!editor[2].equals("corrected"))throw new IllegalStateException("Password edit/reopen failed");
        input(9,0,0,2,"");Thread.sleep(100);input(11,0,0,0,"");Thread.sleep(100);input(8,center,265,0,"");Thread.sleep(150);
        if(!editor().split("\n",3)[2].isEmpty())throw new IllegalStateException("Password clear failed");
        input(11,0,0,0,"");
        System.out.println("MOBILE-HARNESS PASS: four live aspect ratios; login field selection, password deletion, replacement, reopen and clear.");
    }

    private static void registrationTest(String screenshot) throws Exception {
        input(7,1090,503,0,"");
        long resizeDeadline=System.currentTimeMillis()+10000;
        while((frame()[0]!=1090||frame()[1]!=503)&&System.currentTimeMillis()<resizeDeadline)Thread.sleep(100);
        int center=frame()[0]/2;
        if(client.getLoginIndex()!=2){input(0,center-80,291,1,"");Thread.sleep(60);input(1,center-80,291,1,"");Thread.sleep(400);}
        String username=System.getProperty("kronos.testUser");
        String password=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("kronos.testPasswordFile"))),"UTF-8").trim();
        input(8,center,249,0,"");Thread.sleep(150);input(9,0,0,1,username);
        input(12,0,0,2,"");Thread.sleep(150);
        input(9,0,0,2,password+"X");Thread.sleep(100);input(9,0,0,2,password);
        input(10,0,0,10,"");
        long deadline=System.currentTimeMillis()+40000;
        while(System.currentTimeMillis()<deadline&&(client.getGameState()!=net.runelite.api.GameState.LOGGED_IN||client.getLocalPlayer()==null))Thread.sleep(100);
        if(client.getGameState()!=net.runelite.api.GameState.LOGGED_IN||client.getLocalPlayer()==null)throw new IllegalStateException("Fresh-account edited-password login failed: "+status);
        for(int[] shape:new int[][]{{1090,503},{894,503},{1230,503},{765,574}}){
            input(7,shape[0],shape[1],0,"");long until=System.currentTimeMillis()+10000;
            while((frame()[0]!=shape[0]||frame()[1]!=shape[1])&&System.currentTimeMillis()<until)Thread.sleep(100);
            Thread.sleep(500);int[] p=frame();
            if(p[0]!=shape[0]||p[1]!=shape[1])throw new IllegalStateException("In-game resize failed");
            BufferedImage img=new BufferedImage(p[0],p[1],BufferedImage.TYPE_INT_RGB);img.setRGB(0,0,p[0],p[1],p,2,p[0]);
            ImageIO.write(img,"png",new File(screenshot+"."+shape[0]+"x"+shape[1]+".png"));
        }
        if(Boolean.getBoolean("kronos.testMobileUi"))MobileLayoutTest.run(new File(screenshot).getParent()+"/mobile-ui");
        net.runelite.standalone.MobileLayoutProbe.dump(screenshot+".widgets.txt");
        System.out.println("MOBILE-HARNESS PASS: fresh regular account authenticated using corrected native-editor password; four in-game aspect ratios rendered.");
    }
    private static void testKey(int code) throws Exception {input(5,0,0,code,"");Thread.sleep(100);input(6,0,0,code,"");Thread.sleep(150);}
    private static void inputTest(String screenshot) throws Exception {
        int loginButton=frame()[0]/2+80;
        input(2,loginButton,291,0,"");input(0,loginButton,291,1,"");Thread.sleep(100);input(1,loginButton,291,1,"");Thread.sleep(600);
        String username=System.getProperty("kronos.testUser","mobileqa");
        String passwordFile=System.getProperty("kronos.testPasswordFile");
        String password=passwordFile==null?"a":new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(passwordFile)),"UTF-8").trim();
        input(4,0,0,0,username);Thread.sleep(200);testKey(KeyEvent.VK_TAB);input(4,0,0,0,password);Thread.sleep(200);testKey(KeyEvent.VK_ENTER);
        long deadline=System.currentTimeMillis()+40000;
        while(System.currentTimeMillis()<deadline) {
            Thread.sleep(1000);
            int[] p=frame();
            if(p!=null){BufferedImage img=new BufferedImage(p[0],p[1],BufferedImage.TYPE_INT_RGB);img.setRGB(0,0,p[0],p[1],p,2,p[0]);ImageIO.write(img,"png",new File(screenshot));}
            if(client.getGameState()==net.runelite.api.GameState.LOGGED_IN && client.getLocalPlayer()!=null){
                Thread.sleep(2000);
                p=frame();
                if(p==null)throw new IllegalStateException("No in-game frame");
                BufferedImage loggedIn=new BufferedImage(p[0],p[1],BufferedImage.TYPE_INT_RGB);
                loggedIn.setRGB(0,0,p[0],p[1],p,2,p[0]);ImageIO.write(loggedIn,"png",new File(screenshot));
                System.out.println("MOBILE-HARNESS PASS: touch + text + Tab + Enter authenticated "+username+" into Kronos at "+System.getProperty("kronos.host"));return;
            }
        }
        throw new IllegalStateException("Mobile input login failed: "+status);
    }
}
