package com.cusapps.onliecodes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cusapps.onliecodes.ui.theme.*
import java.util.regex.Pattern

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Divider
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(SelectionColor)
        )

        // The gutter line numbers are rendered inside the same text layout as
        // the code (see CodeSyntaxTransformation below), so every number shares
        // the exact line box of its text line. Each number is therefore always
        // anchored to the head of its line regardless of font type, font size,
        // text wrapping, or line spacing.
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            textStyle = TextStyle(
                color = TextWhite,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            ),
            cursorBrush = SolidColor(PrimaryPurple),
            visualTransformation = CodeSyntaxTransformation
        )
    }
}

object CodeSyntaxTransformation : VisualTransformation {
    private val KEYWORDS = Pattern.compile(
        "\\b(package|import|class|interface|object|fun|val|var|return|if|else|for|while|when|is|as|in|try|catch|throw|this|super|null|true|false|void|public|private|protected|static|final|new|const|default|switch|case|break|continue)\\b"
    )
    private val NUMBERS = Pattern.compile("\\b(\\d+)\\b")
    private val STRINGS = Pattern.compile("\"[^\"]*\"|'[^']*'")
    private val COMMENTS = Pattern.compile("//.*|/\\*(?s:.*?)\\*/")

    private const val GUTTER_SEPARATOR = "    "

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val lines = raw.split("\n")
        val lineCount = lines.size.coerceAtLeast(1)
        val maxDigits = lineCount.toString().length

        // Original start offset of each line in the raw text.
        val lineStarts = IntArray(lineCount)
        var position = 0
        for (i in lines.indices) {
            lineStarts[i] = position
            position += lines[i].length + 1
        }

        // Width of the gutter text (padding + number + separator) per line.
        val prefixLen = IntArray(lineCount) { i ->
            val digits = (i + 1).toString().length
            maxDigits - digits + digits + GUTTER_SEPARATOR.length
        }

        // Cumulative gutter width before each line, and the transformed offset
        // at which that line's code content begins.
        val cumPrefix = IntArray(lineCount)
        val contentStart = IntArray(lineCount)
        var running = 0
        for (i in prefixLen.indices) {
            cumPrefix[i] = running
            contentStart[i] = lineStarts[i] + running + prefixLen[i]
            running += prefixLen[i]
        }

        val annotated = buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("\n")

                val gutterStart = length
                val number = (index + 1).toString()
                append(" ".repeat(maxDigits - number.length))
                append(number)
                append(GUTTER_SEPARATOR)
                addStyle(
                    SpanStyle(color = EditorLineNumbers, background = SurfaceDark),
                    gutterStart, length
                )
                append(line)
            }

            // Syntax highlighting, translated from raw coordinates into the
            // gutter-included transformed coordinates.
            highlight(raw, cumPrefix, lineStarts)
        }

        return TransformedText(
            annotated,
            LineNumberOffsetMapping(cumPrefix, contentStart, lineStarts, raw.length)
        )
    }

    private fun AnnotatedString.Builder.highlight(
        raw: String,
        cumPrefix: IntArray,
        lineStarts: IntArray
    ) {
        fun lineIndex(offset: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            var result = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lineStarts[mid] <= offset) {
                    result = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return result
        }

        fun startOffset(s: Int): Int = s + cumPrefix[lineIndex(s)]

        fun endOffset(e: Int): Int =
            if (e <= 0) 0 else e + cumPrefix[lineIndex(e - 1)]

        val numMatcher = NUMBERS.matcher(raw)
        while (numMatcher.find()) {
            addStyle(SpanStyle(color = CodeNumber), startOffset(numMatcher.start()), endOffset(numMatcher.end()))
        }
        val keywordMatcher = KEYWORDS.matcher(raw)
        while (keywordMatcher.find()) {
            addStyle(SpanStyle(color = CodeKeyword), startOffset(keywordMatcher.start()), endOffset(keywordMatcher.end()))
        }
        val stringMatcher = STRINGS.matcher(raw)
        while (stringMatcher.find()) {
            addStyle(SpanStyle(color = CodeString), startOffset(stringMatcher.start()), endOffset(stringMatcher.end()))
        }
        val commentMatcher = COMMENTS.matcher(raw)
        while (commentMatcher.find()) {
            addStyle(SpanStyle(color = CodeComment), startOffset(commentMatcher.start()), endOffset(commentMatcher.end()))
        }
    }
}

private class LineNumberOffsetMapping(
    private val cumPrefix: IntArray,
    private val contentStart: IntArray,
    private val lineStarts: IntArray,
    private val rawLength: Int
) : OffsetMapping {

    private fun lineIndex(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineStarts[mid] <= offset) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    override fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, rawLength)
        val index = lineIndex(o)
        return if (o == lineStarts[index]) {
            contentStart[index]
        } else {
            o + cumPrefix[index]
        }
    }

    override fun transformedToOriginal(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineStarts[mid] + cumPrefix[mid] <= offset) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val original = if (offset < contentStart[result]) {
            lineStarts[result]
        } else {
            offset - cumPrefix[result]
        }
        return original.coerceIn(0, rawLength)
    }
}
