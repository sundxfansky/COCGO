package com.coc.suncode.loadjar.features

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class FeatureSpec(val id: String, val name: String, val entry: String, val prefixes: List<String>) {
    fun owns(className: String): Boolean = prefixes.any {
        if (it.endsWith('.')) className.startsWith(it)
        else className == it || className.startsWith(it + "$" )
    }

    companion object {
        // The same catalog controls runtime isolation and Gradle packaging.
        fun read(context: Context): List<FeatureSpec> = context.assets.open("feature-plugins.json").reader().use {
            Gson().fromJson(it, object : TypeToken<List<FeatureSpec>>() {}.type)
        }
    }
}
