package com.github.karol11.flamberg;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FilePos {
	String fileName;
	int lineNumber;
	int pos;
	FilePos(Parser p) {
		fileName = p.fileName;
		lineNumber = p.lineNumber;
		pos = p.pos;
	}
	<N extends Node> N set(N n) {
		n.file = fileName;
		n.line = lineNumber;
		n.linePos = pos;
		return n;
	}
}

public class Parser {
	Ast ast;
	String fileName;
	int lineNumber = 0;
	char indentChar = 0;
	int indent;
	BufferedReader file;
	String line;
	int pos;
	FnDef currentScope;
	Map<String, FnDef> namedImports = new HashMap<String, FnDef>();
	
	public void parse(String fileName, Ast ast, FnDef module) {
		this.fileName = fileName;
		this.ast = ast;
		try {
			file = new BufferedReader( new FileReader(fileName));
			if (!readLine(true))
				error("empty source file");
			currentScope = module;
			parseExpressionStack(module.body);
		} catch (FileNotFoundException e) {
			throw new CompilerError("cannot read file " + fileName);
		}
	}

	private void parseExpressionStack(List<Node> toFill) {
		int baseIndent = indent;
		while (indent == baseIndent) {
			if (isImport(null))
				continue;
			FilePos fp = getPos();
			boolean chainCall = isn("..");
			Node n = !chainCall ? parseExpression() :
				parseUnTail(fp.set(isAlpha(peek(0)) ? new Ref("." + getId()) : new Ref("."))); // ..atomName handling
			if (pos == indent) {
				toFill.add(n);
				continue;
			}
			fp = getPos();
			if (!chainCall && n instanceof Ref && isAAndNotB('=', '=')) {
				String name = ((Ref)n).targetName;
				if (!isEoln() && isImport(name))
					continue;
				Node named = parseCornerCall(null);
				Name oldName = currentScope.named.get(name);
				if (oldName != null && named instanceof FnDef && oldName.target instanceof FnDef)
					((FnDef)named).overload = (FnDef) oldName.target;
				else if (oldName != null)
					error("Name " + name + " already defined. " + oldName.target.formatError("See") + "only functions can be overloaded");
				named.name = new Name(name, currentScope, named);
				toFill.add(named);
			} else
				toFill.add(parseCornerCall(n, chainCall));
		}
	}
	private boolean isImport(String name) {
		if (!isAAndNotB('+', '+'))
			return false;
		String mName = getId();
		while (isn('.'))
			mName += "/" + getId();
		if (!isEoln())
			error("extra characters in module name");
		if (ast.incompleteModules.contains(mName))
			error("circular dependencies in module " + mName);
		FnDef module = ast.modules.get(mName);
		if (module == null) {
			module = new FnDef();
			ast.modules.put(mName, module);
			ast.incompleteModules.add(mName);
			String fileName = new File(mName + ".~~~").exists() ?
				mName + ".~~~" :
				(mName + ".flam");
			new Parser().parse(fileName, ast, module);
			ast.incompleteModules.remove(ast.modules.size()-1);
		}
		if (name != null)
			namedImports.put(name, module);
		else
			currentScope.imports.add(module);
		readLine(true);
		return true;
	}

	private Node parseExpression() {
		Node n = parseConcatenation();
		while (pos != indent) {
			int sp = pos;
			skip();
			FilePos fp = getPos();
			if (isn("->")) n = makeCall("keyValue", fp, n, parseConcatenation());
			else if (isn("~")) n = makeCall("range", fp, n, parseConcatenation());
			else {
				pos = sp;
				break;
			}
		}
		return n;
	}


	private Node makeCall(String fnName, FilePos fp, Node... params) {
		return makeCall(fnName, fp, Arrays.asList(params));
	}
	private Node makeCall(String fnName, FilePos fp, List<Node> params) {
		Call r = fp.set(new Call(fnName == null ? null : fp.set(new Ref(fnName))));
		r.params.addAll(params);
		return r;
	}

	private Node parseConcatenation() {
		Node r = parseOrs();
		while (pos != indent) {
			int sp = pos;
			FilePos fp = getPos();
			if (!iss(',')) {
				pos = sp;
				break;
			}
			r = makeCall("concatenate", fp, r, parseOrs());
		}
		return r;
	}
	
	private Node parseOrs() {
		Node r = parseAnds();
		while (pos != indent) {
			int sp = pos;
			FilePos fp = getPos();
			if (!iss("||")) {
				pos = sp;
				break;
			}
			FilePos paramFp = getPos();
			r = makeCall("or", fp, r, paramFp.set(new FnDef(currentScope, parseAnds())));
		}
		return r;
	}

	private Node parseAnds() {
		Node r = parseComparisons();
		while (pos != indent) {
			int sp = pos;
			FilePos fp = getPos();
			if (!iss("&&")) {
				pos = sp;
				break;
			}
			FilePos paramFp = getPos();
			r = makeCall("and", fp, r, paramFp.set(new FnDef(currentScope, parseAnds())));
		}
		return r;
	}

	private Node parseComparisons() {
		Node r = parseAdds();
		if (pos != indent) {
			int sp = pos;
			skip();
			FilePos fp = getPos();
			if (isn("==")) r = makeCall("eq", fp, r, parseAdds());
			else if (isn("!=")) r = makeCall("not", fp, makeCall("eq", fp, r, parseAdds()));
			else if (isn("<=")) r = makeCall("not", fp, makeCall("gt", fp, r, parseAdds()));
			else if (isn(">=")) r = makeCall("not", fp, makeCall("gt", fp, parseAdds(), r));
			else if (isn(">")) r = makeCall("gt", fp, r, parseAdds());
			else if (isn("<")) r = makeCall("gt", fp, parseAdds(), r);
			else
				pos = sp;
		}
		return r;
	}

	private Node parseAdds() {
		Node r = parseMuls();
		while (pos != indent) {
			int sp = pos;
			skip();
			FilePos fp = getPos();
			if (isInfix('+')) r = makeCall("add", fp, r, parseMuls());
			else if (isInfix('-')) r = makeCall("sub", fp, r, parseMuls());
			else {
				pos = sp;
				break;
			}
		}
		return r;
	}

	private Node parseMuls() {
		Node r = parseUn();
		while (pos != indent) {
			int sp = pos;
			skip();
			FilePos fp = getPos();
			if (isInfix('*')) r = makeCall("mul", fp, r, parseUn());
			else if (isInfix('/')) r = makeCall("div", fp, r, parseUn());
			else if (isInfix('%')) r = makeCall("mod", fp, r, parseUn());
			else if (isAAndNotBOrC('&', '&', '=')) r = makeCall("bitAnd", fp, r, parseUn());
			else if (isAAndNotBOrC('|', '|', '=')) r = makeCall("bitOr", fp, r, parseUn());
			else if (isInfix('^')) r = makeCall("xor", fp, r, parseUn());
			else if (isn("<<", '=')) r = makeCall("shl", fp, r, parseUn()); //todo remove checking against <<=
			else if (isn(">>", '=')) r = makeCall("shr", fp, r, parseUn());
			else {
				pos = sp;
				break;
			}
		}
		return r;
	}

	private Node parseUn() {
		return parseUnTail(parseUnHead());
	}

	private Node parseUnTail(Node r) {
		while (pos != indent) {
			int savedPos = pos;
			FilePos fp = getPos();
			if (isn('.')) {
				if (isAlpha(peek(0))) {
					if (r instanceof Ref) {
						Ref rr = (Ref) r;
						FnDef namespace = namedImports.get(rr.targetName);
						if (namespace != null) {
							String importedName = getId();
							rr.target = namespace.named.get(importedName);
							if (rr.target == null)
								error("module " + rr.targetName + " does not contain name " + importedName);
							rr.targetName = importedName;
							continue;
						}
					}
					r = makeCall(null, fp, r, fp.set(new Const(ast.get(getId()))));
				} else
					r = fp.set(new Call(r));
			} else if (isn('[')) {
				r = makeCall("at", fp, parseCorner(r));
				expectSameLine(']');
			} else if (isn('(')) {
				r = fp.set(new Call(parseCorner(r)));
				expectSameLine(')');
			} else if (isInfix('`')) {
				r = fp.set(new Cast(r, isSpace(peek(0)) ? parseCornerCall(null) : parseExpression()));
			} else {
				if (iss(":=")) r = makeCall(null, fp, makeCall(null, fp, r, fp.set(new Const(ast.get("set")))), parseCornerCall(null));
				else if (isn("+=")) r = makeCall("setOp", fp, r, fp.set(new Ref("add")), parseCornerCall(null));
				else if (isn("-=")) r = makeCall("setOp", fp, r, fp.set(new Ref("sub")), parseCornerCall(null));
				else if (isn("*=")) r = makeCall("setOp", fp, r, fp.set(new Ref("mul")), parseCornerCall(null));
				else if (isn("/=")) r = makeCall("setOp", fp, r, fp.set(new Ref("div")), parseCornerCall(null));
				else if (isn("%=")) r = makeCall("setOp", fp, r, fp.set(new Ref("mod")), parseCornerCall(null));
				else if (isn("&=")) r = makeCall("setOp", fp, r, fp.set(new Ref("bitAnd")), parseCornerCall(null));
				else if (isn("!=")) r = makeCall("setOp", fp, r, fp.set(new Ref("bitOr")), parseCornerCall(null));
				else if (isn("^=")) r = makeCall("setOp", fp, r, fp.set(new Ref("xor")), parseCornerCall(null));
				else if (isn("<<=")) r = makeCall("setOp", fp, r, fp.set(new Ref("shl")), parseCornerCall(null));
				else if (isn(">>=")) r = makeCall("setOp", fp, r, fp.set(new Ref("shr")), parseCornerCall(null));
				else if (isn("++")) r = makeCall("postIncrement", fp, r);
				else if (isn("--")) r = makeCall("postDecrement", fp, r);
				else {
					pos = savedPos;
					break;
				}
			}
		}
		return r;
	}

	private void expectSameLine(char mark) {
		if (pos == indent)
			error("expected single line expression ended with " + mark);
		if (!iss(mark))
			error("expected " + mark);
	}

	private Node parseUnHead() {
		FilePos fp = getPos();
		if (iss("[]")) {
			expectIndent("incomplete multiline array");
			List<Node> params = new ArrayList<Node>();
			params.add(fp.set(new Ref("fixedList")));
			parseExpressionStack(params);
			return fp.set(new Call(params));
		}
		if (isn('#')) {
			Disp r = fp.set(new Disp());
			if (isn('#'))
				r.exportAll = currentScope;
			while (!isEoln()) {
				FilePos tagFp = getPos();
				String name = getId();
				r.variants.put(ast.get(name), tagFp.set(new FnDef(currentScope, tagFp.set(new Ref(name)))));
			}
			if (isIndent()) {
				int i = indent;
				while (i == indent) {
					if (isn('.')) {
						skip();
						r.defs.add(parseCornerCall(null));
					} else {
						FilePos tagFp = getPos();
						String name = getId();
						FnDef f = tagFp.set(new FnDef(currentScope));
						if (!isEoln())
							f.body.add(parseCornerCall(null));
						else if (isIndent())
							parseFnBody(f, false);
						else
							f.body.add(tagFp.set(new Ref(name)));
						r.variants.put(ast.get(name), f);						
					}
				}
			}
			return r;
		}
		if (isn(':')) {
			if (!isSpace(peek(0)))
				return fp.set(new FnDef(currentScope, !isAlpha(peek(-2)) ?
					parseExpression() :
					parseCornerCall(null)));
			FnDef r = fp.set(new FnDef(currentScope));
			parseFnBody(r, true);
			while (r.body.size() > 1) {
				Node p = r.body.get(0);
				if (p instanceof Ref)
					r.params.add(posFrom(p, new Param(new Name(((Ref)p).targetName, r, null), null)));
				else if (p instanceof Cast) {
					Cast c = (Cast) p;
					if (c.expression == null)
						r.params.add(posFrom(p, new Param(new Name("", r, null), c.typer)));
					else if (c.expression instanceof Ref)
						r.params.add(posFrom(p, new Param(new Name(((Ref)c.expression).targetName, r, null), c.typer)));
					else
						break;
				} else
					break;
				r.body.remove(0);
			}
			return r;
		}
		if (isn('^')) {
			Ret r = fp.set(new Ret());
			r.yields = isn('^');
			if (peek(0) > ' ')
				r.toBreakName = getId();
			skip();
			r.result = isEoln() ? fp.set(new Const(null)) : parseCornerCall(null);
			return r;
		}
		if (isn('(')) {
			Node r = parseExpression();
			if (!iss(')'))
				error("expected ')'");
			return r;
		}
		if (isn('.')) {
			return fp.set(isAlpha(peek(0)) ?
				new Const(ast.get(getId())) :
				new Const(null));
		}
		if (isn('[')) {
			List<Node> params = new ArrayList<Node>();
			params.add(fp.set(new Ref("fixedList")));
			do
				params.add(parseExpression());
			while (!iss(']'));
			return fp.set(new Call(params));
		}
		if (isn('-'))
			return makeCall("negate", fp, parseUn());
		if (isn('~'))
			return makeCall("invert", fp, parseUn());
		if (isn('!'))
			return makeCall("not", fp, parseUn());
		if (isn("++"))
			return makeCall("preIncrement", fp, parseUn());
		if (isn("--"))
			return makeCall("preDecrement", fp, parseUn());
		if (isn('`'))
			return fp.set(new Cast(null, isSpace(peek(0)) ? parseCornerCall(null) : parseExpression()));
		if (isn('\'')) {
			if (peek(0) == 0) {
				int basicIndent = indent;
				int toTrim = Integer.MAX_VALUE;
				List<String> lines = new ArrayList<String>();
				while(readLine(false) && (pos == line.length() || indent > basicIndent)) {
					if (indent < toTrim)
						toTrim = indent;
					lines.add(line);
				}
				if (lines.isEmpty())
					error("incomplete string literal");
				StringBuilder r = new StringBuilder();
				for (String s: lines)
					r.append(s.substring(toTrim)).append('\n');
				return fp.set(new Const(r.toString()));
			}
			int endPos = line.indexOf('\'', pos);
			if (endPos < 0)
				error("'string' not terminated");
			Node r = fp.set(new Const(line.substring(pos, endPos)));
			pos = endPos + 1;
			return r;
		}
		if (isn('\"')) {
			if (peek(0) == 0) {
				int basicIndent = indent;
				int toTrim = Integer.MAX_VALUE;
				List<Object> elements = new ArrayList<Object>();
				FilePos fragmentFp = getPos();
				while(readLine(false) && indent > basicIndent) {
					if (indent < toTrim)
						toTrim = indent;
					pos = 0;
					for (;;) {
						int startPos = pos;
						pos = line.indexOf('{', pos);
						if (pos < 0)
							pos = line.length();
						if (startPos < pos) {
							if (startPos == 0)
								elements.add(line.substring(0, pos));
							else
								elements.add(fragmentFp.set(new Const(line.substring(startPos, pos))));
							fragmentFp = getPos();
						}
						if (pos == line.length())
							break;
						pos++;
						elements.add(parseCornerCall(null));
						expectSameLine('}');
					}
				}
				if (elements.isEmpty())
					error("incomplete string literal");
				StringBuilder current = new StringBuilder();
				List<Node> params = new ArrayList<Node>();
				params.add(fp.set(new Ref("concatenate")));
				boolean firstLine = true;
				for (Object s: elements) {
					if (s instanceof String) {
						if (!firstLine) current.append('\n');
						current.append(((String)s).substring(toTrim)).append('\n');
					} else {
						if (current.length() > 0) {
							params.add(fp.set(new Const(current.toString())));
							current.setLength(0);
						}
						params.add((Node)s);
					}
				}
				if (current.length() > 0)
					params.add(fp.set(new Const(current.toString())));
				return params.size() > 2 ? fp.set(new Call(params)) : params.get(1);
			}
			List<Node> params = new ArrayList<Node>();
			params.add(fp.set(new Ref("concatenate")));
			for (int i = pos, n = line.length(); i < n; i++) {
				if (i == n)
					error("unterminated \"string\"");
				int c = line.charAt(i);
				if (c == '{') {
					if (pos != i) {
						params.add(getPos().set(new Const(line.substring(pos, i))));
						pos = i + 1;
					}
					params.add(parseCornerCall(null));
					expectSameLine('}');
				} else if (c == '"') {
					if (pos != i)
						params.add(getPos().set(new Const(line.substring(pos, i))));
					pos = i + 1;
					break;
				}
			}
			return
				params.size() > 2 ? fp.set(new Call(params)) :
				params.size() > 1 ? params.get(1) : fp.set(new Const(""));
		}
		if (inRange(peek(0), '0', '9'))
			return parseNumber();
		if (isAlpha(peek(0))) {
			String id = getId();
			return fp.set(id.equals("false") ? new Const(false) :
				id.equals("true") ? new Const(true) :
				new Ref(id));
		}
		error("syntax error");
		return null;
	}
	
	protected Const parseNumber() {
		FilePos fp = getPos();
		long r = 0;
		if (isn("0x")) {
			int shift = 0;
			for (;; shift +=4) {
				char c = peek(0);
				if (c == '_')
					continue;
				int d =
					inRange(c, '0', '9') ? c - '0' :
					inRange(c, 'A', 'F') ? c - 'A' + 10 :
					inRange(c, 'a', 'f') ? c - 'a' + 10 : -1;
				if (d < 0)
					break;
				pos++;
				if (shift >= 64)
					error("hexadecimal const exceedes 64 bits");
				r |= d << shift;
			}
		} else if (isn("0b")) {
			int shift = 0;
			for (;; shift++) {
				char c = peek(0);
				if (c == '_')
					continue;
				if (!inRange(c, '0', '1'))
					break;
				r |= (c - '0') << shift;
				pos++;
				if (shift >= 64)
					error("binary const exceedes 64 bits");
			}
		} else if (isn("0o")) {
			int shift = 0;
			for (;; shift +=3) {
				char c = peek(0);
				if (c == '_')
					continue;
				if (!inRange(c, '0', '7'))
					break;
				pos++;
				if (shift >= 64)
					error("hexadecimal const exceedes 64 bits");
				r |= (c - '0') << shift;
			}
		} else {
			for (;;) {
				char c = peek(0);
				if (c == '_')
					continue;
				if (!inRange(c, '0', '9'))
					break;
				pos++;
				long nr = r * 10 + c - '0';
				if (nr < r)
					error("decimal const exceedes 63 bits, use hexadecimal");
				r = nr;
			}
			if (isn('.')) {
				double dr = r;
				for(double offs = 0.1;; offs *= 0.1) {
					char c = peek(0);
					if (!inRange(c, '0', '9'))
						break;
					pos++;
					r += offs * (c - '0');
				}
				return fp.set(isn('f') ?
					new Const(Float.valueOf((float)dr)) : 
					new Const(Double.valueOf(dr)));
			}
		}
		boolean isUnsigned = isn('u');
		return fp.set((isn('l') || r > 0xffffffffL) ? 
			new Const(r, isUnsigned) :
			new Const(Integer.valueOf((int)r), isUnsigned));
	}

	private void parseFnBody(FnDef f, boolean corner) {
		FnDef prevScope = currentScope;
		currentScope = f;
		Map<String, FnDef> prevNamedImports = namedImports;
		namedImports = new HashMap<String, FnDef>(namedImports);
		if (corner)
			f.body = parseCorner(null);
		else
			parseExpressionStack(f.body);
		namedImports = prevNamedImports;
		currentScope = prevScope;
	}

	Node parseCornerCall(Node first, boolean forceCall) {
		FilePos fp = getPos();
		List<Node> row = parseCorner(first);
		return forceCall || row.size() > 1 ? fp.set(new Call(row)) : row.get(0);		
	}
	Node parseCornerCall(Node first) {
		return parseCornerCall(first, false);
	}
	List<Node> parseCorner(Node first) {
		List<Node> row = new ArrayList<Node>();
		if (first != null)
			row.add(first);
		for (;;) {
			char c = peek(0);
			if (c == ')' || c == ']' || c == '}')
				break;
			if (isEoln()) {
				if (isIndent())
					parseExpressionStack(row);
				break;
			}
			row.add(parseExpression());
			if (pos == indent)
				break;
		}
		if (row.size() ==0)
			error("expected operator");
		return row;
	}
	
	static boolean isIdLetter(char c) {
		return isAlpha(c) || inRange(c, '0', '9');
	}
	static boolean isAlpha(char c) {
		return inRange(c, 'a', 'z') || inRange(c, 'A', 'Z') || c == '_';
	}
	String getId(){
		skip();
		char c = line.charAt(pos);
		if (!isAlpha(c))
			error("expected name");
		int start = pos;
		while (pos < line.length() && isIdLetter(line.charAt(pos)))
			pos++;
		return line.substring(start, pos);
	}

	char peek(int offset) {
		offset += pos;
		return offset < 0 || offset >= line.length() ? 0 : line.charAt(offset);
	}

	private boolean isIndent() {
		int prev = indent;
		return readLine(true) && prev < indent;
	}
	private void expectIndent(String message) {
		int prev = indent;
		if (!isEoln() || !readLine(true) || prev >= indent)
			error(message);
	}

	private boolean readLine(boolean skipEmpty) {
		try {
			do {
				if (file == null)
					return false;
				pos = indent = 0;
				lineNumber++;
				line = file.readLine();
				if (line == null) {
					file.close();
					file = null;
					indent = pos = -1;
					return false;
				}
				if (isn(' '))
					skipIndent(' ', '\t');
				else if (isn('\t'))
					skipIndent('\t', ' ');
				indent = pos;
				indentChar = peek(0);
			} while (skipEmpty && isEoln());
		} catch (IOException e) {
			throw new CompilerError("cannot read file " + fileName);
		}
		return true;
	}
	private boolean isn(String string) {
		if (pos >= line.length() || !line.startsWith(string,  pos))
			return false;
		pos += string.length();
		return true;
	}
	private boolean isn(String string, char unexpectedTail) {
		if (pos >= line.length() || !line.startsWith(string,  pos))
			return false;
		if (peek(string.length()) == unexpectedTail)
			return false;
		pos += string.length();
		return true;
	}
	boolean iss(String s) {
		skip();
		return isn(s);
	}
	boolean iss(char c) {
		skip();
		return isn(c);
	}
	boolean isn(char c) {
		if (pos >= line.length() || line.charAt(pos) != c)
			return false;
		pos++;
		return true;
	}
	static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\0'; }
	boolean isInfix(char op) {
		skip();
		return (isSpace(peek(-1)) == isSpace(peek(1))) && peek(1) != op && isn(op);
	}
	boolean isAAndNotB(char expected, char unexpectedTail){
		skip();
		if (peek(0) == expected && peek(1) != unexpectedTail) {
			pos++;
			return true;
		}
		return false;
	}
	boolean isAAndNotBOrC(char expected, char unexpectedTail1, char unexpectedTail2){
		skip();
		if (peek(0) == expected) {
			char c = peek(1);
			if (c == unexpectedTail1 || c == unexpectedTail2)
				return false;
			pos++;
			return true;
		}
		return false;
	}
	void skip() {
		while (pos < line.length()) {
			char c = line.charAt(pos);
			if (c == ' ' || c == '\t')
				pos++;
			else
				break;
		}
	}
	
	static boolean inRange(char c, char from, char to) {
		return c >= from && c <= to;
	}

	boolean isEoln() {
		skip();
		return pos == line.length() || isn(';');
	}
	void skipIndent(char wantedChar, char unwantedChar) {
		if (indentChar == unwantedChar)
			error("space indentaion after tab indentaion");
		while (isn(wantedChar)) {}
		if (isn(unwantedChar))
			error("mixed tabs and spaces indentation");
	}

	void error(String s) {
		throw new CompilerError("Error at " + fileName + "(" + lineNumber + ":" + pos + "):" + s);
	}

	public String toString() {
		return lineNumber + ":" + line.substring(0, pos) + "<@>" + line.substring(pos);
	}
	FilePos getPos() { return new FilePos(this); }
	static <N extends Node> N posFrom(Node src, N n) {
		n.file = src.file;
		n.line = src.line;
		n.linePos = src.linePos;
		return n;
	}
}
