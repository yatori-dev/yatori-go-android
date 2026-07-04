// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// Mirrored from the compose-miuix-ui example app (component.effect) via KernelSU.

package dev.yatori.mobile.app.ui.common.effect

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/** Preset blend-color stacks used by [top.yukonga.miuix.kmp.blur.textureBlur] over the flowing background. */
object ColorBlendToken {

    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
    )

    val Overlay_Thin_Light = listOf(
        BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
        BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
    )
}
