package app.picacgmd.feature.comments

import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.securePopupProperties

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppFilledIconButton
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.theme.withTabularFigures
import app.picacgmd.core.network.Comment

// Pieces shared by the comic comments (board 17) and the game comments (board 29), so both
// composers count the same way, both lists report the same way and both draw the same row and
// input bar. Board 17 is the reference look.

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

/** 「置顶」 with the pin, or a plain section title, above a group of comments. */
@Composable
internal fun CommentGroupLabel(text: String, pinned: Boolean) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinned) {
            Icon(
                Icons.Outlined.PushPin,
                null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The bottom input bar of board 17: a pill field over `surfaceContainer`, the live counter sitting on
 * the send button it gates, and the send button disabled until the draft is sendable.
 * [onSend] is handed the draft plus the callback that clears the field once the send has gone out.
 */
@Composable
internal fun CommentComposer(hint: String, sending: Boolean, onSend: (String, () -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val length = commentLength(text)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it.take(COMMENT_DRAFT_CAP) },
                placeholder = { Text(hint) },
                maxLines = 4,
                isError = length > COMMENT_MAX_LENGTH,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.weight(1f),
            )
            // The count sits on the button it gates, so a disabled send explains itself.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CommentLengthCounter(length)
                AppFilledIconButton(onClick = { onSend(text) { text = "" } }, enabled = commentSendable(length) && !sending) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.comments_send))
                }
            }
        }
    }
}

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
        AppIconButton(onClick = { open = true }) {
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
            AppButton(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.comments_report)) }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.comics_cancel)) } },
    )
}
