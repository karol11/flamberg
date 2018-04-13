package com.github.karol11.flamberg;

import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

class TypeDumper extends TypeMatcher {
	Set<Type> processed = new HashSet<>();
	StringBuilder r = new StringBuilder();
	
	public TypeDumper process(Type type) {
		if (type == null) {
			r.append("null");
			return this;
		}
		type = type.get();
		r.append(type.id);
		if (!processed.add(type) && type instanceof BuiltinType)
			return this;
		r.append('=');
		type.match(this);
		return this;
	}
	void onFnType(FnType me) {
		r.append('(');
		for (Type p: me.params) {
			process(p);
			r.append("->");
		}
		process(me.result);
		r.append(')');
	}
	void onDispType(DispType me) {
		r.append('[');
		for (Entry<Atom, Type> e: me.disp.entrySet()) {
			r.append(e.getKey()).append(':');
			process(e.getValue());
		}
		r.append(']');
	}
	void onFnRefType(FnRefType me) {
		r.append('{');
		for (FnRefType.FnAndRefs fr: me.fns)
			r.append(fr.fnToInstantiate.name == null ? "?" : fr.fnToInstantiate.name).append('@').append(fr.refsToPatch.size());
		r.append('}');		
	}
	void onVarType(VarType me) { r.append('?'); }
	void onBuiltinType(BuiltinType me) { r.append(me.id); }
	public String getString() {
		String rs = r.toString();
		r.setLength(0);
		return rs;
	}

}

public class NodeDumper extends NodeMatcher {
	StringBuilder r = new StringBuilder();
	int indentPos = -1;
	String firstLineTerm = "\n";
	TypeDumper typeDumper = new TypeDumper();
	
	void indent(){
		for (int i = 0; i < indentPos; i++)
			r.append('\t');		
	}
	
	NodeDumper process(Node n) {
		indentPos++;
		indent();
		if (n == null)
			r.append("NULL");
		else {
			if (n.name != null)
				r.append(n.name).append(" = ");
			typeDumper.process(n.type);
			firstLineTerm = " ;type=" + typeDumper.getString() + "\n";
			n.match(this);			
		}
		indentPos--;
		return this;
	}
	
	public void onConst(Const me) {
		if (me.value == null)
			r.append(".");
		else if (me.value instanceof String)
			r.append('"').append(((String)me.value).replace("\"", "\\\"")).append('"');
		else
			r.append(me.value.toString());
		r.append(firstLineTerm);
	}
	public void onCall(Call me) {
		r.append(me.inlined ? "(-)" : "()").append(firstLineTerm);
		for (Node n: me.params) 
			process(n);
	}
	public void onParam(Param me) {
		r.append("param ");
		if (me.byVref)
			r.append("vref ");
		r.append(firstLineTerm);
		if (me.typeExpr != null)
			process(me.typeExpr);
	}
	public void onRef(Ref me) {
		r.append(me.targetName == null ? "unnamed" : me.targetName);
		if (me.target == null)
			r.append('?');
		r.append(firstLineTerm);
	}
	public void onFnDef(FnDef me) {
		r.append(":");
		r.append(firstLineTerm);
		for (Param p: me.params)
			process(p);
		r.append("\n");
		for (Node n: me.body) {
			if (!(n instanceof FnDef))
				process(n);			
		}
	}
	public void onDisp(Disp me) {
		r.append("#").append(me.exportAll != null ? "#" : "").append(firstLineTerm);
		for (Entry<Atom, FnDef> e: me.variants.entrySet()) {
			indent();
			r.append(e.getKey()).append("\n");
			for (Node n: e.getValue().body)
				process(n);
		}
		if (!me.defs.isEmpty()) {
			indent();
			r.append(".");
			for (Node n: me.defs)
				process(n);
		}
	}
	public void onRet(Ret me) {
		r.append("^");
		if (me.yields)
			r.append("^");
		r.append(me.toBreakName != null ? me.toBreakName : "unnamed");
		if (me.toBreak == null)
			r.append('?');
		r.append(firstLineTerm);
		process(me.result);
	}
	public void onCast(Cast me) {
		r.append("`").append(firstLineTerm);
		process(me.expression);
		process(me.typer);
	}
	public String getString() {
		return r.toString();
	}
}
