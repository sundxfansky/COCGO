package com.coc.suncode.core.util.basic



/**
 * Utility for running shell commands.
 */
object RunShell {
    /**
     * Executes a shell command and returns the output as a list of strings.
     * @param isCheckIsPlaying if true, waits until the script is playing before executing
     */
    suspend fun run(cmd: String, isCheckIsPlaying: Boolean = true): List<String> {
        if (isCheckIsPlaying) {
            waitForPlay()
        }
        return ServerShell.exec(cmd).out
    }

    /**
     * Executes a shell command without returning the output.
     * @param isCheckIsPlaying if true, waits until the script is playing before executing
     */
    suspend fun runNoOutput(cmd: String, isCheckIsPlaying: Boolean = true) {
        if (isCheckIsPlaying) {
            waitForPlay()
        }
        ServerShell.exec(cmd)
    }

    /**
     * Executes a shell command and returns the first non-blank line of output.
     * @param isCheckIsPlaying if true, waits until the script is playing before executing
     */
    suspend fun runAndGetFirst(cmd: String, isCheckIsPlaying: Boolean = true): String {
        if (isCheckIsPlaying) {
            waitForPlay()
        }
        return run(cmd, isCheckIsPlaying).firstOrNull { it.isNotBlank() } ?: ""
    }
}