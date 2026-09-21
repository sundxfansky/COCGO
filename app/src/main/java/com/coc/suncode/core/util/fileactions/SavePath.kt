package com.coc.suncode.core.util.fileactions

object SavePath {
    fun isValid(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." &&
        value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
}
