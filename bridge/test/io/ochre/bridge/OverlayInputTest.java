package io.ochre.bridge;

/// Coordinate refresh and menu/drag transitions without a game process or graphics context.
public final class OverlayInputTest {
 public static final class Window { }
 public static final class Mouse { double x,y; boolean grabbed; public boolean isMouseGrabbed(){return grabbed;} public void grabMouse(){grabbed=true;} public double getScaledXPos(Window w){return x;} public double getScaledYPos(Window w){return y;} public static double getScaledXPos(Window w,double x){return x;} public static double getScaledYPos(Window w,double y){return y;} }
 public static final class Gui { public Object screen(){return this;} }
 public static final class Client { public final Mouse mouseHandler=new Mouse();public final Gui gui=new Gui();final Window window=new Window();public Window getWindow(){return window;} }
 public static final class Button { public int button(){return 0;} }
 private static void set(String field,Object value)throws Exception{var f=GameOverlay.class.getDeclaredField(field);f.setAccessible(true);f.set(null,value);}
 private static boolean flag(String field)throws Exception{var f=GameOverlay.class.getDeclaredField(field);f.setAccessible(true);return f.getBoolean(null);}
 public static void main(String[] args)throws Exception{
  Client c=new Client(); Button b=new Button();set("x",280d);set("y",140d);set("windowWidth",320);set("windowHeight",200);c.mouseHandler.x=296;c.mouseHandler.y=155;
  check(GameOverlay.button(c,b,1)&&GameOverlay.button(c,b,0),"click uses actual cursor, not stale render coordinates");check(flag("expanded"),"opens at screen edge");
  set("x",4d);set("y",20d);set("width",308d);set("height",170d);c.mouseHandler.x=30;c.mouseHandler.y=30;GameOverlay.button(c,b,1);GameOverlay.button(c,b,0);check(flag("expanded"),"header click does not close settings");
  c.mouseHandler.x=302;c.mouseHandler.y=32;GameOverlay.button(c,b,1);GameOverlay.button(c,b,0);check(!flag("expanded"),"dedicated close hitbox");
  set("expanded",true);set("width",280d);set("x",4d);set("y",20d);c.mouseHandler.x=319;c.mouseHandler.y=195;
  check(!GameOverlay.button(c,b,1),"native screen receives the outside press while dismissing overlay");check(!GameOverlay.move(c,250,100),"native dragging is never captured by overlay");check(!GameOverlay.button(c,b,0),"native screen receives the matching release");
  check(Math.abs(GameOverlay.anchoredStart(.94,1000,GameOverlay.BALL_SIZE)+GameOverlay.BALL_SIZE-GameOverlay.anchoredStart(.94,1000,280)-280)<.001,"right edge stays fixed while expanding left");
  check(GameOverlay.anchoredStart(.06,1000,GameOverlay.BALL_SIZE)==GameOverlay.anchoredStart(.06,1000,280),"left edge stays fixed while expanding right");
  set("vanillaDrag",false);set("pressed",false);set("status","permission");set("grantId","fixture");set("x",4d);set("y",20d);set("width",280d);set("height",184d);set("grantRows",4);set("grantPage",0);
  var items=GameOverlay.class.getDeclaredField("grants");items.setAccessible(true);((java.util.List<String>)items.get(null)).addAll(java.util.List.of("game.read","player.move"));
  c.mouseHandler.x=250;c.mouseHandler.y=60;GameOverlay.button(c,b,1);GameOverlay.button(c,b,0);var event=GameOverlay.class.getDeclaredField("event");event.setAccessible(true);check("grant:fixture:0".equals(event.get(null)),"permission checkbox publishes exact request and index");
  set("event","");c.mouseHandler.x=40;c.mouseHandler.y=190;GameOverlay.button(c,b,1);GameOverlay.button(c,b,0);check("".equals(event.get(null)),"cannot approve unchecked permissions");
  var checked=GameOverlay.class.getDeclaredField("checkedGrants");checked.setAccessible(true);((java.util.Set<Integer>)checked.get(null)).addAll(java.util.List.of(0,1));GameOverlay.button(c,b,1);GameOverlay.button(c,b,0);check("grant:fixture:approve".equals(event.get(null)),"all checks enable approval");
  System.out.println("PASS: actual cursor refresh, edge expansion and precise close hitbox");
 }
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
