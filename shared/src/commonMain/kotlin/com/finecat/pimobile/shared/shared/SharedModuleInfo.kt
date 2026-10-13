package com.finecat.pimobile.shared

/**
 * KMP 模块的最小稳定入口。
 *
 * 后续迁移会在此模块内增加 protocol、domain、data 和 usecase，
 * Android UI 只依赖公开 facade，不直接耦合内部 Reducer 实现。
 */
object SharedModuleInfo {
    const val NAME = "PiMobileShared"
    const val SCHEMA_VERSION = 1

    val sourceSets: List<String> = listOf("commonMain", "androidMain")

    fun summary(): String = "$NAME · schema $SCHEMA_VERSION"
}
