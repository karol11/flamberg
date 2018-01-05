import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class CompilerError extends RuntimeException {
	private static final long serialVersionUID = 1L;
	CompilerError(String s) { super(s); }
}

class Atom implements Comparable<Atom> {
	final String name;
	Atom(String name) { this.name = name; }
	
	public String toString() { return "." + name; }
	@Override
	public int compareTo(Atom a) {
		return name.compareTo(a.name);
	}
}

class NodeMatcher {
	void onUnsupported(Node me) {
		throw new CompilerError("node " + me + " isn't matched by" + this.getClass().getName());
	}
	public void onConst(Const me) { onUnsupported(me); }
	public void onCall(Call me) { onUnsupported(me);}
	public void onParam(Param me) { onUnsupported(me); }
	public void onRef(Ref me) { onUnsupported(me); }
	public void onFnDef(FnDef me) { onUnsupported(me); }
	public void onDisp(Disp me) { onUnsupported(me); }
	public void onRet(Ret me) { onUnsupported(me); }
	public void onCast(Cast me) { onUnsupported(me); }
}

class Node {
	static int nodeNumerator = 1;
	String file;
	int linePos, line;
	String name;
	FnDef scope;

	void match(NodeMatcher m) { m.onUnsupported(this); }
	String formatError(String e) { return e + " at " + file + "(" + line + ":" + linePos + ")"; }
	void error(String s) {
		throw new CompilerError("Error: " + formatError(s));
	}
}

class Const extends Node {
	Object value;
	
	public Const(Object value) {
		this.value = value;
	}

	public Const(Object value, boolean isUnsigned) {
		this.value = value;
		//TODO: take in account isUnsigned
	}

	void match(NodeMatcher m) { m.onConst(this); }	
}

class Call extends Node {
	List<Node> params; // params[0] == function to call
	
	public Call(List<Node> params) {
		this.params = params;
	}
	public Call(Node fn) {
		this.params = new ArrayList<Node>();
		if (fn!= null)
			this.params.add(fn);
	}

	void match(NodeMatcher m) { m.onCall(this); }
}

class Param extends Node {
	Node typeExpr;

	public Param(String name, Node typeExpr) {
		this.name = name;
		this.typeExpr = typeExpr;
	}
	void match(NodeMatcher m) { m.onParam(this); }
}

class Ref extends Node {
	Node target;
	String targetName;

	public Ref(String targetName) {
		this.targetName = targetName;
	}

	void match(NodeMatcher m) { m.onRef(this); }
}

class Callable extends Node {
}

class FnDef extends Callable {
	List<Param> params = new ArrayList<>();
	List<Node> body = new ArrayList<>();
	Map<String, Node> named = new HashMap<>();
	List<FnDef> imports = new ArrayList<>();
	List<Ret> rets = new ArrayList<>(1);
	FnDef parent;
	FnDef overload;

	public FnDef() {
	}
	public FnDef(FnDef parent) {
		this.parent = parent;
	}
	public FnDef(FnDef parent, Node body) {
		this(parent);
		this.body.add(body);
	}
	
	FnDef(String name) {
		this.name = name;
	}

	void match(NodeMatcher m) { m.onFnDef(this); }
}

class Disp extends Callable {
	Map<Atom, FnDef> variants = new TreeMap<Atom, FnDef>();
	List<Node> defs = new ArrayList<Node>();
	FnDef exportAll;
	
	void match(NodeMatcher m) { m.onDisp(this); }
}

class Ret extends Node {
	FnDef toBreak;
	String toBreakName;
	boolean yields;
	Node result;

	void match(NodeMatcher m) { m.onRet(this); }

	public void setTarget(FnDef fn) {
		toBreak = fn;
		fn.rets.add(this);
	}
}

class Cast extends Node {
	Node expression;
	Node typer;

	public Cast(Node expression, Node typer) {
		this.expression = expression;
		this.typer = typer;
	}

	void match(NodeMatcher m) { m.onCast(this); }
}

public class Ast {
	Map<String, Atom> atoms = new HashMap<String, Atom>();

	Atom get(String name) {
		Atom r = atoms.get(name);
		if (r == null)
			atoms.put(name, r = new Atom(name));
		return r;
	}

	FnDef main;
	Map<String, FnDef> modules = new HashMap<String, FnDef>();
	List<String> incompleteModules = new ArrayList<String>();	
}
