package com.manfis.astramatic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.manfis.astramatic.library.RemoteAuth
import com.manfis.astramatic.library.SmbThumbnailFetcher
import okhttp3.OkHttpClient

class AstraApp : Application(), SingletonImageLoader.Factory {

    /** Превью: кадры видео с телефона и картинки Darkframe (с токеном устройства). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val auth = RemoteAuth(this)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token = auth.tokenFor("${request.url.host}:${request.url.port}")
                chain.proceed(
                    if (token == null) request else request.newBuilder().header("Authorization", "Bearer $token").build()
                )
            }
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(VideoFrameDecoder.Factory())
                add(SmbThumbnailFetcher.Factory(this@AstraApp))
            }
            .build()
    }
}
