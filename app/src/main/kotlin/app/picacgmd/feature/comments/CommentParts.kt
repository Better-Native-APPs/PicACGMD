package app.picacgmd.feature.comments

import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.securePopupProperties

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.R
import app.picacgmd.core.designsystem.theme.withTabularFigures
import app.picacgmd.core.network.Comment

// Pieces shared by the comic comments (board 17) and the game comments (board 29), so both
// composers count the same way and both lists report the same way.

/** The official client's limit for one comment or reply. */
internal const val COMMENT_MAX_LENGTH = 500

/** The counter only shows up for the last tenth of the allowance. */
internal const val COMMENT_COUNTER_FROM = 450

/**
 * A draft may run past the limit: cutting at 500 would break pinyin composition near the end and
 * silently drop part of a long paste. Going over shows in the counter and disables sending instead.
 */
internal const val COMMENT_DRAFT_CAP = 2_000

/** Length as the comment will be sent: the view models trim, so surrounding whitespace does not count. */
internal fun commentLength(draft: String): Int = draft.trim().length

/** A draft of [length] characters may go out: something to say, and not too much of it. */
internal fun commentSendable(length: Int): Boolean = length in 1..COMMENT_MAX_LENGTH

/** 「480/500」 for the composer, from 450 characters on; red once the draft is over the limit. */
@Composable
internal fun CommentLengthCounter(length: Int) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = length >= COMMENT_COUNTER_FROM,
        enter = fadeIn(motion.fastEffectsSpec()) + expandVertically(motion.fastSpatialSpec()),
        exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
    ) {
        val spoken = stringResource(R.string.comments_length_description, length, COMMENT_MAX_LENGTH)
        Text(
            "$length/$COMMENT_MAX_LENGTH",
            style = MaterialTheme.typography.labelSmall.withTabularFigures(),
            color = if (length > COMMENT_MAX_LENGTH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = spoken },
        )
    }
}

/** A comment's ⋮ menu, built like the row menus on the bookshelf and downloads pages. */
@Composable
internal fun CommentMenu(onReport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Small on purpose: it shares a line with the name, and Compose still widens the touch area to 48 dp.
        IconButton(onClick = { open = true }, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Outlined.MoreVert,
                stringResource(R.string.comments_more_actions),
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = securePopupProperties(focusable = true)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.comments_report)) },
                leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                onClick = {
                    open = false
                    onReport()
                },
            )
        }
    }
}

/** Asks before reporting and quotes the comment, so a tap on the wrong row can still be caught. */
@Composable
internal fun ReportCommentDialog(comment: Comment, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comments_report_title)) },
        text = {
            Text(
                stringResource(
                    R.string.comments_report_quote,
                    comment.user?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.comments_anonymous),
                    if (comment.hide) stringResource(R.string.comments_hidden) else comment.content,
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.comments_report)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.comics_cancel)) } },
    )
}
