package com.github.karol11.flamberg;


public class EscapePass extends NodeMatcher {
	Ast ast;
	FnDef currentFn;
	
	class Catcher extends NodeMatcher {
		int minDepth;
		int maxDepth;
		FnDef currentFn;
		
		private void process(Node n) { n.match(this); }

		@Override public void onConst(Const me) {}
		@Override public void onCall(Call me) {
			for (Node n: me.params)
				process(n);
		}
		@Override public void onParam(Param me) {}
		@Override public void onRef(Ref me) {
			if (me.target.named.scope.lexicalDepth >= minDepth && me.target.named.scope.lexicalDepth <= maxDepth) {
				int dist = 0;
				for (FnDef f = currentFn.parent; f != null && me.target.named.scope != f; f = f.parent, dist++){
					if (dist > 0 && !f.named.containsKey("_"))
						me.error("access to long-term name across multiple functions should be protected with '_' name");
				}
				me.target.named.linkage = Node.LN_FIELD;
			} else if (me.target.named.scope.lexicalDepth < currentFn.lexicalDepth)
				me.target.named.linkage = Node.LN_LOCAL_FIELD;
		}
		@Override public void onFnDef(FnDef me) {
			if (me.callsCount <= 0)
				return;
			int prevMax = maxDepth;
			int prevMin = minDepth;
			FnDef prevFn = currentFn;
			currentFn = me;
			if (me.retDepth < minDepth)
				minDepth = me.retDepth;
			if (me.lexicalDepth - 1 > maxDepth)
				maxDepth = me.lexicalDepth - 1;
			for (Node n: me.body)
				process(n);
			minDepth = prevMin;
			maxDepth = prevMax;
			currentFn = prevFn;
		}
		@Override public void onDisp(Disp me) {
			for (Node n: me.defs)
				process(n);
			for (Node n: me.variants.values())
				process(n);
		}
		@Override public void onRet(Ret me) { process(me.result); }
		@Override public void onCast(Cast me) { process(me.expression); }
	}
	
	public void process(Ast ast) {
		this.ast = ast;
		process(ast.main);
		for (FnDef f: ast.modules.values())
			process(f);
		Catcher c = new Catcher();
		for (FnDef f: ast.modules.values())
			c.process(f);
		c.process(ast.main);
	}

	private void process(Node n) { n.match(this); }

	public void onConst(Const me) {}
	public void onCall(Call me) {
		for (Node n: me.params)
			process(n);
		for (Node n: me.typeConstructor.fns) {
			FnDef f = null;
			if (n instanceof FnDef)
				f = (FnDef) n;
			else
				f = ((Disp)n).variants.get(((Const)me.params.get(1)).value);
			if (f.retDepth > currentFn.lexicalDepth)
				f.retDepth = currentFn.lexicalDepth;
		}
	}
	public void onFnDef(FnDef me) {
		me.lexicalDepth = me.parent  == null ? 0 : (me.parent.lexicalDepth + (me.inlined ? 0 : 1));
		if (me.retDepth > me.lexicalDepth)
			me.retDepth = me.lexicalDepth;
		if (me.instances != null) {
			for (FnDef f: me.instances)
				process(f);
		}
		currentFn = me;
		for (Param p: me.params)
			process(p);
		for (Node n: me.body)
			process(n);
		currentFn = me.parent;
	}
	public void onParam(Param me) {}
	public void onRef(Ref me) {}
	public void onDisp(Disp me) {
		for (FnDef fn: me.variants.values())
			process(fn);
		for (Node n: me.defs)
			process(n);
	}
	public void onRet(Ret me) {
		process(me.result);
	}
	public void onCast(Cast me) {
		process(me.expression);
	}
}
