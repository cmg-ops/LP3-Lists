package com.dailyhobbyist.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

/** What the long-press picker is showing. */
private data class EditorPicker(
    val span: SpellCheck.WordSpan?,
    val suggestions: List<String>,
    val canCopy: Boolean,
    val pasteText: String?,
)

/**
 * Note-style text entry, modeled on the LightOS Notes app:
 *
 * - Text renders at exactly the same scaled size as finished list items
 * - Only misspelled words are underlined
 * - Long-press opens a picker: spelling suggestions (when pressed on a
 *   misspelled word) plus COPY ALL and PASTE
 * - Haptic tick on every key press and button
 * - Text area scrolls and follows the cursor
 */
@Composable
fun NoteInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    editorKey: Any,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    val keyboardCallback = remember(state, haptic) {
        NoteKeyboardCallback(state) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val keyboardViewModel: Lp3KeyboardViewModel<*> = viewModel<EnQwertyLp3KeyboardViewModel<*>>(
        key = "NoteInputEditor-$editorKey",
        factory = keyboardFactory(keyboardCallback, keyboardOptionsFlow),
    )

    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography

    // Use the SDK's own screen-height scaling so typed text is exactly
    // the same size as finished list items (which use the Copy variant).
    val base = typography.copy
    val inputStyle = base.copy(
        color = colors.content,
        fontSize = base.fontSize.scaledOrKeep(),
        lineHeight = base.lineHeight.scaledOrKeep(),
        letterSpacing = base.letterSpacing.scaledOrKeep(),
    )

    LaunchedEffect(Unit) { SpellCheck.ensureLoaded() }
    val spellReady by SpellCheck.ready.collectAsState()

    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    var viewportHeight by remember { mutableStateOf(0) }

    var picker by remember { mutableStateOf<EditorPicker?>(null) }

    val textString = state.text.toString()
    val annotated: AnnotatedString = remember(textString, spellReady) {
        buildAnnotatedString {
            append(textString)
            if (spellReady) {
                for (span in SpellCheck.words(textString)) {
                    if (SpellCheck.isMisspelled(span.word)) {
                        addStyle(
                            SpanStyle(textDecoration = TextDecoration.Underline),
                            span.start,
                            span.end,
                        )
                    }
                }
            }
        }
    }

    // Keep the cursor visible: when it moves below or above the visible
    // window, scroll to follow it.
    LaunchedEffect(state.selection, textLayout, viewportHeight) {
        val layout = textLayout ?: return@LaunchedEffect
        if (viewportHeight <= 0) return@LaunchedEffect
        val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
        val rect = layout.getCursorRect(cursorPos)
        val top = rect.top.toInt()
        val bottom = rect.bottom.toInt()
        if (bottom > scrollState.value + viewportHeight) {
            scrollState.animateScrollTo(bottom - viewportHeight)
        } else if (top < scrollState.value) {
            scrollState.animateScrollTo(top)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(scrollState)
                .padding(horizontal = 2f.gridUnitsAsDp()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            textLayout?.let { layout ->
                                state.edit {
                                    selection =
                                        TextRange(layout.getOffsetForPosition(down.position))
                                }
                            }
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Read the text as it is RIGHT NOW, not as it
                                // was when this gesture handler was created.
                                val current = state.text.toString()
                                val layout = textLayout
                                val span = layout?.let {
                                    val offset =
                                        it.getOffsetForPosition(longPress.position)
                                    SpellCheck.wordAt(current, offset)
                                }
                                val misspelled =
                                    span != null && SpellCheck.isMisspelled(span.word)
                                picker = EditorPicker(
                                    span = if (misspelled) span else null,
                                    suggestions = if (misspelled) {
                                        SpellCheck.suggestions(span!!.word)
                                    } else {
                                        emptyList()
                                    },
                                    canCopy = current.isNotEmpty(),
                                    pasteText = clipboard.getText()?.text
                                        ?.takeIf { it.isNotEmpty() },
                                )
                                // Swallow the rest of this gesture.
                                drag(down.id) { it.consume() }
                            } else {
                                // Normal tap/drag: move the cursor.
                                drag(down.id) { change ->
                                    textLayout?.let { layout ->
                                        state.edit {
                                            selection = TextRange(
                                                layout.getOffsetForPosition(change.position)
                                            )
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                BasicText(
                    text = annotated,
                    style = inputStyle,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                textLayout?.let { layout ->
                    val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                    val rect = layout.getCursorRect(cursorPos)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                            .width(2.dp)
                            .height(with(LocalDensity.current) { rect.height.toDp() })
                            .background(colors.content),
                    )
                }
            }
        }

        val activePicker = picker
        if (activePicker == null) {
            LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)

            // SAVE centered, X on the right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3f.gridUnitsAsDp())
                    .padding(horizontal = 2f.gridUnitsAsDp()),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .lightClickable(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSubmit(state.text)
                        })
                        .height(3f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "SAVE",
                        variant = LightTextVariant.Fine,
                        maxLines = 1,
                    )
                }
                LightIcon(
                    icon = LightIcons.CLOSE,
                    size = 2f,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .lightClickable(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        }),
                    contentDescription = "cancel",
                )
            }
        } else {
            // Long-press picker: spelling suggestions (when applicable),
            // then COPY ALL / PASTE, chevron at the bottom to dismiss.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.weight(1f))

                activePicker.suggestions.forEach { suggestion ->
                    LightText(
                        text = suggestion,
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier
                            .lightClickable(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val span = activePicker.span
                                if (span != null) {
                                    state.edit {
                                        replace(
                                            span.start,
                                            span.end.coerceAtMost(state.text.length),
                                            suggestion,
                                        )
                                        selection =
                                            TextRange(span.start + suggestion.length)
                                    }
                                }
                                picker = null
                            })
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
                if (activePicker.span != null && activePicker.suggestions.isEmpty()) {
                    LightText(
                        text = "NO SUGGESTIONS",
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }

                if (activePicker.canCopy) {
                    LightText(
                        text = "COPY ALL",
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier
                            .lightClickable(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                clipboard.setText(AnnotatedString(state.text.toString()))
                                picker = null
                            })
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
                if (activePicker.pasteText != null) {
                    LightText(
                        text = "PASTE",
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier
                            .lightClickable(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.edit {
                                    val start = selection.min
                                    val end = selection.max
                                    replace(start, end, activePicker.pasteText)
                                    selection =
                                        TextRange(start + activePicker.pasteText.length)
                                }
                                picker = null
                            })
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                LightIcon(
                    icon = LightIcons.ARROW_DOWN,
                    modifier = Modifier
                        .lightClickable(onClick = { picker = null })
                        .padding(1f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun TextUnit.scaledOrKeep(): TextUnit =
    if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()

private fun keyboardFactory(
    callback: NoteKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = {
                    val showCloseButton = !it.isRootLayout
                    LayoutOptions(showCloseButton)
                },
            ) as T
        }
    }
