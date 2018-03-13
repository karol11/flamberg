package com.github.karol11.flamberg;

import java.io.IOException;

public class Main {

	public static void main(String[] args) throws IOException {
		Ast ast = new Ast();
		ast.main = new FnDef();
		String src = "test.~~~";
		new Parser().parse(src, ast, ast.main);
		new NameResolver().process(ast);
		new TypeResolver().process(ast);
		new Devirtualizer().process(ast);
		System.out.print(ast.main.toString());
	}
}
