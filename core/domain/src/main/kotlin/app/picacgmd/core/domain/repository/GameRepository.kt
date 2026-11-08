package app.picacgmd.core.domain.repository

import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.CommentsPage
import app.picacgmd.core.network.GameInfo
import app.picacgmd.core.network.GameSimple
import app.picacgmd.core.network.Page

class GameRepository(private val session: SessionManager) {
    suspend fun games(page: Int = 1): Page<GameSimple> = session.withApi { it.games(page) }
    suspend fun game(gameId: String): GameInfo = session.withApi { it.game(gameId) }
    suspend fun switchLike(gameId: String): Boolean = session.withApi { it.switchLikeGame(gameId) } == "like"
    suspend fun comments(gameId: String, page: Int = 1): CommentsPage = session.withApi { it.gameComments(gameId, page) }
    suspend fun replies(commentId: String, page: Int = 1): Page<Comment> =
        session.withApi { it.gameCommentChildren(commentId, page) }

    suspend fun post(gameId: String, content: String) {
        require(content.isNotBlank()) { "评论内容不能为空" }
        session.withApi { it.postGameComment(gameId, content) }
    }

    suspend fun reply(commentId: String, content: String) {
        require(content.isNotBlank()) { "回复内容不能为空" }
        session.withApi { it.postGameChildComment(commentId, content) }
    }

    suspend fun toggleLikeComment(commentId: String): Boolean =
        session.withApi { it.switchLikeGameComment(commentId) } == "like"
}
