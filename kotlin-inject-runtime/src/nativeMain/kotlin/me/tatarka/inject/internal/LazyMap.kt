package me.tatarka.inject.internal

import co.touchlab.stately.collections.IsoMutableMap
import kotlin.native.concurrent.SharedImmutable

@SharedImmutable
private val NULL = Any()

actual class LazyMap {
    private val map = IsoMutableMap<String, Any>()

    actual fun <T> get(key: String, init: () -> T): T {
        return run {
            var result = map[key]
            if (result == null) {
                result = init() ?: NULL
                map[key] = result
            }
            coerceResult(result)
        }
    }

    private fun <T> coerceResult(result: Any): T {
        return if (result === NULL) {
            null
        } else {
            result
        } as T
    }
}
