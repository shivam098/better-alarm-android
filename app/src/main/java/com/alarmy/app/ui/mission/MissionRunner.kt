package com.alarmy.app.ui.mission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.alarmy.app.AppGraph
import com.alarmy.app.camera.BitmapHashing
import com.alarmy.app.sensor.MotionSensorSource
import com.alarmy.core.mission.CodeChallenge
import com.alarmy.core.mission.MathMissionGenerator
import com.alarmy.core.mission.MemorySequenceGenerator
import com.alarmy.core.mission.MissingSymbolGenerator
import com.alarmy.core.mission.MissionAttemptResult
import com.alarmy.core.mission.MissionFailureReason
import com.alarmy.core.mission.MotionOutcome
import com.alarmy.core.mission.PhotoChallenge
import com.alarmy.core.mission.TypingPhraseProvider
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionType
import com.alarmy.core.support.SeededRandom
import kotlinx.coroutines.delay

/**
 * Runs a mission to completion.
 *
 * Challenges are generated from a [SeededRandom] derived from the occurrence
 * key, so the same alarm always poses the same questions no matter how many
 * times the screen is destroyed and recreated. Without that, force-quitting the
 * activity would reroll into an easier problem, which is a trivially
 * discoverable cheat.
 */
@Composable
fun MissionRunner(
    config: MissionConfig,
    occurrenceKey: String,
    onFailure: (MissionFailureReason) -> Unit,
    onComplete: () -> Unit
) {
    if (config.type == MissionType.COMBINATION) {
        var step by remember(occurrenceKey) { mutableIntStateOf(0) }
        val children = config.children.ifEmpty { listOf(MissionConfig()) }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Mission ${step + 1} of ${children.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            SingleMission(
                config = children[step],
                // Salting by step keeps each link in the chain independent;
                // without it, two Math steps in one combination would pose
                // identical questions.
                seed = "$occurrenceKey#c$step",
                onFailure = onFailure,
                onComplete = {
                    if (step + 1 >= children.size) onComplete() else step++
                }
            )
        }
    } else {
        SingleMission(config, occurrenceKey, onFailure, onComplete)
    }
}

@Composable
private fun SingleMission(
    config: MissionConfig,
    seed: String,
    onFailure: (MissionFailureReason) -> Unit,
    onComplete: () -> Unit
) {
    // Motion missions track their own totals inside the validator, so they run
    // as a single round rather than N.
    val rounds = if (config.type.isPhysical || config.type == MissionType.NONE) 1 else config.targetCount
    var round by remember(seed) { mutableIntStateOf(0) }

    val advance: () -> Unit = {
        if (round + 1 >= rounds) onComplete() else round++
    }

    Column(Modifier.fillMaxWidth()) {
        if (rounds > 1) {
            LinearProgressIndicator(
                progress = { round.toFloat() / rounds },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${round + 1} / $rounds",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        val random = remember(seed, round) { SeededRandom.forOccurrence(seed, round.toLong()) }

        when (config.type) {
            MissionType.NONE -> SlideToStop(onComplete)
            MissionType.MATH -> MathMission(random, config, onFailure, advance)
            MissionType.MISSING_SYMBOL -> MissingSymbolMission(random, config, onFailure, advance)
            MissionType.MEMORY -> MemoryMission(random, config, onFailure, advance)
            MissionType.TYPING -> TypingMission(random, config, onFailure, advance)
            MissionType.SHAKE,
            MissionType.STEPS,
            MissionType.SQUAT -> MotionMission(config, onFailure, advance)
            MissionType.PHOTO -> PhotoMission(config, onFailure, advance)
            MissionType.QR -> CodeMission(config, onFailure, advance)
            MissionType.COMBINATION -> MathMission(random, config, onFailure, advance)
        }
    }
}

// ------------------------------------------------------------------ mental

@Composable
private fun MathMission(
    random: SeededRandom,
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onSolved: () -> Unit
) {
    val challenge = remember(random) { MathMissionGenerator().generate(random, config.difficulty) }
    var input by remember(challenge) { mutableStateOf("") }
    var error by remember(challenge) { mutableStateOf<String?>(null) }

    MissionFrame(prompt = "Solve it") {
        Text(
            challenge.expression,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { new ->
                input = new.filter { it.isDigit() || it == '-' }
                error = null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (val result = challenge.check(input)) {
                    is MissionAttemptResult.Correct -> onSolved()
                    is MissionAttemptResult.Incorrect -> {
                        error = result.reason.userMessage
                        input = ""
                        onFailure(result.reason)
                    }
                }
            },
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Check") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissingSymbolMission(
    random: SeededRandom,
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onSolved: () -> Unit
) {
    val challenge = remember(random) {
        MissingSymbolGenerator().generate(random, config.difficulty)
    }
    var error by remember(challenge) { mutableStateOf<String?>(null) }

    MissionFrame(prompt = "Which symbol is missing?") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            challenge.row.forEach { symbol ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (symbol == null) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(symbol ?: "?", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            challenge.options.forEach { option ->
                OutlinedButton(onClick = {
                    when (val result = challenge.check(option)) {
                        is MissionAttemptResult.Correct -> onSolved()
                        is MissionAttemptResult.Incorrect -> {
                            error = result.reason.userMessage
                            onFailure(result.reason)
                        }
                    }
                }) {
                    Text(option, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MemoryMission(
    random: SeededRandom,
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onSolved: () -> Unit
) {
    val challenge = remember(random) {
        MemorySequenceGenerator().generate(random, config.difficulty)
    }
    var showing by remember(challenge) { mutableStateOf(true) }
    var highlighted by remember(challenge) { mutableIntStateOf(-1) }
    var entered by remember(challenge) { mutableStateOf(listOf<Int>()) }
    var error by remember(challenge) { mutableStateOf<String?>(null) }
    var replayToken by remember(challenge) { mutableIntStateOf(0) }

    LaunchedEffect(challenge, replayToken) {
        showing = true
        entered = emptyList()
        delay(600)
        for (tile in challenge.sequence) {
            highlighted = tile
            delay(challenge.displayMillis)
            highlighted = -1
            delay(180)
        }
        showing = false
    }

    MissionFrame(prompt = if (showing) "Watch the pattern" else "Repeat the pattern") {
        LazyVerticalGrid(
            columns = GridCells.Fixed(challenge.gridSize),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(challenge.tileCount) { index ->
                val isLit = highlighted == index
                val alpha by animateFloatAsState(if (isLit) 1f else 0.35f, label = "tile")
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                        .clickable(enabled = !showing) {
                            val next = entered + index
                            when (val result = challenge.checkPrefix(next)) {
                                is MissionAttemptResult.Correct -> {
                                    entered = next
                                    if (challenge.isComplete(next)) onSolved()
                                }
                                is MissionAttemptResult.Incorrect -> {
                                    error = result.reason.userMessage
                                    entered = emptyList()
                                    onFailure(result.reason)
                                    replayToken++
                                }
                            }
                        }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${entered.size} / ${challenge.sequence.size}",
            style = MaterialTheme.typography.labelLarge
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TypingMission(
    random: SeededRandom,
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onSolved: () -> Unit
) {
    val challenge = remember(random) { TypingPhraseProvider().generate(random, config.difficulty) }
    var input by remember(challenge) { mutableStateOf("") }
    var error by remember(challenge) { mutableStateOf<String?>(null) }

    MissionFrame(prompt = "Type this exactly") {
        Card(Modifier.fillMaxWidth()) {
            Text(
                challenge.phrase,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { challenge.progress(input).toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                error = null
            },
            // Autocorrect would do the mission for you.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false
            ),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (val result = challenge.check(input)) {
                    is MissionAttemptResult.Correct -> onSolved()
                    is MissionAttemptResult.Incorrect -> {
                        error = result.reason.userMessage
                        onFailure(result.reason)
                    }
                }
            },
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Check") }
    }
}

// ----------------------------------------------------------------- physical

@Composable
private fun MotionMission(
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(0) }
    var rejected by remember { mutableStateOf<String?>(null) }

    DisposableEffect(config.type, config.targetCount) {
        val source = MotionSensorSource(context)
        val handle: (MotionOutcome) -> Unit = { outcome ->
            when (outcome) {
                is MotionOutcome.Counted -> {
                    count = outcome.count
                    rejected = null
                }
                is MotionOutcome.Completed -> {
                    count = outcome.count
                    onDone()
                }
                is MotionOutcome.Rejected -> {
                    rejected = outcome.reason.userMessage
                    onFailure(outcome.reason)
                }
                MotionOutcome.Ignored -> Unit
            }
        }
        when (config.type) {
            MissionType.SHAKE -> source.startShake(config.targetCount, handle)
            MissionType.STEPS -> source.startSteps(config.targetCount, handle)
            MissionType.SQUAT -> source.startSquats(config.targetCount, handle)
            else -> Unit
        }
        onDispose { source.stopAll() }
    }

    val instruction = when (config.type) {
        MissionType.SHAKE -> "Shake your phone"
        MissionType.STEPS -> "Get up and walk"
        else -> "Do squats holding your phone"
    }

    MissionFrame(prompt = instruction) {
        Text(
            "$count",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "of ${config.targetCount}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (count.toFloat() / config.targetCount).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        )
        rejected?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

// ------------------------------------------------------------------- camera

@Composable
private fun PhotoMission(
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onDone: () -> Unit
) {
    val targetId = config.referenceAssetIds.firstOrNull()
    val target = remember(targetId) { targetId?.let { AppGraph.targets.photo(it) } }
    var message by remember { mutableStateOf<String?>(null) }

    if (target == null) {
        MissingTarget("photo", onDone)
        return
    }

    val challenge = remember(target) { PhotoChallenge(target) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            message = "No photo was taken."
            return@rememberLauncherForActivityResult
        }
        when (val result = challenge.check(BitmapHashing.fingerprint(bitmap))) {
            is MissionAttemptResult.Correct -> onDone()
            is MissionAttemptResult.Incorrect -> {
                message = result.reason.userMessage
                onFailure(result.reason)
            }
        }
    }

    MissionFrame(prompt = challenge.prompt) {
        Text(
            "Take a photo of the same place you registered when you set this alarm.",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open camera")
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CodeMission(
    config: MissionConfig,
    onFailure: (MissionFailureReason) -> Unit,
    onDone: () -> Unit
) {
    val targetId = config.referenceAssetIds.firstOrNull()
    val target = remember(targetId) { targetId?.let { AppGraph.targets.code(it) } }
    var message by remember { mutableStateOf<String?>(null) }

    if (target == null) {
        MissingTarget("code", onDone)
        return
    }

    val challenge = remember(target) { CodeChallenge(target) }
    val launcher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val contents = result.contents
        if (contents == null) {
            message = "Nothing was scanned."
            return@rememberLauncherForActivityResult
        }
        when (val outcome = challenge.check(contents)) {
            is MissionAttemptResult.Correct -> onDone()
            is MissionAttemptResult.Incorrect -> {
                message = outcome.reason.userMessage
                onFailure(outcome.reason)
            }
        }
    }

    MissionFrame(prompt = challenge.prompt) {
        Button(
            onClick = { launcher.launch(scanOptions()) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Scan code") }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

private fun scanOptions() = com.journeyapps.barcodescanner.ScanOptions().apply {
    setBeepEnabled(false)
    setOrientationLocked(false)
    setPrompt("Point at the code you registered")
}

/**
 * Shown when a camera mission's target has been deleted.
 *
 * The alarm must never become impossible to turn off, so this offers a way
 * through rather than trapping the user.
 */
@Composable
private fun MissingTarget(kind: String, onDone: () -> Unit) {
    MissionFrame(prompt = "This mission is not set up") {
        Text(
            "The $kind you registered for this alarm is no longer available, so the " +
                "mission cannot be checked. You can stop the alarm and set it up again later.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Stop alarm") }
    }
}

// -------------------------------------------------------------------- none

/**
 * The no-mission case: a deliberate press-and-hold rather than a tap.
 *
 * Even with no mission configured, an accidental brush of the screen should not
 * turn off an alarm.
 */
@Composable
private fun SlideToStop(onDone: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = tween(HOLD_MILLIS),
        finishedListener = { if (it == 1f) onDone() },
        label = "hold"
    )

    MissionFrame(prompt = "Press and hold to stop") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            held = true
                            tryAwaitRelease()
                            held = false
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                if (held) "Keep holding…" else "Hold to stop",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private const val HOLD_MILLIS = 1200

// ------------------------------------------------------------------- frame

@Composable
private fun MissionFrame(prompt: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            prompt,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(24.dp))
        content()
    }
}
