// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The network request [ScopeWifi] has filed, by its callback [C], and the state it has reached:
 * the bookkeeping without Android, so its rules can be tested. Callbacks may arrive after their
 * request was released; each change checks, under the lock, that its callback is still the
 * current one and otherwise does nothing. Safe from any thread.
 */
internal class RequestSlot<C : Any> {
    private val lock = Any()
    private var current: C? = null  // guarded by lock
    private val _state = MutableStateFlow<ScopeWifi.State>(ScopeWifi.State.Idle)
    val state: StateFlow<ScopeWifi.State> = _state.asStateFlow()
    // Separate from state, so learning the identity never restarts the session.
    private val _identity = MutableStateFlow<ScopeWifi.Identity?>(null)
    val identity: StateFlow<ScopeWifi.Identity?> = _identity.asStateFlow()

    /**
     * Run [request] (filing it with Android; it may throw) and make [cb] current, under the lock,
     * so a fast first callback waits until [cb] is current. False, running nothing, while a
     * request is already filed.
     */
    fun file(cb: C, request: () -> Unit): Boolean = synchronized(lock) {
        if (current != null) return false
        request()
        current = cb
        _identity.value = null
        _state.value = ScopeWifi.State.Requesting
        true
    }

    /** A request could not be filed; ignored while another one is. */
    fun failed(reason: String) = synchronized(lock) {
        if (current == null) _state.value = ScopeWifi.State.Failed(reason)
    }

    /** Let go of the request: Idle. Returns its callback, to unregister, or null if none was filed. */
    fun release(): C? = synchronized(lock) {
        current.also {
            current = null
            _state.value = ScopeWifi.State.Idle
            _identity.value = null
        }
    }

    fun isCurrent(cb: C): Boolean = synchronized(lock) { current === cb }

    /** [cb]'s request reached [state], unless it is no longer current. */
    fun update(cb: C, state: ScopeWifi.State) = synchronized(lock) {
        if (current === cb) _state.value = state
    }

    /** [cb]'s network is [id]; true if that is news (the current request, and changed). */
    fun identify(cb: C, id: ScopeWifi.Identity): Boolean = synchronized(lock) {
        if (current !== cb || _identity.value == id) return false
        _identity.value = id
        true
    }

    /**
     * [cb]'s network was lost: the request is let go of, and the state is [ScopeWifi.State.Lost]
     * until the next [file] or [release]. True if it was the current request, once: the caller
     * then unregisters [cb]. A late loss after [release] changes nothing.
     */
    fun lost(cb: C): Boolean = synchronized(lock) {
        if (current !== cb) return false
        current = null
        _identity.value = null
        _state.value = ScopeWifi.State.Lost
        true
    }

    /** [cb]'s request found nothing; Android has already released it. */
    fun unavailable(cb: C, target: ScopeWifi.Target) = synchronized(lock) {
        if (current !== cb) return@synchronized
        current = null
        _state.value = ScopeWifi.State.Unavailable(target)
    }
}
