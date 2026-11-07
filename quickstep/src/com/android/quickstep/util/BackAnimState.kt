/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.util

import android.animation.AnimatorSet
import android.content.Context
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAnimationRunner.AnimationResult
import com.android.launcher3.LauncherState
import com.android.launcher3.anim.AnimatorListeners.forEndCallback
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.util.RunnableList

/** Interface to represent animation for back to Launcher transition */
interface BackAnimState {

    fun addOnAnimCompleteCallback(r: Runnable)

    fun applyToAnimationResult(result: AnimationResult, c: Context)

    fun start(stateManager: StateManager<LauncherState, Launcher>)

    fun cancel() {}
}

class AnimatorBackState @JvmOverloads constructor(
    private val springAnim: RectFSpringAnim?,
    private val anim: AnimatorSet?,
    private val registerStateAnimation: Boolean = true,
) :
    BackAnimState {

    override fun addOnAnimCompleteCallback(r: Runnable) {
        val animWait = RunnableList()
        if (!registerStateAnimation && springAnim != null && anim != null) {
            var remaining = 2
            val onEnd = Runnable {
                remaining--
                if (remaining == 0) animWait.executeAllAndDestroy()
            }
            springAnim.addAnimatorListener(forEndCallback(onEnd))
            anim.addListener(forEndCallback(onEnd))
        } else {
            springAnim?.addAnimatorListener(forEndCallback(animWait::executeAllAndDestroy))
                ?: anim?.addListener(forEndCallback(animWait::executeAllAndDestroy))
                ?: animWait.executeAllAndDestroy()
        }
        animWait.add(r)
    }

    override fun applyToAnimationResult(result: AnimationResult, c: Context) {
        if (!registerStateAnimation && springAnim?.isCos == true) {
            anim?.addListener(forEndCallback(Runnable { springAnim.detachWindow() }))
        }
        if (springAnim?.isNothing == true) {
            result.setAnimation(anim, c, null, false, springAnim)
        } else {
            result.setAnimation(anim, c)
        }
    }

    override fun start(stateManager: StateManager<LauncherState, Launcher>) {
        if (anim != null && registerStateAnimation) {
            stateManager.setCurrentAnimation(anim)
        }
        anim?.start()
    }

    override fun cancel() {
        if (springAnim?.isNothing == true) {
            springAnim.cancel()
            anim?.cancel()
            return
        }
        if (registerStateAnimation) return
        if (springAnim?.isCos == true) springAnim.detachWindow() else springAnim?.cancel()
        anim?.cancel()
    }
}

class AlreadyStartedBackAnimState(private val onEndCallback: RunnableList) : BackAnimState {

    override fun addOnAnimCompleteCallback(r: Runnable) {
        onEndCallback.add(r)
    }

    override fun applyToAnimationResult(result: AnimationResult, c: Context) {
        addOnAnimCompleteCallback(result::onAnimationFinished)
    }

    override fun start(stateManager: StateManager<LauncherState, Launcher>) {}
}
