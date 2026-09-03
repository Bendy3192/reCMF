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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
 * itself or to say something somebody would otherwise discover by being surprised: that
 * this is not CMF's app, that nothing leaves the phone unless the assistant is switched on,
 * and that one setting needs root and will not work without it.
 *
 * Every step can be skipped and every answer can be changed afterwards in the settings.
 * A wizard that has to be completed before an app will work is a toll gate; this one is an
 * offer, and the app is fully usable by somebody who taps past all of it.
 */
@Composable
fun OnboardingScreen(
    ai: AiSettings,
    activeKcalToday: Int,
    onProfile: (AiContext.Profile) -> Unit,
    onAiInsights: (Boolean) -> Unit,
    onAiEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onAiKey: (String?) -> Unit,
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
                PROFILE -> item { ProfileStep(ai.profile, activeKcalToday, onProfile) }
                ASSISTANT -> item {
                    AssistantStep(ai, onAiInsights, onAiEndpoint, onAiKey)
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
                            activeKcalToday,
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

/** The one thing here that sends anything anywhere, off until somebody turns it on. */
@Composable
private fun AssistantStep(
    ai: AiSettings,
    onAiInsights: (Boolean) -> Unit,
    onAiEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onAiKey: (String?) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf(ai.key.orEmpty()) }
    var model by rememberSaveable(ai.model) { mutableStateOf(ai.model) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paragraph(R.string.onboarding_ai_what)
        Paragraph(R.string.onboarding_ai_sends)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ai_insights),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = ai.insightsEnabled, onCheckedChange = onAiInsights)
        }

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
                    onAiEndpoint(
                        SUGGESTED_BASE_URL,
                        model.ifBlank { SUGGESTED_MODEL },
                        AiEndpoint.Wire.RESPONSES,
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
private const val PROFILE = 1
private const val ASSISTANT = 2
private const val STEPS = 3

private val TITLES = listOf(
    R.string.onboarding_welcome,
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
