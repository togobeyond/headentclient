package sk.tvhclient.android

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.RemovedRecording
import sk.tvhclient.shared.model.TvhServer

/**
 * M757: cleans up the server's "Removed recordings".
 *
 * Tvheadend keeps a DVR entry in its database after the recorded file has been deleted.
 * The HTSP `deleteDvrEntry` only removes files and leaves such entries in place (and still
 * answers success), so the app hides them from the archive. Over time they pile up on the
 * server. The Tvheadend web UI deletes them through `api/idnode/delete`; this action does
 * the same from the app.
 */
@Composable
internal fun RemovedCleanupButton(server: TvhServer?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<RemovedRecording>?>(null) }

    IconButton(
        enabled = server != null && !loading && !busy,
        onClick = {
            val srv = server ?: return@IconButton
            loading = true
            scope.launch {
                val list = try {
                    withContext(Dispatchers.IO) {
                        val api = Tvh.apiFor(srv)
                        try { api.removedRecordings() } finally { api.close() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    loading = false
                    Toast.makeText(
                        context, context.getString(R.string.purge_removed_load_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                loading = false
                if (list.isEmpty()) {
                    Toast.makeText(
                        context, context.getString(R.string.purge_removed_none),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pending = list
                }
            }
        }
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.DeleteSweep,
                contentDescription = stringResource(R.string.purge_removed_title),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    val list = pending
    if (list != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(stringResource(R.string.purge_removed_title)) },
            text = { Text(stringResource(R.string.purge_removed_confirm, list.size)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val srv = server ?: return@TextButton
                        busy = true
                        scope.launch {
                            val (ok, failed) = withContext(Dispatchers.IO) {
                                val api = Tvh.apiFor(srv)
                                var ok = 0
                                var failed = 0
                                try {
                                    for (r in list) {
                                        try {
                                            api.deleteRemovedRecording(r.uuid)
                                            ok++
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (_: Throwable) {
                                            failed++
                                        }
                                    }
                                } finally {
                                    api.close()
                                }
                                ok to failed
                            }
                            busy = false
                            pending = null
                            val msg = if (failed == 0) {
                                context.getString(R.string.purge_removed_done, ok)
                            } else {
                                context.getString(R.string.purge_removed_partial, ok, failed)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onDone()
                        }
                    }
                ) {
                    Text(stringResource(if (busy) R.string.purge_removed_working else R.string.delete))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
