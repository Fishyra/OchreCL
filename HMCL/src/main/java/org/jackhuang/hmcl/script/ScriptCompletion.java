package org.jackhuang.hmcl.script;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/// Local source-aware completion; never executes source or sends it to a service.
@NotNullByDefault
public final class ScriptCompletion {
    /// One insertable symbol; offset places the caret inside arguments or a snippet condition.
    @NotNullByDefault public record Item(String name, String signature, String description, String insertion, int offset) { }
    /// A snapshot-bound replacement prevents stale popup acceptance from overwriting newer text.
    @NotNullByDefault public record Result(String source, int caret, int start, int end, @Unmodifiable List<Item> items) { }
    /// Exact documented builtins, loaded once from the bundled command reference.
    private static final @Unmodifiable Map<String,Item> BUILTINS = catalog();
    /// Approved game and settings properties, including typed nested record fields.
    private static final @Unmodifiable Map<String,String> FIELDS = fields();
    /// Typed declarations are useful while code is incomplete and cannot yet be parsed.
    private static final Pattern DECLARATION = Pattern.compile("\\b(int|float|bool|string|Record|Vec3|Player|List)\\s+([A-Za-z_][A-Za-z_0-9]*)(?:\\s*=\\s*([A-Za-z_][A-Za-z_0-9.]*))?");
    /// User functions retain their parameter names in signature help.
    private static final Pattern FUNCTION = Pattern.compile("\\bfunction\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*\\(([^)]*)\\)");
    /// Utility class.
    private ScriptCompletion() { }
    /// Loads readable signature metadata for all supported commands.
    private static @Unmodifiable Map<String,Item> catalog() {
        Map<String,Item> result=new LinkedHashMap<>();
        try(var input=Objects.requireNonNull(ScriptCompletion.class.getResourceAsStream("/assets/script-completions.json"));var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
            for(var value:JsonParser.parseReader(reader).getAsJsonArray()) {var row=value.getAsJsonObject();String name=row.get("name").getAsString(),signature=row.get("signature").getAsString();String insert=name+"()";result.put(name,new Item(name,signature,row.get("body").getAsString(),insert,signature.contains("()")?insert.length():insert.length()-1));}
        } catch(Exception error) {throw new ExceptionInInitializerError(error);}
        return Map.copyOf(result);
    }
    /// Defines only implemented fields; empty inferred records are deliberately not guessed.
    private static @Unmodifiable Map<String,String> fields() {
        Map<String,String> result=new LinkedHashMap<>();
        for(String path:List.of("game.language","world.day_time","world.game_time","player.position","player.rotation","player.health","player.inventory.slots","player.effects","player.recipes","player.states","player.inputs","player.vitals","player.container","game.screen","game.observation_status","player.hotbar","script.running","script_repeat_count"))result.put(path,"只读属性 · game.read（脚本状态除外）");
        add(result,"player.position","x y z");add(result,"player.rotation","yaw pitch");
        add(result,"player.inputs","forward backward left right jump sneak sprint attack use");
        add(result,"player.states","swimming sprinting crouching underwater fallflying on_ground flying may_fly jumping");
        add(result,"player.vitals","health max_health absorption armor air alive on_fire in_water in_lava freezing frozen_ticks fall_distance passenger food saturation xp_level xp_progress using_item use_ticks using_item_id use_animation");
        add(result,"player.container","open id type token slot_count");add(result,"game.screen","open type paused in_world");add(result,"game.observation_status","hooks sampling_hz retained_events event_page");
        add(result,"settings.audio","master music weather");add(result,"settings.video","render_distance max_fps gamma fov fullscreen");add(result,"body","eyes center feet right_arm left_arm");return Map.copyOf(result);
    }
    /// Registers fixed record members under a known root.
    private static void add(Map<String,String> map,String root,String members) {for(String member:members.split(" "))map.put(root+"."+member,"属性 · "+root);}
    /// Returns symbol names for contract tests and editor integrations.
    public static @Unmodifiable Set<String> builtins() {return BUILTINS.keySet();}
    /// Masks strings and line comments while preserving source offsets and newlines.
    private static String code(String source) {StringBuilder out=new StringBuilder(source);boolean string=false,escape=false,comment=false;for(int i=0;i<source.length();i++){char c=source.charAt(i);if(c=='\n'){comment=false;escape=false;continue;}if(comment){out.setCharAt(i,' ');continue;}if(string){out.setCharAt(i,' ');if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"'){string=true;out.setCharAt(i,' ');}else if(c=='#'||c=='/'&&i+1<source.length()&&source.charAt(i+1)=='/'){comment=true;out.setCharAt(i,' ');}}return out.toString();}
    /// Determines whether the caret is in a literal/comment, including an empty unfinished literal.
    private static boolean suppressed(String source,int caret) {return !code(source.substring(0,caret)+"@").endsWith("@");}
    /// Adds local variables, declared functions, and members of recognized initializer records.
    private static Map<String,Item> symbols(String source,int caret) {
        Map<String,Item> result=new LinkedHashMap<>(BUILTINS);FIELDS.forEach((name,description)->result.putIfAbsent(name,new Item(name,name,description,name,name.length())));
        String clean=code(source),before=clean.substring(0,caret);Matcher variables=DECLARATION.matcher(before);
        while(variables.find()){String name=variables.group(2);result.put(name,new Item(name,name,"局部变量 · "+variables.group(1),name,name.length()));@Nullable String initial=variables.group(3);String root=initial==null?"":initial;
            if(variables.group(1).equals("Vec3"))addMembers(result,name,"x y z");
            if(root.equals("player.snapshot")){addMembers(result,name,"in_world world position x y z yaw pitch slot item count swimming sprinting crouching underwater fallflying on_ground flying may_fly jumping inputs vitals container screen");for(var e:FIELDS.entrySet())for(String base:List.of("player.inputs","player.vitals","player.container","game.screen","player.position"))if(e.getKey().startsWith(base+".")){String alias=name+"."+base.substring(base.indexOf('.')+1)+e.getKey().substring(base.length());result.put(alias,new Item(alias,alias,"快照属性",alias,alias.length()));}}
            else if(root.equals("input.snapshot"))addMembers(result,name,"focused keys mouse_buttons position");
            else if(root.equals("world.target_block")||root.equals("world.block"))addMembers(result,name,"position world id state air solid replaceable loaded distance face");
            else if(root.equals("world.collision"))addMembers(result,name,"clear supported can_stand world distance");
            else if(root.equals("player.container_slots"))addMembers(result,name,"token total slots");
            for(var e:FIELDS.entrySet())if(e.getKey().startsWith(root+".")&&!root.isEmpty()){String alias=name+e.getKey().substring(root.length());result.put(alias,new Item(alias,alias,"属性 · "+root,alias,alias.length()));}
        }
        Matcher functions=FUNCTION.matcher(clean);while(functions.find()){String name=functions.group(1),signature=name+"("+functions.group(2).strip()+")";result.put(name,new Item(name,signature,"当前脚本中的函数",name+"()",name.length()+(functions.group(2).isBlank()?2:1)));}
        for(String keyword:List.of("int","float","bool","string","Record","Vec3","Player","List","true","false","none","return","import","function"))result.putIfAbsent(keyword,new Item(keyword,keyword,"语言关键字",keyword,keyword.length()));
        result.put("if",new Item("if","if (condition)","条件代码块","if () {\n    \n}",4));result.put("while",new Item("while","while (condition)","循环代码块","while () {\n    \n}",7));String loop="for (int i = 0; i < 10; i++) {\n    \n}";result.put("for",new Item("for","for (int i = 0; i < count; i++)","计数循环",loop,loop.indexOf('\n')+5));return result;
    }
    /// Registers a known local record's fields without guessing arbitrary host properties.
    private static void addMembers(Map<String,Item> result,String root,String names){for(String member:names.split(" ")){String name=root+"."+member;result.put(name,new Item(name,name,"记录字段",name,name.length()));}}
    /// Lists direct children of the current qualified prefix, ordered by exact prefix match.
    public static Result suggest(String source,int caret,boolean explicit) {
        if(caret<0||caret>source.length()||source.length()>65536||suppressed(source,caret))return new Result(source,caret,caret,caret,List.of());int start=caret,end=caret;while(start>0&&word(source.charAt(start-1)))start--;while(end<source.length()&&word(source.charAt(end)))end++;
        String prefix=source.substring(start,caret);if(prefix.isEmpty()&&!explicit)return new Result(source,caret,start,end,List.of());int dot=prefix.lastIndexOf('.');String parent=dot<0?"":prefix.substring(0,dot+1);Map<String,Item> candidates=new TreeMap<>();
        for(var entry:symbols(source,caret).entrySet()){String name=entry.getKey();if(!name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))continue;int next=name.indexOf('.',parent.length());if(next>=0){String root=name.substring(0,next);candidates.putIfAbsent(root,new Item(root,root,"命名空间 / 记录",root,root.length()));}else candidates.put(name,entry.getValue());}
        return new Result(source,caret,start,end,List.copyOf(candidates.values().stream().limit(60).toList()));
    }
    /// Accepts only an unchanged buffer and preserves an already typed call's parentheses.
    public static ScriptEditing.@Nullable Edit accept(Result result,Item item,String current,int caret) {
        if(!result.source.equals(current)||result.caret!=caret)return null;String insertion=item.insertion;int offset=item.offset;if(insertion.endsWith("()")&&result.end<current.length()&&current.charAt(result.end)=='('){insertion=item.name;offset=insertion.length();}
        String indent="";int line=current.lastIndexOf('\n',Math.max(0,result.start-1))+1;while(line+indent.length()<result.start&&current.charAt(line+indent.length())==' ')indent+=" ";int lineBreaks=0;for(int i=0;i<offset;i++)if(insertion.charAt(i)=='\n')lineBreaks++;offset+=indent.length()*lineBreaks;insertion=insertion.replace("\n","\n"+indent);return new ScriptEditing.Edit(result.start,result.end,insertion,result.start+offset,result.start+offset);
    }
    /// Finds the innermost open call for a signature hint while arguments are being typed.
    public static String signature(String source,int caret) {
        if(caret<0||caret>source.length()||source.length()>65536)return "";String clean=code(source);Deque<Integer> opens=new ArrayDeque<>();for(int i=0;i<caret;i++){if(clean.charAt(i)=='(')opens.push(i);else if(clean.charAt(i)==')'&&!opens.isEmpty())opens.pop();}if(opens.isEmpty())return "";int end=opens.peek(),start=end;while(start>0&&Character.isWhitespace(clean.charAt(start-1)))start--;end=start;while(start>0&&word(clean.charAt(start-1)))start--;@Nullable Item item=symbols(source,caret).get(clean.substring(start,end));return item==null?"":item.signature+" — "+item.description;
    }
    /// Qualified identifiers consist only of DSL identifier characters and member dots.
    private static boolean word(char c){return c=='.'||c=='_'||c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9';}
}
