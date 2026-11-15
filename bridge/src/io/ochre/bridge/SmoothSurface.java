package io.ochre.bridge;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.util.Arrays;
import java.util.function.Supplier;

/// Supersampled, antialiased product UI uploaded as a private game texture; no changes to the game's GUI scale or fonts.
public final class SmoothSurface {
    private BufferedImage image; private Graphics2D graphics;
    private Object pixels, texture, identifier; private int previousHash; private boolean uploaded;
    private int scale, width, height; private ClassLoader loader;
    public Graphics2D begin(Object client, int w, int h) throws Exception {
        loader=client.getClass().getClassLoader(); int factor=Math.max(1,Math.min(4,(int)Math.sqrt(2_000_000.0/(w*h))));
        if(image==null||width!=w||height!=h||factor!=scale) {
            width=w;height=h;scale=factor;image=new BufferedImage(w*scale,h*scale,BufferedImage.TYPE_INT_ARGB);uploaded=false;
            Class<?> nativeType=type("com.mojang.blaze3d.platform.NativeImage"); Object next=nativeType.getConstructor(int.class,int.class,boolean.class).newInstance(w*scale,h*scale,true);
            Object nextTexture=type("net.minecraft.client.renderer.texture.DynamicTexture").getConstructor(Supplier.class,nativeType).newInstance((Supplier<String>)()->"Ochre overlay",next);
            if(identifier==null)identifier=type("net.minecraft.resources.Identifier").getMethod("parse",String.class).invoke(null,"ochre_bridge:overlay");
            Object manager=client.getClass().getMethod("getTextureManager").invoke(client);
            manager.getClass().getMethod("register",identifier.getClass(),type("net.minecraft.client.renderer.texture.AbstractTexture")).invoke(manager,identifier,nextTexture);
            texture=nextTexture;pixels=next;
        }
        graphics=image.createGraphics();graphics.setComposite(AlphaComposite.Clear);graphics.fillRect(0,0,image.getWidth(),image.getHeight());graphics.setComposite(AlphaComposite.SrcOver);
        graphics.scale(scale,scale);graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);graphics.setFont(new Font("SansSerif",Font.PLAIN,10));return graphics;
    }
    public void finish(Object extractor) throws Exception {
        graphics.dispose();int[] argb=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();int hash=Arrays.hashCode(argb);
        if(!uploaded||hash!=previousHash){ByteBuffer bytes=(ByteBuffer)pixels.getClass().getMethod("getPixelBytes").invoke(pixels);IntBuffer buffer=bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();for(int color:argb)buffer.put((color&0xff00ff00)|((color&255)<<16)|((color>>>16)&255));texture.getClass().getMethod("upload").invoke(texture);previousHash=hash;uploaded=true;}
        blitCanvas(extractor,identifier,width,height);
    }
    /// The public 26.2 overload takes x0,y0,x1,y1,u0,u1,v0,v1; UV endpoints must span both axes.
    static void blitCanvas(Object extractor,Object identifier,int width,int height)throws Exception {
        extractor.getClass().getMethod("blit",identifier.getClass(),int.class,int.class,int.class,int.class,float.class,float.class,float.class,float.class).invoke(extractor,identifier,0,0,width,height,0f,1f,0f,1f);
    }
    private Class<?> type(String name)throws Exception{return Class.forName(name,false,loader);}
}
