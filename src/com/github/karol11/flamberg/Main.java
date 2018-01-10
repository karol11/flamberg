package com.github.karol11.flamberg;

public class Main {

	public static void main(String[] args) {
		Ast ast = new Ast();
		ast.main = new FnDef();
		String src = "test.~~~";
		new Parser().parse(src, ast, ast.main);
		new NameResolver().process(ast);
	}
}
