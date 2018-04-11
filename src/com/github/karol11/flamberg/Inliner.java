import java.util.HashSet;
import java.util.Set;

public class Inliner extends NodeMatcher {
	Ast ast;
	Callable currentFn;
	Set<Callable> currentCallPath = new HashSet<Callable>();

	class Phase2 extends NodeMatcher{
		Set<Callable> visited = new HashSet<Callable>();
		void process(Node n) {
			if (n != null)
				n.match(this);
		}

		void processFn(Callable n) {
			if (visited.contains(n))
				return;
			visited.add(n);
			Callable prev = currentFn;
			currentFn = n;
			if (n instanceof Disp){
				Disp d = (Disp) n;
				for (Node i: d.defs)
					process(i);
				for (FnDef i: d.variants.values())
					processFn(i);
			} else {
				FnDef f = (FnDef) n;
				for (Node i: f.body)
					process(i);
			}
			currentFn = prev;
		}		
		@Override public void onConst(Const me) {}
		@Override public void onCall(Call me) {
			if (me.recursive)
				return;
			for (Node n: me.params)
				process(n);
			for (Callable f: me.params.get(0).typeConstructor.fns)
				processFn(f);
			if (me.params.get(0).typeConstructor.fns.size() != 1)
				return;
			Callable f = me.params.get(0).typeConstructor.fns.iterator().next();
			if (f.callsCount == 1 || f.weight < 5) {
				f.callsCount--;
				me.inlined = true;
				if (f.callsCount > 1) {
					me.params.set(0, new CopyMaker(0).copy(f));
				}
				f.inlined = true;
				currentFn.weight += f.weight - 1;
			}
		}
		@Override public void onParam(Param me) {}
		@Override public void onRef(Ref me) {}
		@Override public void onFnDef(FnDef me) {}
		@Override public void onDisp(Disp me) {}
		@Override public void onRet(Ret me) { process(me.result); }
		@Override public void onCast(Cast me) { process(me.expression); }
	};

	public void process(Ast ast) {
		this.ast = ast;
		processFn(ast.main);
		for (FnDef m: ast.modules.values())
			processFn(m);
		Phase2 p2 = new Phase2();
		p2.processFn(ast.main);
		for (FnDef m: ast.modules.values())
			p2.processFn(m);
	}
	
	void process(Node n) {
		if (n != null)
			n.match(this);
	}

	void processFn(Callable n) {
		n.callsCount++;
		if (n.weight != -1)
			return;
		currentCallPath.add(n);
		Callable prev = currentFn;
		currentFn = n;
		n.weight = 0;
		if (n instanceof Disp){
			Disp d = (Disp) n;
			for (Node i: d.defs)
				process(i);
			for (FnDef i: d.variants.values())
				processFn(i);
		} else {
			FnDef f = (FnDef) n;
			for (Node i: f.body)
				process(i);
		}
		currentFn = prev;
		currentCallPath.remove(n);
	}

	@Override public void onConst(Const me) {}
	@Override public void onCall(Call me) {
		for (Node n: me.params)
			process(n);
		currentFn.weight++;
		for (Callable f: me.params.get(0).typeConstructor.fns) {
			if (currentCallPath.contains(f))
				me.recursive = true;
			else
				processFn(f);
		}
	}
	@Override public void onParam(Param me) {}
	@Override public void onRef(Ref me) {}
	@Override public void onFnDef(FnDef me) {}
	@Override public void onDisp(Disp me) {}
	@Override public void onRet(Ret me) { process(me.result); }
	@Override public void onCast(Cast me) { process(me.expression); }
}
