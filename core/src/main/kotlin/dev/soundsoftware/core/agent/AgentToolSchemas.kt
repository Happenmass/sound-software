package dev.soundsoftware.core.agent

import kotlinx.serialization.json.*

object AgentToolSchemas {
    private fun tool(name: String, description: String, fields: Map<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "function"); putJsonObject("function") {
            put("name", name); put("description", description); putJsonObject("parameters") {
                put("type", "object"); put("additionalProperties", false)
                putJsonObject("properties") {
                    for ((key, field) in fields) {
                        if (key in required) put(key, field)
                        else putJsonObject(key) {
                            put("type", JsonArray(listOf(field.getValue("type"), JsonPrimitive("null"))))
                            put("description", field.getValue("description").jsonPrimitive.content + " Pass null to leave this unset.")
                        }
                    }
                }
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
        }
    }
    private fun field(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
    private val page = mapOf("limit" to field("integer", "1..5，默认3"), "offset" to field("integer", "分页偏移，默认0"))
    private val filters = mapOf("recordingId" to field("string", "可选：限定录音ID"), "afterMs" to field("integer", "可选：录音创建时间起点Unix毫秒，包含"),
        "beforeMs" to field("integer", "可选：录音创建时间终点Unix毫秒，不包含"), "speaker" to field("string", "可选：说话人名称包含"))
    val json = buildJsonArray {
        add(tool("list_recordings", "分页查询录音目录，支持标题、日期、人名。无逐字稿也可列出。", page + filters + ("title" to field("string", "可选标题包含"))))
        add(tool("search_recordings", "跨录音词法检索原文片段，返回可引用证据；中文用短关键词。多个词项为AND。", page + filters + ("query" to field("string", "搜索关键词")), listOf("query")))
        add(tool("read_segments", "按录音ID分页读取原文片段与证据，超长片段拆成连续部分，offset为部分序号。", page + mapOf("recordingId" to field("string", "录音ID")), listOf("recordingId")))
        add(tool("list_todos", "查询已生成待办线索，不是原文证据；返回recordingId后用read_segments核实。", page + filters + mapOf("done" to field("boolean", "可选完成状态"), "owner" to field("string", "可选负责人包含"))))
        add(tool("list_highlights", "跨录音分页读取用户双击标重点的相邻原文，带位置和可引用证据。位置只说明用户关注，不证明句子含义；可继续read_segments补充上下文。",page+filters))
    }
}
