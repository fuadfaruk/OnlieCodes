package com.cusapps.onliecodes.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Dark theme colors
val BackgroundDark = Color(0xFF1E1E24)
val SurfaceDark = Color(0xFF282A36)
val PrimaryPurple = Color(0xFFBD93F9)
val SecondaryPink = Color(0xFFFF79C6)
val TextWhite = Color(0xFFF8F8F2)
val TextMuted = Color(0xFF6272A4)
val SelectionColor = Color(0xFF44475A)
val EditorLineNumbers = Color(0xFF6272A4)

// Light theme colors
val BackgroundLight = Color(0xFFFFFFFF)
val SurfaceLight = Color(0xFFF3F3F3)
val PrimaryDarkPurple = Color(0xFF7C3AED)
val SecondaryDarkPink = Color(0xFFDB2777)
val TextBlack = Color(0xFF1E1E1E)
val TextDarkMuted = Color(0xFF6A737D)
val SelectionLight = Color(0xFFD4D4D4)
val EditorLineNumbersLight = Color(0xFF6A737D)

// Syntax highlighting colors (dark)
val CodeKeyword = Color(0xFFFF79C6)
val CodeString = Color(0xFFF1FA8C)
val CodeComment = Color(0xFF6272A4)
val CodeNumber = Color(0xFFBD93F9)
val CodeFunction = Color(0xFF50FA7B)

// Syntax highlighting colors (light)
val CodeKeywordLight = Color(0xFF0000FF)
val CodeStringLight = Color(0xFFA31515)
val CodeCommentLight = Color(0xFF008000)
val CodeNumberLight = Color(0xFF098658)
val CodeFunctionLight = Color(0xFF795E26)

data class EditorPalette(
    val background: Color,
    val surface: Color,
    val selection: Color,
    val text: Color,
    val muted: Color,
    val gutterNumbers: Color,
    val cursor: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color
)

val DarkEditorPalette = EditorPalette(
    background = BackgroundDark,
    surface = SurfaceDark,
    selection = SelectionColor,
    text = TextWhite,
    muted = TextMuted,
    gutterNumbers = EditorLineNumbers,
    cursor = PrimaryPurple,
    keyword = CodeKeyword,
    string = CodeString,
    comment = CodeComment,
    number = CodeNumber,
    function = CodeFunction
)

val LightEditorPalette = EditorPalette(
    background = BackgroundLight,
    surface = SurfaceLight,
    selection = SelectionLight,
    text = TextBlack,
    muted = TextDarkMuted,
    gutterNumbers = EditorLineNumbersLight,
    cursor = PrimaryDarkPurple,
    keyword = CodeKeywordLight,
    string = CodeStringLight,
    comment = CodeCommentLight,
    number = CodeNumberLight,
    function = CodeFunctionLight
)

val LocalEditorPalette = staticCompositionLocalOf { DarkEditorPalette }
