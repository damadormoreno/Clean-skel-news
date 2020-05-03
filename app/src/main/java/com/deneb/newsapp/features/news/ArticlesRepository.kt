package com.deneb.newsapp.features.news

import android.content.SharedPreferences
import com.deneb.newsapp.core.exception.Failure
import com.deneb.newsapp.core.functional.Either
import com.deneb.newsapp.core.functional.Error
import com.deneb.newsapp.core.functional.Result
import com.deneb.newsapp.core.functional.Success
import com.deneb.newsapp.core.platform.NetworkHandler
import com.deneb.newsapp.core.platform.ServiceKOs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import retrofit2.Call
import java.util.*

interface ArticlesRepository {

    fun getArticles(): Either<Failure, List<Article>>
    fun getArticlesFlow(): Flow<Result<List<Article>>>
    fun getRemoteArticles(): Either<Failure, List<Article>>
    fun add(article: ArticleEntity): Either<Failure, Any>

    class Network
        (
        private val networkHandler: NetworkHandler,
        private val service: ArticlesService,
        private val local: ArticlesLocal,
        private val fetch: FetchLocal,
        private val shared: SharedPreferences
    ) : ArticlesRepository {

        override fun getRemoteArticles(): Either<Failure, List<Article>> {
            return when (networkHandler.isConnected) {
                true -> request(
                    service.getArticles(),
                    {
                        val articlesList: List<ArticleEntity> = it.articleEntities

                        //Guardamos en base de datos la fecha de la actualización
                        fetch.addFetchDate(FetchEntity(0, Date().time))

                        //También se pueden utilizar las shared para guardar este dato:
                        shared.edit().putLong("time", Date().time).apply()

                        addAllArticles(articlesList)
                        articlesList.map { articleEntity ->
                            articleEntity.toArticle()
                        }

                    },
                    NewsEntity(emptyList(), "", 0)
                )
                false, null -> Either.Left(Failure.NetworkConnection())
            }
        }

        private suspend fun getRemoteArticlesFlow(): Result<List<Article>> {
            return when (networkHandler.isConnected) {
                true -> service.getArticlesFlow().run {
                    if (isSuccessful && body() != null) {
                        //Guardamos en base de datos la fecha de la actualización
                        fetch.addFetchDate(FetchEntity(0, Date().time))

                        //También se pueden utilizar las shared para guardar este dato:
                        shared.edit().putLong("time", Date().time).apply()

                        addAllArticles(body()!!.articleEntities)

                        Success(body()!!.articleEntities.map { it.toArticle() })
                    } else {
                        Error(Throwable("Time out"))
                    }
                }
                false, null -> Error(Throwable("network connection"))
            }
        }

        override fun getArticles(): Either<Failure, List<Article>> {
            return try {
                val time = shared.getLong("time", 0L)
                val articles = local.getArticles()
                val fetchDate: FetchEntity? = fetch.getFetchDate(0)
                if (articles.isNullOrEmpty() || fetchDate == null || isFetchCurrentNeeded(fetchDate.fetchData)) {
                    getRemoteArticles()
                } else {
                    Either.Right(local.getArticles().map {
                        it.toArticle()
                    })
                }
            } catch (e: Exception) {
                Either.Left(Failure.CustomError(ServiceKOs.DATABASE_ACCESS_ERROR, e.message))
            }
        }

        override fun getArticlesFlow(): Flow<Result<List<Article>>> {

            return flow {
                val time = shared.getLong("time", 0L)
                val articles = local.getArticles()
                val fetchDate: FetchEntity? = fetch.getFetchDate(0)
                if (articles.isNullOrEmpty() || fetchDate == null || isFetchCurrentNeeded(fetchDate.fetchData)) {
                    emit(getRemoteArticlesFlow())
                } else {
                    emit(Success(local.getArticles().map { it.toArticle() }))
                }
            }.catch {
                Error(Throwable(ServiceKOs.DATABASE_ACCESS_ERROR.toString()))
            }.flowOn(Dispatchers.IO)

        }

        override fun add(article: ArticleEntity): Either<Failure, Any> {
            return try {
                Either.Right(local.addArticle(article))
            } catch (e: Exception) {
                Either.Left(Failure.CustomError(ServiceKOs.DATABASE_ACCESS_ERROR, e.message))
            }
        }

        private fun addAllArticles(articles: List<ArticleEntity>) {
            for (article in articles) {
                local.addArticle(article)
            }
        }

        private fun <T, R> request(
            call: Call<T>,
            transform: (T) -> R,
            default: T
        ): Either<Failure, R> {
            return try {
                val response = call.execute()
                when (response.isSuccessful) {
                    true -> Either.Right(transform((response.body() ?: default)))
                    false -> Either.Left(Failure.ServerError())
                }
            } catch (exception: Throwable) {
                Either.Left(Failure.ServerError())
            }
        }

        private fun <T, R> requestFlow(call: Call<T>, transform: (T) -> R, default: T): Flow<T> {
            val response = call.execute()
            return flow {
                when (response.isSuccessful) {
                    true -> (emit((response.body() ?: default)))
                    false -> emit(default)
                }
            }
        }

        private fun isFetchCurrentNeeded(lastFetchTime: Long): Boolean {
            val oneMinuteInMillis = 60000
            val thirtyMinutesAgo = Date(lastFetchTime - (30 * oneMinuteInMillis)).time
            return Date(lastFetchTime).before(Date(thirtyMinutesAgo))
        }
    }
}