package com.github.karol11.flamberg;

import java.util.Map.Entry;

public class NodeDumper extends NodeMatcher {
	StringBuilder r;
	int indentPos = -1;
	String firstLineTerm = "\n";
	
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
		r.append("()").append(firstLineTerm);
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
		for (Node n: me.body)
			process(n);
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
