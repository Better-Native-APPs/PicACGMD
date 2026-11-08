package app.picacgmd.core.domain.image

import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Counts the wire response before transparent decompression; Content-Length describes those bytes. */
class ImageLoadProgressInterceptor(private val registry: ImageLoadProgressRegistry) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Keep redirects attached to the PageImage.Remote URL rather than the redirect target.
        val url = chain.call().request().url.toString()
        val awaiting = registry.awaiting(url)
        val response = try {
            chain.proceed(chain.request())
        } catch (exception: IOException) {
            awaiting?.finish(completed = false)
            throw exception
        }
        if (response.isRedirect && response.header("Location") != null) return response
        val body = response.body
        val transfer = registry.begin(url, body.contentLength()) ?: return response
        return response.newBuilder().body(ProgressBody(body, transfer)).build()
    }

    /** Also releases registrations when a call fails before a body exists, or uses the HTTP cache. */
    internal fun eventListenerFactory(): EventListener.Factory = EventListener.Factory {
        object : EventListener() {
            private var awaiting: ImageLoadProgressRegistry.Awaiting? = null

            override fun callStart(call: Call) {
                awaiting = registry.awaiting(call.request().url.toString())
            }

            override fun callEnd(call: Call) { awaiting?.finish(completed = true) }

            override fun callFailed(call: Call, ioe: IOException) { awaiting?.finish(completed = false) }
        }
    }

    private class ProgressBody(
        private val delegate: ResponseBody,
        private val transfer: ImageLoadProgressRegistry.Transfer,
    ) : ResponseBody() {
        private val countingSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                private var bytesRead = 0L

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = try {
                        super.read(sink, byteCount)
                    } catch (exception: IOException) {
                        transfer.finish(completed = false)
                        throw exception
                    }
                    if (read == -1L) {
                        transfer.finish(completed = true)
                    } else if (read > 0) {
                        bytesRead += read
                        transfer.report(bytesRead)
                    }
                    return read
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        transfer.finish(completed = false)
                    }
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = countingSource
    }
}
