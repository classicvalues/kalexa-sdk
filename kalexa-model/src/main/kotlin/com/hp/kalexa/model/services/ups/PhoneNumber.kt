package com.hp.kalexa.model.services.ups

import com.fasterxml.jackson.annotation.JsonProperty


data class PhoneNumber(
        val countryCode: String? = null,
        val phoneNumber: String? = null)