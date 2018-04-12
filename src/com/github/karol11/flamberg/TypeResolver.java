package com.github.karol11.flamberg;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

abstract class Action {
	abstract void undo();
}
// TODO: dfs with unification by all not overloaded calls and all fn internals.
// storing the list of postponed overloaded calls.
// bfs? on overloaded calls

class TypematchReason {
	String text;
	List<TypematchReason> causes;
	Node pos; 
	public TypematchReason(String text, Node pos, List<TypematchReason> causes) {
		this.text = text;
		this.causes = causes;
		this.pos = pos;
	}
	public TypematchReason(TypematchReason cause, String text, Node pos) {
		this(text, pos, new ArrayList<TypematchReason>(1));
		causes.add(cause);
	}
	public String toString() {
		StringBuilder r = new StringBuilder();
		dump(r, "");
		return r.toString();
	}
	private void dump(StringBuilder r, String indent) {
		if (pos.file == null) {
			if (causes != null) {
				for (TypematchReason i: causes)
					i.dump(r, indent);
			}
			return;
		}
		r.append(indent).append(pos.formatError(text)).append("\n");
		if (causes != null) {
			indent += "  ";
			for (TypematchReason i: causes)
				i.dump(r, indent);
		}
	}
}
class TypematchError extends RuntimeException {
	private static final long serialVersionUID = 1L;
	TypematchReason reason;
	TypematchError(TypematchReason reason) {
		this.reason = reason;
	}
}
public class TypeResolver extends NodeMatcher {
	Ast ast;
	List<Action> undo = new ArrayList<Action>();
	Call superCall; // inside call.params[0].process a superCall field references the call node
	
	public void onParam(Param me) {}//do nothing
	public void onConst(Const me) {}//do nothing
	public void onCall(Call me) {
		me.superCall = superCall;
		final FnType ft = new FnType();
		ft.result = me.type = new VarType();
		ft.caller = me;
		Iterator<Node> i = me.params.iterator();
		Node fn = i.next();
		process(fn, me);
		while (i.hasNext()) {
			Node p = i.next();
			process(p);
			ft.params.add(p.type);
		}
		unify(fn.type, ft, me);			
	}
	public void onRef(Ref me) {
		me.type =
			me.target.target instanceof FnDef ? new FnRefType(me) :
			me.target.target.type == null ? new VarType() :
			me.target.target.type;
	}
	public void onFnDef(FnDef me) {
		me.type = new FnRefType(me);
	}
	public void onDisp(Disp me) {
		DispType t = new DispType();
		for (Entry<Atom, FnDef> e: me.variants.entrySet())
			t.disp.put(e.getKey(), new FnRefType(e.getValue()));
		for (Node d: me.defs) {
			Type dt = d.type.get();
			if (!(dt instanceof DispType))
				reportError(me, "dispatcher type expected");
			for (Entry<Atom, Type> e: ((DispType)dt).disp.entrySet()) {
				if (!t.disp.containsKey(e.getKey()))
					t.disp.put(e.getKey(), e.getValue());
			}
		}
		me.type = t;
	}
	public void onRet(Ret me) {
		me.type = Ast.tVoid;
		process(me.result);
		unify(((FnType)me.toBreak.type).result, me.result.type, me);
	}
	public void onCast(Cast me) {
		me.type = me.typer.type;
		process(me.expression);
		process(me.typer);
		if (me.expression != null && me.expression.type.get() != Ast.tUnsafePtr)
			unify(me.expression.type, me.typer.type, me);
	}
	private void unify(Type a, Type b, final Node me) {
		a = a.get();
		b = b.get();
		if (a == b)
			return;
		a.override = b; // assume equals
		final Type finA = a;
		undo.add(new Action() {
			@Override void undo() {
				finA.override = null;	
			}
		});
		if (a instanceof VarType)
			; // already done: a.override = b;
		else if (b instanceof VarType) {
			a.override = null;
			b.override = a;
		} else if (a instanceof FnType && b instanceof FnRefType) {
			firstCall((FnType)a, (FnRefType)b, me);
		} else if (b instanceof FnType && a instanceof FnRefType) {
			firstCall((FnType)b, (FnRefType)a, me);
		} else if (a instanceof FnType && b instanceof DispType) {
			firstDispCall((FnType)a, (DispType)b, me);
		} else if (b instanceof FnType && a instanceof DispType) {
			firstDispCall((FnType)b, (DispType)a, me);
		} else if (a.getClass() == b.getClass()) {
			if (a instanceof BuiltinType || b instanceof BuiltinType) {
				if (a != b) {
					a.override = null;
					reportError(me, "", a, b);
				}
				return;
			}
			if (a instanceof FnType) {
				FnType fta = (FnType) a;
				FnType ftb = (FnType) b;
				if (fta.params.size() != ftb.params.size())
					reportError(me, ": params count " + fta.params.size() + "!=" + ftb.params.size(), a, b);
				for (int i = 0, n = fta.params.size(); i < n; i++)
					unify(fta.params.get(i), ftb.params.get(i), me);
				unify(fta.result, ftb.result, me);
			} else if (a instanceof DispType) {
				final DispType dta = (DispType) a;
				final DispType dtb = (DispType) b;
				a.override = null; // connectChild() will patch the appropriate override
				final List<DispType> undoChildrenList = new ArrayList<DispType>();
				if (dta.connectChild(dtb, undoChildrenList) || dtb.connectChild(dta, undoChildrenList))
					undo.add(new Action() {
						@Override void undo() {
							for (DispType d: undoChildrenList)
								d.override = null;
						}
					});
				else {
					b.override = a;
					for (DispType c: dtb.subType) {
						if (dta.subType.add(c))
							undoChildrenList.add(c);
					}
					final List<DispType> undoParentsList = new ArrayList<DispType>();
					for (DispType c: dtb.superType) {
						if (dta.superType.add(c))
							undoParentsList.add(c);
					}
					undo.add(new Action() {
						@Override void undo() {
							for (DispType d: undoChildrenList)
								dta.subType.remove(d);
							for (DispType d: undoParentsList)
								dta.superType.remove(d);
						}
					});
					for(Atom i: dtb.used)
						useAtom(dta, i, me);
					for(Iterator<Entry<Atom, Type>> i = dta.disp.entrySet().iterator(); i.hasNext();) {
						Entry<Atom, Type> ae = i.next();
						Atom aa = ae.getKey();
						Type bt = dtb.disp.get(aa);
						if (bt == null) {
							i.remove();
							removeAtom(dta, aa, dtb, me);
						} else
							unify(ae.getValue(), bt, me);
					}
				}
			} else if (a instanceof FnRefType) {
				FnRefType fra = (FnRefType) a;
				FnRefType frb = (FnRefType) b;
				for (FnRefType.FnAndRefs fa: fra.fns)
					frb.addFn(fa);
			} else
				reportError(me, ": this should not happen", a, b);
		} else {
			a.override = null;
			reportError(me, "", a, b);
		}
	}
	private void removeAtom(final DispType dt, final Atom a, Type b, Node me) {
		if (dt.override != null)
			return;
		if (dt.used.contains(a))
			reportError(me, ": cannot match atom" + a.name, dt, b);
		final Type t = dt.disp.remove(a);
		if (t != null) {
			undo.add(new Action() {
				@Override void undo() {
					dt.disp.put(a, t);
				}
			});
		}
		for (DispType sub: dt.subType)
			removeAtom(sub, a, dt, me);
	}
	private void useAtom(final DispType dt, final Atom a, Node me) {
		if (dt.override != null)
			return;
		if (dt.used.contains(a))
			return;
		if (!dt.disp.containsKey(a))
			reportError(me, "dispatcher doesnt contain atom " + a.name);
		if (dt.used.add(a)) {
			undo.add(new Action() {
				@Override void undo() {
					dt.used.remove(a);
				}
			});
		}
		for (DispType t: dt.superType)
			useAtom(t, a, me);
	}
	private void reportError(Node me, String prefix, Type a, Type b) {
		reportError(me, "cannot match types " + a + " and " + b + prefix);
	}
	private void reportError(Node me, String text) {
		throw new TypematchError(new TypematchReason(text, me, null));
	}
	private void firstDispCall(FnType call, DispType dt, Node me) {
		call.override = null;
		dt.override = null;
		Atom a = null;
		for (;;){
			try {
				a = (Atom)((Const) call.caller.params.get(1)).value;
			} catch(Throwable e) {
				reportError(me, "disp function must be called with const atom");
			}
			if (dt.disp.containsKey(a))
				break;
			if (dt.disp.containsKey(ast.get("get"))) {
				Type t = attachGetter(call.caller, 0);
				if (!(t instanceof DispType))
					reportError(me, "getter should return disp");
				dt = (DispType) t;
			} else {
				List<TypematchReason> reasons = null;
				if (me instanceof Call) {
					Call thisCall = call.caller;
					reasons = new ArrayList<TypematchReason>();
					int undoPos = undo.size();
					FnDef fn = null;
					for (FnDef scope = thisCall.scope; scope != null; scope = scope.parent) {
						Node n = scope.named.get(a.name).target;
						if (n instanceof FnDef) {
							fn = (FnDef) n;
							break;
						}
					}
					for (; fn != null; fn = fn.overload) {
						if (fn.params.size() < 1 || !"_this".equals(fn.params.get(0).name))
							continue;
						if (thisCall.superCall != null && fn.params.size() > 1) {
							Ref ref = new Ref(a.name);
							ref.target = fn.name;
							thisCall.superCall.params.set(0, ref);
							thisCall.superCall.params.add(1, thisCall.params.get(0));
							Type prevSupercallType = thisCall.superCall.type;
							thisCall.superCall.type = null;
							try{
								process(thisCall.superCall);								
								return;
							} catch (TypematchError e) {
								onTypeMatchError(undoPos, reasons, fn, e);
								thisCall.superCall.params.set(0, thisCall);
								thisCall.superCall.params.remove(1);
								thisCall.superCall.type = prevSupercallType;
							}
						} else if (fn.params.size() == 1) {
							Node prevAtom = thisCall.params.get(1);
							thisCall.params.remove(1);
							Ref ref = new Ref(a.name);
							ref.target = fn.name;
							thisCall.params.add(0, ref);
							Type prevType = thisCall.type;
							thisCall.type = null;
							try {
								process(thisCall);
								return;
							} catch (TypematchError e) {
								onTypeMatchError(undoPos, reasons, fn, e);
								thisCall.params.remove(0);
								thisCall.params.add(1, prevAtom);
								thisCall.type = prevType;
							}
						}
					}
				}
				if (reasons == null)
					reportError(me, "no atom " + a.name + " in this type");
				else
					throw new TypematchError(new TypematchReason("no atom " + a.name + " in this type", me, reasons));
			}
		}

		useAtom(dt, a, me);
		FnType emptyCall = new FnType();
		emptyCall.result = call.result;
		unify(emptyCall, dt.disp.get(a), me);
	}
	private void firstCall(FnType call, final FnRefType fns, Node me) {
		call.override = null;
		fns.override = call;
		undo.add(new Action() {
			@Override void undo() {
				fns.override = null;
				for (FnRefType.FnAndRefs fn: fns.fns) {
					for (Ref ref: fn.refsToPatch)
						ref.target = fn.fnToInstantiate.name;
				}
			}
		});
		Call callNode = (Call) call.caller;
		for (FnRefType.FnAndRefs fn: fns.fns) {
			int undoPos = undo.size();
			List<TypematchReason> reasons = new ArrayList<TypematchReason>();
			byOverloads: for (FnDef overload = fn.fnToInstantiate;; overload = overload.overload) {
				if (overload == null)					
					throw new TypematchError(new TypematchReason("none of overloads matched:", me, reasons));
				//TODO: check recursive  instances
				
				FnDef callee = (FnDef) (fn.refsToPatch.size() == 0 ? overload : CopyMaker.copy(overload, call.params.size()));
				callee.type = call;
				try {
					if (callee.params.size() != call.params.size())
						reportError(me, "params count doesnt match: " + callee.params.size() + "!=" + call.params.size(), call, fns);
					for (int i = 0, n = callee.params.size(); i < n; i++) {
						Param p = callee.params.get(i);
						Type rpt = call.params.get(i).get();
						if (!p.byVref) {
							while (rpt instanceof DispType) {
								if (!((DispType)rpt).disp.containsKey(ast.get("get")))
									break;
								if (call.caller == null)
									reportError(me, "cannot match value ref to container ref");
								rpt = attachGetter(callNode, i + 1);
								call.params.set(i, rpt);
							}
						}
						p.type = rpt;
						if (p.typeExpr != null) {
							process(p.typeExpr);
							unify(p.type, p.typeExpr.type, p);
						}
					}
					for (Node n: callee.body)
						process(n);
					for (Ref ref: fn.refsToPatch)
						ref.target = callee.name;
					if (callee != overload) {
						ast.templateInstances.add(callee);
						if (overload.instances == null)
							overload.instances = new ArrayList<FnDef>();
						overload.instances.add(callee);
					}
					break byOverloads;
				} catch (TypematchError e) {
					onTypeMatchError(undoPos, reasons, overload, e);
				}
			}
		}
		call.caller = null;
	}
	private void onTypeMatchError(int undoPos, List<TypematchReason> reasons, FnDef overload, TypematchError e) {
		reasons.add(new TypematchReason(e.reason, "cannot use" + (overload.name != null ? " unnamed fn" : (" " + overload.name)), overload));
		for (int s = undo.size(); s-- > undoPos;)
			undo.remove(s).undo();
	}
	private Type attachGetter(Call callNode, int i) {
		Node actualParamNode = callNode.params.get(i);
		Call getter = Parser.posFrom(actualParamNode, new Call(actualParamNode));
		getter.params.add(
			Parser.posFrom(actualParamNode, new Const(ast.get("get"))));
		callNode.params.set(i, getter);
		onCall(getter);
		return getter.type.get();
	}
	static int callDepth = 0;
	private void process(Node n, Call superCall) {
		if (n == null || n.type != null)
			return;
		if (callDepth++ > 100)
			n.error("overflow");
		Call prevSuperCall = this.superCall;
		try {
			this.superCall = superCall;
			n.match(this);			
		} finally {
			this.superCall = prevSuperCall;
			callDepth--;			
		}
	}
	private void process(Node n) {
		process(n, null);
	}
	void process(Ast ast) {
		try{			
			this.ast = ast;
			for (Node f: ast.modules.values())
				process(f);
			process(ast.main);
			FnType ft = new FnType();
			ft.result = new VarType();
			unify(ast.main.type, ft, ast.main);
		} catch (TypematchError e) {
			throw new CompilerError(e.reason.toString());
		}
	}
}
