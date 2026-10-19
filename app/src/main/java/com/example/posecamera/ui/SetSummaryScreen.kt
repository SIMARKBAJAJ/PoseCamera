package com.example.posecamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.posecamera.network.ApiResult
import com.example.posecamera.network.HistoryResponse
import com.example.posecamera.network.NO_REPS_MESSAGE
import com.example.posecamera.sync.SaveResult
import com.example.posecamera.sync.saveBlocker
import com.example.posecamera.network.SyncedSet
import com.example.posecamera.lifts.FORM_DISCLAIMER
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.ui.design.ScoreRing
import com.example.posecamera.ui.train.rowScore
import com.example.posecamera.ui.train.scoreBand
import com.example.posecamera.ui.train.setScore
import com.example.posecamera.ui.train.topFaults
import java.util.Locale

@Composable
fun SetSummaryScreen(
    summary: RepSetSummary,
    uploadSet: suspend (RepSetSummary) -> ApiResult<Unit>,
    loadHistory: suspend () -> ApiResult<HistoryResponse>,
    onSessionExpired: (String) -> Unit,
    onStartNewSet: () -> Unit,
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var setUploaded by remember(summary) { mutableStateOf(false) }
    var isSyncing by remember(summary) { mutableStateOf(true) }
    var syncFailed by remember(summary) { mutableStateOf(false) }
    var loginRequired by remember(summary) { mutableStateOf(false) }
    var syncMessage by remember(summary) { mutableStateOf("Saving set...") }
    var history by remember(summary) { mutableStateOf<HistoryResponse?>(null) }

    LaunchedEffect(summary, retryKey) {
        isSyncing = true
        syncFailed = false
        loginRequired = false
        notUploadable(summary)?.let {
            syncMessage = it
            isSyncing = false
            return@LaunchedEffect
        }
        syncMessage = if (setUploaded) "Refreshing history..." else "Saving set..."

        if (!setUploaded) {
            when (val result = uploadSet(summary)) {
                is ApiResult.Success -> setUploaded = true
                is ApiResult.Failure -> {
                    syncMessage = "Not saved. ${result.message}"
                    syncFailed = true
                    loginRequired = result.requiresLogin
                    isSyncing = false
                    return@LaunchedEffect
                }
            }
        }

        when (val result = loadHistory()) {
            is ApiResult.Success -> {
                history = result.value
                syncMessage = "Set saved"
            }

            is ApiResult.Failure -> {
                syncMessage = "Set saved. ${result.message}"
                syncFailed = true
                loginRequired = result.requiresLogin
            }
        }
        isSyncing = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${summary.exerciseType.displayLabel} set summary",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
        if (summary.holdSeconds != null) {
            SummaryValue("Hold", String.format(Locale.US, "%.0f s", summary.holdSeconds))
        } else if (summary.exerciseType.lift) {
            SummaryValue("Reps counted", summary.totalReps.toString())
        }
        if (summary.holdSeconds == null && (!summary.exerciseType.lift || summary.attemptedReps > 0)) {
            SummaryValue(if (summary.exerciseType.lift) "Judged" else "Attempted", summary.attemptedReps.toString())
            SummaryValue("Clean", summary.cleanReps.toString())
            SummaryValue("Rejected (lowers score)", summary.rejectedReps.toString())
            val score = setScore(summary)
            Text("SET SCORE", color = Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 20.dp))
            ScoreRing(score)
            Text(score?.let { scoreBand(it).label } ?: "No score", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            topFaults(summary.rejectionReasons).takeIf { it.isNotEmpty() }?.let { faults ->
                Text("TOP FAULTS", color = Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                faults.forEach { (reason, n) -> Text("$reason x$n", color = Color.White, style = MaterialTheme.typography.bodyMedium) }
            }
        } else if (summary.holdSeconds == null) {
            SummaryValue("Form", "Not judged")
        }
        SummaryValue(
            if (summary.exerciseType.lift) "Most common cue" else "Most common rejection",
            summary.mostCommonRejectionReason ?: "None",
        )
        if (summary.cannotSee.isNotEmpty()) {
            SummaryValue("Could not see", summary.cannotSee.joinToString(", "))
        }

        Text(
            text = syncMessage,
            color = if (syncFailed) MaterialTheme.colorScheme.error else Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        if (isSyncing) {
            Text(
                text = "Keep this screen open while syncing.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (syncFailed) {
            Button(
                onClick = {
                    if (loginRequired) onSessionExpired(syncMessage) else retryKey += 1
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (loginRequired) "Log in again" else "Retry sync")
            }
        }

        history?.let { RecentHistory(it) }

        Text(
            text = FORM_DISCLAIMER,
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 24.dp),
        )

        Button(
            onClick = onStartNewSet,
            enabled = !isSyncing,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            modifier = Modifier.padding(top = 28.dp),
        ) {
            Text("Start new set")
        }
    }
}

@Composable
private fun RecentHistory(history: HistoryResponse) {
    HorizontalDivider(
        color = Color.DarkGray,
        modifier = Modifier.padding(top = 28.dp, bottom = 20.dp),
    )
    Text(
        text = "${history.streak} DAY STREAK",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "RECENT SETS",
        color = Color.LightGray,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 20.dp),
    )
    if (history.sets.isEmpty()) {
        Text(
            text = "No synced sets yet.",
            color = Color.LightGray,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else {
        history.sets.take(3).forEach { HistoryRow(it) }
    }
}

@Composable
private fun HistoryRow(set: SyncedSet) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        Text(
            text = when (set.exerciseType) {
                "push-up" -> "Push-up"
                "squat" -> "Squat"
                else -> (set.exerciseSlug ?: "Other").replace('-', ' ').replaceFirstChar { it.uppercase() }
            },
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (set.cleanCount == null || set.attemptedCount == null) {
                "${set.repCount} reps (form not judged)"
            } else {
                "${set.repCount} attempted / ${set.cleanCount} clean / ${set.attemptedCount} rejected"
            },
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = (if (set.repCount <= 0) "not counted  " else rowScore(set.formscore, set.repCount)?.let { "Score $it ${scoreBand(it).label}  " } ?: "") +
                set.timestamp.replace('T', ' ').take(16),
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Text(
        text = "$label: $value",
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** Why this set is not uploaded (null = upload it). Plank has no server field; zero reps is rejected by the server. */
private fun notUploadable(summary: RepSetSummary): String? = when {
    summary.holdSeconds != null -> "Plank holds are not synced yet."
    summary.saveBlocker() == SaveResult.NO_REPS -> NO_REPS_MESSAGE
    else -> null
}
