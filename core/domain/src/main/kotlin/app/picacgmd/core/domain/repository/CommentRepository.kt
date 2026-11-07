package app.picacgmd.core.domain.repository

import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.CommentsPage
import app.picacgmd.core.network.MyComment
import app.picacgmd.core.network.Page

class CommentRepository(private val session: SessionManager) {
    suspend fun comments(comicId: String, page: Int = 1): CommentsPage =
        session.withApi { it.comments(comicId, page) }

    suspend fun replies(commentId: String, page: Int = 1): Page<Comment> =
        session.withApi { it.commentChildren(commentId, page) }

    suspend fun post(comicId: String, content: String) {
        require(content.isNotBlank()) { "评论内容不能为空" }
        session.withApi { it.postComment(comicId, content) }
    }

    suspend fun reply(commentId: String, content: String) {
        require(content.isNotBlank()) { "回复内容不能为空" }
        session.withApi { it.postChildComment(commentId, content) }
    }

    suspend fun toggleLike(commentId: String): Boolean = session.withApi { it.switchLikeComment(commentId) } == "like"
    suspend fun hide(commentId: String) = session.withApi { it.hideComment(commentId) }
    suspend fun myComments(page: Int = 1): Page<MyComment> = session.withApi { it.myComments(page) }
}
