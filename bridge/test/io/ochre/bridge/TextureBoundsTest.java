package io.ochre.bridge;
import java.util.Arrays;
/// Verifies the actual extractor argument order, not merely the CPU-side generated bitmap.
public final class TextureBoundsTest {
 public static final class Identifier { }
 public static final class Extractor {
  int[] bounds;float[] uv;
  public void blit(Identifier id,int x0,int y0,int x1,int y1,float u0,float u1,float v0,float v1){bounds=new int[]{x0,y0,x1,y1};uv=new float[]{u0,u1,v0,v1};}
 }
 public static void main(String[] args)throws Exception{Extractor extractor=new Extractor();SmoothSurface.blitCanvas(extractor,new Identifier(),427,240);if(!Arrays.equals(extractor.bounds,new int[]{0,0,427,240})||!Arrays.equals(extractor.uv,new float[]{0,1,0,1}))throw new AssertionError("Collapsed texture rectangle or UV range");System.out.println("PASS: nonzero on-screen texture bounds and complete UV ranges");}
}
