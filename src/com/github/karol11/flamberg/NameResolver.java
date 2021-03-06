package com.github.karol11.flamberg;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

public class NameResolver extends NodeMatcher {
	Ast ast;
	FnDef currentFn;
	
	public void onConst(Const me) {}
	public void onCall(Call me) {
		Iterator<Node> i = me.params.iterator();
		Node fn = process(i.next());
		if (i.hasNext()) {
			me.params = foldExpressions(i, new ArrayList<Node>());
			me.params.add(0, fn);
		} else
			me.params.set(0, fn);
	}
	public void onParam(Param me) {
		me.error("this couldn't happen");
	}
	public void onRef(Ref me) {
		if (me.target != null)
			return;
	//TODO 
	//  _() is hard immediate recursive,
	//  same_named() call to overloaded,
	//  _reapply() make overload resolution at the point of original call
		me.target = resolve(me.targetStr, currentFn);
		if (me.target == null)
			me.error("unresolved name " + me.targetStr);
		if (me.target.named.scope != currentFn)
			me.target.named.linkage = Node.LN_LOCAL_FIELD;
	}
	Name resolve(String name, FnDef scope) {
		for (FnDef s = scope; s != null; s = s.parent) {
			Name r = s.named.get(name);
			if (r != null) {
				return r;
			}
			for (FnDef m: s.imports) {
				r = m.named.get(name);
				if (r != null)
					return r;
			}
			for (Param p: s.params) {
				if (p.name != null && p.name.equals(name))
					return p.name;
			}
		}
		return null;
	}
	public void onFnDef(FnDef me) {
		currentFn = me;
		ArrayList<Node> currentFnBody = new ArrayList<Node>();
		for (Param p: me.params) {
			p.scope = me;
			if (p.folded)
				continue;
			p.folded = true;
			if (extractContainer(p, p.typeExpr, me, currentFnBody)) { // a`var // a`vref // a`ref
				p.typeExpr = null;
				continue;
			}
			if (p.typeExpr instanceof Call) { // a`ref(0) ...
				Call ci = (Call) p.typeExpr;
				if (ci.params.size() == 2 && extractContainer(p, ci.params.get(0), me, currentFnBody)) {
					p.typeExpr = process(ci.params.get(1));
					continue;
				}
			}
			currentFnBody.add(makeContainer(makeRefTo(p.name), p.name.val, new Ref("pass")));
		}
		foldExpressions(me.body.iterator(), currentFnBody);
		Node last = currentFnBody.get(currentFnBody.size()-1);
		if (!(last instanceof Ret)) {
			Ret ret = Parser.posFrom(last, new Ret());
			ret.result = last;
			ret.setTarget(me);
			currentFnBody.set(currentFnBody.size()-1, ret);
		}
		me.body = currentFnBody;
		currentFn = me.parent;
		if (me.name != null && me.overload == null) {
			Name ovl = resolve(me.name.val, currentFn.parent);
			if (ovl != null && ovl.named instanceof FnDef)
				me.overload = (FnDef) ovl.named;
		}
	}
	private Node makeRefTo(Name target) {
		return Parser.posFrom(target.named, new Ref(target));
	}
	private Node makeContainer(Node src, String name, Ref containerFnRef) {
		Call call = Parser.posFrom(src, new Call(Parser.posFrom(src, process(containerFnRef))));
		if (name != null)
			new Name(name, currentFn, call);
		call.params.add(src);
		return call;
	}
	private boolean extractContainer(Param p, Node initializer, FnDef me, ArrayList<Node> currentFnBody) {
		if (!(initializer instanceof Ref))
			return false;
		Ref ri = (Ref) initializer;
		onRef(ri);
		if (!(ri.target.named instanceof FnDef))
			return false;
		FnDef fi = (FnDef) ri.target.named;
		if (ri.targetStr.equals("vref")) {
			p.byVref = true;
			return true;
		}
		if (ri.targetStr.equals("ref")) {
			return true;
		}
		if (fi.params.size() == 1 && fi.params.get(0).name.equals("_content_")) {
			currentFnBody.add(makeContainer(makeRefTo(p.name), p.name.val, ri));
			return true;
		}
		return false;
	}
	Call getStaticCall(Node n) {
		if (n instanceof Call) {
			Call c = (Call)n;
			Node fn = c.params.get(0);
			if (fn instanceof Ref) {
				Ref r = (Ref) fn;
				if (r.target == null || r.target.named instanceof FnDef)
					return c;
			}
		}
		return null;
	}
	private List<Node> foldExpressions(Iterator<Node> body, List<Node> out) {
		Node first = body.next();
		first = process(first);
		Call firstFn = getStaticCall(first);
		for (; body.hasNext();) {
			Node s = body.next();
			Call fs = getStaticCall(s);
			if (fs != null) {
				String name = ((Ref)fs.params.get(0)).targetStr;
				if (name.startsWith(".")) {
					if (first == null)
						s.error(".. has no expression above");
					Node callee = first;
					if (name.length() != 1 && fs.params.size() > 1) {
						Call atomCall = Parser.posFrom(s, new Call(callee));
						atomCall.params.add(Parser.posFrom(s, new Const(ast.get(name.substring(1)))));
						fs.params.set(0, atomCall);
					} else {
						fs.params.set(0, callee);
						if (name.length() > 1)
							fs.params.add(Parser.posFrom(s, new Const(ast.get(name.substring(1)))));
					}
					if (first.name != null && fs.name == null) {
						fs.name = first.name;
						fs.name.named = fs;
						first.name = null;
					}
					first = fs;
					process(first);
					firstFn = getStaticCall(first); 
					continue;
				}
				if (firstFn != null) {
					FnDef func = (FnDef) ((Ref)firstFn.params.get(0)).target.named;
					if (firstFn.params.size() <= func.params.size() &&
							name.equals(func.params.get(firstFn.params.size()-1).name)) {
						if (!(s instanceof Call))
							s.error("named ..");
						fs.params.remove(0);
						s = fs.params.size() == 1 ? fs.params.get(0) : fs;
						process(s);
						firstFn.params.add(s);
						continue;
					}
				}
			}
			out.add(first);
			first = process(s);
			firstFn = getStaticCall(s);
		}
		out.add(first);
		return out;
	}
	public void onDisp(Disp me) {
		if (me.exportAll != null) {
			for (Entry<String, Name> n: me.exportAll.named.entrySet()) {
				String name = n.getKey();
				Atom aName = ast.get(name);
				if (name.startsWith("_") || me.variants.containsKey(aName))
					continue;
				FnDef fn = Parser.posFrom(me, new FnDef(me.exportAll));
				fn.body.add(Parser.posFrom(n.getValue().named, new Ref(name)));
				me.variants.put(aName, fn);
			}
		}
		for (FnDef e: me.variants.values())
			e.match(this);
		for (Node n: me.defs)
			process(n);
		
	}
	public void onRet(Ret me) {
		process(me.result);
		if (me.toBreak != null)
			return;
		for (FnDef fn = currentFn; fn != null; fn = fn.parent) {
			if (fn.name == null)
				continue;
			if (me.toBreakName == null || me.toBreakName.equals(fn.name)) {
				me.setTarget(fn);
				return;
			}			
		}
		me.error("cannot resolve return target");
	}
	public void onCast(Cast me) {
		process(me.expression);
		process(me.typer);
	}
	
	Node process(Node n) {
		if (n == null)
			return null;
		n.scope = currentFn;
		n.match(this);
		return n;
	}
	public void process(Ast ast) {
		this.ast = ast;
		for (Node m: ast.modules.values()) {
			m.match(this);
		}
		ast.main.match(this);
	}
}
