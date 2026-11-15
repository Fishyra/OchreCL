package io.ochre.bridge;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.concurrent.Executor;
/// Captures only this test JVM's product texture after opening its settings surface on the game thread.
public final class OverlayCaptureAgent {
 public static final class Button { public int button(){return 0;} }
 private static double num(Class<?> overlay,String name)throws Exception {var f=overlay.getDeclaredField(name);f.setAccessible(true);return f.getDouble(null);}
 private static void set(Class<?> overlay,String name,Object value)throws Exception{var f=overlay.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 private static void testDrag(Object client,Class<?> overlay)throws Exception {
  Object window=client.getClass().getMethod("getWindow").invoke(client);double gui=((Number)window.getClass().getMethod("getGuiScaledWidth").invoke(window)).doubleValue(),screen=((Number)window.getClass().getMethod("getScreenWidth").invoke(window)).doubleValue();double scale=num(overlay,"uiScale"),x=num(overlay,"x"),w=num(overlay,"width");
  set(overlay,"pressed",true);set(overlay,"slider",0);set(overlay,"lastSliderApply",0L);double endpoint=x+12+Math.max(40,w/2-30)*.75;
  overlay.getMethod("move",Object.class,double.class,double.class).invoke(null,client,endpoint*scale*screen/gui,40d);
  overlay.getMethod("button",Object.class,Object.class,int.class).invoke(null,client,new Button(),0);
  var onGame=Class.forName("io.ochre.bridge.BridgeAgent").getDeclaredMethod("onGameThread",Object.class,java.util.Map.class);onGame.setAccessible(true);var actual=(java.util.Map<?,?>)onGame.invoke(null,client,java.util.Map.of("command","options.get"));
  if(Math.abs(Double.parseDouble(actual.get("soundCategory_master").toString())-.75)>.005)throw new AssertionError("Game option did not receive dragged value");
  var values=overlay.getDeclaredField("values");values.setAccessible(true);if(Math.abs(((double[])values.get(null))[0]-.75)>.005)throw new AssertionError("Release restored stale cursor value");
  System.out.println("OVERLAY_DRAG_VALUE_CONFIRMED_0.75");
 }
 public static void premain(String target,Instrumentation instrumentation){Thread worker=new Thread(()->{try{
  Class<?> agent=Class.forName("io.ochre.bridge.BridgeAgent");var clientMethod=agent.getDeclaredMethod("client");clientMethod.setAccessible(true);Class<?> overlay=Class.forName("io.ochre.bridge.GameOverlay");var frames=overlay.getDeclaredField("renderedFrames");frames.setAccessible(true);var expanded=overlay.getDeclaredField("expanded");expanded.setAccessible(true);var smooth=overlay.getDeclaredField("smooth");smooth.setAccessible(true);var image=Class.forName("io.ochre.bridge.SmoothSurface").getDeclaredField("image");image.setAccessible(true);
  var status=overlay.getDeclaredField("status");status.setAccessible(true);var bubbleFrames=overlay.getDeclaredField("bubbleFrames");bubbleFrames.setAccessible(true);while(bubbleFrames.getLong(null)==0)Thread.sleep(100);while(!"idle".equals(status.get(null)))Thread.sleep(100);Object client=clientMethod.invoke(null);while(!Files.exists(((java.io.File)client.getClass().getField("gameDirectory").get(client)).toPath().resolve("capture-ready")))Thread.sleep(100);((Executor)client).execute(()->{try{expanded.setBoolean(null,true); }catch(Exception e){throw new RuntimeException(e);}});
  long baseline=frames.getLong(null);while(frames.getLong(null)<baseline+4)Thread.sleep(100);
  var tested=new java.util.concurrent.CompletableFuture<Void>();((Executor)client).execute(()->{try{testDrag(client,overlay);VanillaOptionsProbe.run(client,overlay);set(overlay,"quick",true);set(overlay,"lastOption",0L);tested.complete(null);}catch(Throwable error){tested.completeExceptionally(error);}});tested.get(5,java.util.concurrent.TimeUnit.SECONDS);
  baseline=frames.getLong(null);while(frames.getLong(null)<baseline+3)Thread.sleep(100);
  ((Executor)client).execute(()->{try{javax.imageio.ImageIO.write((java.awt.image.BufferedImage)image.get(smooth.get(null)),"png",Path.of(target).toFile());Class.forName("net.minecraft.client.Screenshot",false,client.getClass().getClassLoader()).getMethod("grab",client.getClass(),boolean.class).invoke(null,client,false);System.out.println("OVERLAY_CAPTURE_OK");}catch(Exception e){e.printStackTrace();}});
 }catch(Exception error){error.printStackTrace();}},"test capture");worker.setDaemon(true);worker.start();}
}
