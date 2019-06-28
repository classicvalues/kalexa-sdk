/*
 * Copyright 2018 HP Development Company, L.P.
 * SPDX-License-Identifier: MIT
 */

package com.hp.kalexa.model.directive

import com.fasterxml.jackson.annotation.JsonTypeName

@JsonTypeName("VoicePlayer.Speak")
class VoicePlayerSpeakDirective(
    val speech: String
) : Directive()
