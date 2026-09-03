/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.recmf.R
import dev.recmf.data.CoachMessageEntity
import java.util.Locale

/**
 * A conversation with the assistant, rather than a paragraph it volunteers.
 *
 * The switch for this existed for a while before the screen did, and all it did was add a
 * line about the wearer to the answers on the metric cards. That is a switch that does not
 * quite do what its name says. This is what it was for.
 *
 * What is sent is the same context the cards send — the standing instructions, the table of
 * days, the profile — plus everything already said here. The provider keeps nothing between
 * requests, so the conversation is not something held on their side that this screen
 * displays; it is held here, and resent whole every time. Which is also why clearing it
 * actually clears it.
 *
 * The openers above the box are filtered by what the app can actually answer: there is no
 * point offering "how did I sleep" to somebody the watch has never recorded a night for,
 * and an answer that has to begin "there is no data for that" is worse than no suggestion.
 *
 * Its own tab, and only when the coach is switched on. A tab for a feature somebody has not
 * turned on is a tab that opens onto an explanation of why it is empty, and there are five
 * other tabs that would rather have the room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachScreen(
    messages: List<CoachMessageEntity>,
    thinking: Boolean,
    problem: String?,
    /** False when there is no key, or no model, or the switch is off. */
    ready: Boolean,
    /** Openers to tap when nothing comes to mind, already filtered to what can be answered. */
    suggestions: List<String>,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()

    val context = LocalContext.current

    // The system's recogniser, as an activity. reCMF asks for no microphone permission and
    // never opens one: the dialog belongs to whichever app handles this, the audio is that
    // app's business, and what comes back here is text. A speech feature that needed
    // RECORD_AUDIO would be the app's most invasive permission by a distance, for a
    // convenience.
    val speech = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    }

    // Hidden rather than shown and broken where nothing handles it. Android Go phones and
    // de-Googled ones have no recogniser, and a button that can only apologise is worse
    // than no button.
    val canSpeak = remember(context) {
        // resolveActivity on the intent rather than queryIntentActivities on the package
        // manager: the flags overload of the latter is deprecated on this target, and a
        // deprecation is a warning, and warnings fail this build.
        speech.resolveActivity(context.packageManager) != null
    }

    // Read in the composition. A string resource cannot be looked up from a click handler,
    // which is not a composition and has caught this app out before.
    val spoken = stringResource(R.string.coach_speak)

    val listen = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()

        // Into the box, not straight out to the model. Recognition mishears names and
        // numbers, and a question about a resting pulse of sixty-nine that went as
        // sixty-nine hundred should be fixable before it is asked.
        if (heard.isNotBlank()) draft = if (draft.isBlank()) heard else "$draft $heard"
    }

    // Follows the conversation down as it grows, including down to the thinking row, which
    // is the one thing on the screen somebody is actually waiting for.
    LaunchedEffect(messages.size, thinking) {
        val last = scroll.layoutInfo.totalItemsCount - 1
        if (last >= 0) scroll.animateScrollToItem(last)
    }

    // No padding for the keyboard here, on purpose, after four attempts that added some.
    //
    // The window moves itself. The tell was a screenshot with the watch bar missing from
    // the top of the screen: that bar is not this screen's to move, so the whole window
    // had been shifted up to keep the focused box in view. Adding a keyboard's height on
    // top of a window that has already moved by a keyboard's height is what put the box
    // a keyboard above the keyboard, and it did that whether the height came from
    // imePadding or from anywhere else.
    //
    // What is left is the floating dock, which the window's own movement knows nothing
    // about — and which is behind the keyboard while there is one, so the room reserved
    // for it collapses then. Measured from the inset rather than asked of a state flag:
    // greater than nothing means there is a keyboard.
    val density = LocalDensity.current
    val imeBelow = WindowInsets.ime.getBottom(density)
    val barsBelow = WindowInsets.navigationBars.getBottom(density)
    val clearance = if (imeBelow > 0) 0.dp else DOCK_CLEARANCE

    val isImeVisible = WindowInsets.isImeVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tab_coach),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (messages.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.coach_clear)) }
            }
        }


        LazyColumn(
            modifier = Modifier.weight(1f),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // With no box to type in, nothing else is holding the last message clear of
            // the floating dock.
            contentPadding = PaddingValues(bottom = if (ready) 8.dp else clearance),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            if (ready) R.string.coach_empty else R.string.coach_off,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(messages, key = { it.id }) { Said(it) }

            if (thinking) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sparkles(color = MaterialTheme.colorScheme.primary, size = 18.dp)
                        Text(
                            stringResource(R.string.coach_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Kept until something else is sent, rather than shown and dismissed: the
            // message it failed on is still on the screen above, and an unexplained
            // unanswered message is worse than a line saying what went wrong.
            problem?.let {
                item {
                    Text(
                        stringResource(R.string.coach_failed, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (ready) {
            // TEMPORARY. It was at the top of the screen and the top of the screen is
            // exactly what leaves when the keyboard opens — the whole window slides up,
            // watch bar and all, and takes the numbers with it. Directly above the chips
            // is the lowest thing that stayed visible in that state, so that is where the
            // reading has to be taken.
            Text(
                "ime=$imeBelow bars=$barsBelow clear=$clearance vis=$isImeVisible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            // Only while the box is empty. They are for not knowing what to ask, and
            // somebody halfway through typing has stopped not knowing.
            if (draft.isBlank() && suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { opener ->
                        AssistChip(
                            onClick = { onSend(opener) },
                            enabled = !thinking,
                            label = { Text(opener) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = clearance),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.coach_hint)) },
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = if (!canSpeak) {
                        null
                    } else {
                        {
                            IconButton(
                                onClick = {
                                    listen.launch(
                                        Intent(speech)
                                            .putExtra(RecognizerIntent.EXTRA_PROMPT, spoken),
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_ui_mic),
                                    contentDescription = spoken,
                                )
                            }
                        }
                    },
                    // Four lines before it scrolls: enough for a real question, and short
                    // enough that a long one does not eat the conversation above it.
                    maxLines = 4,
                )

                FilledIconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !thinking,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_ui_send),
                        contentDescription = stringResource(R.string.coach_send),
                    )
                }
            }
        }
    }
}

/** One thing said, on its own side of the screen. */
@Composable
private fun Said(message: CoachMessageEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            // Square on the corner it comes from, round everywhere else: the shape says
            // which side said it before the colour does, and colour alone is not something
            // to lean on.
            shape = if (message.fromUser) {
                RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
            } else {
                RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
            },
            color = if (message.fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            modifier = Modifier.widthIn(max = BUBBLE_WIDEST),
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.fromUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * How wide a bubble may get.
 *
 * Short of the full width on purpose: a bubble that reaches both edges stops reading as a
 * thing somebody said and starts reading as the page.
 */
private val BUBBLE_WIDEST = 300.dp

/** What the floating dock covers, matching the padding every other tab leaves for it. */
private val DOCK_CLEARANCE = 96.dp
