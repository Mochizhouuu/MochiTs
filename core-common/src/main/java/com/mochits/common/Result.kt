package com.mochits.common

/**
 * Wrapper hasil operasi umum (dipakai lintas module: imaging, inpaint-ml, project, dll)
 * agar caller tidak perlu bergantung pada exception handling ad-hoc.
 */
sealed class OperationResult<out T> {
    data class Success<T>(val data: T) : OperationResult<T>()
    data class Failure(val error: Throwable, val message: String? = null) : OperationResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): OperationResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): OperationResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (Throwable) -> Unit): OperationResult<T> {
        if (this is Failure) action(error)
        return this
    }
}
