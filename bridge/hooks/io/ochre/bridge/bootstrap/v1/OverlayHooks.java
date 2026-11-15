package io.ochre.bridge.bootstrap.v1;

import java.lang.invoke.MethodHandle;

/// JDK-only callbacks shared by isolated game loaders; no game, ASM or loader classes live here.
public final class OverlayHooks {
    private static volatile MethodHandle render, button, move, observe, input;
    private static volatile boolean failed;
    private OverlayHooks() { }
    /// The consenting agent binds its own helpers; class loading never loads a second copy of the agent.
    public static void configure(MethodHandle draw, MethodHandle click, MethodHandle drag) {
        render = draw; button = click; move = drag; failed = false;
    }
    /// Passive callbacks have no return value and cannot consume a game event.
    public static void configureObservation(MethodHandle action, MethodHandle pointer) { observe = action; input = pointer; }
    public static void action(Object source, Object data, Object extra, String kind) { MethodHandle callback = observe; if(callback != null) try { callback.invokeExact(source,data,extra,kind); } catch(Throwable ignored) { } }
    public static void scroll(Object client, double x, double y) { pointer(client,x,y,1); }
    private static void pointer(Object client,double x,double y,int kind) { MethodHandle callback=input; if(callback != null) try { callback.invokeExact(client,x,y,kind); } catch(Throwable ignored) { } }
    /// Optional rendering must never stop vanilla or a loader from completing its own rendering.
    public static void render(Object client, Object state) {
        MethodHandle callback = render; if (failed || callback == null) return;
        try { callback.invokeExact(client, state); } catch (Throwable error) { disable(error); }
    }
    /// False preserves vanilla input whenever an optional callback is unavailable.
    public static boolean button(Object client, Object info, int action) {
        action(client,info,null,"mouse_button:"+action); MethodHandle callback = button; if (failed || callback == null) return false;
        try { return (boolean) callback.invokeExact(client, info, action); } catch (Throwable error) { disable(error); return false; }
    }
    /// Mouse movement outside the overlay keeps following the game's ordinary input path.
    public static boolean move(Object client, double x, double y) {
        pointer(client,x,y,0); MethodHandle callback = move; if (failed || callback == null) return false;
        try { return (boolean) callback.invokeExact(client, x, y); } catch (Throwable error) { disable(error); return false; }
    }
    private static void disable(Throwable error) { if (!failed) System.err.println("[OchreBridge] Overlay callbacks disabled: " + error.getClass().getSimpleName()); failed = true; }
}
