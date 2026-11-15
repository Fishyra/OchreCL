package org.jackhuang.hmcl.script;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/// Completion contracts protect real commands, cursor ranges and literal/comment contexts.
@NotNullByDefault public final class ScriptCompletionTest {
    /// The catalog covers exactly the runtime's registered functions.
    @Test public void catalog()throws Exception {var field=OchreScript.class.getDeclaredField("CALLS");field.setAccessible(true);var expected=new HashSet<Object>(((Map<?,?>)field.get(null)).keySet());expected.addAll(List.of("print","sleep","say","vec3","script.logs"));assertEquals(expected,ScriptCompletion.builtins());}
    /// Qualified input lists direct children, with local snapshot record fields and user function signatures.
    @Test public void symbols(){var items=ScriptCompletion.suggest("player.",7,false).items();assertTrue(items.stream().anyMatch(i->i.name().equals("player.jump")));assertTrue(items.stream().anyMatch(i->i.name().equals("player.inputs")));assertTrue(items.stream().noneMatch(i->i.name().equals("player.inputs.forward")));String nested="Record state = player.snapshot();\nstate.inputs.j";assertTrue(ScriptCompletion.suggest(nested,nested.length(),false).items().stream().anyMatch(i->i.name().equals("state.inputs.jump")));String fn="function greet(name) { print(name); }\ngre";assertEquals("greet(name)",ScriptCompletion.suggest(fn,fn.length(),false).items().get(0).signature());}
    /// Incomplete string literals and line comments suppress suggestions, including escaped quotes.
    @Test public void contexts(){for(String text:List.of("// player.","# player.","say(\"player.","say(\"","say(\"a\\\"player."))assertTrue(ScriptCompletion.suggest(text,text.length(),true).items().isEmpty(),text);String text="// old\nplayer.";assertFalse(ScriptCompletion.suggest(text,text.length(),false).items().isEmpty());assertTrue(ScriptCompletion.suggest("",0,false).items().isEmpty());assertFalse(ScriptCompletion.suggest("",0,true).items().isEmpty());}
    /// Completion is atomic, preserves a typed call's delimiters and refuses stale source.
    @Test public void acceptance(){String text="print(1); player.ju";var result=ScriptCompletion.suggest(text,text.length(),false);var item=result.items().stream().filter(i->i.name().equals("player.jump")).findFirst().orElseThrow();var edit=Objects.requireNonNull(ScriptCompletion.accept(result,item,text,text.length()));assertEquals("print(1); player.jump()",text.substring(0,edit.start())+edit.replacement());assertNull(ScriptCompletion.accept(result,item,text+"x",text.length()+1));text="player.ju()";result=ScriptCompletion.suggest(text,9,false);item=result.items().stream().filter(i->i.name().equals("player.jump")).findFirst().orElseThrow();assertEquals("player.jump",Objects.requireNonNull(ScriptCompletion.accept(result,item,text,9)).replacement());}
    /// Signature hints retain the actual outer call when strings contain parentheses.
    @Test public void hints(){String text="player.look(";assertTrue(ScriptCompletion.signature(text,text.length()).startsWith(text));text="chat.send(\"(\"";assertTrue(ScriptCompletion.signature(text,text.length()).startsWith("chat.send("));text="// player.look(";assertEquals("",ScriptCompletion.signature(text,text.length()));}
    /// Loop snippets keep an indented body's caret on the new line and remain valid DSL.
    @Test public void snippets(){String text="    for";var result=ScriptCompletion.suggest(text,text.length(),false);var item=result.items().stream().filter(i->i.name().equals("for")).findFirst().orElseThrow();var edit=Objects.requireNonNull(ScriptCompletion.accept(result,item,text,text.length()));String expanded=text.substring(0,edit.start())+edit.replacement();assertEquals('\n',expanded.charAt(edit.caret()));assertEquals(' ',expanded.charAt(edit.caret()-1));assertDoesNotThrow(()->OchreScript.compile(expanded));}
}
