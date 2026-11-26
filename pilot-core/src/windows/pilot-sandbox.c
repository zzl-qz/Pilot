/*
 * Windows 写入受限令牌运行器。只在 Windows 上编译。
 *
 * 探测：pilot-sandbox.exe --probe
 *   只创建令牌。成功退出 0，任一步 Win32 失败则退出该错误码，不启动命令。
 *
 * 执行：pilot-sandbox.exe --workspace DIR [--temp DIR] [--write DIR]... -- command args...
 *   令牌标志：DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED，限制 SID 只有一个
 *   由工作区路径派生的能力 SID。写访问检查只看这个 SID：授权过的目录（工作区、私有临时目录、
 *   构建缓存）可写，其余位置一律拒绝；读盘和网络不走这一层，仍是系统原样权限。
 *
 *   授权用继承 ACE：新建的文件和子目录自动可写。已存在的条目在首次授权时逐个补 ACE。
 *   能力 SID 由工作区路径稳定派生，目录里已经带上这个 SID 就跳过整棵子树，不在每次启动时重复遍历。
 *
 * Java 侧见 OsSandbox.windows。运行器缺失或 --probe 非 0 时，Java 不启动命令。
 */
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <shellapi.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

/* 路径缓冲够用即可：单帧占几百字节时，深目录递归才不会把栈吃穿。 */
#define PILOT_PATH_CHARS 1024
#define PILOT_MAX_WRITES 16
#define PILOT_MAX_COMMAND 32768

static int fail(DWORD code) {
    return code == 0 ? 1 : (int) code;
}

/* FNV-1a 逐字符混入，把一个路径摊成四个 31 位子授权值。 */
static DWORD mix(const wchar_t *text, DWORD seed) {
    DWORD hash = 2166136261u ^ seed;
    for (const wchar_t *cursor = text; *cursor != 0; ++cursor) {
        hash = (hash ^ (DWORD)(*cursor & 0xFF)) * 16777619u;
        hash = (hash ^ (DWORD)((*cursor >> 8) & 0xFF)) * 16777619u;
    }
    return hash & 0x7FFFFFFF;
}

/* 同一个工作区每次都得到同一个能力 SID，授权结果可以跨启动复用。 */
static PSID capability_sid(const wchar_t *workspace) {
    SID_IDENTIFIER_AUTHORITY authority = {SECURITY_NT_AUTHORITY};
    PSID sid = NULL;
    if (!AllocateAndInitializeSid(&authority, 5, mix(workspace, 0), mix(workspace, 1), mix(workspace, 2),
            mix(workspace, 3), 0x504C54, 0, 0, 0, &sid)) {
        return NULL;
    }
    return sid;
}

static HANDLE restricted_token(PSID capability) {
    HANDLE current = NULL;
    HANDLE restricted = NULL;
    SID_AND_ATTRIBUTES only[1];
    only[0].Sid = capability;
    only[0].Attributes = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ASSIGN_PRIMARY,
            &current)) {
        return NULL;
    }
    if (!CreateRestrictedToken(current, DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED, 0, NULL, 0, NULL, 1,
            only, &restricted)) {
        restricted = NULL;
    }
    CloseHandle(current);
    return restricted;
}

static BOOL acl_has(PACL dacl, PSID sid, BOOL deny) {
    BYTE type = deny ? ACCESS_DENIED_ACE_TYPE : ACCESS_ALLOWED_ACE_TYPE;
    if (dacl == NULL) {
        return FALSE;
    }
    for (WORD index = 0; index < dacl->AceCount; ++index) {
        LPVOID entry = NULL;
        if (!GetAce(dacl, index, &entry)) {
            continue;
        }
        ACCESS_ALLOWED_ACE *ace = (ACCESS_ALLOWED_ACE *)entry;
        if (ace->Header.AceType == type && EqualSid((PSID)&ace->SidStart, sid)) {
            return TRUE;
        }
    }
    return FALSE;
}

/* 同一条 ACE 已经写进 DACL：启动时每次都要先看这一眼，已经在的条目不再重复写。 */
static BOOL acl_has_on_path(const wchar_t *path, PSID sid, BOOL deny) {
    BOOL present = FALSE;
    PACL dacl = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    if (GetNamedSecurityInfoW((LPWSTR)path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &dacl, NULL,
            &descriptor) == ERROR_SUCCESS) {
        present = acl_has(dacl, sid, deny);
        LocalFree(descriptor);
    }
    return present;
}

/* 拒绝 ACE 会被 SetEntriesInAcl 排到允许 ACE 之前，所以 deny 优先于同一 SID 的允许。 */
static BOOL apply_ace(const wchar_t *path, PSID sid, BOOL deny, BOOL directory) {
    BOOL applied = FALSE;
    PACL existing = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    if (GetNamedSecurityInfoW((LPWSTR)path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &existing, NULL,
            &descriptor) == ERROR_SUCCESS) {
        EXPLICIT_ACCESSW request;
        ZeroMemory(&request, sizeof(request));
        request.grfAccessPermissions = deny ? 0x1F01FF : FILE_ALL_ACCESS;
        request.grfAccessMode = deny ? DENY_ACCESS : GRANT_ACCESS;
        request.grfInheritance = directory ? (OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE) : NO_INHERITANCE;
        request.Trustee.TrusteeForm = TRUSTEE_IS_SID;
        request.Trustee.TrusteeType = TRUSTEE_IS_UNKNOWN;
        request.Trustee.ptstrName = (LPWSTR)sid;
        PACL updated = NULL;
        if (SetEntriesInAclW(1, &request, existing, &updated) == ERROR_SUCCESS) {
            applied = SetNamedSecurityInfoW((LPWSTR)path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL,
                updated, NULL) == ERROR_SUCCESS;
            LocalFree(updated);
        }
        LocalFree(descriptor);
    }
    return applied;
}

static void grant_tree(const wchar_t *root, PSID sid) {
    wchar_t pattern[PILOT_PATH_CHARS];
    if (wcslen(root) + 3 >= PILOT_PATH_CHARS) {
        return;
    }
    wcscpy(pattern, root);
    wcscat(pattern, L"\\*");
    WIN32_FIND_DATAW item;
    HANDLE find = FindFirstFileW(pattern, &item);
    if (find == INVALID_HANDLE_VALUE) {
        return;
    }
    do {
        if (wcscmp(item.cFileName, L".") == 0 || wcscmp(item.cFileName, L"..") == 0) {
            continue;
        }
        wchar_t child[PILOT_PATH_CHARS];
        if (wcslen(root) + wcslen(item.cFileName) + 2 >= PILOT_PATH_CHARS) {
            continue;
        }
        wcscpy(child, root);
        wcscat(child, L"\\");
        wcscat(child, item.cFileName);
        BOOL directory = (item.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        if (acl_has_on_path(child, sid, FALSE)) {
            continue;
        }
        if (!apply_ace(child, sid, FALSE, directory)) {
            /* 权限不足的条目跳过：不让一个只读文件挡住整个工作区的授权。 */
            continue;
        }
        // 符号链接和目录联接不跟进去，避免授权跑到工作区外面
        if (directory && (item.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0) {
            grant_tree(child, sid);
        }
    } while (FindNextFileW(find, &item));
    FindClose(find);
}

static BOOL authorize(const wchar_t *path, PSID sid) {
    DWORD attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES) {
        return FALSE;
    }
    BOOL directory = (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    if (acl_has_on_path(path, sid, FALSE)) {
        return TRUE;
    }
    if (!apply_ace(path, sid, FALSE, directory)) {
        return FALSE;
    }
    if (directory) {
        grant_tree(path, sid);
    }
    return TRUE;
}

/*
 * .git/config、hooks、info 是能改仓库执行行为的入口，允许之后再补拒绝 ACE。
 * 拒绝 ACE 排在允许之前，同一 SID 也压不过它，所以这几处工作区内也一样写不进去。
 */
static BOOL lock_path(const wchar_t *path, PSID sid) {
    DWORD attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES) {
        return TRUE;
    }
    BOOL directory = (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    return acl_has_on_path(path, sid, TRUE) || apply_ace(path, sid, TRUE, directory);
}

/* 授权完成后，把仓库里能改执行行为的几个入口再锁回去。 */
static void lock_git(const wchar_t *workspace, PSID sid) {
    static const wchar_t *const names[] = {L".git\\config", L".git\\hooks", L".git\\info"};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        wchar_t path[PILOT_PATH_CHARS];
        if (wcslen(workspace) + wcslen(names[i]) + 2 >= PILOT_PATH_CHARS) {
            continue;
        }
        wcscpy(path, workspace);
        wcscat(path, L"\\");
        wcscat(path, names[i]);
        lock_path(path, sid);
    }
}

static void append_argument(wchar_t *buffer, const wchar_t *argument) {
    size_t used = wcslen(buffer);
    BOOL quote = *argument == 0 || wcschr(argument, L' ') != NULL || wcschr(argument, L'\t') != NULL
        || wcschr(argument, L'"') != NULL;
    if (used > 0 && used + 1 < PILOT_MAX_COMMAND) {
        buffer[used++] = L' ';
        buffer[used] = 0;
    }
    if (!quote) {
        if (used + wcslen(argument) < PILOT_MAX_COMMAND) {
            wcscpy(buffer + used, argument);
        }
        return;
    }
    if (used + 1 < PILOT_MAX_COMMAND) {
        buffer[used++] = L'"';
        buffer[used] = 0;
    }
    const wchar_t *cursor = argument;
    while (*cursor != 0 && used + 3 < PILOT_MAX_COMMAND) {
        size_t slashes = 0;
        while (*cursor == L'\\') {
            ++slashes;
            ++cursor;
        }
        if (*cursor == L'"') {
            for (size_t i = 0; i < slashes * 2 + 1 && used + 1 < PILOT_MAX_COMMAND; ++i) {
                buffer[used++] = L'\\';
            }
            if (used + 1 < PILOT_MAX_COMMAND) {
                buffer[used++] = *cursor;
            }
            ++cursor;
        } else if (*cursor == 0) {
            for (size_t i = 0; i < slashes * 2 && used + 1 < PILOT_MAX_COMMAND; ++i) {
                buffer[used++] = L'\\';
            }
        } else {
            for (size_t i = 0; i < slashes && used + 1 < PILOT_MAX_COMMAND; ++i) {
                buffer[used++] = L'\\';
            }
            if (used + 1 < PILOT_MAX_COMMAND) {
                buffer[used++] = *cursor;
            }
            ++cursor;
        }
    }
    if (used + 2 < PILOT_MAX_COMMAND) {
        buffer[used++] = L'"';
        buffer[used] = 0;
    }
}

static int run(HANDLE token, const wchar_t *workspace, wchar_t *command_line, void *environment) {
    STARTUPINFOW startup;
    PROCESS_INFORMATION child;
    ZeroMemory(&startup, sizeof(startup));
    startup.cb = sizeof(startup);
    ZeroMemory(&child, sizeof(child));
    if (!CreateProcessAsUserW(token, NULL, command_line, NULL, NULL, TRUE, CREATE_UNICODE_ENVIRONMENT, environment,
            workspace, &startup, &child)) {
        DWORD code = GetLastError();
        fprintf(stderr, "pilot-sandbox: CreateProcessAsUser failed (%lu)\n", (unsigned long)code);
        return fail(code);
    }
    WaitForSingleObject(child.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(child.hProcess, &code);
    CloseHandle(child.hThread);
    CloseHandle(child.hProcess);
    return (int)code;
}

/* 写入目录可能还不存在（私有临时目录每次换名字），缺了就建一层。 */
static BOOL ensure_directory(const wchar_t *path) {
    if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) {
        return TRUE;
    }
    return CreateDirectoryW(path, NULL) || GetLastError() == ERROR_ALREADY_EXISTS;
}

/*
 * 目录统一算成绝对路径。CreateProcessAsUser 的 lpCurrentDirectory 必须是完整路径，
 * 传给子进程的 TEMP 也一样；调用方给相对路径时先在这里补齐。
 */
static void absolute_path(const wchar_t *input, wchar_t *buffer) {
    DWORD length = GetFullPathNameW(input, PILOT_PATH_CHARS, buffer, NULL);
    if (length == 0 || length >= PILOT_PATH_CHARS) {
        wcsncpy(buffer, input, PILOT_PATH_CHARS - 1);
        buffer[PILOT_PATH_CHARS - 1] = 0;
    }
}

/*
 * 复制父进程环境，把 TEMP/TMP 换成本次私有临时目录。
 * 不改这两个变量时，程序会往用户真实临时目录写，而那些位置对受限令牌是拒绝的。
 */
static wchar_t *child_environment(const wchar_t *temp) {
    const wchar_t *source = GetEnvironmentStringsW();
    if (source == NULL) {
        return NULL;
    }
    size_t used = 0;
    size_t capacity = PILOT_MAX_COMMAND;
    wchar_t *block = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, capacity * sizeof(wchar_t));
    if (block == NULL) {
        FreeEnvironmentStringsW((LPWCH)source);
        return NULL;
    }
    for (const wchar_t *entry = source; *entry != 0; entry += wcslen(entry) + 1) {
        if (_wcsnicmp(entry, L"TEMP=", 5) == 0 || _wcsnicmp(entry, L"TMP=", 4) == 0) {
            continue;
        }
        size_t length = wcslen(entry);
        if (used + length + 1 >= capacity) {
            continue;
        }
        wmemcpy(block + used, entry, length + 1);
        used += length + 1;
    }
    FreeEnvironmentStringsW((LPWCH)source);
    if (temp != NULL) {
        size_t length = wcslen(temp);
        if (used + 4 * length + 12 < capacity) {
            used += (size_t)swprintf(block + used, capacity - used, L"TEMP=%ls", temp) + 1;
            used += (size_t)swprintf(block + used, capacity - used, L"TMP=%ls", temp) + 1;
        }
    }
    block[used] = 0;
    block[used + 1] = 0;
    return block;
}

static int probe(void) {
    int status = 1;
    PSID sid = capability_sid(L"probe");
    if (sid == NULL) {
        return fail(GetLastError());
    }
    HANDLE token = restricted_token(sid);
    if (token == NULL) {
        status = fail(GetLastError());
    } else {
        status = 0;
        CloseHandle(token);
    }
    FreeSid(sid);
    return status;
}

int main(void) {
    int status = 1;
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    const wchar_t *workspace = NULL;
    const wchar_t *workdir = NULL;
    const wchar_t *temp = NULL;
    static wchar_t workspace_path[PILOT_PATH_CHARS];
    static wchar_t workdir_path[PILOT_PATH_CHARS];
    static wchar_t temp_path[PILOT_PATH_CHARS];
    static wchar_t write_paths[PILOT_MAX_WRITES][PILOT_PATH_CHARS];
    static wchar_t deny_paths[PILOT_MAX_WRITES][PILOT_PATH_CHARS];
    const wchar_t *writes[PILOT_MAX_WRITES];
    const wchar_t *denies[PILOT_MAX_WRITES];
    int write_count = 0;
    int deny_count = 0;
    PSID sid = NULL;
    HANDLE token = NULL;
    wchar_t *command_line = NULL;
    wchar_t *environment = NULL;
    if (argv == NULL) {
        return fail(GetLastError());
    }
    if (argc >= 2 && wcscmp(argv[1], L"--probe") == 0) {
        status = probe();
        LocalFree(argv);
        return status;
    }
    int index = 1;
    for (; index < argc; ++index) {
        if (wcscmp(argv[index], L"--") == 0) {
            ++index;
            break;
        }
        if (index + 1 >= argc) {
            fprintf(stderr, "pilot-sandbox: %ls needs a value\n", argv[index]);
            goto done;
        }
        const wchar_t *value = argv[index + 1];
        if (wcscmp(argv[index], L"--workspace") == 0) {
            absolute_path(value, workspace_path);
            workspace = workspace_path;
        } else if (wcscmp(argv[index], L"--workdir") == 0) {
            absolute_path(value, workdir_path);
            workdir = workdir_path;
        } else if (wcscmp(argv[index], L"--temp") == 0) {
            absolute_path(value, temp_path);
            temp = temp_path;
            if (write_count < PILOT_MAX_WRITES) {
                writes[write_count++] = temp;
            }
        } else if (wcscmp(argv[index], L"--write") == 0) {
            if (write_count < PILOT_MAX_WRITES) {
                absolute_path(value, write_paths[write_count]);
                writes[write_count] = write_paths[write_count];
                ++write_count;
            }
        } else if (wcscmp(argv[index], L"--deny") == 0) {
            if (deny_count < PILOT_MAX_WRITES) {
                absolute_path(value, deny_paths[deny_count]);
                denies[deny_count] = deny_paths[deny_count];
                ++deny_count;
            }
        } else {
            fprintf(stderr, "pilot-sandbox: unknown option %ls\n", argv[index]);
            goto done;
        }
        ++index;
    }
    if (workspace == NULL || index >= argc) {
        fprintf(stderr, "pilot-sandbox: usage: --workspace DIR [--workdir DIR] [--temp DIR] [--write DIR]... [--deny PATH]... -- command args...\n");
        goto done;
    }
    if (temp != NULL && !ensure_directory(temp)) {
        fprintf(stderr, "pilot-sandbox: cannot create the private temp directory\n");
        goto done;
    }
    sid = capability_sid(workspace);
    if (sid == NULL) {
        status = fail(GetLastError());
        goto done;
    }
    token = restricted_token(sid);
    if (token == NULL) {
        status = fail(GetLastError());
        goto done;
    }
    if (!authorize(workspace, sid)) {
        fprintf(stderr, "pilot-sandbox: cannot grant write access to the workspace\n");
        goto done;
    }
    for (int i = 0; i < write_count; ++i) {
        if (!authorize(writes[i], sid)) {
            fprintf(stderr, "pilot-sandbox: cannot grant write access to %ls\n", writes[i]);
            goto done;
        }
    }
    lock_git(workspace, sid);
    for (int i = 0; i < deny_count; ++i) {
        lock_path(denies[i], sid);
    }
    command_line = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, PILOT_MAX_COMMAND * sizeof(wchar_t));
    environment = child_environment(temp);
    if (command_line == NULL || environment == NULL) {
        goto done;
    }
    for (int i = index; i < argc; ++i) {
        append_argument(command_line, argv[i]);
    }
    status = run(token, workdir != NULL ? workdir : workspace, command_line, environment);
done:
    if (environment != NULL) {
        HeapFree(GetProcessHeap(), 0, environment);
    }
    if (command_line != NULL) {
        HeapFree(GetProcessHeap(), 0, command_line);
    }
    if (token != NULL) {
        CloseHandle(token);
    }
    if (sid != NULL) {
        FreeSid(sid);
    }
    LocalFree(argv);
    return status;
}
#else
int main(void) {
    return 1;
}
#endif
