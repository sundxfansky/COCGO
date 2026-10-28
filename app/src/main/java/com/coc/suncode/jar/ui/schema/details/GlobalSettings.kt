package com.coc.suncode.jar.ui.schema.details

import com.coc.suncode.jar.ui.schema.SettingDef

object GlobalSettings {
    val CONFIG_COUNT = SettingDef("config_count", "配置文件数量", "2", "GLOBAL_SETTINGS")
    val ACCOUNT_COUNT = SettingDef("account_count", "多开账号数量", "2", "GLOBAL_SETTINGS")
    val AUTO_START = SettingDef("auto_start", "开机自启(仅部分设备有效)", 1, "GLOBAL_SETTINGS")
    val EXTRACT_VERSION = SettingDef("extract_version", "提取存档版本选择", "0", "GLOBAL_SETTINGS")
    val EXTRACT_CN = SettingDef("extract_cn", "提取国服存档到此序号", "1", "GLOBAL_SETTINGS")
    val EXTRACT_GLOBAL = SettingDef("extract_global", "提取国际服存档到此序号", "1", "GLOBAL_SETTINGS")

    val EMAIL = SettingDef("email", "邮箱", "free@sun", "GLOBAL_SETTINGS")
    val PASSWORD = SettingDef("password", "密码", "free_for_testing", "GLOBAL_SETTINGS")
    val ENTER_GAME_TIMER = SettingDef("enter_game_timer", "进入游戏计时", "80", "GLOBAL_SETTINGS")
    val DELAY_MULTIPLIER = SettingDef(
        "delay_multiplier", "延时倍率(低性能设备建议设置1.5-2.5)", "1", "GLOBAL_SETTINGS"
    )
    val RECORD_PROGRESS = SettingDef("record_progress", "记录账号进度", 1, "GLOBAL_SETTINGS")
    val AUTO_UPDATE = SettingDef("auto_update", "自动更新", "2", "GLOBAL_SETTINGS")
    val BATCH_CREATE_ACCOUNT = SettingDef("batch_create_account", "批量创号", 0, "GLOBAL_SETTINGS")
    val CREATE_START_ID = SettingDef("create_start_id", "创号开始序号", 1, "GLOBAL_SETTINGS")
    val CREATE_END_ID = SettingDef("create_end_id", "创号结束序号", 10, "GLOBAL_SETTINGS")
    val CREATE_PREFIX = SettingDef("create_prefix", "创号前缀", "太阳", "GLOBAL_SETTINGS")
    val ADD_SUFFIX_SETTING = SettingDef("add_suffix_setting", "添加数字后缀", 0, "GLOBAL_SETTINGS")
    val CREATE_GEM_BUILD = SettingDef("create_gem_build", "创号时宝石秒建筑", 0, "GLOBAL_SETTINGS")
    val GEM_COUNT = SettingDef("gem_count", "宝石数量", "", "GLOBAL_SETTINGS")
    val AFTER_KICK_OPTION = SettingDef("after_kick_option", "顶号后选项", "1", "GLOBAL_SETTINGS")
    val AFTER_KICK_COOLDOWN_MINUTES = SettingDef("after_kick_cooldown_minutes", "被顶账号冷却时间（分钟）", "3", "GLOBAL_SETTINGS")
    val DEVICE_REMARK = SettingDef("device_remark", "设备备注", "", "GLOBAL_SETTINGS")
    val RUNTIME_SCREENSHOT = SettingDef("runtime_screenshot", "运行时截图", 0, "GLOBAL_SETTINGS")
    // Only the account selector is ordinary configuration; QQ grants remain host-private.
    val QQ_ACCOUNT_SLOT = SettingDef("qq_account_slot", "QQ 授权库序号", "1", "GLOBAL_SETTINGS")
    // Persist the migration version so account-owned profiles are never copied from legacy shared profiles again.
    val ACCOUNT_PROFILES_VERSION = SettingDef("account_profiles_version", "账号独立配置版本", "0", "GLOBAL_SETTINGS")
    // Parameters of the account-list tool that copies one account's world profiles to an account range.
    val SYNC_SOURCE_ACCOUNT = SettingDef("sync_source_account", "源账号", "1", "GLOBAL_SETTINGS")
    val SYNC_INCLUDE_BUILDER_BASE = SettingDef("sync_include_builder_base", "同时同步夜世界", 1, "GLOBAL_SETTINGS")
    val SYNC_TARGET_START = SettingDef("sync_target_start", "目标起始账号", "1", "GLOBAL_SETTINGS")
    val SYNC_TARGET_END = SettingDef("sync_target_end", "目标结束账号", "1", "GLOBAL_SETTINGS")

    val all = listOf(
        CONFIG_COUNT,
        ACCOUNT_COUNT,
        AUTO_START,
        EXTRACT_VERSION,
        EXTRACT_CN,
        EXTRACT_GLOBAL,
        EMAIL,
        PASSWORD,
        ENTER_GAME_TIMER,
        DELAY_MULTIPLIER,
        RECORD_PROGRESS,
        AUTO_UPDATE,
        BATCH_CREATE_ACCOUNT,
        CREATE_START_ID,
        CREATE_END_ID,
        CREATE_PREFIX,
        ADD_SUFFIX_SETTING,
        CREATE_GEM_BUILD,
        GEM_COUNT,
        AFTER_KICK_OPTION,
        AFTER_KICK_COOLDOWN_MINUTES,
        DEVICE_REMARK,
        RUNTIME_SCREENSHOT,
        QQ_ACCOUNT_SLOT,
        ACCOUNT_PROFILES_VERSION,
        SYNC_SOURCE_ACCOUNT,
        SYNC_INCLUDE_BUILDER_BASE,
        SYNC_TARGET_START,
        SYNC_TARGET_END
    )
}

object AccountSettings {
    val ISOPEN = SettingDef("isopen", "开启状态", 0, "ACCOUNT_SETTINGS")
    val REMARK = SettingDef("remark", "备注", "", "ACCOUNT_SETTINGS")
    val GAME_VERSION = SettingDef("game_version", "游戏版本", "0", "ACCOUNT_SETTINGS")
    val ACCOUNT_CONFIG = SettingDef("account_config", "配置文件序号", "1", "ACCOUNT_SETTINGS")
    val START_METHOD = SettingDef("start_method", "登录方式", "0", "ACCOUNT_SETTINGS")
    // Account N always reads grant N from the global library; the value is pinned to the account index.
    val QQ_AUTHORIZATION_SLOT = SettingDef("qq_authorization_slot", "QQ 授权序号", "1", "ACCOUNT_SETTINGS")
    val CN_PATH = SettingDef("cn_path", "国服存档序号", "", "ACCOUNT_SETTINGS")
    val GLOBAL_PATH = SettingDef("global_path", "国际服存档序号", "", "ACCOUNT_SETTINGS")
    val DATA_CONTENT = SettingDef("data_content", "数据号内容", "", "ACCOUNT_SETTINGS")

    val all = listOf(
        ISOPEN, REMARK, GAME_VERSION, ACCOUNT_CONFIG, START_METHOD, QQ_AUTHORIZATION_SLOT, CN_PATH, GLOBAL_PATH, DATA_CONTENT
    )
}
