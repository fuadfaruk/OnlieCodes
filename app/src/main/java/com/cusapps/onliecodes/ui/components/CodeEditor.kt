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
import androidx.compose.ui.text.style.TextAlign
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
    val scrollState = rememberScrollState()
    val lines = content.split("\n")
    val lineCount = lines.size.coerceAtLeast(1)

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Line Numbers Column
        Column(
            modifier = Modifier
                .width(48.dp)
                .verticalScroll(scrollState)
                .background(SurfaceDark)
                .padding(vertical = 12.dp)
        ) {
            for (i in 1..lineCount) {
                Text(
                    text = i.toString(),
                    color = EditorLineNumbers,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    textAlign = TextAlign.End
                )
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(SelectionColor)
        )

        // Actual Text Editor Field
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
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

    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text.text)

            // 1. Highlight numbers
            val numMatcher = NUMBERS.matcher(text.text)
            while (numMatcher.find()) {
                addStyle(SpanStyle(color = CodeNumber), numMatcher.start(), numMatcher.end())
            }

            // 2. Highlight keywords
            val keywordMatcher = KEYWORDS.matcher(text.text)
            while (keywordMatcher.find()) {
                addStyle(SpanStyle(color = CodeKeyword), keywordMatcher.start(), keywordMatcher.end())
            }

            // 3. Highlight strings
            val stringMatcher = STRINGS.matcher(text.text)
            while (stringMatcher.find()) {
                addStyle(SpanStyle(color = CodeString), stringMatcher.start(), stringMatcher.end())
            }

            // 4. Highlight comments
            val commentMatcher = COMMENTS.matcher(text.text)
            while (commentMatcher.find()) {
                addStyle(SpanStyle(color = CodeComment), commentMatcher.start(), commentMatcher.end())
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
