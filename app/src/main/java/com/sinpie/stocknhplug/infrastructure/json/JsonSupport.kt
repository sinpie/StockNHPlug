package com.sinpie.stocknhplug.infrastructure.json

import org.json.JSONArray
import org.json.JSONObject

/** 제공자 어댑터 전용 JSON 도우미. 도메인/응용 계층으로 전달하지 않는다. */
fun json(vararg pairs: Pair<String, Any>) =
    JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
