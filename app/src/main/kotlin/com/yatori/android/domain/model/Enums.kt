package com.yatori.android.domain.model

/** 支持的平台枚举，对应 Go 项目 global.AccountTypeStr */
enum class Platform(val code: String, val displayName: String) {
    YINGHUA("YINGHUA", "英华学堂"),
    CANGHUI("CANGHUI", "仓辉实训"),
    ENAEA("ENAEA", "学习公社"),
    XUEXITONG("XUEXITONG", "学习通"),
    CQIE("CQIE", "重庆工程学院"),
    KETANGX("KETANGX", "码上研训"),
    WELEARN("WELEARN", "随行课堂"),
    ICVE("ICVE", "智慧职教"),
    QSXT("QSXT", "青书学堂"),
    HQKJ("HQKJ", "海旗科技");

    companion object {
        fun fromCode(code: String) = entries.find { it.code == code } ?: YINGHUA
    }
}

/** 刷课模式，对应 config.yaml videoModel */
enum class VideoMode(val value: Int, val label: String) {
    NONE(0, "不刷"),
    NORMAL(1, "普通模式"),
    VIOLENT(2, "暴力模式 / 多课程模式"),
    DERED(3, "去红模式 / 多任务点模式");

    companion object { fun fromValue(v: Int) = entries.find { it.value == v } ?: NORMAL }
}

/** 自动考试模式，对应 autoExam */
enum class ExamMode(val value: Int, val label: String) {
    OFF(0, "不考试"),
    AI(1, "AI考试"),
    EXTERNAL(2, "外部题库"),
    CX_FREE_AI(3, "免费AI（学习通）");

    companion object { fun fromValue(v: Int) = entries.find { it.value == v } ?: OFF }
}

/** AI提供商，对应 aiType */
enum class AiProvider(val code: String, val displayName: String) {
    TONGYI("TONGYI", "通义千问"),
    SILICON("SILICON", "硅基流动"),
    DOUBAO("DOUBAO", "豆包"),
    CHATGLM("CHATGLM", "智谱"),
    XINGHUO("XINGHUO", "星火"),
    OPENAI("OPENAI", "ChatGPT"),
    DEEPSEEK("DEEPSEEK", "DeepSeek"),
    METAAI("METAAI", "秘塔AI"),
    OTHER("OTHER", "其他");

    companion object { fun fromCode(c: String) = entries.find { it.code == c } ?: TONGYI }
}
