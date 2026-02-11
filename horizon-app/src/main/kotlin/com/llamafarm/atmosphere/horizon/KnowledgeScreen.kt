package com.llamafarm.atmosphere.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KnowledgeScreen(
    result: KnowledgeResult?,
    suggestions: List<String>,
    loading: Boolean,
    onQuery: (String) -> Unit,
    onLoadSuggestions: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onLoadSuggestions() }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))

        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask about T.O.s, procedures…", color = HorizonColors.TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = HorizonColors.KnowledgePurple) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HorizonColors.KnowledgePurple,
                unfocusedBorderColor = HorizonColors.SurfaceVariant,
                focusedTextColor = HorizonColors.TextPrimary,
                unfocusedTextColor = HorizonColors.TextPrimary,
                cursorColor = HorizonColors.KnowledgePurple
            )
        )

        Spacer(Modifier.height(4.dp))

        // Submit button
        Button(
            onClick = { if (query.isNotBlank()) onQuery(query) },
            enabled = query.isNotBlank() && !loading,
            colors = ButtonDefaults.buttonColors(containerColor = HorizonColors.KnowledgePurple),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Query Knowledge Brain", fontFamily = MonoFont) }

        Spacer(Modifier.height(12.dp))

        // Suggestion chips
        if (suggestions.isNotEmpty()) {
            Text("SUGGESTED", color = HorizonColors.TextMuted, fontSize = 10.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestions) { s ->
                    SuggestionChip(
                        onClick = { query = s; onQuery(s) },
                        label = { Text(s, fontSize = 12.sp, maxLines = 1) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = HorizonColors.KnowledgePurple.copy(alpha = 0.15f),
                            labelColor = HorizonColors.KnowledgePurple
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Result
        result?.let { r ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HorizonColors.CardBg)
                    .padding(14.dp)
            ) {
                // Confidence bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CONFIDENCE", color = HorizonColors.TextMuted, fontSize = 10.sp, fontFamily = MonoFont)
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { r.confidence },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = HorizonColors.KnowledgePurple,
                        trackColor = HorizonColors.SurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(r.confidence * 100).toInt()}%", color = HorizonColors.KnowledgePurple, fontSize = 12.sp, fontFamily = MonoFont)
                }

                Spacer(Modifier.height(10.dp))
                Text(r.answer, color = HorizonColors.TextPrimary, fontSize = 14.sp)

                if (r.sources.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("SOURCES", color = HorizonColors.TextMuted, fontSize = 10.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                    r.sources.forEach { src ->
                        Text("• $src", color = HorizonColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
