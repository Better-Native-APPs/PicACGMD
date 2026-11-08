package app.picacgmd.core.domain.repository

import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.Announcement
import app.picacgmd.core.network.Page

class AnnouncementRepository(private val session: SessionManager) {
    suspend fun page(page: Int = 1): Page<Announcement> {
        require(page >= 1) { "页码必须从 1 开始" }
        return session.withApi { it.announcements(page) }
    }
}
