package com.github.karol11.flamberg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

public class CopyMaker extends NodeMatcher {
	Map<Node, Node> xrefs = new HashMap<Node, Node>();
	Node r;
	List<Param> varParams;
	int actualParamsCount;
	Param srcVarParam;

	static Node copy(Node src, int actualParamsCount) {
		CopyMaker c = new CopyMaker(actualParamsCount);
		Node r = c.copy(src);
		new Fixer(c.xrefs).process(r);
		return r;
	}
	
	CopyMaker(int actualParamsCount) {
		this.actualParamsCount = actualParamsCount;
	}
	Node copy(Node src) {
		if (src == null)
			return null;
		src.match(this);
		if (src != r) {
			xrefs.put(src, r);
			Parser.posFrom(src, r);
			if (r.name == null)
				r.name = src.name;
			r.scope = src.scope;
		}
		return r;
	}
	public void onConst(Const me) { r=me; }
	public void onCall(Call me) {
		Call c = new Call((Node)null);
		for (Node p: me.params) {
			if (srcVarParam != null && p instanceof Ref && ((Ref)p).target == srcVarParam) {
				for (Param vp: varParams) {
					Ref ref = Parser.posFrom(vp, new Ref(vp.name));
					ref.target = vp;
					c.params.add(ref);
				}
			} else
				c.params.add(copy(p));
		}
		r = c;
	}
	public void onParam(Param me) {
		r = new Param(me.name, copy(me.typeExpr));
	}
	public void onRef(Ref me) {
		if (me.target == srcVarParam)
			me.error("_params_ should be only in function call");
		Ref ref = new Ref(me.targetName);
		ref.target = me.target;
		r = ref;
	}
	public void onFnDef(FnDef me) {
		FnDef f = new FnDef(me.parent);
		for (Param p: me.params) {
			if (varParams == null && p.name.equals("_params_")) {
				varParams = new ArrayList<Param>();
				srcVarParam = p;
				for (int i = 0; i < actualParamsCount - (me.params.size() - 1); i++) {
					Param ap = Parser.posFrom(p, new Param("_param_" + i + "_", copy(p.typeExpr)));
					varParams.add(ap);
					f.params.add(ap);
				}
			} else
				f.params.add((Param)copy(p));
		}
		for (Node n: me.body)
			f.body.add(copy(n));
		f.rets = new ArrayList<Ret>(me.rets);
		r = f;
	}
	public void onDisp(Disp me) {
		Disp d = new Disp();
		for (Node i: me.defs)
			d.defs.add(copy(i));
		for (Entry<Atom, FnDef> e: me.variants.entrySet())
			d.variants.put(e.getKey(), (FnDef) copy(e.getValue()));
		r = d;
	}
	public void onRet(Ret me) {
		Ret ret = new Ret();
		ret.result = copy(me.result);
		ret.toBreak = me.toBreak;
		ret.toBreakName = me.toBreakName;
		ret.yields = me.yields;
		r = ret;
	}
	public void onCast(Cast me) {
		r = new Cast(copy(me.expression), copy(me.typer));
	}
}
class Fixer extends NodeMatcher {
	Map<Node, Node> xrefs;
	Fixer(Map<Node, Node> xrefs) { this.xrefs = xrefs; }
	void process(Node n) {
		if (n != null) {
			n.match(this);
			n.scope = fix(n.scope);
		}
	}
	@SuppressWarnings("unchecked")
	<T extends Node> T fix(T src) {
		if (src == null)
			return null;
		Node dst = xrefs.get(src);
		return dst == null ? src : (T)dst;
	}
	public void onConst(Const me) {}
	public void onCall(Call me) {
		for (Node n: me.params) {
			process(n);
		}
	}
	public void onParam(Param me) {
		process(me.typeExpr);
	}
	public void onRef(Ref me) {
		me.target = fix(me.target);
	}
	public void onFnDef(FnDef me) {
		for (Param p: me.params)
			process(p);
		for (Node n: me.body)
			process(n);
		for (ListIterator<Ret> r = me.rets.listIterator(); r.hasNext();)
			r.set(fix(r.next()));
	}
	public void onDisp(Disp me) {
		for (Node n: me.defs)
			process(n);
		for (Node n: me.variants.values())
			process(n);
	}
	public void onRet(Ret me) {
		me.toBreak = fix(me.toBreak);
		process(me.result);
	}
	public void onCast(Cast me) {
		process(me.expression);
		process(me.typer);
	}
}
