package com.huankongyu.app.shizuku;

/**
 * Privileged user service running as shell (uid 2000) or root (uid 0) under Shizuku.
 * Transaction code 16777114 is reserved for destroy by the Shizuku API.
 */
interface IUserService {
    /** Executes a shell command and returns stdout, or stderr when exit code is non-zero. */
    String exec(String command) = 1;

    /** Must perform cleanup and call System.exit(); Shizuku invokes this on unbind/version change. */
    void destroy() = 16777114;
}
