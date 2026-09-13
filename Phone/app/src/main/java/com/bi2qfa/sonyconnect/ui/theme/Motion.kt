package com.bi2qfa.sonyconnect.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
























@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object Motion {

    
    @Composable
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.fastSpatialSpec()

    
    @Composable
    fun <T> spatialDefault(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.defaultSpatialSpec()

    
    @Composable
    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.slowSpatialSpec()

    
    @Composable
    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.fastEffectsSpec()

    
    @Composable
    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.defaultEffectsSpec()

    
    @Composable
    fun <T> effectsSlow(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.slowEffectsSpec()
}
