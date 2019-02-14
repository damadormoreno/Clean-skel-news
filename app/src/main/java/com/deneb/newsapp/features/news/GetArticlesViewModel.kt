package com.deneb.newsapp.features.news

import android.arch.lifecycle.MutableLiveData
import com.deneb.newsapp.core.platform.BaseViewModel
import javax.inject.Inject

class GetArticlesViewModel
@Inject constructor(private val getArticles: GetArticles): BaseViewModel() {

    var articles: MutableLiveData<List<ArticleView>> = MutableLiveData()

    fun getArticles() = getArticles.invoke(
        GetArticles.Params()) {
            it.either(::handleFailure, ::handleArticlesList)
        }

    private fun handleArticlesList(articles: List<Article>) {
        this.articles.value = articles.map {
            it.toArticleView()
        }
    }

}