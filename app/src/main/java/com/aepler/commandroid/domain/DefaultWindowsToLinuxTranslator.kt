// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import com.aepler.commandroid.domain.PosixQuoting.VarStyle
import java.io.File
import javax.inject.Inject

/**
 * Translates Windows CMD command lines into Android `/system/bin/sh` (toybox) command lines.
 *
 * Lines whose commands are not Windows commands are returned unchanged. Each segment of a
 * `|`, `&&`, `||` or `&` chain is translated independently. Drive `C:` maps to shared storage
 * ([ANDROID_STORAGE_ROOT]); other drives are rejected with a diagnostic.
 */
class DefaultWindowsToLinuxTranslator @Inject constructor() : WindowsToLinuxTranslator {

    override fun translate(
        rawCommand: String,
        currentDirectory: File,
        environment: Map<String, String>
    ): TranslationResult {
        val original = rawCommand.trim()
        if (original.isEmpty()) return TranslationResult.Translated(original, "", null)
        return try {
            LineTranslation(original, currentDirectory, environment).run()
        } catch (e: UnsupportedCommandException) {
            e.error
        }
    }

    companion object {
        const val ANDROID_STORAGE_ROOT = "/storage/emulated/0"
        const val BANNER_PREFIX = "[CMD -> SH]"

        fun banner(original: String, posix: String): String = "$BANNER_PREFIX $original -> $posix"
    }
}

private class UnsupportedCommandException(
    val error: TranslationResult.UnsupportedCommandError
) : RuntimeException(error.diagnosticExplanation, null, false, false)

private fun unsupported(token: String, diagnostic: String, suggestion: String? = null): Nothing =
    throw UnsupportedCommandException(
        TranslationResult.UnsupportedCommandError(token, diagnostic, suggestion)
    )

private class Flag(val name: String, val token: String, val value: CmdWord?)

private class ParsedArgs(val flags: List<Flag>, val positionals: List<CmdWord>) {
    fun has(name: String): Boolean = flags.any { it.name == name }
    fun values(name: String): List<CmdWord> = flags.filter { it.name == name }.mapNotNull { it.value }
}

private class SegmentOutput(val text: String, val translated: Boolean)

private class LineTranslation(
    private val original: String,
    private val currentDirectory: File,
    private val environment: Map<String, String>
) {
    private var runningDirectory: String? = normalizeAbsolute(currentDirectory.path)
    private var directoryChanged = false

    fun run(): TranslationResult {
        val chain = CmdLexer.parse(original)
        if (chain.segments.size == 1) builtIn(chain.segments[0])?.let { return it }

        val outputs = chain.segments.map { translateSegment(it, chained = chain.segments.size > 1) }
        if (outputs.none { it.translated }) {
            return TranslationResult.Translated(original, original, null)
        }

        val posix = StringBuilder()
        chain.segments.forEachIndexed { index, segment ->
            if (segment.isEmpty) {
                val trailingSequence = index == chain.segments.lastIndex && index > 0 &&
                    chain.operators[index - 1] == ChainOperator.SEQUENCE
                if (trailingSequence) return@forEachIndexed
                val op = chain.operators.getOrNull(index) ?: chain.operators.getOrNull(index - 1)
                unsupported(
                    op?.posix?.trim() ?: "",
                    "Empty command in a command chain; put a command on both sides of the operator."
                )
            }
            if (index > 0) posix.append(chain.operators[index - 1].posix)
            posix.append(outputs[index].text)
        }
        val posixCommand = posix.toString()
        val tracksDirectory = chain.operators.all { it == ChainOperator.AND }
        val newDirectory = if (directoryChanged && tracksDirectory) runningDirectory?.let(::File) else null
        val explanation = if (posixCommand != original) {
            DefaultWindowsToLinuxTranslator.banner(original, posixCommand)
        } else {
            null
        }
        return TranslationResult.Translated(original, posixCommand, explanation, newDirectory)
    }

    // ---------------------------------------------------------------- built-ins

    private fun builtIn(segment: CmdSegment): TranslationResult? {
        if (segment.redirects.isNotEmpty() || segment.words.isEmpty()) return null
        val name = commandName(segment.words[0]) ?: return null
        val args = segment.words.drop(1)
        return when {
            name == "cls" -> TranslationResult.BuiltInAction(BuiltInTerminalAction.CLEAR_SCREEN)
            name == "exit" -> TranslationResult.BuiltInAction(BuiltInTerminalAction.EXIT_SESSION)
            name == "help" -> TranslationResult.BuiltInAction(BuiltInTerminalAction.PRINT_HELP)
            name in WINDOWS_COMMANDS && args.any { it.bareText == "/?" } ->
                TranslationResult.BuiltInAction(BuiltInTerminalAction.PRINT_HELP)
            else -> null
        }
    }

    // ---------------------------------------------------------------- dispatch

    private fun commandName(word: CmdWord): String? =
        word.literalText?.lowercase()?.removePrefix("@")?.takeIf { it.isNotEmpty() }

    private fun passthrough(segment: CmdSegment): SegmentOutput =
        if (segment.isEmpty) SegmentOutput("", false)
        else SegmentOutput(original.substring(segment.start, segment.end).trim(), false)

    private fun translateSegment(segment: CmdSegment, chained: Boolean): SegmentOutput {
        if (segment.words.isEmpty()) return passthrough(segment)
        val head = segment.words[0]
        val headText = head.literalText?.removePrefix("@") ?: return passthrough(segment)
        var name = headText.lowercase()
        var args = segment.words.drop(1)

        // `cd..` and `cd\` are valid CMD spellings without a separating space.
        CD_GLUED.matchEntire(headText)?.let { match ->
            name = "cd"
            args = listOf(CmdWord(listOf(Piece.Lit(match.groupValues[2], head.anyQuoted)), head.start, head.end)) + args
        }

        // Bare drive switch, e.g. `C:`.
        DRIVE_SWITCH.matchEntire(name)?.let {
            if (args.isEmpty()) return SegmentOutput(withRedirects(driveSwitch(headText), segment), true)
        }

        if (name.endsWith(".exe") || name.endsWith(".com")) {
            val base = name.dropLast(4).substringAfterLast('/').substringAfterLast('\\')
            if (base in WINDOWS_COMMANDS || base in UNSUPPORTED_TOOLS) {
                name = base
            } else {
                unsupported(
                    headText,
                    "'$headText' is a Windows executable; Android cannot run PE (.exe/.com) binaries.",
                    null
                )
            }
        }
        SCRIPT_EXTENSIONS.firstOrNull { name.endsWith(it) }?.let { ext ->
            unsupported(
                headText,
                "'$headText' is a Windows $ext script; it cannot run in the Android shell. " +
                    "Port it to a POSIX sh script.",
                "sh <script>.sh"
            )
        }
        UNSUPPORTED_TOOLS[name]?.let { (diagnostic, suggestion) ->
            unsupported(headText, diagnostic, suggestion)
        }
        if (POWERSHELL_CMDLET.matches(name)) {
            unsupported(
                headText,
                "'$headText' is a PowerShell cmdlet; PowerShell is not available on Android " +
                    "(commands run in /system/bin/sh).",
                CMDLET_EQUIVALENTS[name]
            )
        }

        if (chained && args.any { it.bareText == "/?" } && name in WINDOWS_COMMANDS) {
            unsupported("/?", "'$name /?' cannot be chained; run it on its own line to show help.")
        }

        val translated: String = when (name) {
            "cls" -> "clear"
            "exit" -> exitInChain(args)
            "help" -> unsupported("help", "'help' cannot be chained; run it on its own line.")
            "cd", "chdir" -> cd(name, args)
            "dir" -> dir(args)
            "type" -> type(args)
            "copy" -> copy(args)
            "xcopy" -> xcopy(args)
            "move" -> move(args)
            "ren", "rename" -> rename(name, args)
            "del", "erase" -> del(name, args)
            "rd" -> rmdir(name, args)
            "rmdir" -> if (isWindowsStyle(args, setOf("s", "q"))) rmdir(name, args) else return passthrough(segment)
            "md" -> mkdir(name, args)
            "mkdir" -> if (isWindowsStyle(args, emptySet())) mkdir(name, args) else return passthrough(segment)
            "findstr" -> findstr(args)
            "where" -> where(args)
            "ipconfig" -> ipconfig(args)
            "tasklist" -> tasklist(args)
            "taskkill" -> taskkill(args)
            "echo." -> "echo"
            "echo" -> if (args.any { it.hasVars || it.hadEscape }) echo(args) else return passthrough(segment)
            "set" -> {
                // `set`, `set -e`, `set +x` are POSIX shell builtins; only `set NAME=value` is CMD.
                val first = args.firstOrNull() ?: return passthrough(segment)
                val text = first.literalText
                if (text != null && (text.startsWith("-") || text.startsWith("+"))) return passthrough(segment)
                set(args)
            }
            "ver" -> {
                parseArgs("ver", args, emptySet())
                "uname -a"
            }
            else -> return passthrough(segment)
        }
        return SegmentOutput(withRedirects(translated, segment), true)
    }

    private fun withRedirects(command: String, segment: CmdSegment): String {
        if (segment.redirects.isEmpty()) return command
        val out = StringBuilder(command)
        for (redirect in segment.redirects) {
            val token = redirect.token
            out.append(' ').append(token.fd).append(token.op)
            if (token.duplicate != null) {
                out.append(token.duplicate)
                continue
            }
            val target = redirect.target
                ?: unsupported(token.fd + token.op, "Redirection '${token.fd}${token.op}' is missing a target file.")
            if (target.literalText.equals("nul", ignoreCase = true)) {
                out.append("/dev/null")
            } else {
                out.append(' ').append(renderPath(target))
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- argument parsing

    /**
     * Splits CMD switches (`/s`, `/s/q`, `/a:h`, `/pid 42`, `/c:"text"`) from positional words.
     * Only unquoted words count as switches. A multi-letter switch is recognised only when it is
     * in [known], so `/system/bin` stays a path while unknown single-letter switches are rejected.
     */
    private fun parseArgs(
        command: String,
        args: List<CmdWord>,
        known: Set<String>,
        valued: Set<String> = emptySet()
    ): ParsedArgs {
        val flags = mutableListOf<Flag>()
        val positionals = mutableListOf<CmdWord>()
        var index = 0
        while (index < args.size) {
            val word = args[index]
            index++
            val first = word.pieces.first() as? Piece.Lit
            if (first == null || first.quoted || !first.text.startsWith("/") || first.text.length < 2) {
                positionals += word
                continue
            }
            val rest = word.pieces.drop(1)
            val parts = first.text.substring(1).split('/')
            val partNames = parts.map { it.substringBefore(':').lowercase() }
            val flagLike = partNames.all { n ->
                FLAG_NAME.matches(n) && (n in known || n.length == 1 || n == "-y")
            }
            if (!flagLike || (rest.isNotEmpty() && (parts.size != 1 || !parts[0].endsWith(":")))) {
                positionals += word
                continue
            }
            parts.forEachIndexed { partIndex, part ->
                val flagName = partNames[partIndex]
                val token = "/$part"
                if (flagName == "?") {
                    flags += Flag(flagName, token, null)
                    return@forEachIndexed
                }
                if (flagName !in known) {
                    unsupported(
                        "/${part.substringBefore(':')}",
                        "'$command' does not support the switch /${part.substringBefore(':')} " +
                            "in the Android shell translation."
                    )
                }
                val colonValue = if (':' in part) part.substringAfter(':') else null
                val value: CmdWord? = when {
                    rest.isNotEmpty() -> word.withPieces(rest)
                    !colonValue.isNullOrEmpty() -> word.withPieces(listOf(Piece.Lit(colonValue, false)))
                    flagName in valued -> {
                        val next = args.getOrNull(index)
                            ?: unsupported(token, "'$command $token' requires a value.")
                        index++
                        next
                    }
                    else -> null
                }
                flags += Flag(flagName, token, value)
            }
        }
        return ParsedArgs(flags, positionals)
    }

    private fun isWindowsStyle(args: List<CmdWord>, flags: Set<String>): Boolean = args.any { word ->
        if (word.hasVars || word.hadEscape) return@any true
        val text = word.literalText ?: return@any true
        if ('\\' in text || DRIVE_PREFIX.containsMatchIn(text)) return@any true
        val bare = word.bareText
        bare != null && bare.length >= 2 && bare.startsWith("/") &&
            bare.substring(1).split('/').all { it.lowercase() in flags || it == "?" }
    }

    // ---------------------------------------------------------------- paths & variables

    private fun resolveVarName(name: String): String {
        environment.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        VAR_ALIASES[name.uppercase()]?.let { return it }
        return name
    }

    private fun resolveVars(pieces: List<Piece>): List<Piece> = pieces.map {
        if (it is Piece.Var) Piece.Var(resolveVarName(it.name), it.quoted) else it
    }

    /** Backslashes to `/`, `C:` to shared storage, variable names resolved. */
    private fun normalizePathPieces(word: CmdWord): List<Piece> {
        val firstLiteral = (word.pieces.first() as? Piece.Lit)?.text ?: ""
        if (firstLiteral.startsWith("\\\\")) {
            unsupported(
                word.displayText,
                "UNC network paths (\\\\server\\share) are not reachable from the Android shell.",
                null
            )
        }
        val pieces = resolveVars(word.pieces).map {
            if (it is Piece.Lit) Piece.Lit(it.text.replace('\\', '/'), it.quoted) else it
        }.toMutableList()
        val first = pieces.firstOrNull() as? Piece.Lit ?: return pieces
        val match = DRIVE_PREFIX.find(first.text) ?: return pieces
        val drive = match.groupValues[1].uppercase()
        if (drive != "C") {
            unsupported(
                "$drive:",
                "Drive $drive: does not exist on Android. C:\\ is mapped to " +
                    "${DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT}/; removable SD cards " +
                    "are mounted under /storage/<volume-id>/.",
                DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT + "/"
            )
        }
        val rest = first.text.substring(2)
        val root = DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT
        val followingStartsWithSlash = (pieces.getOrNull(1) as? Piece.Lit)?.text?.startsWith("/") == true
        val mapped = when {
            rest.startsWith("/") -> root + rest
            rest.isEmpty() && pieces.size > 1 && !followingStartsWithSlash -> "$root/"
            else -> root + rest
        }
        pieces[0] = Piece.Lit(mapped, first.quoted)
        return pieces
    }

    private fun renderPath(word: CmdWord, style: VarStyle = VarStyle.QUOTED): String =
        PosixQuoting.render(normalizePathPieces(word), style, allowGlob = true)

    /** Concatenates pieces with environment values; null when a variable is unknown. */
    private fun resolveText(pieces: List<Piece>, unknownAsEmpty: Boolean): String? {
        val out = StringBuilder()
        for (piece in pieces) {
            when (piece) {
                is Piece.Lit -> out.append(piece.text)
                is Piece.Var -> out.append(environment[piece.name] ?: if (unknownAsEmpty) "" else return null)
            }
        }
        return out.toString()
    }

    private fun resolveAgainst(base: String?, path: String): String? = when {
        path.startsWith("/") -> normalizeAbsolute(path)
        base == null -> null
        else -> normalizeAbsolute("$base/$path")
    }

    /** Splits `dir/name*` into (`dir`, `name*`); dir is empty when there is no slash. */
    private fun splitLastComponent(pieces: List<Piece>): Pair<List<Piece>, List<Piece>> {
        val index = pieces.indexOfLast { it is Piece.Lit && '/' in it.text }
        if (index < 0) return emptyList<Piece>() to pieces
        val lit = pieces[index] as Piece.Lit
        val slash = lit.text.lastIndexOf('/')
        val dirText = lit.text.substring(0, slash).ifEmpty { if (index == 0) "/" else "" }
        val dir = pieces.subList(0, index) + listOfNotNull(dirText.takeIf { it.isNotEmpty() }?.let { Piece.Lit(it, lit.quoted) })
        val nameText = lit.text.substring(slash + 1)
        val name = listOfNotNull(nameText.takeIf { it.isNotEmpty() }?.let { Piece.Lit(it, lit.quoted) }) +
            pieces.subList(index + 1, pieces.size)
        return dir to name
    }

    private fun hasUnquotedGlob(pieces: List<Piece>): Boolean =
        pieces.any { it is Piece.Lit && !it.quoted && (it.text.contains('*') || it.text.contains('?')) }

    // ---------------------------------------------------------------- deletion guard

    private fun protectedPaths(): Set<String> {
        val home = environment["HOME"]?.let(::normalizeAbsolute)
        return PROTECTED_PATHS + listOfNotNull(home)
    }

    /**
     * Refuses deletions that resolve to `/`, a wildcard over `/`, an empty target, or (for
     * recursive/wildcard deletes) a protected system or storage root.
     */
    private fun guardDeletion(command: String, word: CmdWord, pieces: List<Piece>, recursive: Boolean) {
        val shown = word.displayText
        fun refuse(reason: String): Nothing = unsupported(
            shown.ifEmpty { command },
            "Refusing '$command': $reason Name the specific file or subdirectory to delete.",
            null
        )
        val text = resolveText(pieces, unknownAsEmpty = true) ?: ""
        if (text.isBlank()) refuse("the target is empty (unset variable or empty argument).")
        var path = text.replace(MULTI_SLASH, "/")
        var wildcardAll = false
        while (true) {
            path = when {
                path.length > 1 && path.endsWith("/") -> path.dropLast(1)
                path.endsWith("/*") || path.endsWith("/*.*") -> {
                    wildcardAll = true
                    path.substringBeforeLast('/').ifEmpty { "/" }
                }
                path == "*" || path == "*.*" -> {
                    wildcardAll = true
                    "."
                }
                path.length > 2 && path.endsWith("/.") -> path.dropLast(2)
                else -> break
            }
        }
        val resolved = resolveAgainst(runningDirectory, path)
        if (resolved == null) {
            if ((recursive || wildcardAll) && (path == "." || path == ".." || path.startsWith("../"))) {
                refuse("the working directory is unknown, so '$shown' cannot be verified as safe.")
            }
            return
        }
        if (resolved == "/") refuse("it would delete the filesystem root '/'.")
        if ((recursive || wildcardAll) && resolved in protectedPaths()) {
            refuse("it resolves to the protected directory $resolved.")
        }
        if (recursive && resolved.removePrefix("/").substringBefore('/').any { it == '*' || it == '?' }) {
            refuse("a wildcard directly under '/' would match system directories.")
        }
    }

    // ---------------------------------------------------------------- commands

    private fun exitInChain(args: List<CmdWord>): String {
        val parsed = parseArgs("exit", args, setOf("b"))
        val code = parsed.positionals.firstOrNull()?.literalText?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        return if (code != null) "exit $code" else "exit"
    }

    private fun driveSwitch(token: String): String {
        val drive = token.substring(0, 1).uppercase()
        if (drive != "C") {
            unsupported(
                "$drive:",
                "Drive $drive: does not exist on Android; C: is mapped to " +
                    "${DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT}.",
                "cd ${DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT}"
            )
        }
        updateDirectory(DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT)
        return "cd ${DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT} && pwd"
    }

    private fun updateDirectory(path: String?) {
        directoryChanged = true
        runningDirectory = path
    }

    private fun cd(name: String, args: List<CmdWord>): String {
        val windowsStyle = args.size > 1 || isWindowsStyle(args, setOf("d"))
        if (!windowsStyle) {
            if (args.isEmpty()) return "pwd"
            // Already POSIX (`cd /sdcard`, `cd "$HOME"`): keep the user's own shell syntax.
            val rawArgs = original.substring(args.first().start, args.last().end)
            updateDirectory(posixCdTarget(args.single()))
            return "cd $rawArgs && pwd"
        }
        val parsed = parseArgs(name, args, setOf("d"))
        if (parsed.positionals.isEmpty()) return "pwd"
        // CMD's cd accepts unquoted spaces: `cd My Documents`.
        val merged = parsed.positionals.flatMapIndexed { index, word ->
            if (index == 0) word.pieces else listOf(Piece.Lit(" ", true)) + word.pieces
        }
        val word = parsed.positionals.first().withPieces(merged)
        val pieces = normalizePathPieces(word)
        val target = resolveText(pieces, unknownAsEmpty = false)?.let { resolveAgainst(runningDirectory, it) }
        updateDirectory(target)
        return "cd ${PosixQuoting.render(pieces, VarStyle.QUOTED, allowGlob = false)} && pwd"
    }

    private fun posixCdTarget(word: CmdWord): String? {
        var text = word.literalText ?: return null
        if (text == "-" || text.any { it in "$`*?[" }) return null
        if (text == "~" || text.startsWith("~/")) {
            if (word.anyQuoted) return resolveAgainst(runningDirectory, text)
            val home = environment["HOME"] ?: return null
            text = home + text.substring(1)
        }
        return resolveAgainst(runningDirectory, text)
    }

    private fun dir(args: List<CmdWord>): String {
        val parsed = parseArgs("dir", args, DIR_FLAGS)
        val all = parsed.has("a")
        val recursive = parsed.has("s")
        val bare = parsed.has("b")
        if (recursive) {
            val targets = parsed.positionals
            if (targets.isEmpty()) return "find ."
            if (targets.size == 1) {
                val pieces = normalizePathPieces(targets[0])
                if (hasUnquotedGlob(pieces)) {
                    val (dirPieces, namePieces) = splitLastComponent(pieces)
                    val root = if (dirPieces.isEmpty()) "." else PosixQuoting.render(dirPieces, VarStyle.QUOTED, true)
                    return "find $root -name ${PosixQuoting.render(namePieces, VarStyle.QUOTED, false)}"
                }
            }
            return "find " + targets.joinToString(" ") { renderPath(it) }
        }
        val options = (if (bare) "-1" else "-l") + (if (all) "a" else "")
        return (listOf("ls", options) + parsed.positionals.map { renderPath(it) }).joinToString(" ")
    }

    private fun type(args: List<CmdWord>): String {
        val parsed = parseArgs("type", args, emptySet())
        if (parsed.positionals.isEmpty()) {
            unsupported("type", "'type' needs at least one file name.", "cat <file>")
        }
        return (listOf("cat") + parsed.positionals.map { renderPath(it) }).joinToString(" ")
    }

    private fun sourceAndDestination(command: String, positionals: List<CmdWord>): List<String> {
        positionals.firstOrNull { w -> w.pieces.any { it is Piece.Lit && !it.quoted && '+' in it.text } }?.let {
            unsupported(
                it.displayText,
                "'$command a+b dest' file concatenation is not supported by cp.",
                "cat <a> <b> > <dest>"
            )
        }
        return when (positionals.size) {
            1 -> listOf(renderPath(positionals[0]), ".")
            2 -> positionals.map { renderPath(it) }
            0 -> unsupported(command, "'$command' needs a source (and optionally a destination).", "cp <src> <dst>")
            else -> unsupported(
                positionals[2].displayText,
                "'$command' takes one source and one destination; quote paths that contain spaces.",
                null
            )
        }
    }

    private fun copy(args: List<CmdWord>): String {
        val parsed = parseArgs("copy", args, COPY_FLAGS)
        val paths = sourceAndDestination("copy", parsed.positionals)
        return (listOf("cp") + listOfNotNull("-f".takeIf { parsed.has("y") }) + paths).joinToString(" ")
    }

    private fun xcopy(args: List<CmdWord>): String {
        val parsed = parseArgs("xcopy", args, XCOPY_FLAGS)
        val paths = sourceAndDestination("xcopy", parsed.positionals)
        val letters = (if (parsed.has("e") || parsed.has("s")) "r" else "") + (if (parsed.has("y")) "f" else "")
        return (listOf("cp") + listOfNotNull(letters.takeIf { it.isNotEmpty() }?.let { "-$it" }) + paths)
            .joinToString(" ")
    }

    private fun move(args: List<CmdWord>): String {
        val parsed = parseArgs("move", args, setOf("y", "-y"))
        val paths = sourceAndDestination("move", parsed.positionals)
        return (listOf("mv") + listOfNotNull("-f".takeIf { parsed.has("y") }) + paths).joinToString(" ")
    }

    private fun rename(name: String, args: List<CmdWord>): String {
        val parsed = parseArgs(name, args, emptySet())
        if (parsed.positionals.size != 2) {
            unsupported(name, "'$name' takes exactly two arguments: <old> <new>.", "mv <old> <new>")
        }
        val source = normalizePathPieces(parsed.positionals[0])
        var target = normalizePathPieces(parsed.positionals[1])
        // CMD's ren keeps the file in its directory: `ren dir\a.txt b.txt` -> dir/b.txt.
        val targetHasSlash = target.any { it is Piece.Lit && '/' in it.text }
        val (sourceDir, _) = splitLastComponent(source)
        if (!targetHasSlash && sourceDir.isNotEmpty()) {
            val dirEndsWithSlash = (sourceDir.last() as? Piece.Lit)?.text?.endsWith("/") == true
            target = sourceDir + listOfNotNull(Piece.Lit("/", false).takeIf { !dirEndsWithSlash }) + target
        }
        return "mv ${PosixQuoting.render(source, VarStyle.QUOTED, true)} " +
            PosixQuoting.render(target, VarStyle.QUOTED, false)
    }

    private fun del(name: String, args: List<CmdWord>): String {
        val parsed = parseArgs(name, args, DEL_FLAGS)
        if (parsed.positionals.isEmpty()) {
            unsupported(name, "'$name' needs at least one file name.", "rm -f <file>")
        }
        if (parsed.has("s")) {
            // del /s deletes matching *files* throughout a tree, never directories.
            val commands = parsed.positionals.map { word ->
                val pieces = normalizePathPieces(word)
                val (dirPieces, namePieces) = splitLastComponent(pieces)
                if (namePieces.isEmpty()) {
                    unsupported(word.displayText, "'$name /s' needs a file name or pattern, not a directory.")
                }
                val rootPieces = dirPieces.ifEmpty { listOf(Piece.Lit(".", false)) }
                val nameText = resolveText(namePieces, unknownAsEmpty = true) ?: ""
                val matchesEverything = nameText == "*" || nameText == "*.*" || nameText.isEmpty()
                guardDeletion(name, word, rootPieces, recursive = matchesEverything)
                val rootText = resolveText(rootPieces, unknownAsEmpty = true)?.let { resolveAgainst(runningDirectory, it) }
                if (rootText == "/") guardDeletion(name, word, listOf(Piece.Lit("/", false)), recursive = true)
                val interactive = if (parsed.has("p")) " -ok rm -f {} ;" else " -delete"
                "find ${PosixQuoting.render(rootPieces, VarStyle.REQUIRED, true)} -type f -name " +
                    PosixQuoting.render(namePieces, VarStyle.REQUIRED, false) + interactive
            }
            return if (commands.size == 1) commands[0] else "{ " + commands.joinToString(" && ") + "; }"
        }
        val targets = parsed.positionals.map { word ->
            val pieces = normalizePathPieces(word)
            guardDeletion(name, word, pieces, recursive = false)
            PosixQuoting.render(pieces, VarStyle.REQUIRED, allowGlob = true)
        }
        val option = if (parsed.has("p")) "-i" else "-f"
        return (listOf("rm", option) + targets).joinToString(" ")
    }

    private fun rmdir(name: String, args: List<CmdWord>): String {
        val parsed = parseArgs(name, args, setOf("s", "q"))
        if (parsed.positionals.isEmpty()) {
            unsupported(name, "'$name' needs a directory name.", "rmdir <dir>")
        }
        val recursive = parsed.has("s")
        val targets = parsed.positionals.map { word ->
            val pieces = normalizePathPieces(word)
            guardDeletion(name, word, pieces, recursive)
            PosixQuoting.render(pieces, VarStyle.REQUIRED, allowGlob = true)
        }
        val command = when {
            recursive && parsed.has("q") -> listOf("rm", "-rf")
            recursive -> listOf("rm", "-r")
            else -> listOf("rmdir")
        }
        return (command + targets).joinToString(" ")
    }

    private fun mkdir(name: String, args: List<CmdWord>): String {
        val parsed = parseArgs(name, args, emptySet())
        if (parsed.positionals.isEmpty()) unsupported(name, "'$name' needs a directory name.", "mkdir -p <dir>")
        return (listOf("mkdir", "-p") + parsed.positionals.map { renderPath(it) }).joinToString(" ")
    }

    private fun findstr(args: List<CmdWord>): String {
        val parsed = parseArgs("findstr", args, FINDSTR_FLAGS)
        val options = mutableListOf<String>()
        for (flag in parsed.flags) {
            val option = when (flag.name) {
                "i" -> "-i"
                "n" -> "-n"
                "r" -> "-E"
                "v" -> "-v"
                "l" -> "-F"
                "x" -> "-x"
                "s" -> "-r"
                "m" -> "-l"
                "c" -> null
                else -> unsupported(flag.token, "'findstr ${flag.token}' has no grep equivalent.", null)
            }
            if (option != null && option !in options) options += option
        }
        val patterns = mutableListOf<CmdWord>()
        val literal = parsed.values("c")
        val positionals = parsed.positionals.toMutableList()
        if (literal.isNotEmpty()) {
            patterns += literal
        } else {
            val pattern = positionals.removeFirstOrNull()
                ?: unsupported("findstr", "'findstr' needs a search pattern.", "grep <pattern> <file>")
            val text = pattern.literalText
            // findstr "a b" searches for a OR b.
            if (text != null && ' ' in text.trim()) {
                text.trim().split(WHITESPACE).forEach { patterns += pattern.withPieces(listOf(Piece.Lit(it, true))) }
            } else {
                patterns += pattern
            }
        }
        val patternArgs = if (patterns.size == 1 && patterns[0].displayText.firstOrNull() != '-') {
            listOf(PosixQuoting.render(resolveVars(patterns[0].pieces), VarStyle.QUOTED, false))
        } else {
            patterns.flatMap { listOf("-e", PosixQuoting.render(resolveVars(it.pieces), VarStyle.QUOTED, false)) }
        }
        val files = if (parsed.has("s") && positionals.size == 1 && hasUnquotedGlob(positionals[0].pieces) &&
            positionals[0].literalText?.contains('\\') == false && positionals[0].literalText?.contains('/') == false
        ) {
            listOf("--include=" + PosixQuoting.render(positionals[0].pieces, VarStyle.QUOTED, false), ".")
        } else {
            positionals.map { renderPath(it) }
        }
        return (listOf("grep") + options + patternArgs + files).joinToString(" ")
    }

    private fun where(args: List<CmdWord>): String {
        val parsed = parseArgs("where", args, setOf("r", "q", "t", "f"), valued = setOf("r"))
        if (parsed.positionals.isEmpty()) unsupported("where", "'where' needs a program name.", "which <name>")
        val root = parsed.values("r").firstOrNull()
        if (root != null) {
            val names = parsed.positionals.joinToString(" -o ") {
                "-name " + PosixQuoting.render(resolveVars(it.pieces), VarStyle.QUOTED, false)
            }
            return "find ${renderPath(root)} " + if (parsed.positionals.size > 1) "\\( $names \\)" else names
        }
        val names = parsed.positionals.map {
            PosixQuoting.render(resolveVars(it.pieces), VarStyle.QUOTED, false)
        }
        val quiet = if (parsed.has("q")) " >/dev/null 2>&1" else ""
        return (listOf("which") + names).joinToString(" ") + quiet
    }

    private fun ipconfig(args: List<CmdWord>): String {
        val parsed = parseArgs("ipconfig", args, IPCONFIG_FLAGS)
        parsed.flags.firstOrNull { it.name != "all" }?.let {
            unsupported(
                it.token,
                "'ipconfig ${it.token}' changes Windows network state; Android apps cannot " +
                    "release, renew or flush network configuration.",
                "ip addr show"
            )
        }
        if (parsed.positionals.isNotEmpty()) {
            unsupported(parsed.positionals[0].displayText, "'ipconfig' does not take arguments.", "ip addr show")
        }
        return if (parsed.has("all")) "ifconfig -a" else "ip addr show"
    }

    private fun tasklist(args: List<CmdWord>): String {
        val parsed = parseArgs("tasklist", args, TASKLIST_FLAGS, valued = setOf("fi", "fo", "s", "u", "p", "m"))
        parsed.flags.firstOrNull { it.name == "fi" }?.let {
            unsupported(it.token, "'tasklist /fi' filters are not supported; filter ps output with grep.", "ps -A | grep <name>")
        }
        parsed.flags.firstOrNull { it.name == "s" || it.name == "u" || it.name == "p" }?.let {
            unsupported(it.token, "Remote process listing is not available on Android.", "ps -A")
        }
        return "ps -A"
    }

    private fun taskkill(args: List<CmdWord>): String {
        val parsed = parseArgs("taskkill", args, setOf("pid", "im", "f", "t"), valued = setOf("pid", "im"))
        val pids = parsed.values("pid")
        val images = parsed.values("im")
        if (pids.isEmpty() && images.isEmpty()) {
            unsupported("taskkill", "'taskkill' needs /pid <id> or /im <name>.", "kill <pid>")
        }
        val force = parsed.has("f")
        val commands = mutableListOf<String>()
        if (pids.isNotEmpty()) {
            val ids = pids.map { word ->
                word.literalText?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: unsupported(word.displayText, "'taskkill /pid' needs a numeric process id.", "ps -A")
            }
            commands += (listOf("kill") + listOfNotNull("-9".takeIf { force }) + ids).joinToString(" ")
        }
        images.forEach { word ->
            val image = word.pieces.map {
                if (it is Piece.Lit) Piece.Lit(EXE_SUFFIX.replace(it.text, ""), it.quoted) else it
            }
            commands += (listOf("pkill") + listOfNotNull("-9".takeIf { force }) +
                listOf("-f", PosixQuoting.render(resolveVars(image), VarStyle.QUOTED, false))).joinToString(" ")
        }
        return if (commands.size == 1) commands[0] else "{ " + commands.joinToString("; ") + "; }"
    }

    private fun echo(args: List<CmdWord>): String =
        (listOf("echo") + args.map { PosixQuoting.render(resolveVars(it.pieces), VarStyle.BARE, false) })
            .joinToString(" ")

    private fun set(args: List<CmdWord>): String {
        val parsed = parseArgs("set", args, setOf("a", "p"))
        if (parsed.has("a")) unsupported("/a", "'set /a' arithmetic is not supported.", "export NAME=\$(( <expr> ))")
        if (parsed.has("p")) unsupported("/p", "'set /p' prompting is not supported.", "read -r NAME")
        val words = parsed.positionals
        if (words.isEmpty()) return "env"
        val first = words[0].pieces.first() as? Piece.Lit
        val equals = first?.text?.indexOf('=') ?: -1
        if (first == null || equals <= 0) {
            unsupported(words[0].displayText, "'set' expects NAME=value.", "export NAME=value")
        }
        val name = first.text.substring(0, equals)
        if (!POSIX_NAME.matches(name)) {
            unsupported(name, "'$name' is not a valid POSIX environment variable name.", null)
        }
        val valuePieces = mutableListOf<Piece>()
        first.text.substring(equals + 1).takeIf { it.isNotEmpty() }?.let { valuePieces += Piece.Lit(it, first.quoted) }
        valuePieces += words[0].pieces.drop(1)
        words.drop(1).forEach { valuePieces += Piece.Lit(" ", true); valuePieces += it.pieces }
        if (valuePieces.isEmpty()) return "unset $name"
        return "export $name=" + PosixQuoting.render(resolveVars(valuePieces), VarStyle.QUOTED, false)
    }

    companion object {
        private val CD_GLUED = Regex("^(cd|chdir)([.\\\\/].*)$", RegexOption.IGNORE_CASE)
        private val DRIVE_SWITCH = Regex("^[a-z]:$")
        private val DRIVE_PREFIX = Regex("^([A-Za-z]):(?=[\\\\/]|$)")
        private val MULTI_SLASH = Regex("/+")
        private val FLAG_NAME = Regex("[a-z0-9?-]+")
        private val WHITESPACE = Regex("\\s+")
        private val EXE_SUFFIX = Regex("\\.exe$", RegexOption.IGNORE_CASE)
        private val POSIX_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val SCRIPT_EXTENSIONS = listOf(".bat", ".cmd", ".ps1", ".vbs", ".msi")
        private val POWERSHELL_CMDLET = Regex(
            "^(get|set|new|remove|copy|move|rename|select|write|start|stop|invoke|test|clear|out|" +
                "import|export|add|format|measure|sort|where|foreach|restart|resolve|split|join)-[a-z]+$"
        )

        private val DIR_FLAGS = setOf("a", "s", "b", "w", "p", "q", "l", "x", "c", "n", "o", "t", "r", "4", "d")
        private val COPY_FLAGS = setOf("y", "-y", "v", "b", "a", "d", "z", "l", "n")
        private val XCOPY_FLAGS = setOf("e", "s", "y", "-y", "i", "q", "h", "c", "k", "f", "v", "w", "r", "d")
        private val DEL_FLAGS = setOf("q", "f", "s", "p", "a")
        private val FINDSTR_FLAGS = setOf("i", "n", "r", "v", "l", "x", "s", "m", "c")
        private val IPCONFIG_FLAGS =
            setOf("all", "release", "release6", "renew", "renew6", "flushdns", "displaydns", "registerdns")
        private val TASKLIST_FLAGS = setOf("v", "svc", "m", "nh", "fo", "fi", "s", "u", "p")

        val WINDOWS_COMMANDS = setOf(
            "cls", "exit", "help", "cd", "chdir", "dir", "type", "copy", "xcopy", "move", "ren", "rename",
            "del", "erase", "rd", "rmdir", "md", "mkdir", "findstr", "where", "ipconfig", "tasklist",
            "taskkill", "echo", "set", "ver"
        )

        private val PROTECTED_PATHS = setOf(
            "/", "/storage", "/storage/emulated", DefaultWindowsToLinuxTranslator.ANDROID_STORAGE_ROOT,
            "/storage/self", "/storage/self/primary", "/sdcard", "/mnt", "/mnt/sdcard", "/system",
            "/data", "/data/data", "/data/user", "/data/user/0", "/vendor", "/product", "/odm", "/oem",
            "/apex", "/proc", "/sys", "/dev", "/cache", "/etc", "/bin"
        )

        /** Windows-specific variables with Android equivalents (used when not set in the session). */
        private val VAR_ALIASES = mapOf(
            "USERPROFILE" to "HOME",
            "HOMEPATH" to "HOME",
            "CD" to "PWD",
            "TEMP" to "TMPDIR",
            "TMP" to "TMPDIR",
            "USERNAME" to "USER"
        )

        private val UNSUPPORTED_TOOLS: Map<String, Pair<String, String?>> = mapOf(
            "regedit" to ("The Windows Registry does not exist on Android; system configuration " +
                "lives in Android system properties and settings." to "getprop"),
            "reg" to ("The Windows Registry does not exist on Android." to "getprop"),
            "wmic" to ("WMI (wmic) is a Windows-only management interface. Query Android system " +
                "properties instead." to "getprop"),
            "sc" to ("The Windows Service Control Manager is not available; Android services are " +
                "managed by init and cannot be controlled from an app shell." to "ps -A"),
            "net" to ("'net' (shares, users, services) has no Android equivalent." to "ip addr show"),
            "netsh" to ("'netsh' network configuration is not available to Android apps." to "ip addr show"),
            "powershell" to ("PowerShell is not installed; commands run in /system/bin/sh." to "sh"),
            "pwsh" to ("PowerShell is not installed; commands run in /system/bin/sh." to "sh"),
            "systeminfo" to ("'systeminfo' is Windows-only." to "uname -a"),
            "chkdsk" to ("Disk checking requires root and is not available on Android." to "df -h"),
            "format" to ("Formatting volumes is not available to Android apps." to null),
            "diskpart" to ("Disk partitioning is not available to Android apps." to null),
            "sfc" to ("Windows system file checking has no Android equivalent." to null),
            "dism" to ("Windows image servicing has no Android equivalent." to null),
            "bcdedit" to ("Windows boot configuration has no Android equivalent." to null),
            "gpupdate" to ("Windows Group Policy has no Android equivalent." to null),
            "gpresult" to ("Windows Group Policy has no Android equivalent." to null),
            "schtasks" to ("The Windows Task Scheduler has no Android shell equivalent." to null),
            "runas" to ("Running as another user is not available to Android apps." to null),
            "msiexec" to ("Windows installer packages cannot be installed on Android." to null),
            "mstsc" to ("Remote Desktop is not available from the Android shell." to null),
            "rundll32" to ("Windows DLLs cannot be loaded on Android." to null),
            "mshta" to ("HTML applications are Windows-only." to null),
            "cscript" to ("Windows Script Host is not available on Android." to "sh <script>.sh"),
            "wscript" to ("Windows Script Host is not available on Android." to "sh <script>.sh"),
            "certutil" to ("'certutil' is Windows-only." to null),
            "bitsadmin" to ("BITS transfers are Windows-only." to null),
            "explorer" to ("Windows Explorer is not available on Android." to "ls -la"),
            "control" to ("The Windows Control Panel is not available on Android." to null),
            "taskmgr" to ("Task Manager is Windows-only." to "top"),
            "notepad" to ("Notepad is Windows-only." to "cat <file>"),
            "attrib" to ("Windows file attributes do not exist on Android; use POSIX permissions." to "ls -l"),
            "icacls" to ("Windows ACLs do not exist on Android; use POSIX permissions." to "chmod <mode> <file>"),
            "cacls" to ("Windows ACLs do not exist on Android; use POSIX permissions." to "chmod <mode> <file>"),
            "robocopy" to ("'robocopy' is Windows-only." to "cp -r <src> <dst>"),
            "tracert" to ("'tracert' is Windows-only." to "ping -c 4 <host>"),
            "fc" to ("'fc' is Windows-only." to "cmp <file1> <file2>"),
            "comp" to ("'comp' is Windows-only." to "cmp <file1> <file2>"),
            "mklink" to ("'mklink' is Windows-only." to "ln -s <target> <link>"),
            "shutdown" to ("Apps cannot shut down or reboot Android." to null),
            "subst" to ("Drive substitution has no Android equivalent." to null),
            "vol" to ("Volume labels have no Android equivalent." to "df -h"),
            "wevtutil" to ("The Windows event log has no Android equivalent." to "logcat -d"),
            "eventvwr" to ("The Windows event log has no Android equivalent." to "logcat -d"),
            "start" to ("'start' launches Windows programs; use Android intents instead." to null),
            "winget" to ("Windows package managers are not available on Android." to null),
            "choco" to ("Windows package managers are not available on Android." to null)
        )

        private val CMDLET_EQUIVALENTS = mapOf(
            "get-childitem" to "ls -la",
            "get-content" to "cat <file>",
            "set-location" to "cd <dir>",
            "get-location" to "pwd",
            "remove-item" to "rm <path>",
            "copy-item" to "cp <src> <dst>",
            "move-item" to "mv <src> <dst>",
            "rename-item" to "mv <old> <new>",
            "new-item" to "touch <file>",
            "get-process" to "ps -A",
            "stop-process" to "kill <pid>",
            "write-host" to "echo <text>",
            "write-output" to "echo <text>",
            "select-string" to "grep <pattern> <file>",
            "clear-host" to "clear",
            "test-path" to "test -e <path>",
            "get-command" to "which <name>"
        )
    }
}

/** Collapses `.`, `..` and duplicate slashes in an absolute POSIX path without touching the filesystem. */
internal fun normalizeAbsolute(path: String): String {
    val parts = ArrayDeque<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(part)
        }
    }
    return "/" + parts.joinToString("/")
}
