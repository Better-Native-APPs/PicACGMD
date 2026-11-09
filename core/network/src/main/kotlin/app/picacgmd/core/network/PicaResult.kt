package app.picacgmd.core.network

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

val PicaJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

sealed interface PicaResult<out T> {
    data class Success<T>(val data: T) : PicaResult<T>
    data class Failure(val exception: PicaException) : PicaResult<Nothing>

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw exception
    }
}

open class PicaException(
    message: String,
    val code: Int? = null,
    val httpStatus: Int? = null,
    val error: String? = null,
    val detail: JsonElement? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class UnauthorizedException(
    message: String = "登录已过期",
    httpStatus: Int? = null,
    error: String? = null,
    detail: JsonElement? = null,
) : PicaException(message, 401, httpStatus, error, detail)

class PicaTransportException(cause: IOException) : PicaException("网络请求失败", cause = cause)

class PicaDecodingException(cause: Throwable) : PicaException("解析响应失败：格式错误", cause = cause)

@Serializable
data class PicaEnvelope(
    val code: Int? = null,
    val error: String = "",
    val message: String = "",
    val detail: JsonElement? = null,
    val data: JsonElement? = null,
)

/** Separates HTTP/business failures before attempting to decode endpoint-specific data. */
class PicaEnvelopeParser(private val json: Json = PicaJson) {
    fun parse(body: String, httpStatus: Int = 200): PicaResult<JsonElement> {
        val envelope = try {
            json.decodeFromString<PicaEnvelope>(body)
        } catch (exception: SerializationException) {
            return when {
                httpStatus == 401 -> PicaResult.Failure(UnauthorizedException(httpStatus = httpStatus))
                httpStatus !in 200..299 -> PicaResult.Failure(
                    PicaException("请求失败：HTTP $httpStatus", httpStatus = httpStatus),
                )
                else -> PicaResult.Failure(PicaDecodingException(exception))
            }
        }
        if (httpStatus == 401 || envelope.code == 401) {
            return PicaResult.Failure(
                UnauthorizedException(
                    envelope.message.ifBlank { "登录已过期" },
                    httpStatus, envelope.error, envelope.detail,
                ),
            )
        }
        if (httpStatus !in 200..299 || envelope.code != 200) {
            return PicaResult.Failure(
                PicaException(
                    envelope.message.ifBlank { "请求失败：${envelope.code ?: httpStatus}" },
                    envelope.code, httpStatus, envelope.error, envelope.detail,
                ),
            )
        }
        return PicaResult.Success(envelope.data ?: JsonNull)
    }
}
