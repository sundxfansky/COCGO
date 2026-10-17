package com.coc.suncode.core.system.checkpermissions

enum class RootStatus {
    CHECKING,
    ROOT_DENIED,
    PERMISSION_DENIED,
    INITIALIZING_LOCAL,
    LOCAL_ERROR,
    GRANTED
}
