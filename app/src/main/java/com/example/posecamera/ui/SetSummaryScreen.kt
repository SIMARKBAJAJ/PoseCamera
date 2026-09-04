package com.example.posecamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.posecamera.pose.RepSetSummary
import java.util.Locale

@Composable
fun SetSummaryScreen(
    summary: RepSetSummary,
    onStartNewSet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Set summary",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
        SummaryValue("Attempted", summary.attemptedReps.toString())
        SummaryValue("Clean", summary.cleanReps.toString())
        SummaryValue("Rejected", summary.rejectedReps.toString())
        SummaryValue(
            "FormScore",
            String.format(Locale.US, "%.1f%%", summary.formScore),
        )
        SummaryValue(
            "Most common rejection",
            summary.mostCommonRejectionReason ?: "None",
        )
        Button(
            onClick = onStartNewSet,
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
private fun SummaryValue(label: String, value: String) {
    Text(
        text = "$label: $value",
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
}
