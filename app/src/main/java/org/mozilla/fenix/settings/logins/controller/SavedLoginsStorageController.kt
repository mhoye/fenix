/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins.controller

import android.content.Context
import android.util.Log
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.storage.Login
import mozilla.components.service.sync.logins.InvalidRecordException
import mozilla.components.service.sync.logins.LoginsStorageException
import mozilla.components.service.sync.logins.NoSuchRecordException
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.settings.logins.LoginsAction
import org.mozilla.fenix.settings.logins.LoginsFragmentStore
import org.mozilla.fenix.settings.logins.fragment.EditLoginFragmentDirections
import org.mozilla.fenix.settings.logins.mapToSavedLogin

/**
 * Controller for all saved logins interactions with the password storage component
 */
open class SavedLoginsStorageController(
    private val context: Context,
    private val viewLifecycleScope: CoroutineScope,
    private val navController: NavController,
    private val loginsFragmentStore: LoginsFragmentStore
) {

    private suspend fun getLogin(loginId: String): Login? =
        context.components.core.passwordsStorage.get(loginId)

    fun delete(loginId: String) {
        var deleteLoginJob: Deferred<Boolean>? = null
        val deleteJob = viewLifecycleScope.launch(Dispatchers.IO) {
            deleteLoginJob = async {
                context.components.core.passwordsStorage.delete(loginId)
            }
            deleteLoginJob?.await()
            withContext(Dispatchers.Main) {
                navController.popBackStack(R.id.savedLoginsFragment, false)
            }
        }
        deleteJob.invokeOnCompletion {
            if (it is CancellationException) {
                deleteLoginJob?.cancel()
            }
        }
    }

    fun save(loginId: String, usernameText: String, passwordText: String) {
        var saveLoginJob: Deferred<Unit>? = null
        viewLifecycleScope.launch(Dispatchers.IO) {
            saveLoginJob = async {
                // must retrieve from storage to get the httpsRealm and formActionOrigin
                val oldLogin = context.components.core.passwordsStorage.get(loginId)

                // Update requires a Login type, which needs at least one of
                // httpRealm or formActionOrigin
                val loginToSave = Login(
                    guid = loginId,
                    origin = oldLogin?.origin!!,
                    username = usernameText, // new value
                    password = passwordText, // new value
                    httpRealm = oldLogin.httpRealm,
                    formActionOrigin = oldLogin.formActionOrigin
                )

                save(loginToSave)
                syncAndUpdateList(loginToSave)
            }
            saveLoginJob?.await()
            withContext(Dispatchers.Main) {
                val directions =
                    EditLoginFragmentDirections.actionEditLoginFragmentToLoginDetailFragment(
                        loginId
                    )
                navController.navigate(directions)
            }
        }
        saveLoginJob?.invokeOnCompletion {
            if (it is CancellationException) {
                saveLoginJob?.cancel()
            }
        }
    }

    private suspend fun save(loginToSave: Login) {
        try {
            context.components.core.passwordsStorage.update(loginToSave)
        } catch (loginException: LoginsStorageException) {
            when (loginException) {
                is NoSuchRecordException,
                is InvalidRecordException -> {
                    Log.e("Edit login",
                        "Failed to save edited login.", loginException)
                }
                else -> Log.e("Edit login",
                    "Failed to save edited login.", loginException)
            }
        }
    }

    private fun syncAndUpdateList(updatedLogin: Login) {
        val login = updatedLogin.mapToSavedLogin()
        loginsFragmentStore.dispatch(
            LoginsAction.UpdateLoginsList(
                listOf(login)
            )
        )
    }

    fun findPotentialDuplicates(loginId: String) {
        var deferredLogin: Deferred<List<Login>>? = null
        // What scope should be used here?
        val fetchLoginJob = viewLifecycleScope.launch(Dispatchers.IO) {
            deferredLogin = async {
                val login = getLogin(loginId)
                context.components.core.passwordsStorage.getPotentialDupesIgnoringUsername(login!!)
            }
            val fetchedDuplicatesList = deferredLogin?.await()
            fetchedDuplicatesList?.let { list ->
                withContext(Dispatchers.Main) {
                    val savedLoginList = list.map { it.mapToSavedLogin() }
                    loginsFragmentStore.dispatch(
                        LoginsAction.ListOfDupes(
                            savedLoginList
                        )
                    )
                }
            }
        }
        fetchLoginJob.invokeOnCompletion {
            if (it is CancellationException) {
                deferredLogin?.cancel()
            }
        }
    }

    fun fetchLoginDetails(loginId: String) {
        var deferredLogin: Deferred<List<Login>>? = null
        val fetchLoginJob = viewLifecycleScope.launch(Dispatchers.IO) {
            deferredLogin = async {
                context.components.core.passwordsStorage.list()
            }
            val fetchedLoginList = deferredLogin?.await()

            fetchedLoginList?.let {
                withContext(Dispatchers.Main) {
                    val login = fetchedLoginList.filter {
                        it.guid == loginId
                    }.first()
                    loginsFragmentStore.dispatch(
                        LoginsAction.UpdateCurrentLogin(
                            login.mapToSavedLogin()
                        )
                    )
                }
            }
        }
        fetchLoginJob.invokeOnCompletion {
            if (it is CancellationException) {
                deferredLogin?.cancel()
            }
        }
    }

    fun handleLoadAndMapLogins() {
        var deferredLogins: Deferred<List<Login>>? = null
        val fetchLoginsJob = viewLifecycleScope.launch(Dispatchers.IO) {
            deferredLogins = async {
                context.components.core.passwordsStorage.list()
            }
            val logins = deferredLogins?.await()
            logins?.let {
                withContext(Dispatchers.Main) {
                    loginsFragmentStore.dispatch(
                        LoginsAction.UpdateLoginsList(
                            logins.map { it.mapToSavedLogin() })
                    )
                }
            }
        }
        fetchLoginsJob.invokeOnCompletion {
            if (it is CancellationException) {
                deferredLogins?.cancel()
            }
        }
    }
}
