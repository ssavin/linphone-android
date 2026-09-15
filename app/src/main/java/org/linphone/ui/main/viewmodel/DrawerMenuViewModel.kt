/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.main.model.AccountModel
import org.linphone.ui.main.model.ShortcutModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event

class DrawerMenuViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Drawer Menu ViewModel]"
        private const val SELF_STATUS_URL = "https://kiwicall.ru/api/mobile/self-status"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    val accounts = MutableLiveData<ArrayList<AccountModel>>()

    val hideAddAccount = MutableLiveData<Boolean>()

    // "На линии / Не на линии" - self-service queue pause, same effect as the
    // toggle on the website (see kiwicall's routes/telephony.ts) but callable
    // from a SIP client that has no browser session, using the account's own
    // SIP credentials as Basic Auth. Only shown once a successful fetch
    // confirms this account actually has a KiwiCall operator extension -
    // hidden (not an error toast) for any other kind of SIP account, since
    // the feature simply doesn't apply to those.
    val onlineStatus = MutableLiveData<Boolean>()

    val onlineStatusAvailable = MutableLiveData<Boolean>()

    val onlineStatusUpdating = MutableLiveData<Boolean>()

    private val onlineStatusHttpClient = OkHttpClient()

    val hideRecordings = MutableLiveData<Boolean>()

    val hideSettings = MutableLiveData<Boolean>()

    val shortcuts = MutableLiveData<ArrayList<ShortcutModel>>()

    val hideQuitButton = MutableLiveData<Boolean>()

    val startAssistantEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val closeDrawerEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val openAccountProfileEvent: MutableLiveData<Event<AccountModel>> by lazy {
        MutableLiveData()
    }

    val defaultAccountChangedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val openLinkInBrowserEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            if (account == null) {
                Log.w("$TAG Default account is now null!")
            } else {
                Log.i(
                    "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] has been set as default"
                )
                for (model in accounts.value.orEmpty()) {
                    model.isDefault.postValue(model.account == account)
                }
                defaultAccountChangedEvent.postValue(
                    Event(account.params.identityAddress?.asStringUriOnly() ?: "")
                )
            }
        }

        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            Log.i(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] has been added to the Core"
            )
            computeAccountsList()
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            Log.i(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] has been removed from the Core"
            )
            computeAccountsList()
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (core.globalState == GlobalState.On) {
                accounts.value.orEmpty().forEach(AccountModel::destroy)

                Log.i("$TAG Global state is [${core.globalState}], reload accounts & shortcuts")
                computeAccountsList()
                computeShortcuts()
                fetchOnlineStatus()
            }
        }
    }

    init {
        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            hideRecordings.postValue(corePreferences.disableCallRecordings)
            hideSettings.postValue(corePreferences.hideSettings)

            checkIfKeepAliveServiceIsEnabled()

            computeAccountsList()
            computeShortcuts()
            fetchOnlineStatus()
        }
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
            accounts.value.orEmpty().forEach(AccountModel::destroy)
        }

        super.onCleared()
    }

    @UiThread
    fun closeDrawerMenu() {
        closeDrawerEvent.value = Event(true)
    }

    @UiThread
    fun addAccount() {
        startAssistantEvent.value = Event(true)
    }

    @UiThread
    fun updateAccountsList() {
        coreContext.postOnCoreThread {
            computeAccountsList()
        }
    }

    @UiThread
    fun refreshAccountsNotificationsCount() {
        coreContext.postOnCoreThread {
            for (model in accounts.value.orEmpty()) {
                model.computeNotificationsCount()
            }
        }
    }

    @WorkerThread
    fun checkIfKeepAliveServiceIsEnabled() {
        val useKeepAliveService = corePreferences.keepServiceAlive
        hideQuitButton.postValue(!useKeepAliveService)
        if (useKeepAliveService) {
            Log.i("$TAG Keep alive service is enabled, do not hide quit button")
        }
    }

    @WorkerThread
    private fun computeAccountsList() {
        accounts.value.orEmpty().forEach(AccountModel::destroy)

        val list = arrayListOf<AccountModel>()
        for (account in coreContext.core.accountList) {
            val model = AccountModel(account) { model ->
                // onClicked
                openAccountProfileEvent.postValue(Event(model))
            }
            list.add(model)

            if (account == coreContext.core.defaultAccount) {
                defaultAccountChangedEvent.postValue(
                    Event(account.params.identityAddress?.asStringUriOnly() ?: "")
                )
            }
        }
        accounts.postValue(list)

        val maxAccount = corePreferences.maxAccountsCount
        val accountsCount = list.size
        val maxAccountsReached = maxAccount in 1..accountsCount
        if (maxAccountsReached) {
            Log.w(
                "$TAG Max number of allowed accounts reached [$maxAccount], hiding add account button"
            )
        }
        hideAddAccount.postValue(maxAccountsReached)
    }

    /** (extension, password) from the default account's own auth info, or null if unavailable. */
    @WorkerThread
    private fun defaultAccountCredentials(): Pair<String, String>? {
        val account = coreContext.core.defaultAccount ?: return null
        val authInfo = account.findAuthInfo() ?: return null
        val username = authInfo.username
        val password = authInfo.password
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return Pair(username, password)
    }

    @WorkerThread
    private fun fetchOnlineStatus() {
        val credentials = defaultAccountCredentials()
        if (credentials == null) {
            onlineStatusAvailable.postValue(false)
            return
        }
        val (username, password) = credentials

        val request = Request.Builder()
            .url(SELF_STATUS_URL)
            .header("Authorization", Credentials.basic(username, password))
            .build()
        onlineStatusHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("$TAG Couldn't fetch online status: $e")
                onlineStatusAvailable.postValue(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.use { it.body?.string().orEmpty() }
                if (!response.isSuccessful) {
                    // Not a KiwiCall operator extension (or no extension at all) -
                    // hide the toggle rather than show an error, it just doesn't
                    // apply to this account.
                    onlineStatusAvailable.postValue(false)
                    return
                }
                try {
                    val json = JSONObject(bodyString)
                    onlineStatus.postValue(json.optBoolean("online", true))
                    onlineStatusAvailable.postValue(true)
                } catch (e: Exception) {
                    Log.w("$TAG Couldn't parse online status response: $e")
                    onlineStatusAvailable.postValue(false)
                }
            }
        })
    }

    @UiThread
    fun toggleOnlineStatus() {
        val newValue = onlineStatus.value != true
        onlineStatusUpdating.value = true
        coreContext.postOnCoreThread {
            val credentials = defaultAccountCredentials()
            if (credentials == null) {
                onlineStatusUpdating.postValue(false)
                onlineStatusAvailable.postValue(false)
                return@postOnCoreThread
            }
            val (username, password) = credentials

            val body = JSONObject().apply { put("online", newValue) }
                .toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(SELF_STATUS_URL)
                .header("Authorization", Credentials.basic(username, password))
                .post(body)
                .build()
            onlineStatusHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("$TAG Couldn't update online status: $e")
                    onlineStatusUpdating.postValue(false)
                    showFormattedRedToast(
                        AppUtils.getString(R.string.drawer_menu_online_status_network_error),
                        R.drawable.warning_circle
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    onlineStatusUpdating.postValue(false)
                    val bodyString = response.use { it.body?.string().orEmpty() }
                    val json = try {
                        JSONObject(bodyString)
                    } catch (e: Exception) {
                        null
                    }
                    if (!response.isSuccessful || json == null) {
                        val serverMessage = json?.optString("error").orEmpty().ifEmpty {
                            AppUtils.getString(R.string.drawer_menu_online_status_network_error)
                        }
                        showFormattedRedToast(serverMessage, R.drawable.warning_circle)
                        return
                    }
                    onlineStatus.postValue(json.optBoolean("online", newValue))
                }
            })
        }
    }

    @WorkerThread
    private fun computeShortcuts() {
        val config = coreContext.core.config
        val shortcutsList = arrayListOf<ShortcutModel>()
        val shortcutsCount = config.getInt("ui", "shortcut_count", 0)
        if (shortcutsCount > 0) {
            Log.i("$TAG Found [$shortcutsCount] shortcuts to display")
            for (i in 0 until shortcutsCount) {
                val key = "shortcut_$i"
                val label = config.getString(key, "name", "").orEmpty()
                val iconUrl = config.getString(key, "icon", "").orEmpty()
                val linkUrl = config.getString(key, "link", "").orEmpty()
                val shortcutModel = ShortcutModel(
                    label,
                    iconUrl,
                    linkUrl
                ) { link ->
                    openLinkInBrowserEvent.postValue(Event(link))
                }
                shortcutsList.add(shortcutModel)
            }
        }
        shortcuts.postValue(shortcutsList)
    }
}
