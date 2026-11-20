package com.lagradost.cloudstream3.utils

import androidx.annotation.VisibleForTesting
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.StringUtils.decodeUrl
import com.lagradost.cloudstream3.utils.StringUtils.encodeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.truncate
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Lightweight pure-Kotlin JavaScript interpreter designed to replace Rhino for
 * our own deobfuscation use-cases.
 *
 * Supports the subset of JS that appears in obfuscated video-hosting scripts:
 *  - Variable declarations (var / let / const)
 *  - String, number (including hex `0x..` and legacy octal `0..`) and boolean literals
 *  - Arithmetic / bitwise / comparison / logical operators
 *  - String concatenation with +
 *  - String methods: split, join, reverse, replace, charAt, charCodeAt,
 *                    fromCharCode, substr, substring, slice, indexOf,
 *                    trim, toLowerCase, toUpperCase, toString, length
 *  - Array literals and methods: join, reverse, split, push, pop,
 *                                map, filter, forEach, length
 *  - parseInt / parseFloat / isNaN / isFinite
 *  - Math.*  (sin, cos, floor, ceil, round, abs, pow, sqrt, log, max, min, random)
 *  - String.fromCharCode
 *  - Ternary operator (a ? b : c)
 *  - if / else / while / for statements
 *  - Function declarations and calls (named and anonymous)
 *  - return / break / continue
 *  - Template literals (back-tick strings)
 *  - instanceof
 *  - typeof
 *
 * Every evaluation is bounded by an execution budget (wall-clock time and/or instruction
 * count, see [JS_DEFAULT_MAX_EXECUTION_TIME] / [JS_DEFAULT_MAX_INSTRUCTIONS]) so that
 * untrusted/obfuscated scripts containing an infinite loop (such as `while(true){}`)
 * cannot hang the calling thread forever. Note that since evaluation is synchronous, wrapping a call
 * in `withTimeout` will not pre-empt it mid-flight (there's no suspension point for the
 * coroutine machinery to act on) so the budget below is what actually guarantees the call
 * returns.
 *
 * Usage:
 *   val result = evalJs("var x = 1+2; x")
 *   // result == 3.0 (numbers are always Double internally)
 *
 *   // Drop-in replacement for the old Rhino-based evaluateString pattern:
 *   val ctx = JsContext()
 *   ctx.eval("var url = 'https:' + computeSuffix()")
 *   val url = ctx["url"]   // retrieves the variable as a String
 *
 *   // Or simpler:
 *   val url = evalJs("var url = 'https:' + computeSuffix()", "url")
 */

/** Default wall-clock budget for a single [evalJs] / [JsContext.eval] call before it's aborted. */
private val JS_DEFAULT_MAX_EXECUTION_TIME: Duration = 5.seconds

/** Hard backstop on statements/expressions executed, independent of wall-clock time. */
private const val JS_DEFAULT_MAX_INSTRUCTIONS: Long = 50_000_000L

/**
 * Audit-13 #12: cap for a SINGLE native allocation whose size a
 * script controls (Array(n), String.repeat, padStart/padEnd).
 * The execution budget is only checked between statements, so
 * without this a single expression like Array(1e9) or
 * "x".repeat(2_000_000_000) attempts a multi-GB allocation on
 * the device heap before any budget check can fire.
 */
private const val JS_MAX_NATIVE_SIZE: Int = 1_000_000

/**
 * Convert any JS runtime value to its JavaScript string representation.
 * Mirrors what JS `String(value)` would produce.
 */
fun jsValueToString(v: Any?): String = toJsString(v)

/**
 * Stateful JS execution context. Keeps variables alive between [eval] calls,
 * mimicking the Rhino "scope" object that extensions used to hold on to.
 *
 * Instances are created via [newJsContext], not by calling this constructor directly.
 *
 * @param maxExecutionTime wall-clock budget given to each [eval] call. Can still be
 *        changed after construction (e.g. from within [newJsContext]'s initializer
 *        block) as long as it's set before the first [eval]/[get]/[set] call, since the
 *        underlying interpreter is built lazily from whatever values these hold at that
 *        point. Changes made afterward have no effect.
 * @param maxInstructions hard cap on statements/expressions executed per [eval] call,
 *        independent of wall-clock time. Same lazy-initialization caveat as
 *        [maxExecutionTime] applies.
 * @param scope the [CoroutineScope] this context's cancellation is tied to. Supplied
 *        automatically by [newJsContext] from the caller's own coroutine context.
 */
class JsContext internal constructor(
    var maxExecutionTime: Duration = JS_DEFAULT_MAX_EXECUTION_TIME,
    var maxInstructions: Long = JS_DEFAULT_MAX_INSTRUCTIONS,
    private val scope: CoroutineScope,
) {
    /**
     * Built lazily so that any changes made to [maxExecutionTime]/[maxInstructions] inside
     * [newJsContext]'s initializer block (before the first [eval]/[get]/[set] call) are
     * still in effect when JsInterpreter is actually constructed. Once this has been
     * accessed once, further changes to the two vars above have no effect.
     */
    private val interpreter: JsInterpreter by lazy {
        JsInterpreter(maxExecutionTime, maxInstructions, scope)
    }

    /**
     * Evaluate [code] in this context. Returns the last expression value.
     *
     * @throws CancellationException if this context's underlying coroutine scope
     *         has been cancelled, e.g. by an enclosing `withTimeout`.
     */
    @Throws(CancellationException::class)
    suspend fun eval(code: String): Any? = interpreter.eval(code)

    /**
     * Retrieve a variable set by previously evaluated code, or via [set].
     * Returns `null` if never set.
     */
    operator fun get(name: String): Any? = interpreter.getVar(name)

    /** Expose a Kotlin value to subsequently evaluated JS code under the name [name]. */
    operator fun set(name: String, value: Any?) = interpreter.setVar(name, value)
}

/**
 * Creates a new [JsContext], running [initializer] on it before returning.
 *
 * The context's cancellation is automatically tied to whichever coroutine calls this
 * function. If that coroutine is later cancelled (e.g. an enclosing `withTimeout`
 * expires), any in-flight or subsequent [JsContext.eval] call on the returned
 * context will throw [CancellationException].
 *
 * [JsContext.maxExecutionTime] / [JsContext.maxInstructions] can be changed from
 * within [initializer] (via `this.maxExecutionTime = ...`) and will take effect, since
 * the underlying interpreter isn't built until the context is first used.
 *
 * Usage:
 *   val ctx = newJsContext {
 *       maxInstructions = 10_000
 *       set("x", 1.0)
 *       eval("x + 1")
 *   }
 */
suspend fun newJsContext(
    initializer: suspend JsContext.() -> Unit = {},
): JsContext {
    val scope = CoroutineScope(currentCoroutineContext())
    return JsContext(scope = scope).apply { initializer() }
}

/**
 * Evaluate [js] and return its last value, or the value of [variable] if specified.
 * Convenience wrapper for one-shot evaluations, equivalent to the old
 * `rhino.evaluateString(scope, js, ...)`.
 *
 * Cancellation is automatically tied to whichever coroutine calls this function. If
 * that coroutine is cancelled (e.g. an enclosing `withTimeout` expires), this call
 * throws [CancellationException].
 *
 * @param js The JavaScript code to evaluate.
 * @param variable Optional variable name to retrieve from the scope after evaluation.
 * @param maxExecutionTime wall-clock budget before the script is forcibly aborted.
 *        Defaults to [JS_DEFAULT_MAX_EXECUTION_TIME]; pass a smaller value for time-sensitive
 *        call sites (e.g. inside a `withTimeout`, which cannot itself interrupt this call).
 * @param maxInstructions hard cap on statements/expressions executed, independent of
 *        wall-clock time. Defaults to [JS_DEFAULT_MAX_INSTRUCTIONS].
 * @return The last expression value, or the named variable value if [variable] is specified.
 *         Returns [Unit] on evaluation failure, timeout, or when the result is JS undefined.
 *         JS null is represented as Kotlin null. Use [jsValueToString] to convert to a JS string.
 */
@Throws(CancellationException::class)
suspend fun evalJs(
    js: String,
    variable: String? = null,
    maxExecutionTime: Duration = JS_DEFAULT_MAX_EXECUTION_TIME,
    maxInstructions: Long = JS_DEFAULT_MAX_INSTRUCTIONS,
): Any? {
    val scope = CoroutineScope(currentCoroutineContext())
    return evalJsInternal(js, variable, maxExecutionTime, maxInstructions, scope)
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun evalJsInternal(
    js: String,
    variable: String? = null,
    maxExecutionTime: Duration = JS_DEFAULT_MAX_EXECUTION_TIME,
    maxInstructions: Long = JS_DEFAULT_MAX_INSTRUCTIONS,
    scope: CoroutineScope? = null,
): Any? {
    val interpreter = JsInterpreter(maxExecutionTime, maxInstructions, scope)
    val result = interpreter.eval(js)
    return if (variable != null) interpreter.getVar(variable) else result
}

private enum class TT {
    NUMBER, STRING, TEMPLATE, IDENT, PLUS, MINUS, STAR, SLASH, PERCENT, POW, EQ, EQEQ, EQEQEQ,
    NEQ, NEQEQ, LT, LTEQ, GT, GTEQ, AND, OR, NOT, AMP, PIPE, CARET, TILDE,
    LSHIFT, RSHIFT, URSHIFT, PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, PERCENTEQ, POWEQ,
    ANDEQ, OREQ, CARETEQ, LSHIFTEQ, RSHIFTEQ, URSHIFTEQ,
    PLUSPLUS, MINUSMINUS, DOT, COMMA, SEMI, COLON, QUESTION, LPAREN, RPAREN,
    LBRACE, RBRACE, LBRACKET, RBRACKET, EOF
}

private data class Token(val type: TT, val raw: String, val pos: Int)

private class Lexer(private val src: String) {
    var pos = 0
    private val tokens = mutableListOf<Token>()
    private var idx = 0

    init { tokenize() }

    private fun tokenize() {
        while (pos < src.length) {
            skipWhitespaceAndComments()
            if (pos >= src.length) break
            val c = src[pos]
            when {
                c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) -> readNumber()
                c == '"' || c == '\'' || c == '`' -> readString(c)
                c.isLetter() || c == '_' || c == '$' -> readIdent()
                else -> readOp()
            }
        }
        tokens.add(Token(TT.EOF, "", pos))
    }

    private fun skipWhitespaceAndComments() {
        while (pos < src.length) {
            val c = src[pos]
            if (c.isWhitespace()) { pos++; continue }
            if (c == '/' && pos + 1 < src.length) {
                when (src[pos + 1]) {
                    '/' -> { pos = src.indexOf('\n', pos).let { if (it < 0) src.length else it + 1 }; continue }
                    '*' -> {
                        val end = src.indexOf("*/", pos + 2)
                        pos = if (end < 0) src.length else end + 2; continue
                    }
                    else -> {}
                }
            }
            break
        }
    }

    private fun readNumber() {
        val start = pos
        if (pos + 1 < src.length && src[pos] == '0' &&
            (src[pos + 1] == 'x' || src[pos + 1] == 'X')
        ) {
            pos += 2
            while (pos < src.length && src[pos].isLetterOrDigit()) pos++
        } else {
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
        }
        tokens.add(Token(TT.NUMBER, src.substring(start, pos), start))
    }

    private fun readString(quote: Char) {
        val start = pos++
        val sb = StringBuilder()
        if (quote == '`') {
            readTemplate(start)
            return
        } else {
            while (pos < src.length && src[pos] != quote) {
                if (src[pos] == '\\') appendEscape(sb)
                else sb.append(src[pos++])
            }
            if (pos < src.length) pos++
        }
        tokens.add(Token(TT.STRING, sb.toString(), start))
    }

    // Audit-18 #4: \xHH and \uHHHH were never decoded —
    // unescape() only maps single letters, so "\x68\x74"
    // came out as "x68x74" instead of "ht". Hex/unicode
    // escapes are a standard deobfuscation idiom.
    private fun appendEscape(sb: StringBuilder) {
        // src[pos] is the backslash
        if (pos + 1 >= src.length) {
            sb.append('\\'); pos++; return
        }
        when (val c = src[pos + 1]) {
            'x' -> {
                val hex = src.substring(pos + 2, minOf(pos + 4, src.length))
                val v = if (hex.length == 2) hex.toIntOrNull(16) else null
                if (v != null) { sb.append(v.toChar()); pos += 4 }
                else { sb.append('x'); pos += 2 }
            }
            'u' -> {
                val hex = src.substring(pos + 2, minOf(pos + 6, src.length))
                val v = if (hex.length == 4) hex.toIntOrNull(16) else null
                if (v != null) { sb.append(v.toChar()); pos += 6 }
                else { sb.append('u'); pos += 2 }
            }
            '0' -> { sb.append('\u0000'); pos += 2 }
            else -> { sb.append(unescape(c)); pos += 2 }
        }
    }

    // Audit-19 #5: template literals now interpolate
    // ${...}. Literal parts are escape-decoded as before;
    // each expression's source is carried in the token
    // between U+0001/U+0002 markers for the parser to
    // sub-parse (those control chars cannot appear in
    // real script source unescaped).
    private fun readTemplate(start: Int) {
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != '`') {
            when {
                src[pos] == '\\' -> appendEscape(sb)
                src[pos] == '$' && pos + 1 < src.length && src[pos + 1] == '{' -> {
                    val close = findCloseBrace(pos + 2)
                    sb.append('\u0001')
                    sb.append(src.substring(pos + 2, close))
                    sb.append('\u0002')
                    pos = if (close < src.length) close + 1 else src.length
                }
                else -> sb.append(src[pos++])
            }
        }
        if (pos < src.length) pos++ // consume closing `
        tokens.add(Token(TT.TEMPLATE, sb.toString(), start))
    }

    private fun findCloseBrace(from: Int): Int {
        var depth = 1
        var i = from
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"', '\'' -> i = skipQuoted(i)
                '`' -> i = skipTemplateEnd(i)
                '\\' -> i++
            }
            i++
        }
        return src.length
    }

    private fun skipQuoted(i: Int): Int {
        val q = src[i]
        var j = i + 1
        while (j < src.length) {
            if (src[j] == '\\') {
                j += 2
                continue
            }
            if (src[j] == q) return j
            j++
        }
        return src.length
    }

    private fun skipTemplateEnd(i: Int): Int {
        var j = i + 1
        while (j < src.length) {
            when (src[j]) {
                '\\' -> j++
                '`' -> return j
                '$' -> if (j + 1 < src.length && src[j + 1] == '{') j = findCloseBrace(j + 2)
            }
            j++
        }
        return src.length
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'
        '\'' -> '\''; '"' -> '"'; '\\' -> '\\'; '`' -> '`'
        else -> c
    }

    private fun readIdent() {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '$')) pos++
        tokens.add(Token(TT.IDENT, src.substring(start, pos), start))
    }

    private fun readOp() {
        val start = pos
        val c = src[pos++]
        val next = if (pos < src.length) src[pos] else '\u0000'
        val nn = if (pos + 1 < src.length) src[pos + 1] else '\u0000'
        fun adv(t: TT, n: Int = 0): Token { pos += n; return Token(t, "", start) }
        val tok = when (c) {
            '+' -> when (next) { '+' -> adv(TT.PLUSPLUS, 1); '=' -> adv(TT.PLUSEQ, 1); else -> Token(TT.PLUS, "+", start) }
            '-' -> when (next) { '-' -> adv(TT.MINUSMINUS, 1); '=' -> adv(TT.MINUSEQ, 1); else -> Token(TT.MINUS, "-", start) }
            '*' -> when {
                next == '*' && nn == '=' -> adv(TT.POWEQ, 2)
                next == '*' -> adv(TT.POW, 1)
                next == '=' -> adv(TT.STAREQ, 1)
                else -> Token(TT.STAR, "*", start)
            }
            '/' -> if (next == '=') adv(TT.SLASHEQ, 1) else Token(TT.SLASH, "/", start)
            '%' -> if (next == '=') adv(TT.PERCENTEQ, 1) else Token(TT.PERCENT, "%", start)
            '=' -> when { next == '=' && nn == '=' -> adv(TT.EQEQEQ, 2); next == '=' -> adv(TT.EQEQ, 1); else -> Token(TT.EQ, "=", start) }
            '!' -> when { next == '=' && nn == '=' -> adv(TT.NEQEQ, 2); next == '=' -> adv(TT.NEQ, 1); else -> Token(TT.NOT, "!", start) }
            '<' -> when { next == '<' && nn == '=' -> adv(TT.LSHIFTEQ, 2); next == '<' -> adv(TT.LSHIFT, 1); next == '=' -> adv(TT.LTEQ, 1); else -> Token(TT.LT, "<", start) }
            '>' -> when { next == '>' && nn == '>' && src.getOrNull(pos + 2) == '=' -> adv(TT.URSHIFTEQ, 3); next == '>' && nn == '=' -> adv(TT.RSHIFTEQ, 2); next == '>' && nn == '>' -> adv(TT.URSHIFT, 2); next == '>' -> adv(TT.RSHIFT, 1); next == '=' -> adv(TT.GTEQ, 1); else -> Token(TT.GT, ">", start) }
            '&' -> when { next == '&' -> adv(TT.AND, 1); next == '=' -> adv(TT.ANDEQ, 1); else -> Token(TT.AMP, "&", start) }
            '|' -> when { next == '|' -> adv(TT.OR, 1); next == '=' -> adv(TT.OREQ, 1); else -> Token(TT.PIPE, "|", start) }
            '^' -> if (next == '=') adv(TT.CARETEQ, 1) else Token(TT.CARET, "^", start)
            '~' -> Token(TT.TILDE, "~", start)
            '.' -> Token(TT.DOT, ".", start)
            ',' -> Token(TT.COMMA, ",", start)
            ';' -> Token(TT.SEMI, ";", start)
            ':' -> Token(TT.COLON, ":", start)
            '?' -> Token(TT.QUESTION, "?", start)
            '(' -> Token(TT.LPAREN, "(", start)
            ')' -> Token(TT.RPAREN, ")", start)
            '{' -> Token(TT.LBRACE, "{", start)
            '}' -> Token(TT.RBRACE, "}", start)
            '[' -> Token(TT.LBRACKET, "[", start)
            ']' -> Token(TT.RBRACKET, "]", start)
            else -> Token(TT.SEMI, "", start) // swallow unknown
        }
        tokens.add(tok)
    }

    fun peek(offset: Int = 0): Token = tokens.getOrElse(idx + offset) { tokens.last() }
    fun consume(): Token = tokens[idx++]
    fun expect(t: TT): Token {
        val tok = consume()
        if (tok.type != t) throw RuntimeException("Expected $t got ${tok.type} ('${tok.raw}') at ${tok.pos}")
        return tok
    }
    fun matchIf(t: TT): Boolean = if (peek().type == t) { consume(); true } else false
}

private sealed class Node
private data class NumLit(val v: Double) : Node()
private data class StrLit(val v: String) : Node()

private data class TemplateExpr(val parts: List<Node>) : Node()
private data class BoolLit(val v: Boolean) : Node()
private object NullLit : Node()
private object UndefinedLit : Node()
private data class Ident(val name: String) : Node()
private data class ArrayLit(val elems: List<Node>) : Node()
private data class ObjLit(val pairs: List<Pair<String, Node>>) : Node()
private data class MemberExpr(val obj: Node, val prop: Node, val computed: Boolean) : Node()
private data class CallExpr(val callee: Node, val args: List<Node>) : Node()
private data class NewExpr(val callee: Node, val args: List<Node>) : Node()
private data class UnaryExpr(val op: String, val expr: Node, val prefix: Boolean) : Node()
private data class BinExpr(val op: String, val left: Node, val right: Node) : Node()
private data class AssignExpr(val op: String, val left: Node, val right: Node) : Node()
private data class CondExpr(val test: Node, val cons: Node, val alt: Node) : Node()
private data class TypeofExpr(val expr: Node) : Node()
private data class SeqExpr(val exprs: List<Node>) : Node()
private data class FuncExpr(val name: String?, val params: List<String>, val body: List<Node>) : Node()
private data class VarDecl(val decls: List<Pair<String, Node?>>) : Node()
private data class ExprStmt(val expr: Node) : Node()
private data class BlockStmt(val stmts: List<Node>) : Node()
private data class ReturnStmt(val expr: Node?) : Node()
private data class IfStmt(val test: Node, val cons: Node, val alt: Node?) : Node()
private data class WhileStmt(val test: Node, val body: Node) : Node()
private data class ForStmt(val init: Node?, val test: Node?, val update: Node?, val body: Node) : Node()
private data class ForInStmt(val decl: String, val obj: Node, val body: Node) : Node()
private object BreakStmt : Node()
private object ContinueStmt : Node()
private data class TryCatch(val body: List<Node>, val catchParam: String?, val catchBody: List<Node>?, val finallyBody: List<Node>?) : Node()
private data class ThrowStmt(val expr: Node) : Node()

private class Parser(private val lex: Lexer) {

    // Audit-19 #5: split a template token's raw text into
    // literal and expression parts; each expression source
    // is parsed by a fresh Parser over its own Lexer. An
    // unparseable interpolation degrades to an empty string
    // instead of failing the whole script.
    private fun parseTemplate(raw: String): Node {
        val parts = mutableListOf<Node>()
        var i = 0
        while (i < raw.length) {
            val open = raw.indexOf('\u0001', i)
            if (open == -1) {
                parts.add(StrLit(raw.substring(i)))
                break
            }
            if (open > i) parts.add(StrLit(raw.substring(i, open)))
            val close = raw.indexOf('\u0002', open)
            val exprSrc =
                if (close == -1) raw.substring(open + 1)
                else raw.substring(open + 1, close)
            val exprNode = try {
                val stmts = Parser(Lexer(exprSrc)).parseProgram()
                (stmts.singleOrNull() as? ExprStmt)?.expr ?: StrLit("")
            } catch (e: Exception) {
                StrLit("")
            }
            parts.add(exprNode)
            if (close == -1) break
            i = close + 1
        }
        return TemplateExpr(parts)
    }

    fun parseProgram(): List<Node> {
        val stmts = mutableListOf<Node>()
        while (lex.peek().type != TT.EOF) stmts.add(parseStatement())
        return stmts
    }

    private fun parseStatement(): Node {
        return when (lex.peek().type) {
            TT.LBRACE -> parseBlock()
            TT.SEMI -> { lex.consume(); BlockStmt(emptyList()) }
            TT.IDENT -> when (lex.peek().raw) {
                "var", "let", "const" -> parseVarDecl()
                "function" -> parseFunctionDecl()
                "return" -> parseReturn()
                "if" -> parseIf()
                "while" -> parseWhile()
                "for" -> parseFor()
                "break" -> { lex.consume(); lex.matchIf(TT.SEMI); BreakStmt }
                "continue" -> { lex.consume(); lex.matchIf(TT.SEMI); ContinueStmt }
                "try" -> parseTryCatch()
                "throw" -> parseThrow()
                "typeof" -> parseExprStmt()
                else -> parseExprStmt()
            }
            else -> parseExprStmt()
        }
    }

    private fun parseBlock(): BlockStmt {
        lex.expect(TT.LBRACE)
        val stmts = mutableListOf<Node>()
        while (lex.peek().type != TT.RBRACE && lex.peek().type != TT.EOF) stmts.add(parseStatement())
        lex.expect(TT.RBRACE)
        return BlockStmt(stmts)
    }

    private fun parseVarDecl(): VarDecl {
        lex.consume() // var / let / const
        val decls = mutableListOf<Pair<String, Node?>>()
        do {
            val name = lex.expect(TT.IDENT).raw
            val init = if (lex.matchIf(TT.EQ)) parseAssign() else null
            decls.add(name to init)
        } while (lex.matchIf(TT.COMMA))
        lex.matchIf(TT.SEMI)
        return VarDecl(decls)
    }

    private fun parseFunctionDecl(): Node {
        lex.consume() // "function"
        val name = if (lex.peek().type == TT.IDENT) lex.consume().raw else null
        val params = parseParams()
        val body = parseBlock().stmts
        return if (name != null) VarDecl(listOf(name to FuncExpr(name, params, body))) else FuncExpr(name, params, body)
    }

    private fun parseParams(): List<String> {
        lex.expect(TT.LPAREN)
        val params = mutableListOf<String>()
        while (lex.peek().type != TT.RPAREN && lex.peek().type != TT.EOF) {
            params.add(lex.expect(TT.IDENT).raw)
            if (!lex.matchIf(TT.COMMA)) break
        }
        lex.expect(TT.RPAREN)
        return params
    }

    private fun parseReturn(): ReturnStmt {
        lex.consume()
        val expr = if (lex.peek().type == TT.SEMI || lex.peek().type == TT.RBRACE || lex.peek().type == TT.EOF) null else parseAssign()
        lex.matchIf(TT.SEMI)
        return ReturnStmt(expr)
    }

    private fun parseIf(): IfStmt {
        lex.consume()
        lex.expect(TT.LPAREN)
        val test = parseAssign()
        lex.expect(TT.RPAREN)
        val cons = parseStatement()
        val alt = if (lex.peek().type == TT.IDENT && lex.peek().raw == "else") { lex.consume(); parseStatement() } else null
        return IfStmt(test, cons, alt)
    }

    private fun parseWhile(): WhileStmt {
        lex.consume()
        lex.expect(TT.LPAREN)
        val test = parseAssign()
        lex.expect(TT.RPAREN)
        return WhileStmt(test, parseStatement())
    }

    private fun parseFor(): Node {
        lex.consume()
        lex.expect(TT.LPAREN)
        // for..in check
        if (lex.peek().type == TT.IDENT && (lex.peek().raw == "var" || lex.peek().raw == "let" || lex.peek().raw == "const")) {
            val savedIdx = lex.peek()
            lex.consume()
            val varName = lex.expect(TT.IDENT).raw
            if (lex.peek().type == TT.IDENT && lex.peek().raw == "in") {
                lex.consume()
                val obj = parseAssign()
                lex.expect(TT.RPAREN)
                return ForInStmt(varName, obj, parseStatement())
            }
            // backtrack is hard so we just reconstruct as normal for
            val init: Node? = if (lex.peek().type != TT.SEMI) {
                val initExpr = if (lex.matchIf(TT.EQ)) parseAssign() else null
                VarDecl(listOf(varName to initExpr))
            } else VarDecl(listOf(varName to null))
            lex.matchIf(TT.SEMI)
            val test = if (lex.peek().type != TT.SEMI) parseAssign() else null
            lex.matchIf(TT.SEMI)
            val update = if (lex.peek().type != TT.RPAREN) parseAssign() else null
            lex.expect(TT.RPAREN)
            return ForStmt(init, test, update, parseStatement())
        }
        val init = if (lex.peek().type != TT.SEMI) parseAssign() else null
        lex.matchIf(TT.SEMI)
        val test = if (lex.peek().type != TT.SEMI) parseAssign() else null
        lex.matchIf(TT.SEMI)
        val update = if (lex.peek().type != TT.RPAREN) parseAssign() else null
        lex.expect(TT.RPAREN)
        return ForStmt(init, test, update, parseStatement())
    }

    private fun parseTryCatch(): TryCatch {
        lex.consume() // "try"
        val body = parseBlock().stmts
        var catchParam: String? = null
        var catchBody: List<Node>? = null
        if (lex.peek().type == TT.IDENT && lex.peek().raw == "catch") {
            lex.consume()
            if (lex.matchIf(TT.LPAREN)) {
                catchParam = lex.expect(TT.IDENT).raw
                lex.expect(TT.RPAREN)
            }
            catchBody = parseBlock().stmts
        }
        val finallyBody = if (lex.peek().type == TT.IDENT && lex.peek().raw == "finally") {
            lex.consume(); parseBlock().stmts
        } else null
        return TryCatch(body, catchParam, catchBody, finallyBody)
    }

    private fun parseThrow(): ThrowStmt {
        lex.consume()
        val expr = parseAssign()
        lex.matchIf(TT.SEMI)
        return ThrowStmt(expr)
    }

    private fun parseExprStmt(): Node {
        val expr = parseSeq()
        lex.matchIf(TT.SEMI)
        return ExprStmt(expr)
    }

    private fun parseSeq(): Node {
        val first = parseAssign()
        if (lex.peek().type != TT.COMMA) return first
        val exprs = mutableListOf(first)
        while (lex.matchIf(TT.COMMA)) exprs.add(parseAssign())
        return SeqExpr(exprs)
    }

    private fun parseAssign(): Node {
        val left = parseTernary()
        val op = when (lex.peek().type) {
            TT.EQ -> "="; TT.PLUSEQ -> "+="; TT.MINUSEQ -> "-="; TT.STAREQ -> "*="; TT.SLASHEQ -> "/="; TT.PERCENTEQ -> "%="; TT.POWEQ -> "**="
            TT.ANDEQ -> "&="; TT.OREQ -> "|="; TT.CARETEQ -> "^="; TT.LSHIFTEQ -> "<<="; TT.RSHIFTEQ -> ">>="; TT.URSHIFTEQ -> ">>>="
            else -> return left
        }
        lex.consume()
        return AssignExpr(op, left, parseAssign())
    }

    private fun parseTernary(): Node {
        val test = parseOr()
        if (!lex.matchIf(TT.QUESTION)) return test
        val cons = parseAssign()
        lex.expect(TT.COLON)
        return CondExpr(test, cons, parseAssign())
    }

    private fun parseOr(): Node = parseBin(::parseAnd, TT.OR to "||")
    private fun parseAnd(): Node = parseBin(::parseBitor, TT.AND to "&&")
    private fun parseBitor(): Node = parseBin(::parseBitxor, TT.PIPE to "|")
    private fun parseBitxor(): Node = parseBin(::parseBitand, TT.CARET to "^")
    private fun parseBitand(): Node = parseBin(::parseEq, TT.AMP to "&")
    private fun parseEq(): Node = parseBin(::parseRel, TT.EQEQ to "==", TT.EQEQEQ to "===", TT.NEQ to "!=", TT.NEQEQ to "!==")
    private fun parseRel(): Node {
        var left = parseShift()
        while (true) {
            left = when {
                lex.peek().type == TT.LT -> { lex.consume(); BinExpr("<", left, parseShift()) }
                lex.peek().type == TT.LTEQ -> { lex.consume(); BinExpr("<=", left, parseShift()) }
                lex.peek().type == TT.GT -> { lex.consume(); BinExpr(">", left, parseShift()) }
                lex.peek().type == TT.GTEQ -> { lex.consume(); BinExpr(">=", left, parseShift()) }
                lex.peek().type == TT.IDENT && lex.peek().raw == "in" -> { lex.consume(); BinExpr("in", left, parseShift()) }
                lex.peek().type == TT.IDENT && lex.peek().raw == "instanceof" -> { lex.consume(); BinExpr("instanceof", left, parseShift()) }
                else -> break
            }
        }
        return left
    }
    private fun parseShift(): Node = parseBin(::parseAdd, TT.LSHIFT to "<<", TT.RSHIFT to ">>", TT.URSHIFT to ">>>")
    private fun parseAdd(): Node = parseBin(::parseMul, TT.PLUS to "+", TT.MINUS to "-")
    private fun parseMul(): Node = parseBin(::parsePow, TT.STAR to "*", TT.SLASH to "/", TT.PERCENT to "%")

    /** `**` binds tighter than `* / %` and is right-associative: `2 ** 3 ** 2 == 2 ** (3 ** 2)`. */
    private fun parsePow(): Node {
        val base = parseUnary()
        if (lex.peek().type == TT.POW) {
            lex.consume()
            return BinExpr("**", base, parsePow())
        }
        return base
    }

    private fun parseBin(next: () -> Node, vararg ops: Pair<TT, String>): Node {
        var left = next()
        while (true) {
            val op = ops.firstOrNull { it.first == lex.peek().type } ?: break
            lex.consume()
            left = BinExpr(op.second, left, next())
        }
        return left
    }

    private fun parseUnary(): Node {
        return when (lex.peek().type) {
            TT.MINUS -> { lex.consume(); UnaryExpr("-", parseUnary(), true) }
            TT.PLUS -> { lex.consume(); UnaryExpr("+", parseUnary(), true) }
            TT.NOT -> { lex.consume(); UnaryExpr("!", parseUnary(), true) }
            TT.TILDE -> { lex.consume(); UnaryExpr("~", parseUnary(), true) }
            TT.PLUSPLUS -> { lex.consume(); UnaryExpr("++", parsePostfix(), true) }
            TT.MINUSMINUS -> { lex.consume(); UnaryExpr("--", parsePostfix(), true) }
            TT.IDENT -> when (lex.peek().raw) {
                "typeof" -> { lex.consume(); TypeofExpr(parseUnary()) }
                "void" -> { lex.consume(); parseUnary(); UndefinedLit }
                "new" -> parseNew()
                else -> parsePostfix()
            }
            else -> parsePostfix()
        }
    }

    private fun parseNew(): Node {
        lex.consume() // "new"
        val callee = parsePrimary()
        val args = if (lex.peek().type == TT.LPAREN) parseArgs() else emptyList()
        var expr: Node = NewExpr(callee, args)
        // Allow member access and calls on the constructed object: new Foo().bar, new Foo()[0]
        while (true) {
            expr = when (lex.peek().type) {
                TT.DOT -> {
                    lex.consume()
                    val prop = lex.expect(TT.IDENT).raw
                    MemberExpr(expr, StrLit(prop), false)
                }
                TT.LBRACKET -> {
                    lex.consume()
                    val prop = parseAssign()
                    lex.expect(TT.RBRACKET)
                    MemberExpr(expr, prop, true)
                }
                TT.LPAREN -> CallExpr(expr, parseArgs())
                else -> break
            }
        }
        return expr
    }

    private fun parsePostfix(): Node {
        var expr = parseCall()
        while (true) {
            expr = when (lex.peek().type) {
                TT.PLUSPLUS -> { lex.consume(); UnaryExpr("++", expr, false) }
                TT.MINUSMINUS -> { lex.consume(); UnaryExpr("--", expr, false) }
                else -> break
            }
        }
        return expr
    }

    private fun parseCall(): Node {
        var expr = parsePrimary()
        while (true) {
            expr = when (lex.peek().type) {
                TT.LPAREN -> CallExpr(expr, parseArgs())
                TT.DOT -> {
                    lex.consume()
                    val prop = lex.expect(TT.IDENT).raw
                    MemberExpr(expr, StrLit(prop), false)
                }
                TT.LBRACKET -> {
                    lex.consume()
                    val prop = parseAssign()
                    lex.expect(TT.RBRACKET)
                    MemberExpr(expr, prop, true)
                }
                else -> break
            }
        }
        return expr
    }

    private fun parseArgs(): List<Node> {
        lex.expect(TT.LPAREN)
        val args = mutableListOf<Node>()
        while (lex.peek().type != TT.RPAREN && lex.peek().type != TT.EOF) {
            args.add(parseAssign())
            if (!lex.matchIf(TT.COMMA)) break
        }
        lex.expect(TT.RPAREN)
        return args
    }

    private fun parsePrimary(): Node {
        val tok = lex.peek()
        return when (tok.type) {
            TT.NUMBER -> {
                lex.consume()
                val raw = tok.raw
                val value = when {
                    raw.startsWith("0x") || raw.startsWith("0X") ->
                        raw.drop(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
                    // Legacy octal literal, a leading zero followed only by octal digits
                    // (0-7), e.g. "010" == 8. If any digit is 8/9 it's just decimal ("08" == 8).
                    raw.length > 1 && raw[0] == '0' && raw.all { it in '0'..'7' } ->
                        raw.toLongOrNull(8)?.toDouble() ?: Double.NaN
                    else -> raw.toDoubleOrNull() ?: Double.NaN
                }
                NumLit(value)
            }
            TT.STRING -> { lex.consume(); StrLit(tok.raw) }
            TT.TEMPLATE -> { lex.consume(); parseTemplate(tok.raw) }
            TT.LPAREN -> {
                lex.consume()
                val expr = parseSeq()
                lex.expect(TT.RPAREN)
                expr
            }
            TT.LBRACKET -> {
                lex.consume()
                val elems = mutableListOf<Node>()
                while (lex.peek().type != TT.RBRACKET && lex.peek().type != TT.EOF) {
                    if (lex.peek().type == TT.COMMA) {
                        // Elision, a "hole" in the array, e.g. the middle slot of [1,,3].
                        // Reads back as undefined but still occupies a slot/length.
                        elems.add(UndefinedLit)
                        lex.consume()
                    } else {
                        elems.add(parseAssign())
                        if (lex.peek().type == TT.COMMA) lex.consume() else break
                    }
                }
                lex.expect(TT.RBRACKET)
                ArrayLit(elems)
            }
            TT.LBRACE -> {
                lex.consume()
                val pairs = mutableListOf<Pair<String, Node>>()
                while (lex.peek().type != TT.RBRACE && lex.peek().type != TT.EOF) {
                    val key = when (lex.peek().type) {
                        TT.IDENT, TT.STRING -> lex.consume().raw
                        TT.NUMBER -> lex.consume().raw
                        else -> lex.consume().raw
                    }
                    lex.expect(TT.COLON)
                    pairs.add(key to parseAssign())
                    lex.matchIf(TT.COMMA)
                }
                lex.expect(TT.RBRACE)
                ObjLit(pairs)
            }
            TT.IDENT -> {
                val name = tok.raw
                lex.consume()
                when (name) {
                    "true" -> BoolLit(true)
                    "false" -> BoolLit(false)
                    "null" -> NullLit
                    "undefined" -> UndefinedLit
                    "function" -> {
                        val fname = if (lex.peek().type == TT.IDENT) lex.consume().raw else null
                        val params = parseParams()
                        FuncExpr(fname, params, parseBlock().stmts)
                    }
                    else -> Ident(name)
                }
            }
            else -> { lex.consume(); UndefinedLit }
        }
    }
}

private class ReturnSignal(val value: Any?) : Throwable()
private object BreakSignal : Throwable()
private object ContinueSignal : Throwable()
private class ThrowSignal(val value: Any?) : Throwable()

/**
 * Thrown when the enclosing [CoroutineScope] is cancelled (e.g. by [withTimeout]).
 * Extends [CancellationException] so coroutines recognize and handle it correctly.
 *
 * We use a dedicated subclass rather than [CancellationException] directly so that
 * tests can use [assertFailsWith]<[JsCancellationException]> to verify that cancellation
 * originated from the interpreter's scope check rather than some other source. The class
 * is [internal] rather than private for the same reason.
 *
 * The [TryCatch] handler in [JsInterpreter.execNode] explicitly rethrows this before
 * its generic `catch (Exception)` clause, so a JS script's own try/catch block cannot
 * swallow it and keep a cancelled loop alive.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal class JsCancellationException(message: String) : CancellationException(message)

/**
 * Internal signal thrown once a script exceeds its execution budget (time or instruction
 * count), or the enclosing [CoroutineScope] has been cancelled.
 *
 * Deliberately extends [Throwable] rather than [Exception]. The interpreter's own
 * `try`/`catch` node handling (see [JsInterpreter.execNode]'s `TryCatch` branch) only catches
 * `Exception`, so a JS script can't wrap an infinite loop in its own try/catch and swallow this,
 * keeping the loop alive forever.
 */
private class JsExecutionLimitExceeded(message: String) : Throwable(message)

// Audit-16 #3: JS ToInt32/ToUint32 — truncate toward zero,
// wrap modulo 2^32, then sign-adjust. (Kotlin's
// Double.toInt() saturates instead of wrapping, and the
// bitwise operators used raw Longs, so 32-bit hash math
// in deobfuscation scripts computed wrong values.)
private fun toInt32(v: Any?): Int {
    val n = toNumber(v)
    if (n.isNaN() || n.isInfinite() || n == 0.0) return 0
    // Audit-17 #4: truncate the fraction FIRST (JS
    // ToIntegerOrInfinity), then wrap — Audit-16 #3 wrapped
    // first, so fractions just under a wrap boundary landed
    // one off (e.g. 2147483648.5 -> -2147483647, JS -2147483648).
    val wrapped = ((truncate(n) % 4294967296.0) + 4294967296.0) % 4294967296.0
    return if (wrapped >= 2147483648.0) (wrapped - 4294967296.0).toInt()
    else wrapped.toInt()
}

private fun toUint32(v: Any?): Long {
    val n = toNumber(v)
    if (n.isNaN() || n.isInfinite() || n == 0.0) return 0L
    return (((truncate(n) % 4294967296.0) + 4294967296.0) % 4294967296.0).toLong()
}

// Audit-16 #8: Double.toLong() saturates at 2^63, so
// String(1e21) printed Long.MAX_VALUE's digits. JS prints
// the full digits below 1e21 and exponential form from
// 1e21 up; expand/convert Kotlin's scientific form there.
private fun integralDoubleToJsString(v: Double): String {
    if (abs(v) < 9.223372036854776e18) return v.toLong().toString()
    val raw = v.toString()
    val ePos = raw.indexOf('E')
    if (ePos == -1) return raw
    val exp = raw.substring(ePos + 1).toInt()
    if (abs(v) >= 1e21) {
        var mantissa = raw.substring(0, ePos)
        if (mantissa.endsWith(".0")) mantissa = mantissa.dropLast(2)
        return mantissa + "e+" + exp
    }
    val digits = raw.substring(0, ePos).replace(".", "")
        .replace("-", "").trimEnd('0')
    val sign = if (v < 0) "-" else ""
    return sign + digits.padEnd(exp + 1, '0')
}

private fun parseFloatPrefix(s: String): Double {
    var i = 0
    while (i < s.length && s[i].isWhitespace()) i++
    val start = i
    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
    if (s.startsWith("Infinity", i)) {
        return if (s.getOrNull(start) == '-') Double.NEGATIVE_INFINITY
        else Double.POSITIVE_INFINITY
    }
    var sawDigit = false
    var sawDot = false
    while (i < s.length) {
        val c = s[i]
        if (c.isDigit()) {
            sawDigit = true; i++
        } else if (c == '.' && !sawDot) {
            sawDot = true; i++
        } else break
    }
    if (!sawDigit) return Double.NaN
    var end = i
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        var j = i + 1
        if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
        var expDigits = false
        while (j < s.length && s[j].isDigit()) {
            expDigits = true; j++
        }
        if (expDigits) end = j
    }
    return s.substring(start, end).toDoubleOrNull() ?: Double.NaN
}

private fun toNumber(v: Any?): Double = when (v) {
    null -> 0.0 // In JS Number(null) === 0
    is Unit -> Double.NaN // In JS Number(undefined) === NaN
    is Double -> v
    is Boolean -> if (v) 1.0 else 0.0
    is String -> stringToNumber(v)
    is JsList -> stringToNumber(toJsString(v))
    else -> Double.NaN
}

/**
 * Returns the numeric value of [c] in the given [radix] (2..36), or -1 if [c] isn't a valid
 * digit for that radix. Only handles ASCII '0'-'9'/'a'-'z'/'A'-'Z', which is all JS
 * number syntax (and parseInt) ever recognizes anyway.
 */
private fun digitValue(c: Char, radix: Int): Int {
    val d = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> return -1
    }
    return if (d < radix) d else -1
}

/** Mirrors the JS ToNumber(string) abstract operation closely enough for our use-cases. */
private fun stringToNumber(s: String): Double {
    val trimmed = s.trim()
    if (trimmed.isEmpty()) return 0.0 // In JS Number("") === 0, Number("   ") === 0
    when (trimmed) {
        "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
        "-Infinity" -> return Double.NEGATIVE_INFINITY
    }
    return when {
        trimmed.startsWith("0x") || trimmed.startsWith("0X") -> trimmed.drop(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
        trimmed.startsWith("0o") || trimmed.startsWith("0O") -> trimmed.drop(2).toLongOrNull(8)?.toDouble() ?: Double.NaN
        trimmed.startsWith("0b") || trimmed.startsWith("0B") -> trimmed.drop(2).toLongOrNull(2)?.toDouble() ?: Double.NaN
        else -> trimmed.toDoubleOrNull() ?: Double.NaN
    }
}

private fun toBoolean(v: Any?): Boolean = when (v) {
    null, is Unit -> false
    is Boolean -> v
    is Double -> v != 0.0 && !v.isNaN()
    is String -> v.isNotEmpty()
    is JsList -> true
    is JsObject -> true
    else -> true
}

/** Array.prototype.join/toString treats holes/null/undefined elements as the empty string. */
private fun joinElement(e: Any?): String = if (e == null || e is Unit) "" else toJsString(e)

private fun toJsString(v: Any?): String = when (v) {
    null -> "null"
    is Unit -> "undefined"
    is Boolean -> v.toString()
    is Double -> if (v == floor(v) && !v.isInfinite()) integralDoubleToJsString(v) else nonIntegralDoubleToJsString(v)
    is String -> v
    is JsList -> v.elements.joinToString(",") { joinElement(it) }
    is JsObject -> "[object Object]"
    is NativeFn -> v.toString()
    is JsFunction -> "function ${v.name ?: ""}() { [native code] }"
    else -> v.toString()
}

// Audit-19 #6: JS Number-to-String for non-integers —
// plain decimal inside [1e-6, 1e21), exponential outside,
// never Kotlin's "1.0E-4" shape. (0.0001 -> "0.0001",
// 1.5e-7 -> "1.5e-7".)
private fun nonIntegralDoubleToJsString(v: Double): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val bd = java.math.BigDecimal.valueOf(v).stripTrailingZeros()
    val digits = bd.unscaledValue().abs().toString()
    val exp = digits.length - bd.scale() - 1
    val sign = if (v < 0) "-" else ""
    return if (exp in -6..20) {
        sign + bd.abs().toPlainString()
    } else {
        val mantissa =
            if (digits.length == 1) digits
            else digits.substring(0, 1) + "." + digits.substring(1)
        sign + mantissa + "e" + (if (exp >= 0) "+" else "") + exp
    }
}

private fun strictEq(a: Any?, b: Any?): Boolean = when {
    a is Double && b is Double -> a == b
    a is String && b is String -> a == b
    a is Boolean && b is Boolean -> a == b
    a == null && b == null -> true
    a is Unit && b is Unit -> true
    else -> a === b
}

private fun looseEq(a: Any?, b: Any?): Boolean {
    if (strictEq(a, b)) return true
    // null == undefined
    val aNullish = a == null || a is Unit
    val bNullish = b == null || b is Unit
    if (aNullish || bNullish) return aNullish && bNullish
    // number coercion
    val an = a is Double || a is Boolean
    val bn = b is Double || b is Boolean
    if (an || bn) return toNumber(a) == toNumber(b)
    if (a is String || b is String) return toJsString(a) == toJsString(b)
    return false
}

private class JsList(val elements: MutableList<Any?> = mutableListOf()) {
    var length: Int
        get() = elements.size
        set(v) {
            // Audit-14 #2: same single-statement allocation the
            // Audit-13 #12 cap exists for — `a.length = 2e9` grew
            // the list with no cap and no budget check inside
            // the loop. Enforce the same limit here.
            if (v !in 0..JS_MAX_NATIVE_SIZE) {
                throw JsExecutionLimitExceeded("list length $v exceeds max native size of $JS_MAX_NATIVE_SIZE")
            }
            while (elements.size < v) elements.add(Unit)
            while (elements.size > v) elements.removeAt(elements.size - 1)
        }

    operator fun get(idx: Int): Any? = elements.getOrElse(idx) { Unit }
    operator fun set(idx: Int, v: Any?) {
        // Audit-14 #2: sparse-index assignment is the same
        // bypass — `a[2e9] = 1` padded the list to the index.
        if (idx !in 0 until JS_MAX_NATIVE_SIZE) {
            throw JsExecutionLimitExceeded("list index $idx exceeds max native size of $JS_MAX_NATIVE_SIZE")
        }
        while (elements.size <= idx) elements.add(Unit)
        elements[idx] = v
    }
}

private class JsObject(val props: MutableMap<String, Any?> = mutableMapOf(), var constructor: JsFunction? = null)

private class JsFunction(
    val name: String?,
    val params: List<String>,
    val body: List<Node>,
    val closure: Scope,
)

private class Scope(val parent: Scope? = null, val isFunctionRoot: Boolean = false) {
    val vars = mutableMapOf<String, Any?>()

    // Audit-19 #2: the scope `var` declarations belong to —
    // the nearest function (or global) scope, not the
    // innermost block.
    fun functionScope(): Scope =
        if (isFunctionRoot || parent == null) this else parent.functionScope()

    fun get(name: String): Any? {
        if (vars.containsKey(name)) return vars[name]
        return parent?.get(name) ?: Unit
    }

    fun set(name: String, value: Any?) {
        val scope = findOwner(name)
        if (scope != null) scope.vars[name] = value else vars[name] = value
    }

    fun define(name: String, value: Any?) { vars[name] = value }

    private fun findOwner(name: String): Scope? =
        if (vars.containsKey(name)) this else parent?.findOwner(name)
}

// Audit-15 #6: CharSequence that aborts regex matching
// once a deadline passes. java.util.regex reads the input
// exclusively through these methods while backtracking,
// so a check every few thousand accesses bounds even a
// catastrophic pattern by the eval budget. (On targets
// whose regex engine copies the input first this simply
// degrades to the old behaviour — never worse.)
private class JsDeadlineExceeded : RuntimeException()

private class DeadlineCharSequence(
    private val delegate: String,
    private val deadline: TimeMark,
) : CharSequence {
    private var accesses = 0

    private fun checkDeadline() {
        if (++accesses % 4096 == 0 && deadline.hasPassedNow()) {
            throw JsDeadlineExceeded()
        }
    }

    override val length: Int
        get() {
            checkDeadline()
            return delegate.length
        }

    override fun get(index: Int): Char {
        checkDeadline()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        checkDeadline()
        return delegate.subSequence(startIndex, endIndex)
    }

    override fun toString(): String = delegate
}

private class JsInterpreter(
    private val maxExecutionTime: Duration = JS_DEFAULT_MAX_EXECUTION_TIME,
    private val maxInstructions: Long = JS_DEFAULT_MAX_INSTRUCTIONS,
    private val scope: CoroutineScope? = null,
) {
    private val globalScope = Scope(isFunctionRoot = true)

    // Execution budget, reset at the start of every top-level eval() call.
    private var instructionCount = 0L
    private var startMark: TimeMark = TimeSource.Monotonic.markNow()

    init { installGlobals() }

    private fun installGlobals() {
        val mathObj = JsObject(mutableMapOf(
            "PI" to PI, "E" to E,
            "floor" to nativeFn("floor") { args -> floor(toNumber(args.getOrNull(0))) },
            "ceil" to nativeFn("ceil") { args -> ceil(toNumber(args.getOrNull(0))) },
            "round" to nativeFn("round") { args -> round(toNumber(args.getOrNull(0))) },
            "abs" to nativeFn("abs") { args -> abs(toNumber(args.getOrNull(0))) },
            "sqrt" to nativeFn("sqrt") { args -> sqrt(toNumber(args.getOrNull(0))) },
            "pow" to nativeFn("pow") { args -> toNumber(args.getOrNull(0)).pow(toNumber(args.getOrNull(1))) },
            "log" to nativeFn("log") { args -> ln(toNumber(args.getOrNull(0))) },
            "sin" to nativeFn("sin") { args -> sin(toNumber(args.getOrNull(0))) },
            "cos" to nativeFn("cos") { args -> cos(toNumber(args.getOrNull(0))) },
            "tan" to nativeFn("tan") { args -> tan(toNumber(args.getOrNull(0))) },
            "max" to nativeFn("max") { args -> args.maxOfOrNull { toNumber(it) } ?: Double.NEGATIVE_INFINITY },
            "min" to nativeFn("min") { args -> args.minOfOrNull { toNumber(it) } ?: Double.POSITIVE_INFINITY },
            "random" to nativeFn("random") { _ -> Random.nextDouble() },
            "trunc" to nativeFn("trunc") { args -> truncate(toNumber(args.getOrNull(0))) },
            "log2" to nativeFn("log2") { args -> log2(toNumber(args.getOrNull(0))) },
            "log10" to nativeFn("log10") { args -> log10(toNumber(args.getOrNull(0))) },
        ))
        globalScope.define("Math", mathObj)

        // For String.fromCharCode
        val stringObj = JsObject(mutableMapOf(
            "fromCharCode" to nativeFn("fromCharCode") { args -> args.joinToString("") { toNumber(it).toInt().toChar().toString() } }
        ))
        globalScope.define("String", stringObj)

        globalScope.define("parseInt", nativeFn("parseInt") { args ->
            val s = toJsString(args.getOrNull(0)).trim()
            var i = 0
            var negative = false
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                negative = s[i] == '-'
                i++
            }
            var radix = args.getOrNull(1)?.let { toNumber(it).toInt() } ?: 0
            if (radix != 0 && (radix < 2 || radix > 36)) {
                Double.NaN
            } else {
                val stripHexPrefix = radix == 0 || radix == 16
                if (radix == 0) radix = 10
                if (stripHexPrefix && i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X')) {
                    radix = 16
                    i += 2
                }
                val start = i
                while (i < s.length && digitValue(s[i], radix) != -1) i++
                if (i == start) {
                    Double.NaN
                } else {
                    var value = 0.0
                    for (idx in start until i) value = value * radix + digitValue(s[idx], radix)
                    if (negative) -value else value
                }
            }
        })
        // Audit-16 #7: JS parseFloat parses the longest
        // numeric PREFIX ("12.5abc" -> 12.5, "0x10" -> 0);
        // it used to convert the whole string, yielding NaN.
        globalScope.define("parseFloat", nativeFn("parseFloat") { args ->
            parseFloatPrefix(toJsString(args.getOrNull(0)))
        })
        globalScope.define("isNaN", nativeFn("isNaN") { args -> toNumber(args.getOrNull(0)).isNaN() })
        globalScope.define("isFinite", nativeFn("isFinite") { args -> toNumber(args.getOrNull(0)).isFinite() })
        globalScope.define("decodeURIComponent", nativeFn("decodeURIComponent") { args -> toJsString(args.getOrNull(0)).decodeUrl() })
        globalScope.define("encodeURIComponent", nativeFn("encodeURIComponent") { args -> toJsString(args.getOrNull(0)).encodeUrl() })
        globalScope.define("escape", nativeFn("escape") { args -> toJsString(args.getOrNull(0)).encodeUrl() })
        globalScope.define("unescape", nativeFn("unescape") { args -> toJsString(args.getOrNull(0)).decodeUrl() })
        // Nested eval() reuses the current budget rather than resetting it, otherwise a
        // script could keep itself alive forever via `while(true){ eval("1") }`.
        globalScope.define("eval", nativeFn("eval") { args -> evalInternal(toJsString(args.getOrNull(0))) })
        globalScope.define("undefined", Unit)
        globalScope.define("NaN", Double.NaN)
        globalScope.define("Infinity", Double.POSITIVE_INFINITY)

        globalScope.define("Array", nativeFn("Array") { args ->
            if (args.size == 1 && args[0] is Double) JsList(MutableList((args[0] as Double).toInt().coerceIn(0, JS_MAX_NATIVE_SIZE)) { Unit })
            else JsList(args.toMutableList())
        })

        globalScope.define("Object", NativeFn({ args ->
            // Object() called as a function, wrap primitive or return object as-is
            when (val v = args.getOrNull(0)) {
                null, is Unit -> JsObject()
                is JsObject -> v
                is JsList -> v
                else -> JsObject()
            }
        }, "Object", mutableMapOf(
            "keys" to nativeFn("keys") { args ->
                when (val o = args.getOrNull(0)) {
                    is JsObject -> JsList(o.props.keys.map { it as Any? }.toMutableList())
                    else -> JsList()
                }
            },
            "values" to nativeFn("values") { args ->
                when (val o = args.getOrNull(0)) {
                    is JsObject -> JsList(o.props.values.toMutableList())
                    else -> JsList()
                }
            }
        )))

        globalScope.define("Function", nativeFn("Function") { _ -> nativeFn("anonymous") { Unit } })
        globalScope.define("Number", nativeFn("Number") { args -> toNumber(args.getOrNull(0)) })
        globalScope.define("Boolean", nativeFn("Boolean") { args -> toBoolean(args.getOrNull(0)) })

        // console.log (no-op for silence, but avoids errors)
        val consoleObj = JsObject(mutableMapOf(
            "log" to nativeFn("log") { _ -> Unit },
            "error" to nativeFn("error") { _ -> Unit },
            "warn" to nativeFn("warn") { _ -> Unit },
        ))
        globalScope.define("console", consoleObj)
    }

    private fun nativeFn(name: String, fn: (List<Any?>) -> Any?): Any? = NativeFn(fn, name)

    fun eval(code: String): Any? {
        instructionCount = 0
        startMark = TimeSource.Monotonic.markNow()
        return evalInternal(code)
    }

    /** Runs [code] against the current budget, without resetting it. */
    private fun evalInternal(code: String): Any? {
        return try {
            val lexer = Lexer(code)
            val parser = Parser(lexer)
            val stmts = parser.parseProgram()
            var last: Any? = Unit
            for (stmt in stmts) last = execNode(stmt, globalScope)
            last
        } catch (r: ReturnSignal) {
            r.value
        } catch (e: JsCancellationException) {
            // CancellationException must never be swallowed. It signals that the
            // enclosing coroutine has been cancelled and must propagate so that the
            // coroutine can clean up correctly.
            throw e
        } catch (t: Throwable) {
            logError(t)
            Unit
        }
    }

    /**
     * Called on every statement execution. Throws [JsExecutionLimitExceeded] once the
     * script has used up its time or instruction budget, or the enclosing [CoroutineScope]
     * has been cancelled. This is what lets something like `evalJs("while(true){}")`
     * return instead of burning the CPU forever.
     *
     * [JsExecutionLimitExceeded] extends [Throwable] rather than [Exception], so a JS
     * script cannot catch it with its own try/catch block (the [TryCatch] handler in
     * [execNode] only catches [Exception]).
     */
    private fun checkBudget() {
        instructionCount++
        if (instructionCount >= maxInstructions) {
            throw JsExecutionLimitExceeded("script exceeded max instruction count of $maxInstructions")
        }
        if (scope != null && !scope.isActive) {
            throw JsCancellationException("script cancelled: coroutine scope is no longer active")
        }
        // Only sample the clock every 1024 ticks. Calling elapsedNow() on every single
        // statement would add measurable overhead to normal (non-runaway) scripts.
        if (instructionCount and 0x3FFL == 0L && startMark.elapsedNow() >= maxExecutionTime) {
            throw JsExecutionLimitExceeded("script exceeded max execution time of $maxExecutionTime")
        }
    }

    fun getVar(name: String): Any? = globalScope.get(name).let { if (it is Unit) null else it }
    fun setVar(name: String, value: Any?) = globalScope.define(name, value)

    private fun execNode(node: Node, scope: Scope): Any? {
        checkBudget()
        return when (node) {
            is VarDecl -> {
                // Audit-19 #2: JS `var` is function-scoped —
                // a declaration inside a block or loop used to
                // vanish with the block's child scope. Define
                // in the function scope; the initializer still
                // evaluates in the current scope. (This
                // interpreter parses let/const to the same
                // node; they share the treatment.)
                val target = scope.functionScope()
                for ((name, init) in node.decls) target.define(name, init?.let { evalExpr(it, scope) })
                Unit
            }
            is ExprStmt -> evalExpr(node.expr, scope)
            is BlockStmt -> {
                val inner = Scope(scope)
                var last: Any? = Unit
                for (s in node.stmts) last = execNode(s, inner)
                last
            }
            is ReturnStmt -> throw ReturnSignal(node.expr?.let { evalExpr(it, scope) })
            is IfStmt -> {
                if (toBoolean(evalExpr(node.test, scope))) execNode(node.cons, scope)
                else node.alt?.let { execNode(it, scope) }
            }
            is WhileStmt -> {
                try {
                    while (toBoolean(evalExpr(node.test, scope))) {
                        try { execNode(node.body, scope) } catch (_: ContinueSignal) {}
                    }
                } catch (_: BreakSignal) {}
                Unit
            }
            is ForStmt -> {
                val inner = Scope(scope)
                node.init?.let { execNode(it, inner) }
                try {
                    while (node.test == null || toBoolean(evalExpr(node.test, inner))) {
                        try { execNode(node.body, inner) } catch (_: ContinueSignal) {}
                        node.update?.let { evalExpr(it, inner) }
                    }
                } catch (_: BreakSignal) {}
                Unit
            }
            is ForInStmt -> {
                val obj = evalExpr(node.obj, scope)
                val inner = Scope(scope)
                try {
                    when (obj) {
                        is JsObject -> for (key in obj.props.keys) {
                            inner.define(node.decl, key)
                            try { execNode(node.body, inner) } catch (_: ContinueSignal) {}
                        }
                        is JsList -> for (i in obj.elements.indices) {
                            // Audit-17 #5: JS binds for-in
                            // keys as STRINGS, even for arrays
                            // (the object branch above already
                            // did); a Double key made
                            // k === "0" never true.
                            inner.define(node.decl, i.toString())
                            try { execNode(node.body, inner) } catch (_: ContinueSignal) {}
                        }
                        else -> {}
                    }
                } catch (_: BreakSignal) {}
                Unit
            }
            is TryCatch -> {
                try {
                    for (s in node.body) execNode(s, scope)
                } catch (ts: ThrowSignal) {
                    if (node.catchBody != null) {
                        val inner = Scope(scope)
                        if (node.catchParam != null) inner.define(node.catchParam, ts.value)
                        for (s in node.catchBody) execNode(s, inner)
                    }
                } catch (e: JsCancellationException) {
                    // CancellationException must never be swallowed by a JS try/catch.
                    // It must propagate so withTimeout and structured concurrency
                    // work correctly.
                    throw e
                } catch (_: Exception) {
                    // swallow other exceptions inside try (e.g. runtime errors)
                } finally {
                    node.finallyBody?.forEach { execNode(it, scope) }
                }
                Unit
            }
            is ThrowStmt -> throw ThrowSignal(evalExpr(node.expr, scope))
            is BreakStmt -> throw BreakSignal
            is ContinueStmt -> throw ContinueSignal
            else -> evalExpr(node, scope)
        }
    }

    private fun evalExpr(node: Node, scope: Scope): Any? = when (node) {
        is NumLit -> node.v
        is StrLit -> node.v
        is TemplateExpr -> node.parts.joinToString("") { toJsString(evalExpr(it, scope)) }
        is BoolLit -> node.v
        NullLit -> null
        UndefinedLit -> Unit
        is Ident -> scope.get(node.name)
        is ArrayLit -> JsList(node.elems.map { evalExpr(it, scope) }.toMutableList())
        is ObjLit -> JsObject(node.pairs.associate { (k, v) -> k to evalExpr(v, scope) }.toMutableMap())
        is FuncExpr -> {
            val fn = JsFunction(node.name, node.params, node.body, scope)
            if (node.name != null) scope.define(node.name, fn)
            fn
        }
        is SeqExpr -> node.exprs.fold(Unit as Any?) { _, e -> evalExpr(e, scope) }
        is TypeofExpr -> {
            val v = try { evalExpr(node.expr, scope) } catch (_: Exception) { Unit }
            when (v) {
                is Unit -> "undefined"
                null -> "object"
                is Double -> "number"
                is Boolean -> "boolean"
                is String -> "string"
                is JsFunction, is NativeFn -> "function"
                else -> "object"
            }
        }
        is UnaryExpr -> evalUnary(node, scope)
        is BinExpr -> evalBinary(node, scope)
        is AssignExpr -> evalAssign(node, scope)
        is CondExpr -> if (toBoolean(evalExpr(node.test, scope))) evalExpr(node.cons, scope) else evalExpr(node.alt, scope)
        is MemberExpr -> evalMember(node, scope)
        is CallExpr -> evalCall(node, scope)
        is NewExpr -> evalNew(node, scope)
        else -> Unit
    }

    private fun evalUnary(node: UnaryExpr, scope: Scope): Any? {
        if (node.op == "++" || node.op == "--") {
            val delta = if (node.op == "++") 1.0 else -1.0
            val old = toNumber(evalExpr(node.expr, scope))
            val newVal = old + delta
            assignTo(node.expr, newVal, scope)
            return if (node.prefix) newVal else old
        }
        val v = evalExpr(node.expr, scope)
        return when (node.op) {
            "-" -> -toNumber(v)
            "+" -> toNumber(v)
            "!" -> !toBoolean(v)
            "~" -> toInt32(v).inv().toDouble()
            else -> Unit
        }
    }

    private fun evalBinary(node: BinExpr, scope: Scope): Any? {
        // Short-circuit
        if (node.op == "&&") {
            val l = evalExpr(node.left, scope)
            return if (!toBoolean(l)) l else evalExpr(node.right, scope)
        }
        if (node.op == "||") {
            val l = evalExpr(node.left, scope)
            return if (toBoolean(l)) l else evalExpr(node.right, scope)
        }
        val l = evalExpr(node.left, scope)
        val r = evalExpr(node.right, scope)
        return when (node.op) {
            "+" -> {
                val lp = if (l is JsList || l is JsObject || l is NativeFn || l is JsFunction) toJsString(l) else l
                val rp = if (r is JsList || r is JsObject || r is NativeFn || r is JsFunction) toJsString(r) else r
                if (lp is String || rp is String) toJsString(lp) + toJsString(rp)
                else toNumber(lp) + toNumber(rp)
            }
            "-" -> toNumber(l) - toNumber(r)
            "*" -> toNumber(l) * toNumber(r)
            "/" -> toNumber(l) / toNumber(r)
            "%" -> toNumber(l) % toNumber(r)
            "**" -> toNumber(l).pow(toNumber(r))
            // Audit-16 #6: in JS two strings compare
            // lexicographically ("10" < "9" is true);
            // only mixed/non-string operands go numeric.
            "<" -> if (l is String && r is String) l < r else toNumber(l) < toNumber(r)
            "<=" -> if (l is String && r is String) l <= r else toNumber(l) <= toNumber(r)
            ">" -> if (l is String && r is String) l > r else toNumber(l) > toNumber(r)
            ">=" -> if (l is String && r is String) l >= r else toNumber(l) >= toNumber(r)
            "==" -> looseEq(l, r)
            "!=" -> !looseEq(l, r)
            "===" -> strictEq(l, r)
            "!==" -> !strictEq(l, r)
            "&" -> (toInt32(l) and toInt32(r)).toDouble()
            "|" -> (toInt32(l) or toInt32(r)).toDouble()
            "^" -> (toInt32(l) xor toInt32(r)).toDouble()
            "<<" -> (toInt32(l) shl (toUint32(r) and 31L).toInt()).toDouble()
            ">>" -> (toInt32(l) shr (toUint32(r) and 31L).toInt()).toDouble()
            ">>>" -> (toUint32(l) ushr (toUint32(r) and 31L).toInt()).toDouble()
            "instanceof" -> when (r) {
                is NativeFn -> when (r.name) {
                    "Array" -> l is JsList
                    "Object" -> l is JsObject || l is JsList
                    "Function" -> l is JsFunction || l is NativeFn
                    "String" -> l is String
                    "Number" -> l is Double
                    "Boolean" -> l is Boolean
                    else -> false
                }
                is JsFunction -> l is JsObject && l.constructor === r
                else -> false
            }
            "in" -> when (r) {
                is JsObject -> toJsString(l) in r.props
                is JsList -> toNumber(l).toInt().let { it >= 0 && it < r.elements.size }
                else -> false
            }
            else -> Unit
        }
    }

    private fun evalAssign(node: AssignExpr, scope: Scope): Any? {
        val right = evalExpr(node.right, scope)
        val value = if (node.op == "=") right else {
            val left = evalExpr(node.left, scope)
            when (node.op) {
                // Audit-18 #6: same ToPrimitive rule as
                // binary + — arrays/objects stringify first,
                // so [1,2] += 3 is "1,23", not NaN.
                "+=" -> {
                    val lp = if (left is JsList || left is JsObject || left is NativeFn || left is JsFunction) toJsString(left) else left
                    val rp = if (right is JsList || right is JsObject || right is NativeFn || right is JsFunction) toJsString(right) else right
                    if (lp is String || rp is String) toJsString(lp) + toJsString(rp)
                    else toNumber(lp) + toNumber(rp)
                }
                "-=" -> toNumber(left) - toNumber(right)
                "*=" -> toNumber(left) * toNumber(right)
                "/=" -> toNumber(left) / toNumber(right)
                "%=" -> toNumber(left) % toNumber(right)
                "**=" -> toNumber(left).pow(toNumber(right))
                // Audit-19 #3: compound bitwise/shift
                // assignment — the lexer did not even know
                // these tokens, so any script using one
                // failed to evaluate at all. Same ToInt32
                // semantics as the binary operators.
                "&=" -> (toInt32(left) and toInt32(right)).toDouble()
                "|=" -> (toInt32(left) or toInt32(right)).toDouble()
                "^=" -> (toInt32(left) xor toInt32(right)).toDouble()
                "<<=" -> (toInt32(left) shl (toUint32(right) and 31L).toInt()).toDouble()
                ">>=" -> (toInt32(left) shr (toUint32(right) and 31L).toInt()).toDouble()
                ">>>=" -> (toUint32(left) ushr (toUint32(right) and 31L).toInt()).toDouble()
                else -> right
            }
        }
        assignTo(node.left, value, scope)
        return value
    }

    private fun assignTo(target: Node, value: Any?, scope: Scope) {
        when (target) {
            is Ident -> scope.set(target.name, value)
            is MemberExpr -> {
                val obj = evalExpr(target.obj, scope)
                val key = propKey(target.prop, target.computed, scope)
                when (obj) {
                    is JsObject -> obj.props[key] = value
                    is JsList -> {
                        val idx = key.toIntOrNull()
                        if (idx != null) obj[idx] = value
                        else if (key == "length") obj.length = toNumber(value).toInt()
                    }
                    else -> {}
                }
            }
            // Anything else (e.g. `true++` or `5 = 3`) is not a valid assignment target in
            // JS. This would be a SyntaxError/ReferenceError there, so we fail the same way
            // here rather than silently doing nothing.
            else -> throw RuntimeException("Invalid assignment target: $target")
        }
    }

    /** Resolves a MemberExpr property node to its string key without unsafe casts. */
    private fun propKey(prop: Node, computed: Boolean, scope: Scope): String =
        if (computed) toJsString(evalExpr(prop, scope))
        else (prop as? StrLit)?.v ?: toJsString(evalExpr(prop, scope))

    private fun evalMember(node: MemberExpr, scope: Scope): Any? {
        val obj = evalExpr(node.obj, scope)
        val key = propKey(node.prop, node.computed, scope)
        return getMember(obj, key)
    }

    private fun getMember(obj: Any?, key: String): Any? = when (obj) {
        is NativeFn -> obj.props[key] ?: Unit
        is JsObject -> obj.props[key] ?: Unit
        is JsList -> when (key) {
            "length" -> obj.length.toDouble()
            "join" -> nativeFn("join") { args ->
                val sep = args.getOrNull(0)?.let { toJsString(it) } ?: ","
                obj.elements.joinToString(sep) { joinElement(it) }
            }
            "reverse" -> nativeFn("reverse") { _ -> obj.elements.reverse(); obj }
            "push" -> nativeFn("push") { args -> args.forEach { obj.elements.add(it) }; obj.elements.size.toDouble() }
            "pop" -> nativeFn("pop") { _ -> if (obj.elements.isEmpty()) Unit else obj.elements.removeAt(obj.elements.size - 1) }
            "shift" -> nativeFn("shift") { _ -> if (obj.elements.isEmpty()) Unit else obj.elements.removeAt(0) }
            "unshift" -> nativeFn("unshift") { args -> args.reversed().forEach { obj.elements.add(0, it) }; obj.elements.size.toDouble() }
            "slice" -> nativeFn("slice") { args ->
                val start = args.getOrNull(0)?.let { toNumber(it).toInt() } ?: 0
                val end = args.getOrNull(1)?.let { toNumber(it).toInt() } ?: obj.elements.size
                val s = if (start < 0) maxOf(0, obj.elements.size + start) else minOf(start, obj.elements.size)
                val e = if (end < 0) maxOf(0, obj.elements.size + end) else minOf(end, obj.elements.size)
                JsList(obj.elements.subList(maxOf(0, s), maxOf(s, e)).toMutableList())
            }
            "splice" -> nativeFn("splice") { args ->
                // Audit-18 #7: splice() with NO arguments is
                // a no-op returning [] in JS — the defaults
                // below turned it into "delete everything".
                if (args.isEmpty()) return@nativeFn JsList(mutableListOf())
                val start = args.getOrNull(0)?.let { toNumber(it).toInt() }?.let { if (it < 0) maxOf(0, obj.elements.size + it) else minOf(it, obj.elements.size) } ?: 0
                val deleteCount = args.getOrNull(1)?.let { toNumber(it).toInt() }?.coerceIn(0, obj.elements.size - start) ?: (obj.elements.size - start)
                val removed = JsList(obj.elements.subList(start, start + deleteCount).toMutableList())
                repeat(deleteCount) { obj.elements.removeAt(start) }
                args.drop(2).forEachIndexed { i, v -> obj.elements.add(start + i, v) }
                removed
            }
            "indexOf" -> nativeFn("indexOf") { args ->
                // Audit-15 #27: fromIndex is the search START
                // in JS, not a validity check on the first
                // match — [1,2,1].indexOf(1, 1) returned -1
                // here instead of 2, breaking deobfuscation
                // scripts that search with an offset.
                val v = args.getOrNull(0)
                val rawStart = args.getOrNull(1)?.let { toNumber(it).toInt() } ?: 0
                val start = if (rawStart < 0) maxOf(0, obj.elements.size + rawStart) else rawStart
                val idx = obj.elements.drop(start).indexOfFirst { strictEq(it, v) }
                if (idx == -1) -1.0 else (start + idx).toDouble()
            }
            "map" -> nativeFn("map") { args ->
                val fn = args.getOrNull(0)
                JsList(obj.elements.mapIndexed { i, v -> callAny(fn, listOf(v, i.toDouble(), obj), null) }.toMutableList())
            }
            "filter" -> nativeFn("filter") { args ->
                val fn = args.getOrNull(0)
                JsList(obj.elements.filterIndexed { i, v -> toBoolean(callAny(fn, listOf(v, i.toDouble(), obj), null)) }.toMutableList())
            }
            "forEach" -> nativeFn("forEach") { args ->
                val fn = args.getOrNull(0)
                obj.elements.forEachIndexed { i, v -> callAny(fn, listOf(v, i.toDouble(), obj), null) }
                Unit
            }
            "reduce" -> nativeFn("reduce") { args ->
                val fn = args.getOrNull(0)
                var acc: Any? = if (args.size > 1) args[1] else obj.elements.firstOrNull() ?: Unit
                val startIdx = if (args.size > 1) 0 else 1
                for (i in startIdx until obj.elements.size) acc = callAny(fn, listOf(acc, obj.elements[i], i.toDouble(), obj), null)
                acc
            }
            "concat" -> nativeFn("concat") { args ->
                val result = JsList(obj.elements.toMutableList())
                args.forEach { a -> when (a) { is JsList -> result.elements.addAll(a.elements); else -> result.elements.add(a) } }
                result
            }
            "find" -> nativeFn("find") { args ->
                val fn = args.getOrNull(0)
                obj.elements.firstOrNull { toBoolean(callAny(fn, listOf(it), null)) } ?: Unit
            }
            "some" -> nativeFn("some") { args ->
                val fn = args.getOrNull(0)
                obj.elements.any { toBoolean(callAny(fn, listOf(it), null)) }
            }
            "every" -> nativeFn("every") { args ->
                val fn = args.getOrNull(0)
                obj.elements.all { toBoolean(callAny(fn, listOf(it), null)) }
            }
            "sort" -> nativeFn("sort") { args ->
                val fn = args.getOrNull(0)
                if (fn == null) obj.elements.sortWith { a, b -> toJsString(a).compareTo(toJsString(b)) }
                else obj.elements.sortWith { a, b -> toNumber(callAny(fn, listOf(a, b), null)).toInt() }
                obj
            }
            // Audit-17 #6: includes uses SameValueZero in
            // JS (strict equality, except NaN matches NaN) —
            // looseEq made [1].includes("1") true and
            // [NaN].includes(NaN) false.
            "includes" -> nativeFn("includes") { args ->
                val target = args.getOrNull(0)
                obj.elements.any {
                    strictEq(it, target) ||
                        (it is Double && target is Double && it.isNaN() && target.isNaN())
                }
            }
            "toString" -> nativeFn("toString") { _ -> obj.elements.joinToString(",") { joinElement(it) } }
            "flat" -> nativeFn("flat") { _ ->
                val result = JsList()
                obj.elements.forEach { if (it is JsList) result.elements.addAll(it.elements) else result.elements.add(it) }
                result
            }
            else -> key.toIntOrNull()?.let { obj[it] } ?: Unit
        }
        is String -> when (key) {
            "length" -> obj.length.toDouble()
            "split" -> nativeFn("split") { args ->
                val sep = args.getOrNull(0)
                when {
                    sep == null || sep is Unit -> JsList(mutableListOf(obj))
                    sep is String && sep.isEmpty() -> JsList(obj.map { it.toString() as Any? }.toMutableList())
                    sep is String -> JsList(obj.split(sep).map { it as Any? }.toMutableList())
                    else -> JsList(obj.split(toJsString(sep)).map { it as Any? }.toMutableList())
                }
            }
            "join" -> nativeFn("join") { args -> obj } // strings don't have join but just in case
            "replace" -> nativeFn("replace") { args ->
                val from = args.getOrNull(0); val to = toJsString(args.getOrNull(1))
                when (from) {
                    is String -> obj.replaceFirst(from, to)
                    else -> obj.replace(toJsString(from), to)
                }
            }
            "replaceAll" -> nativeFn("replaceAll") { args ->
                val from = args.getOrNull(0); val to = toJsString(args.getOrNull(1))
                obj.replace(toJsString(from), to)
            }
            "indexOf" -> nativeFn("indexOf") { args ->
                // Audit-15 #27 (string sibling): the position
                // argument was ignored here too.
                val rawStart = args.getOrNull(1)?.let { toNumber(it).toInt() } ?: 0
                val start = if (rawStart < 0) 0 else minOf(rawStart, obj.length)
                obj.indexOf(toJsString(args.getOrNull(0)), start).toDouble()
            }
            "lastIndexOf" -> nativeFn("lastIndexOf") { args -> obj.lastIndexOf(toJsString(args.getOrNull(0))).toDouble() }
            "includes" -> nativeFn("includes") { args -> obj.contains(toJsString(args.getOrNull(0))) }
            "startsWith" -> nativeFn("startsWith") { args -> obj.startsWith(toJsString(args.getOrNull(0))) }
            "endsWith" -> nativeFn("endsWith") { args -> obj.endsWith(toJsString(args.getOrNull(0))) }
            "slice" -> nativeFn("slice") { args ->
                val start = args.getOrNull(0)?.let { toNumber(it).toInt() }?.let { if (it < 0) maxOf(0, obj.length + it) else minOf(it, obj.length) } ?: 0
                val end = args.getOrNull(1)?.let { toNumber(it).toInt() }?.let { if (it < 0) maxOf(0, obj.length + it) else minOf(it, obj.length) } ?: obj.length
                if (end <= start) "" else obj.substring(start, end)
            }
            "substr" -> nativeFn("substr") { args ->
                val start = args.getOrNull(0)?.let { toNumber(it).toInt() }?.let { if (it < 0) maxOf(0, obj.length + it) else minOf(it, obj.length) } ?: 0
                val len = args.getOrNull(1)?.let { toNumber(it).toInt() } ?: (obj.length - start)
                if (len <= 0) "" else obj.substring(start, minOf(start + len, obj.length))
            }
            "substring" -> nativeFn("substring") { args ->
                val a = args.getOrNull(0)?.let { toNumber(it).toInt().coerceIn(0, obj.length) } ?: 0
                val b = args.getOrNull(1)?.let { toNumber(it).toInt().coerceIn(0, obj.length) } ?: obj.length
                obj.substring(minOf(a, b), maxOf(a, b))
            }
            "charAt" -> nativeFn("charAt") { args ->
                val i = args.getOrNull(0)?.let { toNumber(it).toInt() } ?: 0
                if (i < 0 || i >= obj.length) "" else obj[i].toString()
            }
            "charCodeAt" -> nativeFn("charCodeAt") { args ->
                val i = args.getOrNull(0)?.let { toNumber(it).toInt() } ?: 0
                if (i < 0 || i >= obj.length) Double.NaN else obj[i].code.toDouble()
            }
            "codePointAt" -> nativeFn("codePointAt") { args ->
                val i = args.getOrNull(0)?.let { toNumber(it).toInt() } ?: 0
                if (i < 0 || i >= obj.length) Double.NaN else obj[i].code.toDouble()
            }
            "toUpperCase", "toLocaleUpperCase" -> nativeFn("toUpperCase") { _ -> obj.uppercase() }
            "toLowerCase", "toLocaleLowerCase" -> nativeFn("toLowerCase") { _ -> obj.lowercase() }
            "trim" -> nativeFn("trim") { _ -> obj.trim() }
            "trimStart", "trimLeft" -> nativeFn("trimStart") { _ -> obj.trimStart() }
            "trimEnd", "trimRight" -> nativeFn("trimEnd") { _ -> obj.trimEnd() }
            "repeat" -> nativeFn("repeat") { args ->
                // Audit-14 #6: the Audit-13 #12 cap bounded the
                // count, but the RESULT is obj.length x count —
                // "x".repeat(100000).repeat(1000000) chained to a
                // ~1e11-char allocation. Cap the result size.
                val count = toNumber(args.getOrNull(0)).toInt().coerceIn(0, JS_MAX_NATIVE_SIZE)
                if (obj.isNotEmpty() && count > JS_MAX_NATIVE_SIZE / obj.length) {
                    throw JsExecutionLimitExceeded("repeat result exceeds max native size of $JS_MAX_NATIVE_SIZE")
                }
                obj.repeat(count)
            }
            "padStart" -> nativeFn("padStart") { args ->
                val len = toNumber(args.getOrNull(0)).toInt().coerceIn(0, JS_MAX_NATIVE_SIZE); val pad = args.getOrNull(1)?.let { toJsString(it) } ?: " "
                // Audit-14 #6: pad.repeat(len) allocated
                // pad.length x len before the takeLast — repeat
                // the pad only as often as the gap needs.
                if (obj.length >= len || pad.isEmpty()) obj else (pad.repeat((len - obj.length + pad.length - 1) / pad.length) + obj).takeLast(len)
            }
            "padEnd" -> nativeFn("padEnd") { args ->
                val len = toNumber(args.getOrNull(0)).toInt().coerceIn(0, JS_MAX_NATIVE_SIZE); val pad = args.getOrNull(1)?.let { toJsString(it) } ?: " "
                // Audit-14 #6: see padStart.
                if (obj.length >= len || pad.isEmpty()) obj else (obj + pad.repeat((len - obj.length + pad.length - 1) / pad.length)).take(len)
            }
            "toString" -> nativeFn("toString") { _ -> obj }
            "valueOf" -> nativeFn("valueOf") { _ -> obj }
            "match" -> nativeFn("match") { args ->
                val pattern = toJsString(args.getOrNull(0))
                try {
                    // Audit-15 #6: a script-controlled regex
                    // ran with no time bound — the budget is
                    // only checked between statements, so a
                    // catastrophic-backtracking pattern hung
                    // the calling thread forever. The matcher
                    // reads its input through this CharSequence,
                    // which aborts once the eval deadline
                    // passes; the exception lands in the catch
                    // below and the call returns null.
                    val result = Regex(pattern).find(
                        DeadlineCharSequence(obj, startMark + maxExecutionTime)
                    )
                    if (result == null) null
                    else JsList(result.groupValues.map { it as Any? }.toMutableList())
                } catch (_: Exception) { null }
            }
            else -> key.toIntOrNull()?.let {
                if (it >= 0 && it < obj.length) obj[it].toString() else Unit
            } ?: Unit
        }
        is Double -> when (key) {
            "toString" -> nativeFn("toString") { args ->
                val radix = args.getOrNull(0)?.let { toNumber(it).toInt() } ?: 10
                if (radix == 10) toJsString(obj) else obj.toLong().toString(radix)
            }
            "toFixed" -> nativeFn("toFixed") { args ->
                val digits = (args.getOrNull(0)?.let { toNumber(it).toInt() } ?: 0).coerceIn(0, 20)
                val factor = 10.0.pow(digits)
                val rounded = round(obj * factor) / factor
                val sign = if (rounded < 0) "-" else ""
                val absVal = abs(rounded)
                val intPart = absVal.toLong()
                val fracPart = round((absVal - intPart) * factor).toLong()
                val fracStr = fracPart.toString().padStart(digits, '0')
                if (digits == 0) "$sign$intPart" else "$sign$intPart.$fracStr"
            }
            else -> Unit
        }
        else -> Unit
    }

    private fun evalCall(node: CallExpr, scope: Scope): Any? {
        val (callee, thisVal) = resolveCallee(node.callee, scope)
        val args = node.args.map { evalExpr(it, scope) }
        return callAny(callee, args, thisVal)
    }

    private fun resolveCallee(calleeNode: Node, scope: Scope): Pair<Any?, Any?> {
        return if (calleeNode is MemberExpr) {
            val obj = evalExpr(calleeNode.obj, scope)
            val key = propKey(calleeNode.prop, calleeNode.computed, scope)
            getMember(obj, key) to obj
        } else {
            evalExpr(calleeNode, scope) to null
        }
    }

    private fun evalNew(node: NewExpr, scope: Scope): Any? {
        val callee = evalExpr(node.callee, scope)
        val args = node.args.map { evalExpr(it, scope) }
        // NativeFn constructors (e.g. Array) return their value directly
        if (callee is NativeFn) return callee.fn(args)
        val thisVal = JsObject(constructor = callee as? JsFunction)
        val result = callAny(callee, args, thisVal)
        // JS 'new' returns the constructed object unless the constructor explicitly returns an object
        return if (result is JsObject) result else thisVal
    }

    private fun callAny(callee: Any?, args: List<Any?>, thisVal: Any?): Any? = when (callee) {
        is NativeFn -> callee.fn(args)
        is JsFunction -> {
            val fnScope = Scope(callee.closure, isFunctionRoot = true)
            fnScope.define("this", thisVal)
            fnScope.define("arguments", JsList(args.toMutableList()))
            callee.params.forEachIndexed { i, p -> fnScope.define(p, args.getOrElse(i) { Unit }) }
            try {
                var last: Any? = Unit
                for (s in callee.body) last = execNode(s, fnScope)
                last
            } catch (r: ReturnSignal) {
                r.value
            }
        }
        else -> Unit
    }
}

// Wrapper so we can store Kotlin lambdas as "callable" values
private class NativeFn(val fn: (List<Any?>) -> Any?, val name: String, val props: MutableMap<String, Any?> = mutableMapOf()) {
    override fun toString(): String = "function $name() { [native code] }"
}
