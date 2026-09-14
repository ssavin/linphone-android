/*
 * Copyright (c) 2026 KiwiCall.
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
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event

/**
 * Login screen backed by the KiwiCall web account (email + password) instead
 * of manual SIP fields. On success the backend returns a Linphone remote
 * provisioning URL, which is applied through the exact same mechanism the
 * QR code scanner already uses (see QrCodeViewModel.onQrcodeFound) — this
 * class never touches SIP credentials directly.
 */
class KiwiCallLoginViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[KiwiCall Login ViewModel]"
        private const val LOGIN_URL = "https://kiwicall.ru/api/mobile/login"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    val email = MutableLiveData<String>()

    val password = MutableLiveData<String>()

    val showPassword = MutableLiveData<Boolean>()

    val loginEnabled = MediatorLiveData<Boolean>()

    val loginInProgress = MutableLiveData<Boolean>()

    val accountLoggedInEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val loginErrorEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    private val httpClient = OkHttpClient()

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onConfiguringStatus(core: Core, status: ConfiguringState, message: String?) {
            if (status == ConfiguringState.Failed) {
                Log.e("$TAG Failure applying remote provisioning: $message")
                core.removeListener(this)
                loginInProgress.postValue(false)
                loginErrorEvent.postValue(
                    Event(AppUtils.getString(R.string.assistant_kiwicall_login_network_error))
                )
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) {
                core.removeListener(this)
                loginInProgress.postValue(false)
                if (core.accountList.isEmpty()) {
                    Log.w("$TAG Provisioning applied but no account was configured")
                    loginErrorEvent.postValue(
                        Event(AppUtils.getString(R.string.assistant_kiwicall_login_network_error))
                    )
                } else {
                    Log.i("$TAG Account configured from KiwiCall login, leaving assistant")
                    accountLoggedInEvent.postValue(Event(true))
                }
            }
        }
    }

    init {
        showPassword.value = false
        loginInProgress.value = false

        loginEnabled.addSource(email) { loginEnabled.value = isLoginButtonEnabled() }
        loginEnabled.addSource(password) { loginEnabled.value = isLoginButtonEnabled() }
    }

    @UiThread
    fun toggleShowPassword() {
        showPassword.value = showPassword.value == false
    }

    @UiThread
    private fun isLoginButtonEnabled(): Boolean {
        return email.value.orEmpty().trim().isNotEmpty() && password.value.orEmpty().isNotEmpty()
    }

    @UiThread
    fun login() {
        val emailValue = email.value.orEmpty().trim()
        val passwordValue = password.value.orEmpty()
        if (emailValue.isEmpty() || passwordValue.isEmpty()) return

        loginInProgress.value = true

        val body = JSONObject().apply {
            put("email", emailValue)
            put("password", passwordValue)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(LOGIN_URL).post(body).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("$TAG Network error while calling [$LOGIN_URL]: $e")
                loginInProgress.postValue(false)
                loginErrorEvent.postValue(
                    Event(AppUtils.getString(R.string.assistant_kiwicall_login_network_error))
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.use { it.body?.string().orEmpty() }
                val json = try {
                    JSONObject(bodyString)
                } catch (e: Exception) {
                    Log.e("$TAG Couldn't parse server response: $e")
                    loginInProgress.postValue(false)
                    loginErrorEvent.postValue(
                        Event(AppUtils.getString(R.string.assistant_kiwicall_login_network_error))
                    )
                    return
                }

                if (!response.isSuccessful || json.has("error")) {
                    val serverMessage = json.optString("error", "HTTP ${response.code}")
                    Log.e("$TAG Login failed: $serverMessage")
                    loginInProgress.postValue(false)
                    loginErrorEvent.postValue(Event(serverMessage))
                    return
                }

                if (json.optBoolean("requiresClinicChoice", false)) {
                    // Rare case (one email shared by several clinics) — not handled by this
                    // screen yet, same as the desktop app; ask the user to sort it out with
                    // their admin instead of showing a confusing partial login.
                    Log.w("$TAG Account belongs to several clinics, not supported here yet")
                    loginInProgress.postValue(false)
                    loginErrorEvent.postValue(
                        Event(AppUtils.getString(R.string.assistant_kiwicall_login_network_error))
                    )
                    return
                }

                val provisioningUrl = json.optString("provisioningUrl", "")
                if (provisioningUrl.isEmpty()) {
                    Log.e("$TAG Server response has no provisioningUrl: $bodyString")
                    loginInProgress.postValue(false)
                    loginErrorEvent.postValue(
                        Event(AppUtils.getString(R.string.assistant_kiwicall_login_network_error))
                    )
                    return
                }

                Log.i("$TAG Got provisioning URL, applying it and restarting the Core")
                coreContext.postOnCoreThread { core ->
                    core.addListener(coreListener)
                    core.provisioningUri = provisioningUrl
                    core.stop()
                    core.start()
                }
            }
        })
    }
}
