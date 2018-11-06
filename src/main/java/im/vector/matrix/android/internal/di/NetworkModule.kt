package im.vector.matrix.android.internal.di

import im.vector.matrix.android.internal.network.AccessTokenInterceptor
import im.vector.matrix.android.internal.network.NetworkConnectivityChecker
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.context.ModuleDefinition
import org.koin.dsl.module.Module
import org.koin.dsl.module.module
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

class NetworkModule : Module {

    override fun invoke(): ModuleDefinition = module {

        single {
            AccessTokenInterceptor(get())
        }

        single {
            val logger = HttpLoggingInterceptor.Logger { message -> Timber.v(message) }
            val interceptor = HttpLoggingInterceptor(logger)
            interceptor.level = HttpLoggingInterceptor.Level.BASIC
            interceptor
        }

        single {
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(get() as AccessTokenInterceptor)
                    .addInterceptor(get() as HttpLoggingInterceptor)
                    .build()
        }

        single {
            MoshiProvider.providesMoshi()
        }

        single {
            MoshiConverterFactory.create(get()) as Converter.Factory
        }

        single {
            NetworkConnectivityChecker(get())
        }

        factory {
            Retrofit.Builder()
                    .client(get())
                    .addConverterFactory(get())
        }

    }.invoke()
}