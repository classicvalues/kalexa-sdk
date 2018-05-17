package com.hp.kalexa.model.payload.print

import com.fasterxml.jackson.annotation.JsonTypeName

@JsonTypeName("WebPage")
class WebPage(title: String = "", description: String = "", url: String = "", version: String = "1.0") : Print.PrintType(title, description, url, version)