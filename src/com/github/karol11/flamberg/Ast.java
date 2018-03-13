package com.github.karol11.flamberg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

class CompilerError extends RuntimeException {
	private static final long serialVersionUID = 1L;
	CompilerError(String s) { super(s); }
}

class Numerator {
	char[] r = {'a'};
	String next() {
		for (int i = r.length; --i >= 0;) {
			if (++r[i] <= 'z')
				return new String(r);
			r[i] = 'a';
		}
		r = new char[r.length + 1];
		Arrays.fill(r, 'a');
		return new String(r);
	}
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


class TypeMatcher {
	void onUnsupported(Type me) { throw new CompilerError("type " + me + " unsopported by " + this.getClass().getName()); }
	void onFnType(FnType me) { onUnsupported(me); }
	void onDispType(DispType me) { onUnsupported(me); }
	void onFnRefType(FnRefType me) { onUnsupported(me); }
	void onVarType(VarType me) { onUnsupported(me); }
	void onBuiltinType(BuiltinType me) { onUnsupported(me); }
}

abstract class Type {
	static Numerator numerator = new Numerator();
	
	/**
	 * First it is null. After some unifications it can reference other type.
	 * The "get" call can shortcut the chain of "typeA.override->typeB.override->typeC" to "typeA.override->typeC".
	 * During unifications typeA.override->typeB to reflect the fact that they assumed equals.
	 * So on unsuccessful unification it should be reverted back to null (or else "toString" won't work).
	 */
	Type override;
	
	/**
	 * Unique id for to string conversion.
	 */
	String id = numerator.next();
	
	void match(TypeMatcher m) { m.onUnsupported(this); }

	/**
	 * Returns the type override. Should be called for each type prior using.
	 * 
	 * All types can be overridden.
	 * - for VarType it is the only reason of existence,
	 * - for FnRefType it is the process of type resolution and template instantiation,
	 * - for Disp type it is the interface merging (for cycle removal in type category),
	 * - for FnType it is merging of the same-type functions: called by the same Call node.
	 */
	Type get() {
		Type t = this;
		for (int i = 1000; t.override != null;) {
			if (--i == 0)
				throw new CompilerError("type override error");
			t = t.override;
		}
		if (t != this)
			override = t;
		return t;
	}
	
	public String toString() {
		return new TypeDumper().process(this).getString();
	}
}

/**
 * A function, called at least once.
 */
class FnType extends Type {
	final List<Type> params = new ArrayList<Type>();
	Type result;
	
	/**
	 * 
	 * FnType first created by a Call node, and its "caller" field referenced this Call node.
	 * After unification the "caller" turns to null.
	 * During unification "caller" can be used to insert .get calls if caller provides container, and function demands some unwrapped type.
	 * If caller==null, no .get can be inserted, because this is not the first unification and wrapped/unwrapped status already proven by previous call.
	 */
	Call caller;
	void match(TypeMatcher m) { m.onFnType(this); }
}
/**
 * Dispatcher type.
 */
class DispType extends Type {
	/**
	 *  Intersection of atom:result_type pairs from all implementations.
	 */
	final Map<Atom, Type> disp = new TreeMap<Atom, Type>();
	
	/**
	 * Union of atoms, really called by all usages.
	 */
	final Set<Atom> used = new TreeSet<Atom>();
	
	/**
	 * 
	 */
	final Set<DispType> superType = new TreeSet<DispType>();
	final Set<DispType> subType = new TreeSet<DispType>();

	/**
	 * Searches for loops in super/sub graph.
	 */
	public boolean connectChild(DispType c, List<DispType> undoList) {
		if (override != null)
			return false;
		for (DispType s: subType) {
			if (s.connectChild(c, undoList)) {
				if (override == null)
					undoList.add(this);
				override = c;
			}
		}
		return override != null;
	}
	void match(TypeMatcher m) { m.onDispType(this); }
}

/**
 * A function that has been referenced but never called.
 * It can denote a variable storing a function reference.
 * In this case it can be assigned multiple different fnDefs
 * (and it stores all references, that fills this function with value).
 * Been called it instantiates all funDefs with actual parameter and set all refs to actual fnDefs.
 * 
 * It can also denote some anonymous and thus non-overloadable function having no patcheable references.
 */

class FnRefType extends Type {
	static class FnAndRefs {
		FnDef fnToInstantiate;
		final List<Ref> refsToPatch = new ArrayList<Ref>(1);
		public void addRefs(List<Ref> toAdd) {
			byToAdd: for (Ref ref: toAdd) {
				for (Ref my: refsToPatch){
					if (my == ref)
						continue byToAdd;
				}
				refsToPatch.add(ref);
			}
		}
	}
	FnRefType(Ref ref) {
		FnAndRefs fr = new FnAndRefs();
		fr.fnToInstantiate = (FnDef)ref.target;
		fr.refsToPatch.add(ref);
		fns.add(fr);
	}
	FnRefType(FnDef def) {
		FnAndRefs fr = new FnAndRefs();
		fr.fnToInstantiate = def;
		fns.add(fr);
	}
	
	final List<FnAndRefs> fns = new ArrayList<FnAndRefs>();	
	void match(TypeMatcher m) { m.onFnRefType(this); }
	void addFn(FnAndRefs toAdd) {
		for (FnAndRefs f: fns) {
			if (f.fnToInstantiate == toAdd.fnToInstantiate) {
				f.addRefs(toAdd.refsToPatch);
				return;
			}
		}
		FnAndRefs f = new FnAndRefs();
		f.fnToInstantiate = toAdd.fnToInstantiate;
		f.refsToPatch.addAll(toAdd.refsToPatch);
		fns.add(f);
	}
}

class VarType extends Type {
	void match(TypeMatcher m) { m.onVarType(this); }
}

class BuiltinType extends Type {
	String name;
	
	BuiltinType(String name) {
		this.name = name;
	}
	void match(TypeMatcher m) { m.onBuiltinType(this); }
}

class Node {
	final static int LN_LOCAL = 0;
	final static int LN_LOCAL_FIELD = 1;
	final static int LN_FIELD = 2;
	static int nodeNumerator = 1;

	String file;
	int linePos, line;
	String name;
	FnDef scope;
	Type type;
	int linkage = LN_LOCAL;
	TypeConstructor typeConstructor;
	
	void match(NodeMatcher m) { m.onUnsupported(this); }
	String formatError(String e) { return e + " at " + file + "(" + line + ":" + linePos + ")"; }
	void error(String s) {
		throw new CompilerError("Error: " + formatError(s));
	}
	public String toString() {
		return new NodeDumper().process(this).getString();
	}
}

class TypeConstructor {
	public TypeConstructor() {}
	public TypeConstructor(Callable n) {
		fns.add(n);
	}
	Set<Callable> fns = new HashSet<Callable>(2); // FnDef or Disp, thats entry point activates when access to this values
	List<Node> depends; //Param or Call
	List<Integer> dependIndex;
	public String id;
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

class Const extends Node {
	Object value;
	
	public Const(Object value) {
		this(value, false);
	}

	public Const(Object value, boolean isUnsigned) {
		this.value = value;
		if (value == null)
			type = Ast.tVoid;
		else if (value instanceof Integer)
			type = isUnsigned ? Ast.tUint : Ast.tInt;
		else if (value instanceof Long)
			type = isUnsigned ? Ast.tUlong : Ast.tLong;
		else if (value instanceof Float)
			type = Ast.tFloat;
		else if (value instanceof Double)
			type = Ast.tDouble;
		else if (value instanceof Boolean)
			type = Ast.tBool;
		else if (value instanceof Atom)
			type = Ast.tAtom;
		else if (value instanceof String)
			type = Ast.tString;
		else
			error("unexpected builtin type");
	}

	void match(NodeMatcher m) { m.onConst(this); }	
}

class Call extends Node {
	List<Node> params; // params[0] -> function to call

	//  points to parent call node if this call is nested in another call
	// example: disp.atom(params) -> call(call(disp .atom) params)
	// used in rebind to call(atom_name_fn disp params) that happens at the type resolution stage
	Call superCall;
	
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
	boolean folded;
	boolean byVref;

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
	List<FnDef> instances;

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
	Map<String, FnDef> modules = new HashMap<>();
	List<String> incompleteModules = new ArrayList<>();
	List<FnDef> templateInstances = new ArrayList<>();
	
	static BuiltinType tInt = new BuiltinType("int");
	static BuiltinType tUint = new BuiltinType("uint");
	static BuiltinType tLong = new BuiltinType("long");
	static BuiltinType tUlong = new BuiltinType("ulong");
	static BuiltinType tBool = new BuiltinType("bool");
	static BuiltinType tFloat = new BuiltinType("float");
	static BuiltinType tDouble = new BuiltinType("double");
	static BuiltinType tAtom = new BuiltinType("atom");
	static BuiltinType tVoid = new BuiltinType("void");
	static BuiltinType tString = new BuiltinType("string");
	static BuiltinType tUnsafePtr = new BuiltinType("unsafe.ptr");
}
