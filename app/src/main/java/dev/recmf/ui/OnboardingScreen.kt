/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.recmf.R
import dev.recmf.ai.AiContext
import dev.recmf.ai.AiEndpoint
import dev.recmf.data.AiSettings
import dev.recmf.health.restingEnergy
import dev.recmf.health.spentToday
import java.time.Year

/**
 * The first run, once.
 *
 * Not a tour. Every screen it shows exists to collect something the app cannot work out for
 * itself, to offer back what a previous phone already knew, or to say something somebody
 * would otherwise discover by being surprised: that this is not CMF's app, that nothing
 * leaves the phone unless the assistant is switched on, and that one setting needs root and
 * will not work without it.
 *
 * Every step can be skipped and every answer can be changed afterwards in the settings.
 * A wizard that has to be completed before an app will work is a toll gate; this one is an
 * offer, and the app is fully usable by somebody who taps past all of it.
 */
@Composable
fun OnboardingScreen(
    ai: AiSettings,
    activeKcalToday: Int,
    backupState: BackupState?,
    onImportBackup: () -> Unit,
    onProfile: (AiContext.Profile) -> Unit,
    onAiInsights: (Boolean) -> Unit,
    onAiEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onAiKey: (String?) -> Unit,
    onAiDeclined: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        LinearProgressIndicator(
            progress = { (step + 1) / STEPS.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
        ) {
            item {
                Text(
                    stringResource(TITLES[step]),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            when (step) {
                WELCOME -> item { Welcome() }
                RESTORE -> item { RestoreStep(backupState, onImportBackup) }
                PROFILE -> item { ProfileStep(ai.profile, activeKcalToday, onProfile) }
                ASSISTANT -> item {
                    AssistantStep(ai, onAiInsights, onAiEndpoint, onAiKey, onAiDeclined)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (step > 0) {
                TextButton(onClick = { step-- }) {
                    Text(stringResource(R.string.onboarding_back))
                }
            }

            // Always there, on every step. The way out has to be as visible as the way on,
            // or the offer stops being one.
            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }

            FilledTonalButton(
                onClick = { if (step == STEPS - 1) onDone() else step++ },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (step == STEPS - 1) R.string.onboarding_finish else R.string.onboarding_next,
                    ),
                )
            }
        }
    }
}

/** What this app is, and the two things somebody would otherwise find out by surprise. */
@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paragraph(R.string.onboarding_welcome_what)
        Paragraph(R.string.onboarding_welcome_offline)
        Paragraph(R.string.onboarding_welcome_root)
    }
}

/**
 * The step for somebody who has been here before.
 *
 * It comes second, ahead of the profile, because everything the profile asks for is in
 * the file: a wizard that asks somebody to retype their height and then offers to restore
 * it afterwards has wasted their time and will overwrite what they typed.
 *
 * What a restore cannot bring back is said here rather than discovered later. The watch
 * pairing key and the assistant's key are sealed in the Android keystore, which is not
 * backed up by design, so those two are the only things to redo — and somebody who is not
 * told that reads the empty pairing screen as the restore having failed.
 */
@Composable
private fun RestoreStep(state: BackupState?, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paragraph(R.string.onboarding_restore_what)
        Paragraph(R.string.onboarding_restore_keys)

        OutlinedButton(
            onClick = onImport,
            enabled = state != BackupState.Working,
        ) { Text(stringResource(R.string.action_backup_import)) }

        BackupOutcome(state)

        if (state is BackupState.Imported) Paragraph(R.string.onboarding_restore_after)
    }
}

/**
 * The four figures an equation needs, and the figure they produce.
 *
 * Shown while it is being typed rather than behind a later screen: the reason to give a
 * height is the number that comes out of it, and an app that asks first and explains after
 * is asking somebody to take it on trust.
 */
@Composable
private fun ProfileStep(
    saved: AiContext.Profile,
    activeKcalToday: Int,
    onProfile: (AiContext.Profile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paragraph(R.string.onboarding_profile_why)

        AiProfileFields(saved = saved, onProfile = onProfile)

        val resting = restingEnergy(
            sex = saved.sex,
            age = saved.age(),
            heightCm = saved.heightCm,
            weightKg = saved.weightKg,
        )

        if (resting == null) {
            Paragraph(R.string.onboarding_energy_waiting)
        } else {
            val spent = spentToday(resting, activeKcalToday)

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.onboarding_energy_spent, spent.readable()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.onboarding_energy_parts,
                            resting.readable(),
                            // A string rather than a number, like the half beside it.
                            // Lint reads "%d" followed by a word as a count needing plural
                            // forms, and this one is kilocalories; the resting figure is
                            // already text because it can be a span, so both being text is
                            // the symmetric answer as well as the quiet one.
                            activeKcalToday.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.onboarding_energy_not_a_budget),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The one thing here that sends anything anywhere, and a plain way to refuse it.
 *
 * Two answers, not a switch. A switch already in the off position is technically a choice
 * and reads as a default waiting to be corrected: it leaves the model name, the key box
 * and somebody else's company name on the screen of a person who wants none of it, and
 * says nothing about what happens if they walk past. So the question is put once, both
 * answers are buttons, and "no" is a real one — it turns the switches off, drops any key,
 * and takes the assistant out of the settings screen afterwards.
 *
 * Nobody is trapped by either answer. "No" is reversible from one line in the settings,
 * and the wording says so, because a choice somebody believes is permanent is a choice
 * they make fearfully.
 */
@Composable
private fun AssistantStep(
    ai: AiSettings,
    onAiInsights: (Boolean) -> Unit,
    onAiEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onAiKey: (String?) -> Unit,
    onAiDeclined: (Boolean) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf(ai.key.orEmpty()) }
    var model by rememberSaveable(ai.model) { mutableStateOf(ai.model) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paragraph(R.string.onboarding_ai_what)
        Paragraph(R.string.onboarding_ai_sends)
        Paragraph(R.string.onboarding_ai_without)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    onAiDeclined(true)
                    key = ""
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.onboarding_ai_no)) }

            FilledTonalButton(
                onClick = { onAiInsights(true) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.onboarding_ai_yes)) }
        }

        if (ai.declined) Paragraph(R.string.onboarding_ai_declined)

        if (ai.insightsEnabled) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.ai_model)) },
                placeholder = { Text(SUGGESTED_MODEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.ai_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FilledTonalButton(
                onClick = {
                    // An endpoint somebody already chose is kept — including one that
                    // arrived in a restored backup a step ago. Only an install with
                    // nothing in it is pointed at the suggestion. The model name is the
                    // tell: it is the one of the three with no default, so a non-empty
                    // one means these were set by a person rather than by this file.
                    val chosen = ai.model.isNotBlank()

                    onAiEndpoint(
                        if (chosen) ai.baseUrl else SUGGESTED_BASE_URL,
                        model.ifBlank { SUGGESTED_MODEL },
                        if (chosen) ai.wire else AiEndpoint.Wire.RESPONSES,
                    )
                    onAiKey(key.takeIf { it.isNotBlank() })
                },
            ) { Text(stringResource(R.string.action_save)) }

            Paragraph(R.string.onboarding_ai_later)
        }
    }
}

@Composable
private fun Paragraph(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A range as one number when both ends agree, and as a span when they do not.
 *
 * The span is not a formatting accident to be tidied away — it is the answer when nobody
 * said which coefficient the equation takes.
 */
private fun IntRange.readable(): String =
    if (first == last) first.toString() else "$first–$last"

/** Years, or zero when the birth year is missing or cannot be one. */
private fun AiContext.Profile.age(): Int {
    val thisYear = Year.now().value
    return if (birthYear in 1900..thisYear) thisYear - birthYear else 0
}

private const val WELCOME = 0
private const val RESTORE = 1
private const val PROFILE = 2
private const val ASSISTANT = 3
private const val STEPS = 4

private val TITLES = listOf(
    R.string.onboarding_welcome,
    R.string.onboarding_restore,
    R.string.onboarding_profile,
    R.string.onboarding_ai,
)

/**
 * Where the assistant points before anybody changes it.
 *
 * Perplexity's Agent API in the Responses shape, because it is the one with a web search
 * behind it and because the shape is what everything else here speaks. Both are three text
 * fields away from being something else, on the settings screen, at any time.
 */
private const val SUGGESTED_BASE_URL = "https://api.perplexity.ai/v1"
private const val SUGGESTED_MODEL = "anthropic/claude-sonnet-5"
