package com.github.karol11.flamberg;

import java.util.ArrayList;

public class Devirtualizer extends NodeMatcher {	
	void process(Ast ast) {
		process(ast.main);
		for (Node n: ast.templateInstances)
			process(n);
		for (Node n: ast.modules.values())
			process(n);
	}
	void process(Node n) {
		if (n != null && n.type != null)
			n.match(this);
	}
	
	public void onConst(Const me) {}
	public void onFnDef(FnDef me) {
		for (Node n: me.body)
			process(n);
	}
	public void onDisp(Disp me) {
		for (Node n: me.variants.values())
			process(n);
		for (Node n: me.defs)
			process(n);
	}
	public void onParam(Param me) {}
	public void onRet(Ret me) {
		process(me.result);
	}
	public void onRef(Ref me) {
		Node dst = me;
		while (dst instanceof Ref)
			dst = ((Ref)dst).target.target;
		me.typeConstructor = dst.typeConstructor;
	}
	public void onCast(Cast me) {
		me.error("internal, casts should be removed at this stage");
	}
	public void onCall(Call me) {
		for (int i = 0, n = me.params.size(); i < n; i++) {
			Node p = me.params.get(i);
			process(p);
			registerDepend(me, p.typeConstructor, i);
		}
	}
	void registerDepend(Node depend /* only Param and Call*/, TypeConstructor source, int index /* -1 for param and call result, 0 for call fn, >0 for call param*/) {
		if (source.depends == null) {
			source.depends = new ArrayList<Node>();
			source.dependIndex = new ArrayList<Integer>();
		}
		source.depends.add(depend);
		source.dependIndex.add(index);
		for (Callable fn: source.fns)
			pushVariant(depend, index, fn);
	}
	void pushVariant(Node depend /* Param or Call */, int index, Callable newFn) {
		if (index < 0) {
			if (depend.typeConstructor.fns.add(newFn) && depend.typeConstructor.depends != null) {
				for (int i = 0, n = depend.typeConstructor.depends.size(); i < n; i++)
					pushVariant(depend.typeConstructor.depends.get(i), depend.typeConstructor.dependIndex.get(i), newFn);
			}
			return;
		}
		Call c = (Call) depend;
		if (index == 0) {
			if (newFn instanceof Disp) {
				for (Ret r: ((Disp)newFn).variants.get(((Const)c.params.get(1)).value).rets)
					registerDepend(c, deref(r.result), -1);
			} else {
				FnDef f = (FnDef) newFn;
				for (int i = 1, n = c.params.size(); i < n; i++) {
					for (Callable pv: deref(c.params.get(i)).fns)
						pushVariant(f.params.get(i-1), -1, pv);
				}
				for (Ret r: f.rets)
					registerDepend(c, deref(r.result), -1);				
			}
		} else if (index > 0) {
			for (Node fn: c.params.get(0).typeConstructor.fns) {
				if (fn instanceof FnDef)
					pushVariant(((FnDef)fn).params.get(index-1), -1, newFn);
			}
		}
	}
	private TypeConstructor deref(Node n) {
		if (n.typeConstructor == null && n instanceof Ref)
			onRef((Ref)n);
		return n.typeConstructor;
	}
}
