package uk.co.mfrost.inkfold.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.co.mfrost.inkfold.storage.DocumentSummary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookListScreen(
    vm: AppViewModel,
    onSignIn: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSyncLog by remember { mutableStateOf(false) }
    var syncLogText by remember { mutableStateOf("") }

    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.statusMessage = null
        }
    }

    LaunchedEffect(vm.syncing, showSyncLog) {
        if (showSyncLog && !vm.syncing) syncLogText = vm.syncLogText()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Inkfold") },
                    actions = {
                        IconButton(onClick = { vm.sync() }, enabled = !vm.syncing) {
                            Icon(
                                if (vm.signedInEmail != null) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                contentDescription = "Sync",
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Account")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val email = vm.signedInEmail
                            if (email != null) {
                                DropdownMenuItem(text = { Text(email, maxLines = 1) }, onClick = {})
                                DropdownMenuItem(text = { Text("Sync now") }, onClick = {
                                    menuOpen = false; vm.sync()
                                })
                                DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                                    menuOpen = false; vm.signOut()
                                })
                            } else {
                                DropdownMenuItem(text = { Text("Sign in to Google Drive") }, onClick = {
                                    menuOpen = false; onSignIn()
                                })
                            }
                            DropdownMenuItem(text = { Text("Sync log") }, onClick = {
                                menuOpen = false
                                syncLogText = vm.syncLogText()
                                showSyncLog = true
                            })
                        }
                    },
                )
                if (vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New notebook")
            }
        },
    ) { padding ->
        if (vm.notebooks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No notebooks yet.\nTap + to create one.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.notebooks, key = { it.id }) { summary ->
                    NotebookCard(
                        summary = summary,
                        onOpen = { vm.open(summary.id) },
                        onDelete = { vm.delete(summary.id) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        TextPromptDialog(
            title = "New notebook",
            initial = "Untitled",
            confirmLabel = "Create",
            onConfirm = { showCreate = false; vm.create(it) },
            onDismiss = { showCreate = false },
        )
    }

    if (showSyncLog) {
        SyncLogDialog(
            text = syncLogText,
            onClear = {
                vm.clearSyncLog()
                syncLogText = vm.syncLogText()
            },
            onDismiss = { showSyncLog = false },
        )
    }
}

@Composable
private fun SyncLogDialog(
    text: String,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync log") },
        text = {
            Text(
                text.ifBlank { "No sync attempts logged yet." },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookCard(
    summary: DocumentSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(end = 32.dp),
                )
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
            Text(
                "${summary.pageCount} page${if (summary.pageCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(summary.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${summary.title}\"?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
