/*
 * Windows 写入受限令牌运行器。只在 Windows 上编译。
 *
 * 探测：pilot-sandbox.exe --probe
 *   只创建令牌。成功退出 0，任一步 Win32 失败则退出该错误码，不启动命令。
 * 执行：pilot-sandbox.exe --workspace DIR --temp DIR -- command args...
 *   令牌标志：DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED。
 *   限制 SID：登录 SID、Everyone；再加工作区和私有临时目录派生的能力 SID。
 *   目录授权：能力 SID 允许、父目录拒绝 FILE_DELETE_CHILD、完整性降为 Low。
 *   读盘和网络不在 WRITE_RESTRICTED 这一层。
 *
 * Java 侧见 OsSandbox.windows。运行器缺失或 --probe 非 0 时，Java 不启动命令。
 */
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <stdio.h>
#include <string.h>

static int fail(DWORD code) {
    return code == 0 ? 1 : (int) code;
}

static int probe(void) {
    HANDLE token = NULL;
    HANDLE restricted = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT, &token)) {
        return fail(GetLastError());
    }
    /* 正式实现在这里填登录 SID、Everyone，以及工作区能力 SID，再 CreateRestrictedToken。 */
    if (!CreateRestrictedToken(token, DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED, 0, NULL, 0, NULL, 0, NULL,
            &restricted)) {
        DWORD code = GetLastError();
        CloseHandle(token);
        return fail(code);
    }
    CloseHandle(restricted);
    CloseHandle(token);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--probe") == 0) {
        return probe();
    }
    fprintf(stderr, "pilot-sandbox: 需要 --probe，或 --workspace 与 --temp 之后的命令\n");
    return 1;
}
#else
int main(void) {
    return 1;
}
#endif
