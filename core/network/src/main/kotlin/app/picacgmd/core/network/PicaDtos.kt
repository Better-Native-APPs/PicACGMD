package app.picacgmd.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PicaImage(
    val fileServer: String = "",
    val path: String = "",
    val originalName: String = "",
)

@Serializable
data class Page<T>(
    val docs: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    @Serializable(with = PageNumberSerializer::class) val page: Int = 1,
    val pages: Int = 0,
)

/** The API sometimes echoes the page query parameter as a JSON string. */
object PageNumberSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PageNumber", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int = if (decoder is JsonDecoder) {
        decoder.decodeJsonElement().jsonPrimitive.intOrNull ?: throw SerializationException("Invalid page number")
    } else {
        decoder.decodeInt()
    }
}

@Serializable
data class RegisterDto(
    val email: String,
    val password: String,
    val name: String,
    val birthday: String,
    val gender: String,
    val question1: String,
    val question2: String,
    val question3: String,
    val answer1: String,
    val answer2: String,
    val answer3: String,
)

/** Shared profile fields also cover creators, comment users, and knight leaderboard entries. */
@Serializable
data class UserProfile(
    @SerialName("_id") val id: String = "",
    val name: String = "",
    val gender: String = "",
    val title: String = "",
    val verified: Boolean = false,
    val exp: Long = 0,
    val level: Int = 0,
    val characters: List<String> = emptyList(),
    val avatar: PicaImage? = null,
    val slogan: String? = null,
    val birthday: String? = null,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val isPunched: Boolean = false,
    val role: String = "",
    val character: String? = null,
    val comicsUploaded: Int = 0,
)

typealias Knight = UserProfile

@Serializable
data class PunchStatus(val status: String = "", val punchInLastDay: String = "")

@Serializable
data class ForgotPasswordResult(
    val question1: String = "",
    val question2: String = "",
    val question3: String = "",
)

@Serializable
data class ResetPasswordResult(val password: String = "")

@Serializable
data class Category(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val description: String = "",
    val thumb: PicaImage? = null,
    val isWeb: Boolean = false,
    val active: Boolean = false,
    val link: String? = null,
)

@Serializable
data class ComicSimple(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val author: String = "",
    val pagesCount: Int = 0,
    val epsCount: Int = 0,
    val finished: Boolean = false,
    val categories: List<String> = emptyList(),
    val thumb: PicaImage? = null,
    val likesCount: Int = 0,
)

@Serializable
data class ComicInfo(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val author: String = "",
    val pagesCount: Int = 0,
    val epsCount: Int = 0,
    val finished: Boolean = false,
    val categories: List<String> = emptyList(),
    val thumb: PicaImage? = null,
    val likesCount: Int = 0,
    @SerialName("_creator") val creator: UserProfile? = null,
    val description: String = "",
    val chineseTeam: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val allowDownload: Boolean = true,
    val viewsCount: Long = 0,
    val isFavourite: Boolean = false,
    val isLiked: Boolean = false,
    val commentsCount: Int = 0,
)

@Serializable
data class Ep(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val order: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ComicPicture(
    @SerialName("_id") val id: String = "",
    val media: PicaImage = PicaImage(),
)

@Serializable
data class PicturePage(
    val pages: Page<ComicPicture> = Page(),
    val ep: Ep? = null,
)

@Serializable
data class ComicCollection(val title: String = "", val comics: List<ComicSimple> = emptyList())

@Serializable
data class Comment(
    @SerialName("_id") val id: String = "",
    val content: String = "",
    @SerialName("_user") val user: UserProfile? = null,
    val isTop: Boolean = false,
    val hide: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val likesCount: Int = 0,
    val commentsCount: Int = 0,
    val isLiked: Boolean = false,
    @SerialName("_comic") val comicId: String? = null,
    @SerialName("_game") val gameId: String? = null,
    @SerialName("_parent") val parentId: String? = null,
)

@Serializable
data class CommentsPage(
    val comments: Page<Comment> = Page(),
    val topComments: List<Comment> = emptyList(),
)

@Serializable
data class ComicReference(@SerialName("_id") val id: String = "", val title: String = "")

@Serializable
data class MyComment(
    @SerialName("_id") val id: String = "",
    val content: String = "",
    @SerialName("_comic") val comic: ComicReference? = null,
    val hide: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val likesCount: Int = 0,
    val commentsCount: Int = 0,
    val isLiked: Boolean = false,
)

@Serializable
data class GameSimple(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val version: String = "",
    val icon: PicaImage? = null,
    val publisher: String = "",
    val adult: Boolean = false,
    val suggest: Boolean = false,
    val likesCount: Int = 0,
    val android: Boolean = false,
)

@Serializable
data class GameInfo(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val version: String = "",
    val icon: PicaImage? = null,
    val publisher: String = "",
    val adult: Boolean = false,
    val suggest: Boolean = false,
    val likesCount: Int = 0,
    val android: Boolean = false,
    val description: String = "",
    val updateContent: String = "",
    val videoLink: String? = null,
    val screenshots: List<PicaImage> = emptyList(),
    val commentsCount: Int = 0,
    val downloadsCount: Int = 0,
    val isLiked: Boolean = false,
    val androidLinks: List<String> = emptyList(),
    val androidSize: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
