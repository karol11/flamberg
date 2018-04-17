package com.github.karol11.flamberg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

public class CopyMaker extends NodeMatcher {
	Map<Object, Object> xrefs = new HashMap<>();
	Node r;
	List<Param> varParams;
	int actualParamsCount;
	Name srcVarParam;
	FnDef currentScope;

	static Node copy(FnDef src, int actualParamsCount) {
		CopyMaker c = new CopyMaker(actualParamsCount);
		c.currentScope = src.scope;
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
			if (r.name == null && src.name != null) {
				xrefs.put(src.name, new Name(src.name.val, null, r));
			}
			r.scope = currentScope;
		}
		return r;
	}
	public void onConst(Const me) { r=me; }
	public void onCall(Call me) {
		Call c = new Call((Node)null);
		for (Node p: me.params) {
			if (srcVarParam != null && p instanceof Ref && ((Ref)p).target == srcVarParam) {
				for (Param vp: varParams)
					c.params.add(Parser.posFrom(p, new Ref(vp.name)));
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
		Ref ref = new Ref(me.targetStr);
		ref.target = me.target;
		r = ref;
	}
	public void onFnDef(FnDef me) {
		FnDef f = new FnDef(me.parent);
		FnDef prevCurrentScope = currentScope;
		currentScope = f;
		for (Param p: me.params) {
			if (varParams == null && p.name.equals("_params_")) {
				varParams = new ArrayList<Param>();
				srcVarParam = p.name;
				for (int i = 0; i < actualParamsCount - (me.params.size() - 1); i++) {
					Param ap = Parser.posFrom(p, new Param(new Name("_param_" + i + "_", f, null), copy(p.typeExpr)));
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
		currentScope = prevCurrentScope;
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
	Map<Object, Object> xrefs;
	Fixer(Map<Object, Object> xrefs) { this.xrefs = xrefs; }
	void process(Node n) {
		if (n != null) {
			n.match(this);
			n.scope = fix(n.scope);
			n.name = fix(n.name);
		}
	}
	@SuppressWarnings("unchecked")
	<T extends Object> T fix(T src) {
		return src == null ? null : (T)xrefs.getOrDefault(src, src);
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
