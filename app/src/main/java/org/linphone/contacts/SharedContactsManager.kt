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
package org.linphone.contacts

import androidx.annotation.WorkerThread
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Core
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.tools.Log

/**
 * Shared (call-center wide) address book: colleagues and CRM clients of the
 * operator's clinic, pulled from kiwicall.ru (/api/mobile/contacts) with the
 * default account's own SIP credentials and mirrored into a dedicated
 * read-only friend list. The server returns a version token, so polling is
 * cheap when nothing changed.
 */
class SharedContactsManager {
    companion object {
        private const val TAG = "[Shared Contacts]"
        private const val CONTACTS_URL = "https://kiwicall.ru/api/mobile/contacts"
        private const val SHARED_FRIEND_LIST = "kiwicall_shared"
        private const val REF_KEY_PREFIX = "kiwicall:"
        private const val START_DELAY_MS = 5_000L
        private const val RETRY_INTERVAL_MS = 30_000L // until the first successful sync
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L
    }

    private val httpClient = OkHttpClient()
    private var version = ""
    private var syncing = false
    private var started = false

    @WorkerThread
    fun onCoreStarted() {
        if (started) return
        started = true
        scheduleNext(START_DELAY_MS)
    }

    @WorkerThread
    private fun scheduleNext(delay: Long) {
        coreContext.postOnCoreThreadDelayed({ sync() }, delay)
    }

    @WorkerThread
    private fun nextDelay(): Long {
        return if (version.isEmpty()) RETRY_INTERVAL_MS else REFRESH_INTERVAL_MS
    }

    /** (extension, password) from the default account's own auth info, or null. */
    @WorkerThread
    private fun defaultAccountCredentials(core: Core): Pair<String, String>? {
        val authInfo = core.defaultAccount?.findAuthInfo() ?: return null
        val username = authInfo.username
        // The SDK clears the plaintext password after a successful REGISTER;
        // the server accepts the HA1 digest as well (see sip-provisioning.ts).
        val password = authInfo.password.orEmpty().ifEmpty { authInfo.ha1.orEmpty() }
        if (username.isNullOrEmpty() || password.isEmpty()) return null
        return Pair(username, password)
    }

    @WorkerThread
    private fun sync() {
        if (syncing) return
        val core = coreContext.core
        val credentials = defaultAccountCredentials(core)
        if (credentials == null) {
            scheduleNext(nextDelay())
            return
        }
        val (username, password) = credentials

        val url = CONTACTS_URL.toHttpUrl().newBuilder().apply {
            if (version.isNotEmpty()) addQueryParameter("version", version)
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, password))
            .build()

        syncing = true
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("$TAG Couldn't fetch shared contacts: $e")
                finish(success = false)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string().orEmpty() }
                if (!response.isSuccessful) {
                    Log.w("$TAG Server answered [${response.code}], shared contacts not available for this account")
                    finish(success = false)
                    return
                }
                try {
                    val json = JSONObject(body)
                    if (json.optBoolean("unchanged", false)) {
                        finish(success = true)
                    } else {
                        coreContext.postOnCoreThread { core ->
                            apply(core, username, json)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("$TAG Couldn't parse shared contacts response: $e")
                    finish(success = false)
                }
            }
        })
    }

    private fun finish(success: Boolean) {
        coreContext.postOnCoreThread {
            syncing = false
            if (!success && version.isEmpty()) Log.i("$TAG Will retry shortly")
            scheduleNext(nextDelay())
        }
    }

    private fun makeFriend(core: Core, refKey: String, name: String, phones: List<String>): Friend {
        val friend = core.createFriend()
        friend.refKey = refKey
        friend.name = name
        for (phone in phones) {
            friend.addPhoneNumber(phone)
        }
        return friend
    }

    /** Mirrors the server payload into the read-only shared friend list. */
    @WorkerThread
    private fun apply(core: Core, ownExtension: String, json: JSONObject) {
        val friends = arrayListOf<Friend>()

        val employees = json.optJSONArray("employees")
        for (i in 0 until (employees?.length() ?: 0)) {
            val row = employees!!.getJSONObject(i)
            val extension = row.optString("extension")
            if (extension.isEmpty() || extension == ownExtension) continue // not ourselves
            val name = row.optString("name")
            friends.add(
                makeFriend(core, "${REF_KEY_PREFIX}emp:$extension", "$name ($extension)", listOf(extension))
            )
        }

        val clients = json.optJSONArray("clients")
        for (i in 0 until (clients?.length() ?: 0)) {
            val row = clients!!.getJSONObject(i)
            val phones = arrayListOf<String>()
            val phone = row.optString("phone")
            if (phone.isNotEmpty()) phones.add(phone)
            val extraPhones = row.optJSONArray("extraPhones")
            for (j in 0 until (extraPhones?.length() ?: 0)) {
                val extra = extraPhones!!.optString(j)
                if (extra.isNotEmpty() && !phones.contains(extra)) phones.add(extra)
            }
            if (phones.isEmpty()) continue
            val name = row.optString("name").ifEmpty { phones[0] }
            friends.add(makeFriend(core, "${REF_KEY_PREFIX}cli:${row.optInt("id")}", name, phones))
        }

        val list = core.getFriendListByName(SHARED_FRIEND_LIST) ?: core.createFriendList()
        val created = list.displayName.isNullOrEmpty()
        if (created) {
            Log.i("$TAG Friend list [$SHARED_FRIEND_LIST] didn't exist yet, let's create it")
            list.isDatabaseStorageEnabled = true
            list.type = FriendList.Type.Default
            list.displayName = SHARED_FRIEND_LIST
            core.addFriendList(list)
        }
        // A read-only list refuses changes, so lift the flag only while syncing.
        list.isReadOnly = false
        try {
            if (created) {
                for (friend in friends) {
                    list.addLocalFriend(friend)
                }
            } else {
                list.synchronizeFriendsWith(friends.toTypedArray())
            }
        } finally {
            list.isReadOnly = true
        }
        // Presence SUBSCRIBEs for thousands of CRM clients would only be noise.
        list.isSubscriptionsEnabled = false

        version = json.optString("version")
        Log.i("$TAG Shared contacts synchronized: [${friends.size}] entries, version [$version]")
        // Refresh avatar caches and contact lists so new names show up.
        coreContext.contactsManager.onNativeContactsLoaded()

        syncing = false
        scheduleNext(nextDelay())
    }
}
