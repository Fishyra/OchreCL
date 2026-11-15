package io.ochre.bridge;

/// Uses the actual vanilla sound screen and slider class after an overlay change, not an inert widget mock.
public final class VanillaOptionsProbe {
 public static void run(Object client,Class<?> overlay)throws Exception {
  ClassLoader loader=client.getClass().getClassLoader();Class<?> screenType=Class.forName("net.minecraft.client.gui.screens.Screen",false,loader),optionType=Class.forName("net.minecraft.client.OptionInstance",false,loader),optionsType=Class.forName("net.minecraft.client.Options",false,loader);Object gui=client.getClass().getField("gui").get(client),parent=gui.getClass().getMethod("screen").invoke(gui),options=client.getClass().getField("options").get(client);
  Object screen=Class.forName("net.minecraft.client.gui.screens.options.SoundOptionsScreen",false,loader).getConstructor(screenType,optionsType).newInstance(parent,options);gui.getClass().getMethod("setScreen",screenType).invoke(gui,screen);
  var option=BridgeAgent.option(options,"soundCategory_master");option.getClass().getMethod("set",Object.class).invoke(option,.55);
  screen.getClass().getMethod("resetOption",optionType).invoke(screen,option);
  var base=Class.forName("net.minecraft.client.gui.screens.options.OptionsSubScreen",false,loader);var listField=base.getDeclaredField("list");listField.setAccessible(true);Object list=listField.get(screen);Object widget=list.getClass().getMethod("findOption",optionType).invoke(list,option);
  Class<?> sliderType=Class.forName("net.minecraft.client.gui.components.AbstractSliderButton",false,loader);var value=sliderType.getDeclaredField("value");value.setAccessible(true);if(Math.abs(value.getDouble(widget)-.55)>.005)throw new AssertionError("Vanilla widget did not synchronize external setting");
  double x=((Number)widget.getClass().getMethod("getX").invoke(widget)).doubleValue(),y=((Number)widget.getClass().getMethod("getY").invoke(widget)).doubleValue(),w=((Number)widget.getClass().getMethod("getWidth").invoke(widget)).doubleValue();
  Class<?> infoType=Class.forName("net.minecraft.client.input.MouseButtonInfo",false,loader),eventType=Class.forName("net.minecraft.client.input.MouseButtonEvent",false,loader);Object info=infoType.getConstructor(int.class,int.class).newInstance(0,0),start=eventType.getConstructor(double.class,double.class,infoType).newInstance(x+4+(w-8)*.2,y+10,info),finish=eventType.getConstructor(double.class,double.class,infoType).newInstance(x+4+(w-8)*.8,y+10,info);
  sliderType.getMethod("onClick",eventType,boolean.class).invoke(widget,start,false);var drag=sliderType.getDeclaredMethod("onDrag",eventType,double.class,double.class);drag.setAccessible(true);drag.invoke(widget,finish,(w-8)*.6,0d);sliderType.getMethod("onRelease",eventType).invoke(widget,finish);
  if(Math.abs(value.getDouble(widget)-.8)>.01||Math.abs(((Number)option.getClass().getMethod("get").invoke(option)).doubleValue()-.8)>.01)throw new AssertionError("Vanilla slider display and actual option diverged");
  gui.getClass().getMethod("setScreen",screenType).invoke(gui,parent);System.out.println("VANILLA_SLIDER_WIDGET_AND_OPTION_CONFIRMED_0.8");
 }
}
