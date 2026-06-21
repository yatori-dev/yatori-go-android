package com.yatori.android.engine.ai

/**
 * AI 题目消息构建器
 * 等价移植 Go que-core/aiq/QuestionMessage.go BuildAiQuestionMessage
 */
object QuestionMessageBuilder {

    fun build(type: QuestionType, content: String, options: List<String>): List<AiMessage> {
        val header = buildHeader(type, content, options)
        return when (type) {
            QuestionType.SINGLE   -> singleSystem() + AiMessage("user", header)
            QuestionType.MULTI    -> multiSystem()  + AiMessage("user", header)
            QuestionType.JUDGE    -> listOf(
                AiMessage("system", """接下来你只需要回答"正确"或者"错误"即可...格式：["正确"]"""),
                AiMessage("system", "就算你不知道选什么也随机选...无需回答任何解释！！！"),
                AiMessage("user", header)
            )
            QuestionType.FILL     -> listOf(
                AiMessage("system", """其中，"（answer_数字）"相关字样的地方是你需要填写答案的地方，回答时请严格遵循json格式：["答案1","答案2"]"""),
                AiMessage("system", "就算你不知道选什么也随机选...无需回答任何解释！！！"),
                AiMessage("user", header)
            )
            QuestionType.SHORT, QuestionType.ESSAY, QuestionType.TERM -> listOf(
                AiMessage("system", "最终输出必须是一个合法 JSON 数组格式：[\"答案内容\"]，数组中只能包含一个字符串元素，字符串内如需换行必须写为 \\n。"),
                AiMessage("user", header)
            )
            QuestionType.MATCH    -> listOf(
                AiMessage("system", "接下来你只需要以json格式回答选项对应内容即可，比如：[\"xxx->xxx\",\"xxx->xxx\"]"),
                AiMessage("user", header)
            )
        }
    }

    /** 等价 Go buildProblemHeader */
    private fun buildHeader(type: QuestionType, content: String, options: List<String>): String {
        val labels = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList()
        var s = "题目类型：${type.label}\n题目内容：\n$content\n"
        when (type) {
            QuestionType.SINGLE, QuestionType.MULTI, QuestionType.JUDGE, QuestionType.FILL ->
                options.forEachIndexed { i, opt -> s += "${labels.getOrElse(i) { '?' }}.$opt\n" }
            QuestionType.MATCH -> {
                s += "组别一：\n"; options.filter { it.startsWith("[1]") }.forEach { s += it.removePrefix("[1]") + "\n" }
                s += "组别二：\n"; options.filter { it.startsWith("[2]") }.forEach { s += it.removePrefix("[2]") + "\n" }
            }
            else -> {}
        }
        return s
    }

    private fun singleSystem() = listOf(AiMessage("system", """接下来无论出现任何题目，你都必须只回答题目中某个选项对应的内容，严格按以下格式输出 JSON 数组：["选项内容"]，不携带 A/B/C/D 前缀，不输出任何解释。"""))
    private fun multiSystem()  = listOf(AiMessage("system", """多选题，严格按以下格式输出 JSON 数组：["内容1","内容2"]，不携带 A/B/C/D 前缀，不输出任何解释。"""))
}

enum class QuestionType(val label: String) {
    SINGLE("单选题"), MULTI("多选题"), JUDGE("判断题"),
    FILL("填空题"), SHORT("简答题"), ESSAY("论述题"),
    TERM("名词解释"), MATCH("连线题");

    companion object {
        fun fromString(s: String) = entries.find { s.contains(it.label) } ?: SHORT
    }
}

private operator fun List<AiMessage>.plus(msg: AiMessage) = this + listOf(msg)
