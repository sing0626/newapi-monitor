package com.newapimonitor.data.api

import com.newapimonitor.domain.AppError
import com.newapimonitor.domain.ErrorKind

/** 錯誤工廠（對應 js apiError(kind, message, status)） */
class ApiException(
    val kind: ErrorKind,
    override val message: String,
    val status: Int? = null,
) : RuntimeException(message) {
    fun toAppError(): AppError = AppError(kind, message, status)
}
