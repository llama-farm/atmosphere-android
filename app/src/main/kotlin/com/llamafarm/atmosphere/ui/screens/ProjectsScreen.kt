package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.network.ProjectInfo
import com.llamafarm.atmosphere.ui.components.DashCard
import com.llamafarm.atmosphere.ui.components.EmptyState
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel

@Composable
fun ProjectsScreen(viewModel: MeshDebugViewModel) {
    val projects by viewModel.projects.collectAsState()
    val isLoading by viewModel.projectsLoading.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.loadProjects()
    }

    val displayedProjects = when (selectedTab) {
        0 -> projects.filter { it.isDiscoverable }
        else -> projects
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground)
            .padding(12.dp)
    ) {
        // Header with refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Projects",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { viewModel.loadProjects() },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AccentBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, "Refresh", tint = TextSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Exposed", "All").forEachIndexed { index, label ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AccentBlue.copy(alpha = 0.2f) else CardBackground)
                        .border(
                            1.dp,
                            if (isSelected) AccentBlue else BorderColor,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedTab = index }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        label,
                        color = if (isSelected) AccentBlue else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (displayedProjects.isEmpty() && !isLoading) {
            EmptyState(
                text = if (selectedTab == 0) "No exposed projects" else "No projects found"
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedProjects, key = { "${it.namespace}/${it.projectId}" }) { project ->
                    ProjectCard(
                        project = project,
                        onExpose = { viewModel.exposeProject(project.namespace, project.projectId) },
                        onHide = { viewModel.hideProject(project.projectId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectInfo,
    onExpose: () -> Unit,
    onHide: () -> Unit
) {
    DashCard(title = project.name.ifEmpty { project.projectId }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {

            Spacer(modifier = Modifier.height(4.dp))

            // Namespace / project_id
            Text(
                "${project.namespace}/${project.projectId}",
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            // Model
            project.model?.let { model ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    model,
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Badges and action button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Badges
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (project.hasRag) {
                        Badge(text = "RAG", color = StatusPurple, bgColor = StatusPurpleBg)
                    }
                    if (project.hasTools) {
                        Badge(text = "Tools", color = AccentOrange, bgColor = AccentOrange.copy(alpha = 0.15f))
                    }
                }

                // Expose/Hide button
                if (project.isDiscoverable) {
                    SmallButton(
                        text = "Hide",
                        color = StatusRed,
                        icon = Icons.Default.VisibilityOff,
                        onClick = onHide
                    )
                } else {
                    SmallButton(
                        text = "Expose",
                        color = ButtonGreen,
                        icon = Icons.Default.Visibility,
                        onClick = onExpose
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color, bgColor: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
