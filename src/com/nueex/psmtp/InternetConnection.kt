/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal const val NO_INTERNET_CONNECTION = "No internet connection"

private fun Context.internetCapabilities(): NetworkCapabilities? {
    val manager = getSystemService(ConnectivityManager::class.java)
    return manager.getNetworkCapabilities(manager.activeNetwork)
}

internal fun Context.hasInternetRoute() =
    internetCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

internal fun Context.hasValidatedInternet() =
    internetCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

internal inline fun <T> Context.internetRequest(run: () -> T): T = try {
    run()
} catch (error: IOException) {
    if (generateSequence(error as Throwable) { it.cause }.any {
            it is UnknownHostException || it is SocketException || it is SocketTimeoutException
        } && !hasValidatedInternet()) throw TransferError(NO_INTERNET_CONNECTION)
    throw error
}

internal fun Context.requireInternetRoute() {
    if (!hasInternetRoute()) throw TransferError(NO_INTERNET_CONNECTION)
}
