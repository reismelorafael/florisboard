/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.lifecycle

import android.inputmethodservice.InputMethodService
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle-aware InputMethodService optimized for Android 15 ARM64 stability.
 * 
 * Key optimizations:
 * - Thread-safe lifecycle state management using atomic operations
 * - Defensive null checks for Android 15 window handling
 * - Proper lifecycle event sequencing to prevent zombie states
 * - Resource cleanup on destruction to prevent memory leaks
 */
open class LifecycleInputMethodService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner
{
    // Atomic state tracking for thread-safe lifecycle management
    private val lifecycleState = AtomicInteger(STATE_INITIAL)
    private val isDestroyed = AtomicBoolean(false)
    
    companion object {
        // Deterministic state constants using bitwise values
        private const val STATE_INITIAL = 0
        private const val STATE_CREATED = 1
        private const val STATE_STARTED = 2
        private const val STATE_RESUMED = 3
        private const val STATE_PAUSED = 4
        private const val STATE_STOPPED = 5
        private const val STATE_DESTROYED = 6
    }
    
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val store by lazy { ViewModelStore() }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }

    val uiScope: CoroutineScope
        get() = lifecycle.coroutineScope

    final override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        if (isDestroyed.get()) return
        
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            // Defensive error handling for Android 15 compatibility
            android.util.Log.e("LifecycleIMS", "Error restoring saved state", e)
        }
        
        // Deterministic lifecycle event sequencing with error handling
        if (lifecycleState.compareAndSet(STATE_INITIAL, STATE_CREATED)) {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            } catch (e: Exception) {
                android.util.Log.e("LifecycleIMS", "Error handling ON_CREATE event", e)
            }
        }
        if (lifecycleState.compareAndSet(STATE_CREATED, STATE_STARTED)) {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            } catch (e: Exception) {
                android.util.Log.e("LifecycleIMS", "Error handling ON_START event", e)
            }
        }
    }

    /**
     * Installs view tree owners with defensive null handling for Android 15.
     * Uses safe navigation to prevent NPE on window access.
     */
    fun installViewTreeOwners() {
        if (isDestroyed.get()) return
        
        try {
            val decorView = window?.window?.decorView ?: run {
                android.util.Log.w("LifecycleIMS", "Unable to install view tree owners: decorView is null")
                return
            }
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        } catch (e: Exception) {
            // Defensive error handling for Android 15 compatibility
            android.util.Log.e("LifecycleIMS", "Error installing view tree owners", e)
        }
    }

    @CallSuper
    override fun onWindowShown() {
        super.onWindowShown()
        if (isDestroyed.get()) return
        
        // Use loop with CAS for atomic state transition with timeout protection
        var attempts = 0
        val maxAttempts = 10 // Prevent infinite loop on Android 15
        
        while (attempts < maxAttempts) {
            val currentState = lifecycleState.get()
            if (currentState >= STATE_STARTED && currentState < STATE_RESUMED) {
                if (lifecycleState.compareAndSet(currentState, STATE_RESUMED)) {
                    try {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    } catch (e: Exception) {
                        // Defensive error handling for Android 15 compatibility
                        android.util.Log.e("LifecycleIMS", "Error handling ON_RESUME event", e)
                    }
                    break
                }
                // CAS failed, retry with fresh state
                attempts++
            } else if (currentState == STATE_PAUSED) {
                if (lifecycleState.compareAndSet(STATE_PAUSED, STATE_RESUMED)) {
                    try {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    } catch (e: Exception) {
                        // Defensive error handling for Android 15 compatibility
                        android.util.Log.e("LifecycleIMS", "Error handling ON_RESUME event", e)
                    }
                    break
                }
                // CAS failed, retry with fresh state
                attempts++
            } else {
                // State already resumed or invalid, no action needed
                break
            }
        }
        
        // Log warning if we hit the retry limit
        if (attempts >= maxAttempts) {
            android.util.Log.w("LifecycleIMS", "CAS retry limit reached in onWindowShown, current state: ${lifecycleState.get()}")
        }
    }

    @CallSuper
    override fun onWindowHidden() {
        super.onWindowHidden()
        if (isDestroyed.get()) return
        
        // Use CAS for atomic state transition with error handling
        if (lifecycleState.compareAndSet(STATE_RESUMED, STATE_PAUSED)) {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            } catch (e: Exception) {
                // Defensive error handling for Android 15 compatibility
                android.util.Log.e("LifecycleIMS", "Error handling ON_PAUSE event", e)
            }
        }
    }

    @CallSuper
    override fun onDestroy() {
        // Mark as destroyed first to prevent concurrent operations
        if (!isDestroyed.compareAndSet(false, true)) {
            super.onDestroy()
            return
        }
        
        // Use loop with CAS for atomic state transitions with timeout protection
        var attempts = 0
        val maxAttempts = 10 // Prevent infinite loop on Android 15
        
        while (attempts < maxAttempts) {
            val currentState = lifecycleState.get()
            
            // Handle proper state transitions based on current state
            if (currentState == STATE_RESUMED) {
                if (lifecycleState.compareAndSet(STATE_RESUMED, STATE_PAUSED)) {
                    try {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    } catch (e: Exception) {
                        // Defensive error handling for Android 15 compatibility
                        android.util.Log.e("LifecycleIMS", "Error handling ON_PAUSE event in onDestroy", e)
                    }
                    // Continue to stop state
                }
                attempts++
            } else if (currentState >= STATE_STARTED && currentState < STATE_STOPPED) {
                if (lifecycleState.compareAndSet(currentState, STATE_STOPPED)) {
                    try {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    } catch (e: Exception) {
                        // Defensive error handling for Android 15 compatibility
                        android.util.Log.e("LifecycleIMS", "Error handling ON_STOP event in onDestroy", e)
                    }
                    break
                }
                attempts++
            } else {
                break
            }
        }
        
        // Log warning if we hit the retry limit
        if (attempts >= maxAttempts) {
            android.util.Log.w("LifecycleIMS", "CAS retry limit reached in onDestroy, current state: ${lifecycleState.get()}")
        }
        
        lifecycleState.set(STATE_DESTROYED)
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (e: Exception) {
            // Defensive error handling for Android 15 compatibility
            android.util.Log.e("LifecycleIMS", "Error handling ON_DESTROY event", e)
        }
        
        // Clear ViewModelStore to release resources
        try {
            store.clear()
        } catch (e: Exception) {
            // Defensive error handling for cleanup operations
            android.util.Log.e("LifecycleIMS", "Error clearing ViewModelStore", e)
        }
        
        super.onDestroy()
    }
}
