package com.example.dance.ui.theme

import androidx.compose.ui.graphics.Color

// Unified blue palette (2026-09-08): every colored UI element uses one of
// these so the app reads as a single blue family — dark schemes use the bright
// shades (readable on black, e.g. the player overlay), light schemes the deep ones.
val Blue80 = Color(0xFFA9C7FF)
val BlueGrey80 = Color(0xFFBFC6DC)
val Sky80 = Color(0xFFA8CDEB)

val Blue40 = Color(0xFF2A5CBF)
val BlueGrey40 = Color(0xFF505C7A)
val Sky40 = Color(0xFF2C6389)

/**
 * The single interactive accent used on the dark player overlay (timeline
 * played portion, loop/beat/segment-edit active state, recording indicator).
 * Matches [Blue80] so the player and the themed screens agree.
 */
val AccentBlue = Color(0xFF4A90E2)
