package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var argCounter = 0

    /**
     * Returns the next unique argument name and increments the counter.
     */
    private fun freshArg(): String {
        return "arg${argCounter++}"
    }

    /**
     * Main entry point for the compiler.
     */
    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        argCounter = 0

        val compiledFunctions = program.functionDeclaration()
            .joinToString("\n\n") { functionCtx ->
                compileFunctionDecl(functionCtx)
            }

        // Build the final Java file
        return """
public class $className {

${compiledFunctions.prependIndent("    ")}

}
""".trimStart()
    }

    /**
     * Compiles a single MiniKotlin function into a Java static method.
     */
    private fun compileFunctionDecl(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val functionName = ctx.IDENTIFIER().text
        val returnType   = javaBoxedType(ctx.type().text)

        // Build the parameter list string
        val regularParams = ctx.parameterList()
            ?.parameter()
            ?.joinToString(", ") { param ->
                val javaType  = javaBoxedType(param.type().text)
                val paramName = param.IDENTIFIER().text
                "$javaType $paramName"
            }
            ?: ""  // empty string when there are no parameters

        // Append the continuation parameter after the regular ones
        // If there are no regular params we just have the continuation alone
        val allParams = if (regularParams.isEmpty()) {
            "Continuation<$returnType> __continuation"
        } else {
            "$regularParams, Continuation<$returnType> __continuation"
        }

        // "main" needs the real Java signature
        return if (functionName == "main") {
            // Create a dummy terminal continuation so the chain ends cleanly
            val body = compileBlockAsCPS(ctx.block(), "__end_continuation")
            """public static void main(String[] args) {
    Continuation<$returnType> __end_continuation = (__ignored) -> {};
${body.prependIndent("    ")}
}"""
        } else {
            val body = compileBlockAsCPS(ctx.block(), "__continuation")
            """public static void $functionName($allParams) {
${body.prependIndent("    ")}
}"""
        }
    }

    /**
     * Compiles an entire block into Java statements.
     */
    private fun compileBlockAsCPS(
        block: MiniKotlinParser.BlockContext,
        finalCont: String
    ): String {
        return compileStatements(block.statement(), 0, finalCont)
    }

    /**
     * The core CPS sequencing function for statements.
     */
    private fun compileStatements(
        stmts: List<MiniKotlinParser.StatementContext>,
        index: Int,
        finalCont: String
    ): String {
        // No statements left
        if (index >= stmts.size) {
            return "$finalCont.accept(null);"
        }

        val stmt = stmts[index]
        val rest = { cont: String -> compileStatements(stmts, index + 1, cont) }

        return when {
            stmt.returnStatement()      != null -> compileReturn(stmt.returnStatement()!!, finalCont)
            stmt.variableDeclaration()  != null -> compileVarDecl(stmt.variableDeclaration()!!, finalCont, rest)
            stmt.variableAssignment()   != null -> compileVarAssign(stmt.variableAssignment()!!, finalCont, rest)
            stmt.ifStatement()          != null -> compileIf(stmt.ifStatement()!!, finalCont, rest)
            stmt.whileStatement()       != null -> compileWhile(stmt.whileStatement()!!, finalCont, rest)
            stmt.expression()           != null -> compileExprStatement(stmt.expression()!!, finalCont, rest)
            else                                -> rest(finalCont)
        }
    }

    /**
     * Compiles a "return" statement.
     */
    private fun compileReturn(ctx: MiniKotlinParser.ReturnStatementContext, cont: String): String {
        val returnExpr = ctx.expression()

        // "return" with no value - call continuation with null and stop
        if (returnExpr == null) {
            return "$cont.accept(null);\nreturn;"
        }

        // "return <expr>" - evaluate the expression, then pass the result to the continuation
        return compileExprToCPS(returnExpr) { resultValue ->
            "$cont.accept($resultValue);\nreturn;"
        }
    }

    /**
     * Compiles a variable declaration.
     */
    private fun compileVarDecl(
        ctx: MiniKotlinParser.VariableDeclarationContext,
        finalCont: String,
        rest: (String) -> String
    ): String {
        val variableName = ctx.IDENTIFIER().text
        val javaType     = javaBoxedType(ctx.type().text)

        return compileExprToCPS(ctx.expression()) { value ->
            "$javaType $variableName = $value;\n" + rest(finalCont)
        }
    }

    /**
     * Compiles a variable assignment: "x = someExpression".
     */
    private fun compileVarAssign(
        ctx: MiniKotlinParser.VariableAssignmentContext,
        finalCont: String,
        rest: (String) -> String
    ): String {
        val variableName = ctx.IDENTIFIER().text

        return compileExprToCPS(ctx.expression()) { value ->
            "$variableName = $value;\n" + rest(finalCont)
        }
    }

    /**
     * Compiles an if/else statement.
     */
    private fun compileIf(
        ctx: MiniKotlinParser.IfStatementContext,
        finalCont: String,
        rest: (String) -> String
    ): String {
        // Step 1: decide on the "after" continuation.
        val remainingCode = rest(finalCont)
        val afterCont: String
        val preamble: String

        if (remainingCode.isBlank() || remainingCode.trim() == "$finalCont.accept(null);") {
            // Nothing meaningful after the if/else
            afterCont = finalCont
            preamble  = ""
        } else {
            // Wrap the remaining code in a named lambda so both branches share it
            val tmp   = freshArg()
            afterCont = "__after_$tmp"
            preamble  = "Continuation<Void> $afterCont = (__v_$tmp) -> {\n" +
                    remainingCode.prependIndent("    ") + "\n};\n"
        }

        // Evaluate the condition, then emit the if/else inside the callback
        val thenBody = compileBlockAsCPS(ctx.block(0), afterCont)
        val elseBody = if (ctx.block().size > 1) {
            compileBlockAsCPS(ctx.block(1), afterCont)
        } else {
            "$afterCont.accept(null);"
        }

        return compileExprToCPS(ctx.expression()) { condValue ->
            buildString {
                append(preamble)
                append("if ($condValue) {\n")
                append(thenBody.prependIndent("    "))
                if (!thenBody.trimEnd().endsWith(";")) append("\n")
                append("}\n")
                append("else {\n")
                append(elseBody.prependIndent("    "))
                if (!elseBody.trimEnd().endsWith(";")) append("\n")
                append("}\n")
            }
        }
    }

    /**
     * Compiles a while loop.
     *
     * We create a Runnable stored in a one-element array.
     * The lambda inside can reference the array to call itself again.
     *
     * Generated pattern:
     *
     *   Runnable[] __loop_arg0_box = { null };
     *   __loop_arg0_box[0] = () -> {
     *       if (<condition>) {
     *           <body code>
     *           __loop_arg0_box[0].run();   // next iteration
     *       } else {
     *           <code after the loop>       // loop is done
     *       }
     *   };
     *   __loop_arg0_box[0].run();           // begin the first iteration
     */
    private fun compileWhile(
        ctx: MiniKotlinParser.WhileStatementContext,
        finalCont: String,
        rest: (String) -> String
    ): String {
        val loopName = "__loop_${freshArg()}"
        val loopRef  = "${loopName}_box[0]"   // how we refer to the lambda inside itself
        val afterLoopCode = rest(finalCont)
        val loopRecurseCont = "__loop_recurse_${freshArg()}"

        return buildString {
            append("Runnable[] ${loopName}_box = { null };\n")
            append("$loopRef = () -> {\n")
            append(buildIterationBody(ctx, loopRef, afterLoopCode, loopRecurseCont).prependIndent("    "))
            append("\n};\n")
            append("$loopRef.run();\n")
        }
    }

    /**
     * Helper for compileWhile: builds the body of one loop iteration.
     */
    private fun buildIterationBody(
        ctx: MiniKotlinParser.WhileStatementContext,
        loopRef: String,
        afterLoopCode: String,
        loopRecurseCont: String
    ): String {
        val bodyCode = compileBlockWithLoopRecurse(ctx.block(), loopRecurseCont, loopRef)

        return compileExprToCPS(ctx.expression()) { condValue ->
            buildString {
                append("if ($condValue) {\n")
                append(bodyCode.prependIndent("    "))
                if (!bodyCode.trimEnd().endsWith(";")) append("\n")
                append("} else {\n")
                append(afterLoopCode.prependIndent("    "))
                if (!afterLoopCode.trimEnd().endsWith(";")) append("\n")
                append("}\n")
            }
        }
    }

    /**
     * Compiles a block where "falling off the end" means starting the next loop
     * iteration (by calling loopRef.run()), rather than calling a normal finalCont.
     */
    private fun compileBlockWithLoopRecurse(
        block: MiniKotlinParser.BlockContext,
        loopRecurseCont: String,
        loopRef: String
    ): String {
        // Compile the body with loopRecurseCont as the finalCont.
        val bodyCode = compileBlockAsCPS(block, loopRecurseCont)

        // Declare the continuation that calls loopRef.run().
        val contDecl = "Continuation<Void> $loopRecurseCont = (__ignored) -> { $loopRef.run(); };\n"

        return contDecl + bodyCode
    }


    /**
     * Compiles a standalone expression used as a statement.
     */
    private fun compileExprStatement(
        expr: MiniKotlinParser.ExpressionContext,
        finalCont: String,
        rest: (String) -> String
    ): String {
        return if (hasFunctionCall(expr)) {
            compileExprToCPS(expr) { _ ->
                rest(finalCont)
            }
        } else {
            val exprText = pureExpr(expr)
            "$exprText;\n" + rest(finalCont)
        }
    }


    /**
     * Evaluates an expression in CPS and passes the resulting Java value string
     * to the continuation "k".
     */
    private fun compileExprToCPS(
        expr: MiniKotlinParser.ExpressionContext,
        k: (String) -> String
    ): String {
        // No function calls - plain Java expression
        if (!hasFunctionCall(expr)) {
            return k(pureExpr(expr))
        }

        // The expression (or part of it) is a function call
        return when (expr) {

            // Direct function call: f(arg1, arg2, ...)
            is MiniKotlinParser.FunctionCallExprContext -> {
                val functionName   = expr.IDENTIFIER().text
                val argExpressions = expr.argumentList().expression()

                // Evaluate each argument expression
                compileCPSArgs(argExpressions, emptyList()) { argValueStrings ->
                    val argList    = argValueStrings.joinToString(", ")
                    val resultName = freshArg()
                    val codeAfterCall = k(resultName)

                    if (functionName == "println") {
                        "Prelude.println($argList, ($resultName) -> {\n" +
                                codeAfterCall.prependIndent("    ") + "\n});"
                    } else {
                        "$functionName($argList, ($resultName) -> {\n" +
                                codeAfterCall.prependIndent("    ") + "\n});"
                    }
                }
            }

            // Binary operators: evaluate both sides, then combine
            is MiniKotlinParser.MulDivExprContext ->
                compileBinaryToCPS(expr.expression(0), expr.expression(1), binaryOp(expr), k)
            is MiniKotlinParser.AddSubExprContext ->
                compileBinaryToCPS(expr.expression(0), expr.expression(1), binaryOp(expr), k)
            is MiniKotlinParser.ComparisonExprContext ->
                compileBinaryToCPS(expr.expression(0), expr.expression(1), binaryOp(expr), k)
            is MiniKotlinParser.EqualityExprContext -> {
                val op = binaryOp(expr)
                compileExprToCPS(expr.expression(0)) { leftVal ->
                    compileExprToCPS(expr.expression(1)) { rightVal ->
                        if (op == "==") {
                            k("java.util.Objects.equals($leftVal, $rightVal)")
                        } else {
                            k("!java.util.Objects.equals($leftVal, $rightVal)")
                        }
                    }
                }
            }
            is MiniKotlinParser.AndExprContext ->
                compileExprToCPS(expr.expression(0)) { leftValue ->
                    val rightCode = compileExprToCPS(expr.expression(1), k)
                    val falseCode = k("false")

                    buildString {
                        append("if ($leftValue) {\n")
                        append(rightCode.prependIndent("    "))
                        if (!rightCode.trimEnd().endsWith(";")) append("\n")
                        append("} else {\n")
                        append(falseCode.prependIndent("    "))
                        if (!falseCode.trimEnd().endsWith(";")) append("\n")
                        append("}\n")
                    }
                }

            is MiniKotlinParser.OrExprContext ->
                compileExprToCPS(expr.expression(0)) { leftValue ->
                    val trueCode = k("true")
                    val rightCode = compileExprToCPS(expr.expression(1), k)

                    buildString {
                        append("if ($leftValue) {\n")
                        append(trueCode.prependIndent("    "))
                        if (!trueCode.trimEnd().endsWith(";")) append("\n")
                        append("} else {\n")
                        append(rightCode.prependIndent("    "))
                        if (!rightCode.trimEnd().endsWith(";")) append("\n")
                        append("}\n")
                    }
                }

            // Logical NOT: evaluate the inner expression, then wrap with "!"
            is MiniKotlinParser.NotExprContext ->
                compileExprToCPS(expr.expression()) { innerValue ->
                    k("!($innerValue)")
                }

            // Primary / parenthesised - should be pure, but handle defensively
            else -> k(pureExpr(expr))
        }
    }

    /**
     * Compiles a binary expression (left OP right).
     */
    private fun compileBinaryToCPS(
        left:  MiniKotlinParser.ExpressionContext,
        right: MiniKotlinParser.ExpressionContext,
        op:    String,
        k:     (String) -> String
    ): String {
        return compileExprToCPS(left) { leftValue ->
            compileExprToCPS(right) { rightValue ->
                k("($leftValue $op $rightValue)")
            }
        }
    }

    /**
     * Evaluates a list of argument expressions one by one
     */
    private fun compileCPSArgs(
        args: List<MiniKotlinParser.ExpressionContext>,
        acc:  List<String>,
        k:    (List<String>) -> String
    ): String {
        if (args.isEmpty()) {
            return k(acc)
        }

        val firstArg      = args.first()
        val remainingArgs = args.drop(1)

        // Evaluate the first argument, then recurse
        return compileExprToCPS(firstArg) { firstValue ->
            compileCPSArgs(remainingArgs, acc + firstValue, k)
        }
    }

    /**
     * Converts an expression parse-tree node directly into a Java expression string.
     */
    private fun pureExpr(expr: MiniKotlinParser.ExpressionContext): String {
        return when (expr) {
            // Function call — should not reach here
            is MiniKotlinParser.FunctionCallExprContext ->
                error("Function calls are not pure expressions: ${expr.text}")

            // Parenthesised expression or literal or identifier
            is MiniKotlinParser.PrimaryExprContext ->
                pureExpr(expr.primary())

            // Logical NOT
            is MiniKotlinParser.NotExprContext ->
                "!(${pureExpr(expr.expression())})"

            // Binary operators — wrap in parentheses for clarity and precedence safety.
            is MiniKotlinParser.MulDivExprContext ->
                "(${pureExpr(expr.expression(0))} ${binaryOp(expr)} ${pureExpr(expr.expression(1))})"
            is MiniKotlinParser.AddSubExprContext ->
                "(${pureExpr(expr.expression(0))} ${binaryOp(expr)} ${pureExpr(expr.expression(1))})"
            is MiniKotlinParser.ComparisonExprContext ->
                "(${pureExpr(expr.expression(0))} ${binaryOp(expr)} ${pureExpr(expr.expression(1))})"
            is MiniKotlinParser.EqualityExprContext -> {
                val op    = binaryOp(expr)
                val left  = pureExpr(expr.expression(0))
                val right = pureExpr(expr.expression(1))
                if (op == "==") "java.util.Objects.equals($left, $right)"
                else            "!java.util.Objects.equals($left, $right)"
            }
            is MiniKotlinParser.AndExprContext ->
                "(${pureExpr(expr.expression(0))} && ${pureExpr(expr.expression(1))})"
            is MiniKotlinParser.OrExprContext ->
                "(${pureExpr(expr.expression(0))} || ${pureExpr(expr.expression(1))})"

            else -> expr.text
        }
    }

    /**
     * Converts a "primary" expression into a Java expression string.
     */
    private fun pureExpr(primary: MiniKotlinParser.PrimaryContext): String {
        return when (primary) {
            is MiniKotlinParser.ParenExprContext      -> "(${pureExpr(primary.expression())})"
            is MiniKotlinParser.IntLiteralContext     -> primary.text
            is MiniKotlinParser.StringLiteralContext  -> primary.text
            is MiniKotlinParser.BoolLiteralContext    -> primary.text
            is MiniKotlinParser.IdentifierExprContext -> primary.text
            else                                      -> primary.text
        }
    }

    /**
     * Checks whether an expression contains a function call anywhere inside it.
     */
    private fun hasFunctionCall(expr: MiniKotlinParser.ExpressionContext): Boolean {
        return when (expr) {
            is MiniKotlinParser.FunctionCallExprContext -> true
            is MiniKotlinParser.PrimaryExprContext -> {
                val primary = expr.primary()
                if (primary is MiniKotlinParser.ParenExprContext) {
                    hasFunctionCall(primary.expression())
                } else {
                    false  // literal or identifier
                }
            }

            is MiniKotlinParser.NotExprContext ->
                hasFunctionCall(expr.expression())
            is MiniKotlinParser.MulDivExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))
            is MiniKotlinParser.AddSubExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))
            is MiniKotlinParser.ComparisonExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))
            is MiniKotlinParser.EqualityExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))
            is MiniKotlinParser.AndExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))
            is MiniKotlinParser.OrExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            else -> false
        }
    }

    /**
     * Extracts the operator symbol from a binary expression node.
     */
    private fun binaryOp(ctx: MiniKotlinParser.ExpressionContext): String {
        for (child in ctx.children) {
            if (child !is MiniKotlinParser.ExpressionContext) {
                val text = child.text
                if (text.isNotBlank()) return text
            }
        }

        error("No operator token found in binary expression: ${ctx.text}")
    }

    /**
     * Converts a MiniKotlin type name into the corresponding Java boxed type
     */
    private fun javaBoxedType(miniKotlinType: String): String {
        return when (miniKotlinType) {
            "Int"     -> "Integer"
            "Boolean" -> "Boolean"
            "String"  -> "String"
            "Unit"    -> "Void"
            else      -> miniKotlinType  // pass through any unknown types unchanged
        }
    }
}