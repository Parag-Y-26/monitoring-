package com.edgepulse.monitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edgepulse.core.cloud.ApiUsage
import com.edgepulse.core.cloud.ProviderDefaults
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiUsageScreen(entries: List<ApiUsage>, onBack: () -> Unit, embedded: Boolean = false) {
    val known = entries.mapNotNull { it.totalTokens }
    val total = known.fold(BigInteger.ZERO) { sum, n -> sum + BigInteger.valueOf(n) }
    Scaffold(topBar = { EdgePageHeader("API activity","Optional request history") {
        EdgeTextAction("Back",onBack)
    } }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Your API activity", style = MaterialTheme.typography.headlineSmall)
                Text("Key checks · Model discovery · Approved photos", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Live monitoring runs offline", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                Text("${entries.size} recent requests", style = MaterialTheme.typography.titleLarge)
                Text(if (known.isEmpty()) "Tokens: not reported" else "Known total tokens: $total (${known.size} requests reported usage)")
                Text("Cost: check provider billing · Last 500 requests since v0.5", style = MaterialTheme.typography.bodySmall)
            }
            if (entries.isEmpty()) item { Text("No requests logged yet", style = MaterialTheme.typography.titleMedium)
            }
            items(entries.asReversed(), key = { it.id }) { entry ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(ProviderDefaults.profile(entry.provider).name + " · " + when (entry.purpose) {
                    "MODEL_CATALOG" -> "Load models"
                    "KEY_OR_MODEL_CHECK" -> "Check key/model"
                    "ITEM_PHOTO_DESCRIPTION" -> "Describe item photo"
                    else -> entry.purpose
                }, style = MaterialTheme.typography.titleMedium)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(entry.wallMs)), style = MaterialTheme.typography.bodySmall)
                if (entry.model.isNotEmpty()) Text(entry.model, style = MaterialTheme.typography.bodySmall)
                Text("${entry.outcome} · ${entry.images} images", style = MaterialTheme.typography.bodySmall)
                Text("Tokens: ${entry.totalTokens?.toString() ?: "not reported"} · input ${entry.inputTokens?.toString() ?: "unknown"} · output ${entry.outputTokens?.toString() ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
