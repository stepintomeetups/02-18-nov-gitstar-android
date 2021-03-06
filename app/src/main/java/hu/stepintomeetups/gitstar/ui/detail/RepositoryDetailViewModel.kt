/*
 * Created by Tamás Szincsák on 2018-11-04.
 * Copyright (c) 2018 Tamás Szincsák.
 */

package hu.stepintomeetups.gitstar.ui.detail

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.gson.JsonSyntaxException
import hu.stepintomeetups.gitstar.api.GitHubService
import hu.stepintomeetups.gitstar.api.entities.Commit
import hu.stepintomeetups.gitstar.api.asBooleanDeferred
import hu.stepintomeetups.gitstar.api.asUnitDeferred
import hu.stepintomeetups.gitstar.ui.common.CoroutineViewModel
import hu.stepintomeetups.gitstar.ui.common.DataRequestState
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.IOException

class RepositoryDetailViewModel(private val ownerName: String, private val  repositoryName: String) : CoroutineViewModel() {
    val data = MutableLiveData<DataRequestState<RepoDetails>>()

    private var initialJob: Job? = null

    init {
        data.value = DataRequestState.Loading()

        initialJob = launch(Dispatchers.IO) {
            try {
                val deferredRepo = GitHubService.instance.getRepositoryDetails(ownerName, repositoryName)
                val deferredReadme = GitHubService.instance.getReadmeForRepository(ownerName, repositoryName)
                val deferredCommits = GitHubService.instance.getCommitsForRepository(ownerName, repositoryName)
                val deferredIsStarred = GitHubService.instance.isRepoStarred(ownerName, repositoryName).asBooleanDeferred()

                val repo = deferredRepo.await()
                val readme = try { deferredReadme.await() } catch (e: HttpException) { if (e.code() == 404) null else throw e }
                val commits = try { deferredCommits.await() } catch (e: HttpException) { if (e.code() == 409) emptyList<Commit>() else throw e }
                val isStarred = deferredIsStarred.await()

                withContext(Dispatchers.Main) {
                    val details = RepoDetails.initFrom(repo, readme, commits.subList(0, 5.coerceAtMost(commits.size)), isStarred)

                    data.value = DataRequestState.Success(details)
                }
            } catch (e: Throwable) {
                when (e) {
                    is IOException -> data.postValue(DataRequestState.Error(e))
                    is HttpException -> data.postValue(DataRequestState.Error(e))
                    is JsonSyntaxException -> { e.printStackTrace(); data.postValue(DataRequestState.Error(e)) }
                    else -> throw e
                }
            } finally {
                initialJob = null
            }
        }
    }

    fun starRepository() = async<Unit>(Dispatchers.IO + NonCancellable) {
        try {
            GitHubService.instance.starRepo(ownerName, repositoryName).asUnitDeferred().await()
        } catch (e: Throwable) {
            when (e) {
                is IOException -> throw e
                is HttpException, is JsonSyntaxException -> throw IOException(e)
                else -> throw e
            }
        }

        initialJob?.join()

        (data.value as? DataRequestState.Success)?.data?.isStarred?.postValue(true)

        /*val isStarred = GitHubService.instance.isRepoStarred(ownerName, repositoryName).asBooleanDeferred().await()

        withContext(Dispatchers.Main) {
            (data.value as? DataRequestState.Success)?.data?.isStarred?.value = isStarred
        }*/
    }

    fun unstarRepository() = async<Unit>(Dispatchers.IO + NonCancellable) {
        try {
            GitHubService.instance.unstarRepo(ownerName, repositoryName).asUnitDeferred().await()
        } catch (e: Throwable) {
            when (e) {
                is IOException -> throw e
                is HttpException, is JsonSyntaxException -> throw IOException(e)
                else -> throw e
            }
        }

        initialJob?.join()

        (data.value as? DataRequestState.Success)?.data?.isStarred?.postValue(false)
    }

    class Factory(private val ownerName: String, private val  repositoryName: String) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return RepositoryDetailViewModel(ownerName, repositoryName) as T
        }
    }
}