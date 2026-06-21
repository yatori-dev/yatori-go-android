package com.yatori.android.engine

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SimpleCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(rootDomain(url.host)) { CopyOnWriteArrayList<Cookie>() }
        cookies.forEach { new -> list.removeIf { it.name == new.name }; list.add(new) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[rootDomain(url.host)] ?: emptyList()

    fun forceSave(host: String, cookies: List<Cookie>) {
        val list = store.getOrPut(rootDomain(host)) { CopyOnWriteArrayList<Cookie>() }
        cookies.forEach { new -> list.removeIf { it.name == new.name }; list.add(new) }
    }

    private fun rootDomain(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }
}
