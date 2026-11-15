package io.ochre.bridge;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/// Draggable in-game overlay drawn after vanilla menus, using ordinary client setting APIs.
public final class GameOverlay {
    /// Product-sized idle ball; the expanded panel uses independent dimensions.
    static final int BALL_SIZE = 58;
    private static boolean quick, vanillaDrag;
    private static boolean[] switches = new boolean[4];
    private static final String[] QUICK = {"fov", "sensitivity", "bobView", "showSubtitles", "autoJump", "toggleSprint"};
    private static final String[] QUICK_LABELS = {"视野 FOV", "鼠标灵敏度", "视角摇晃", "声音字幕", "自动跳跃", "切换疾跑"};
    private static boolean failed, expanded, library, pressed, dragged, pointerReleased;
    private static int page, total, theme = 0xffeedab5, slider = -1;
    private static double nx = .94, ny = .82, x, y, width = BALL_SIZE, height = BALL_SIZE, mx, my, pressX, pressY, startX, startY;
    private static double uiScale = 1;
    private static long lastSliderApply;
    private static int windowWidth = 640, windowHeight = 400;
    private static long lastFrame, completedUntil, lastOption, renderedFrames;
    private static String status = "idle", current = "", event = "", lastStatus = "idle";
    private static final List<String> scripts = new ArrayList<>();
    private static final String[] OPTIONS = {"soundCategory_master", "renderDistance", "soundCategory_music", "maxFps", "soundCategory_weather", "gamma"};
    private static final String[] LABELS = {"主音量", "渲染距离", "音乐", "最大帧率", "天气", "亮度"};
    private static final double[] values = {.5, 12, .5, 120, 1, .5};
    private static Path preferences;
    private static Method fill, text;
    private static Object font;
    private static Method fontWidth;
    private static final SpeechBubble bubble = new SpeechBubble();
    private static long bubbleFrames;
    private static final SmoothSurface smooth = new SmoothSurface();
    private static java.awt.Graphics2D paint;
    private static final List<String> grants = new ArrayList<>();
    private static final Set<Integer> checkedGrants = new HashSet<>();
    private static String grantId = "";
    private static int grantPage, grantRows = 6;
    private static int ink=0xff39362f, card=0xfffaf6ec, accent=0xff80633d;
    private GameOverlay() { }
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
    private static Object field(Object target, String name) throws Exception { return target.getClass().getField(name).get(target); }
    /// Position is private per-instance data, not code or a network address.
    public static void initialize(Path file, String color) { preferences = file; try { if (Files.isRegularFile(file) && Files.size(file) < 4096) { Properties p = new Properties(); try (var input = Files.newInputStream(file)) { p.load(input); } nx = Math.max(0, Math.min(1, Double.parseDouble(p.getProperty("x", ".94")))); ny = Math.max(0, Math.min(1, Double.parseDouble(p.getProperty("y", ".82")))); } } catch (Exception ignored) { } color(color); }
    private static int parseColor(String value,int fallback) { try{return value.matches("#[a-fA-F0-9]{6}")?0xff000000|Integer.parseInt(value.substring(1),16):fallback;}catch(Exception error){return fallback;} }
    private static void color(String color) { try { if (color.matches("#[a-fA-F0-9]{6}")) theme = 0xff000000 | Integer.parseInt(color.substring(1), 16); } catch (Exception ignored) { } }
    public static void palette(Map<String,String> request) { color(request.getOrDefault("theme", "")); ink=parseColor(request.getOrDefault("ink",""),ink); card=parseColor(request.getOrDefault("card",""),card); accent=parseColor(request.getOrDefault("accent",""),accent); }
    /// Returns one consumed UI event; arbitrary file paths are never accepted by the launcher.
    public static void sync(Map<String, String> request, Map<String, String> response) throws Exception {
        if (!OverlayTransformer.gui || !OverlayTransformer.mouse || failed) throw new java.io.IOException("overlay.adapter.unavailable");
        if ("true".equals(request.get("resetPage"))) page = 0;
        palette(request); status = request.getOrDefault("status", "idle"); current = request.getOrDefault("statement", ""); if (current.length() > 180) current = current.substring(0, 180);
        if (status.equals("success") && !lastStatus.equals("success")) completedUntil = System.nanoTime() + 5_000_000_000L;
        if (!grantId.equals(request.getOrDefault("grant_id", ""))) grantPage = 0; grantId = request.getOrDefault("grant_id", ""); grants.clear(); checkedGrants.clear(); int grantCount = Math.max(0, Math.min(32, Integer.parseInt(request.getOrDefault("grant_count", "0")))); for (int i = 0; i < grantCount; i++) { grants.add(request.getOrDefault("grant." + i, "")); if ("true".equals(request.get("grant_checked." + i))) checkedGrants.add(i); }
        lastStatus = status;
        total = Math.max(0, Math.min(4096, Integer.parseInt(request.getOrDefault("total", "0"))));
        if (Integer.toString(page).equals(request.getOrDefault("page", "0"))) { scripts.clear(); int count = Math.max(0, Math.min(8, Integer.parseInt(request.getOrDefault("count", "0")))); for (int i = 0; i < count; i++) { String value = request.getOrDefault("script." + i, ""); if (value.length() <= 160 && !value.contains("/") && !value.contains("\\") && (value.endsWith(".ochre") || value.endsWith(".txt"))) scripts.add(value); } }
        response.put("page", Integer.toString(page)); response.put("event", event); event = ""; response.put("overlay", "ready"); response.put("frames", Long.toString(renderedFrames)); response.put("bubbleVisible", Boolean.toString(bubble.visible(System.nanoTime()))); response.put("bubbleFrames", Long.toString(bubbleFrames));
    }
    /// Authenticated local UI output is separate from world commands and current-statement status.
    public static void say(Map<String, String> request) throws Exception {
        if (!OverlayTransformer.gui || !OverlayTransformer.mouse || failed) throw new java.io.IOException("overlay.adapter.unavailable");
        bubble.show(request.getOrDefault("text", ""), Double.parseDouble(request.getOrDefault("duration", "3")), System.nanoTime());
    }
    private static void fail(Throwable error) { if (!failed) System.err.println("[OchreBridge] Overlay disabled safely: " + error); failed = true; }
    private static void rect(Object graphics, int left, int top, int right, int bottom, int color) throws Exception { if (right > left && bottom > top) { paint.setColor(new java.awt.Color(color,true)); paint.fill(new java.awt.geom.Rectangle2D.Double(left,top,right-left,bottom-top)); } }
    private static void rounded(Object graphics, int w, int h, int radius, int color) throws Exception { roundedAt(graphics, (int)x, (int)y, w, h, radius, color); }
    private static void roundedAt(Object graphics,int left,int top,int w,int h,int radius,int color) { paint.setColor(new java.awt.Color(color,true)); paint.fill(new java.awt.geom.RoundRectangle2D.Double(left,top,w,h,radius*2,radius*2)); }
    private static void line(double ax,double ay,double bx,double by,int color,float thickness) { paint.setColor(new java.awt.Color(color,true));paint.setStroke(new java.awt.BasicStroke(thickness,java.awt.BasicStroke.CAP_ROUND,java.awt.BasicStroke.JOIN_ROUND));paint.draw(new java.awt.geom.Line2D.Double(ax,ay,bx,by)); }
    private static void cube() { double cx=x+width/2,cy=y+height/2;line(cx,cy-8,cx+7,cy-4,ink,1.4f);line(cx+7,cy-4,cx+7,cy+5,ink,1.4f);line(cx+7,cy+5,cx,cy+9,ink,1.4f);line(cx,cy+9,cx-7,cy+5,ink,1.4f);line(cx-7,cy+5,cx-7,cy-4,ink,1.4f);line(cx-7,cy-4,cx,cy-8,ink,1.4f);line(cx-7,cy-4,cx,cy,ink,1.4f);line(cx+7,cy-4,cx,cy,ink,1.4f);line(cx,cy,cx,cy+9,ink,1.4f); }
    /// The bubble follows the moving surface without changing its click or drag bounds.
    private static void renderBubble(Object graphics, long now) throws Exception {
        if (!bubble.visible(now)) return;
        int textWidth = Math.max(8, Math.min(260, windowWidth-40));
        int room = (int)Math.max(y-14, windowHeight-y-height-14), limit = Math.max(1, Math.min(8, (room-16)/11));
        java.util.function.ToIntFunction<String> measure = value -> { try { return paint.getFontMetrics().stringWidth(value); } catch (Exception error) { throw new IllegalStateException(error); } };
        List<String> lines = bubble.lines(measure, textWidth, limit); int w = Math.max(44, lines.stream().mapToInt(measure).max().orElse(0)+24), h = 16+lines.size()*11;
        boolean above = y >= h+14; int left = (int)Math.max(4, Math.min(windowWidth-w-4, x+width/2-w/2));
        int top = (int)Math.max(4, Math.min(windowHeight-h-4, above ? y-h-10 : y+height+10));
        top = Math.min(windowHeight-h-4, top+(int)Math.round((1-bubble.entrance(now))*6));
        double fade = bubble.opacity(now); if (fade < .02) return; int background = ((int)(255*fade)<<24) | (card&0xffffff), bubbleInk = ((int)(255*fade)<<24) | (ink&0xffffff);
        roundedAt(graphics,left,top,w,h,10,background); int anchor = (int)Math.max(left+12, Math.min(left+w-12,x+width/2));
        for (int row=0;row<6;row++) { int inset=above?row:5-row, ty=above?top+h+row:top-6+row; rect(graphics,anchor-6+inset,ty,anchor+6-inset,ty+1,background); }
        for (int i=0;i<lines.size();i++) drawText(graphics,lines.get(i),left+12,top+8+i*11,bubbleInk);
        bubbleFrames++;
    }
    /// Runs on the extraction thread; errors disable only this optional surface, not the game.
    public static void render(Object client, Object renderState) {
        if (failed) return;
        try {
            Object window = call(client, "getWindow"), mouse = field(client,"mouseHandler"); int guiWidth=((Number)call(window,"getGuiScaledWidth")).intValue(), guiHeight=((Number)call(window,"getGuiScaledHeight")).intValue(); uiScale=Math.max(.125,Math.min(1,2.0*guiWidth/((Number)call(window,"getWidth")).doubleValue())); windowWidth=(int)(guiWidth/uiScale);windowHeight=(int)(guiHeight/uiScale);
            boolean screen = call(field(client,"gui"),"screen") != null;
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW",false,client.getClass().getClassLoader()); long handle = ((Number)call(window,"handle")).longValue();
            boolean alt = ((Number)glfw.getMethod("glfwGetKey",long.class,int.class).invoke(null,handle,342)).intValue()==1 || ((Number)glfw.getMethod("glfwGetKey",long.class,int.class).invoke(null,handle,346)).intValue()==1;
            if (!screen && alt && (Boolean)call(mouse,"isMouseGrabbed")) { call(mouse,"releaseMouse"); pointerReleased=true; }
            // Releasing Alt keeps the pointer available until an explicit outside click or close.
            if (!pressed) { mx = ((Number)mouse.getClass().getMethod("getScaledXPos",window.getClass()).invoke(mouse,window)).doubleValue()/uiScale; my = ((Number)mouse.getClass().getMethod("getScaledYPos",window.getClass()).invoke(mouse,window)).doubleValue()/uiScale; }
            paint=smooth.begin(client,guiWidth,guiHeight); paint.scale(uiScale,uiScale);
            renderedFrames++;
            grantRows=Math.max(1,Math.min(6,(windowHeight-108)/22)); grantPage=Math.min(grantPage,Math.max(0,(grants.size()-1)/grantRows)); boolean granting=status.equals("permission"); boolean running=status.equals("running")||status.equals("paused"); double tw=granting?Math.min(280,windowWidth-12):running?Math.min(280,windowWidth-12):expanded?Math.min(280,windowWidth-12):BALL_SIZE, th=granting?Math.min(windowHeight-12,96+Math.min(grantRows,grants.size())*22):running?66:expanded?(library?Math.min(246,72+scripts.size()*20):170):BALL_SIZE;
            long now=System.nanoTime(); double ease=lastFrame==0?1:Math.min(1,(now-lastFrame)/1e9*14); lastFrame=now; width+=(tw-width)*ease; height+=(th-height)*ease;
            if (!pressed) { x=anchoredStart(nx,windowWidth,width); y=anchoredStart(ny,windowHeight,height); }
            x=Math.max(4,Math.min(x,windowWidth-width-4)); y=Math.max(4,Math.min(y,windowHeight-height-4));
            ClassLoader loader=client.getClass().getClassLoader(); Class<?> graphicsType=Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor",false,loader);
            Object graphics=graphicsType.getConstructor(client.getClass(),renderState.getClass(),int.class,int.class).newInstance(client,renderState,(int)mx,(int)my); call(graphics,"nextStratum");
            if(fill==null) { fill=graphicsType.getMethod("fill",int.class,int.class,int.class,int.class,int.class); font=field(client,"font"); text=graphicsType.getMethod("text",font.getClass(),String.class,int.class,int.class,int.class,boolean.class); fontWidth=font.getClass().getMethod("width",String.class); }
            rounded(graphics,(int)width,(int)height,Math.min((expanded||running||granting)?18:BALL_SIZE/2,(int)height/2),(expanded||running||granting)?card:theme);
            if (granting && width>160) { drawText(graphics,"运行脚本所需权限",x+12,y+10,ink); for(int row=0;row<grantRows && grantPage*grantRows+row<grants.size();row++) { int i=grantPage*grantRows+row; double yy=y+34+row*22, bx=x+width-28; drawText(graphics,shorten(grants.get(i),(int)(width-60)/6),x+12,yy,ink); line(bx,yy,bx+12,yy,ink,1.2f);line(bx+12,yy,bx+12,yy+12,ink,1.2f);line(bx+12,yy+12,bx,yy+12,ink,1.2f);line(bx,yy+12,bx,yy,ink,1.2f);if(checkedGrants.contains(i)){line(bx+2,yy+6,bx+5,yy+9,accent,1.5f);line(bx+5,yy+9,bx+10,yy+2,accent,1.5f);} } drawText(graphics,"‹ 上一页    "+(grantPage+1)+" / "+Math.max(1,(grants.size()+grantRows-1)/grantRows)+"    下一页 ›",x+12,y+height-43,ink); drawText(graphics,"授权并运行",x+12,y+height-20,checkedGrants.size()==grants.size()?accent:0xff888888);drawText(graphics,"取消",x+width-42,y+height-20,ink); }
            else if (running && width>160) { drawText(graphics,status.equals("paused")?"已暂停 · 继续              停止":"脚本运行中 · 停止",x+12,y+10,ink); drawText(graphics,shorten(current,(int)(width-24)/6),x+12,y+31,ink); }
            else if (expanded && width>180 && height>80) {
                paint.setFont(paint.getFont().deriveFont(java.awt.Font.BOLD,11f));
                if(library) drawText(graphics,"脚本库",x+14,y+10,ink); else { drawText(graphics,quick?"视角与操作":"声音",x+30,y+10,ink);if(!quick)drawText(graphics,"视频",x+width/2+30,y+10,ink); line(x+13,y+14,x+19,y+11,ink,1.3f);line(x+19,y+11,x+19,y+22,ink,1.3f);line(x+19,y+22,x+13,y+19,ink,1.3f);line(x+13,y+14,x+13,y+19,ink,1.3f);paint.setColor(new java.awt.Color(ink,true));paint.draw(new java.awt.geom.Arc2D.Double(x+17,y+12,10,10,-60,120,java.awt.geom.Arc2D.OPEN));double vx=x+width/2+12;line(vx,y+12,vx+12,y+12,ink,1.3f);line(vx+12,y+12,vx+12,y+21,ink,1.3f);line(vx+12,y+21,vx,y+21,ink,1.3f);line(vx,y+21,vx,y+12,ink,1.3f); }
                paint.setFont(paint.getFont().deriveFont(java.awt.Font.PLAIN,10f));line(x+width-21,y+12,x+width-15,y+18,ink,1.4f);line(x+width-15,y+12,x+width-21,y+18,ink,1.4f);
                if (library) { for(int i=0;i<scripts.size();i++) { drawText(graphics,shorten(scripts.get(i),(int)(width-50)/6),x+12,y+36+i*20,ink); drawText(graphics,"▶",x+width-26,y+36+i*20,ink); } drawText(graphics,"‹ 上一页       "+(page+1)+"       下一页 ›",x+12,y+height-18,ink); }
                else { if(!vanillaDrag && now-lastOption>150_000_000L) { Map<String,String> options=BridgeAgent.onGameThread(client,Map.of("command","options.get")); for(int i=0;i<6;i++) if(i!=slider && options.containsKey(quick?QUICK[i]:OPTIONS[i])) { String v=options.get(quick?QUICK[i]:OPTIONS[i]); if(quick&&i>=2)switches[i-2]=Boolean.parseBoolean(v);else values[i]=Double.parseDouble(v); } lastOption=now; }
                    for(int i=0;i<6;i++) { int column=i%2,row=i/2; double bx=x+12+column*(width/2),by=y+36+row*34, length=Math.max(40,width/2-30); if(quick&&i>=2) { drawText(graphics,QUICK_LABELS[i],bx,by,ink);roundedAt(graphics,(int)(bx+length-28),(int)by+13,28,13,6,switches[i-2]?accent:0x33716b5f);paint.setColor(new java.awt.Color(switches[i-2]?card:ink,true));paint.fill(new java.awt.geom.Ellipse2D.Double(bx+length-(switches[i-2]?12:26),by+15,9,9));continue;} String value=quick?(i==0?Integer.toString((int)Math.round(70+40*values[i])):String.format(Locale.ROOT,"%.0f%%",values[i]*200)):i==1||i==3?Integer.toString((int)values[i]):String.format(Locale.ROOT,"%.2f",values[i]); drawText(graphics,quick?QUICK_LABELS[i]:LABELS[i],bx,by,ink); drawText(graphics,value,bx+length-paint.getFontMetrics().stringWidth(value),by,ink); double ratio=Math.max(0,Math.min(1,quick?(i==0?(values[i]+1)/2:values[i]):i==1?(values[i]-2)/30:i==3?(values[i]-10)/250:values[i])); line(bx,by+17,bx+length,by+17,0x22716b5f,1.4f);line(bx,by+17,bx+ratio*length,by+17,accent,1.4f); paint.setColor(new java.awt.Color(accent,true));paint.fill(new java.awt.geom.Ellipse2D.Double(bx+ratio*length-3.8,by+13.2,7.6,7.6)); }
                    drawText(graphics,"运行脚本 →",x+12,y+height-18,ink);drawText(graphics,quick?"声音 / 视频":"快捷设置 →",x+width/2,y+height-18,ink);
                }
            } else if(width<90) { var iconTransform=paint.getTransform(); paint.translate(x+width/2,y+height/2);paint.scale(1.5,1.5);paint.translate(-x-width/2,-y-height/2); if(status.equals("warning")) { double cx=x+width/2,cy=y+height/2;line(cx,cy-10,cx-10,cy+8,ink,1.8f);line(cx-10,cy+8,cx+10,cy+8,ink,1.8f);line(cx+10,cy+8,cx,cy-10,ink,1.8f);line(cx,cy-4,cx,cy+1,ink,1.8f);line(cx,cy+4,cx,cy+5,ink,1.8f); } else if(now<completedUntil) { line(x+width/2-7,y+height/2,x+width/2-2,y+height/2+5,ink,2);line(x+width/2-2,y+height/2+5,x+width/2+8,y+height/2-6,ink,2); } else cube(); paint.setTransform(iconTransform); }
            renderBubble(graphics, now); smooth.finish(graphics);
        } catch(Throwable error) { fail(error); }
    }
    private static void drawText(Object graphics,String value,double px,double py,int color)throws Exception { paint.setColor(new java.awt.Color(color,true));paint.drawString(value,(float)px,(float)py+9); }
    private static String shorten(String value,int length) { return value.length()>Math.max(1,length)?value.substring(0,Math.max(1,length))+"…":value; }
    /// Keeps the edge nearest the ball fixed while its surface grows toward the available space.
    static double anchoredStart(double fraction,int viewport,double extent) { double start=4+fraction*Math.max(0,viewport-BALL_SIZE-8); if(fraction>=.5)start+=BALL_SIZE-extent;return Math.max(4,Math.min(start,viewport-extent-4)); }
    private static boolean contains(double px,double py) { return px>=x&&px<=x+width&&py>=y&&py<=y+height; }
    /// Consumes only overlay interactions; existing screens retain all other input.
    public static boolean button(Object client,Object info,int action) {
        if(failed) return false;
        try { Object window=call(client,"getWindow"), mouse=field(client,"mouseHandler"); if(!pressed) { mx=((Number)mouse.getClass().getMethod("getScaledXPos",window.getClass()).invoke(mouse,window)).doubleValue()/uiScale; my=((Number)mouse.getClass().getMethod("getScaledYPos",window.getClass()).invoke(mouse,window)).doubleValue()/uiScale; } if (!pressed && (Boolean)call(field(client,"mouseHandler"),"isMouseGrabbed")) return false; int button=((Number)call(info,"button")).intValue(); if(button!=0) return false; if(action==0 && vanillaDrag){vanillaDrag=false;return false;}
            if(action==1) { if(!contains(mx,my)) { vanillaDrag=true;if(expanded || pointerReleased) { expanded=false;library=false;pointerReleased=false;if(call(field(client,"gui"),"screen")!=null)return false;restoreMouse(client);return true;} return false; } pressed=true;dragged=false;pressX=mx;pressY=my;startX=x;startY=y;
                if(expanded&&!library&&my>=y+44&&my<y+139) { int row=(int)((my-y-36)/34),column=mx>x+width/2?1:0; if(row>=0&&row<3) { int index=row*2+column; if(quick&&index>=2){switches[index-2]=!switches[index-2];BridgeAgent.onGameThread(client,Map.of("command","options.set","option."+QUICK[index],Boolean.toString(switches[index-2])));refreshOptionsScreen(client,QUICK[index]);}else {slider=index; lastSliderApply=0; adjust(client,false);} } } return true;
            }
            if(action==0&&pressed) { pressed=false; if(slider>=0) { int finished=slider;adjust(client,true);slider=-1;refreshOptionsScreen(client,quick?QUICK[finished]:OPTIONS[finished]);return true; } if(dragged) { nx=Math.max(0,Math.min(1,((nx>=.5?x+width-BALL_SIZE:x)-4)/Math.max(1,windowWidth-BALL_SIZE-8)));ny=Math.max(0,Math.min(1,((ny>=.5?y+height-BALL_SIZE:y)-4)/Math.max(1,windowHeight-BALL_SIZE-8)));savePosition();return true; }
                if(status.equals("permission")) { if(my>=y+height-27)event=mx>x+width-65?"stop":checkedGrants.size()==grants.size()?"grant:"+grantId+":approve":"";else if(my>=y+height-52){grantPage=Math.max(0,Math.min(Math.max(0,(grants.size()-1)/grantRows),grantPage+(mx>x+width/2?1:-1)));}else {int i=grantPage*grantRows+(int)((my-y-34)/22);if(my>=y+34&&i<grants.size())event="grant:"+grantId+":"+i;}return true; }
                if(status.equals("running")||status.equals("paused")) { event=status.equals("paused")&&mx<x+width-65?"resume":"stop";return true; }
                if(!expanded) { expanded=true;library=false;pointerReleased=true;return true; }
                if(my<y+28 && mx>x+width-36) { expanded=false;library=false;restoreMouse(client);return true; }
                if(library) { int row=(int)((my-y-32)/20);if(my>y+height-26) { if(mx<x+width/2)page=Math.max(0,page-1);else if((page+1)*8<total)page++; } else if(row>=0&&row<scripts.size()) { event="run:"+scripts.get(row);expanded=false;library=false;restoreMouse(client); } }
                else if(my>y+height-28) { if(mx>x+width/2){quick=!quick;lastOption=0;}else library=true; }
                return true;
            }
        } catch(Throwable error) { fail(error); } return false;
    }
    /// Dragging never forwards a camera movement or inventory click to vanilla.
    public static boolean move(Object client,double rawX,double rawY) {
        if(failed || vanillaDrag)return false;
        try { Object window=call(client,"getWindow"),mouse=field(client,"mouseHandler");mx=((Number)mouse.getClass().getMethod("getScaledXPos",window.getClass(),double.class).invoke(null,window,rawX)).doubleValue()/uiScale;my=((Number)mouse.getClass().getMethod("getScaledYPos",window.getClass(),double.class).invoke(null,window,rawY)).doubleValue()/uiScale;
            if(!pressed)return false;if(slider>=0){adjust(client,false);return true;} if(Math.hypot(mx-pressX,my-pressY)>3)dragged=true;if(dragged){x=Math.max(4,Math.min(windowWidth-width-4,startX+mx-pressX));y=Math.max(4,Math.min(windowHeight-height-4,startY+my-pressY));}return true;
        }catch(Throwable error){fail(error);return false;}
    }
    private static void restoreMouse(Object client) throws Exception { if(call(field(client,"gui"),"screen")==null) call(field(client,"mouseHandler"),"grabMouse"); pointerReleased=false; }
    private static void adjust(Object client,boolean commit)throws Exception {
        if(slider<0)return;int column=slider%2;double begin=x+12+column*width/2,ratio=Math.max(0,Math.min(1,(mx-begin)/Math.max(40,width/2-30)));
        double desired=quick?(slider==0?ratio*2-1:ratio):slider==1?Math.rint(2+ratio*30):slider==3?Math.rint(10+ratio*250):ratio;
        long now=System.nanoTime(); if(!commit&&now-lastSliderApply<50_000_000L){values[slider]=desired;return;}
        BridgeAgent.onGameThread(client,Map.of("command","options.set","transient",Boolean.toString(!commit),"option."+(quick?QUICK[slider]:OPTIONS[slider]),Double.toString(desired)));
        var actual=BridgeAgent.onGameThread(client,Map.of("command","options.get"));double confirmed=Double.parseDouble(actual.get(quick?QUICK[slider]:OPTIONS[slider]));
        if(Math.abs(confirmed-desired)>.005)throw new IllegalStateException("Option change rejected by game: "+OPTIONS[slider]);
        values[slider]=confirmed;lastSliderApply=now;
    }
    /// Vanilla option widgets cache their value; refresh them only after committing an overlay drag.
    private static void refreshOptionsScreen(Object client,String name) {
        try { Object screen=call(field(client,"gui"),"screen"); if(screen==null)return;Class<?> base=Class.forName("net.minecraft.client.gui.screens.options.OptionsSubScreen",false,client.getClass().getClassLoader());if(!base.isInstance(screen))return;
            Object options=field(client,"options");Object option=BridgeAgent.option(options,name);base.getMethod("resetOption",Class.forName("net.minecraft.client.OptionInstance",false,client.getClass().getClassLoader())).invoke(screen,option);
        }catch(Exception ignored){ }
    }
    private static void savePosition(){if(preferences==null)return;try{Properties p=new Properties();p.setProperty("x",Double.toString(nx));p.setProperty("y",Double.toString(ny));try(var output=Files.newOutputStream(preferences)){p.store(output,"Game overlay position");}}catch(Exception ignored){}}
}
