#include <errno.h>
#include <fcntl.h>
#include <linux/landlock.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

static void fail(const char* reason) {
    perror(reason);
    exit(125);
}

static void allow(int ruleset, const char* path, uint64_t rights) {
    int path_fd = open(path, O_PATH | O_CLOEXEC);
    if (path_fd < 0) fail(path);
    struct landlock_path_beneath_attr rule = {.allowed_access = rights, .parent_fd = path_fd};
    if (syscall(__NR_landlock_add_rule, ruleset, LANDLOCK_RULE_PATH_BENEATH, &rule, 0) < 0) fail(path);
    close(path_fd);
}

// Model-authored Node scripts have no relay or socket access.
static void restrict_script(void) {
    const unsigned deny = SECCOMP_RET_ERRNO | EPERM;
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, deny),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, deny),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {
        .len = sizeof(instructions) / sizeof(instructions[0]),
        .filter = instructions,
    };
    const struct rlimit file_size = {16 * 1024 * 1024, 16 * 1024 * 1024};
    if (setrlimit(RLIMIT_FSIZE, &file_size) < 0) fail("RLIMIT_FSIZE");
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) < 0) fail("PR_SET_SECCOMP");
}

int main(int argc, char** argv) {
    if (argc == 2 && !strcmp(argv[1], "--check")) {
        return syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION) >= 6 ? 0 : 125;
    }
    if (argc < 5) {
        fprintf(stderr, "usage: sandbox TASK_DIR NATIVE_LIB_DIR EXECUTABLE [ARG...]\n");
        return 125;
    }
    long abi = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 6) fail("Landlock ABI >= 6 needed for this probe");
    const uint64_t read_exec = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_EXECUTE;
    const uint64_t all_fs = (LANDLOCK_ACCESS_FS_IOCTL_DEV << 1) - 1;
    const uint64_t job_fs = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR
        | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_MAKE_REG | LANDLOCK_ACCESS_FS_MAKE_DIR
        | LANDLOCK_ACCESS_FS_REMOVE_FILE | LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REFER
        | LANDLOCK_ACCESS_FS_TRUNCATE | LANDLOCK_ACCESS_FS_MAKE_SYM;
    struct landlock_ruleset_attr policy = {
        .handled_access_fs = all_fs,
        .handled_access_net = LANDLOCK_ACCESS_NET_BIND_TCP | LANDLOCK_ACCESS_NET_CONNECT_TCP,
        .scoped = LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET | LANDLOCK_SCOPE_SIGNAL,
    };
    int ruleset = syscall(__NR_landlock_create_ruleset, &policy, sizeof(policy), 0);
    if (ruleset < 0) fail("landlock_create_ruleset");
    const char* relay = getenv("SOUND_MODEL_RELAY_PORT");
    const int script = getenv("SOUND_AGENT_SCRIPT") != NULL;
    if (script && relay) return 125;
    if (relay) {
        char* end;
        unsigned long port = strtoul(relay, &end, 10);
        if (!port || port > 65535 || *end) return 125;
        struct landlock_net_port_attr rule = {
            .allowed_access = LANDLOCK_ACCESS_NET_CONNECT_TCP, .port = port,
        };
        if (syscall(__NR_landlock_add_rule, ruleset, LANDLOCK_RULE_NET_PORT, &rule, 0) < 0)
            fail("landlock_add_rule relay");
        unsetenv("SOUND_MODEL_RELAY_PORT");
    }
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) fail("PR_SET_PDEATHSIG");
    allow(ruleset, argv[1], job_fs);
    const char* workspace = getenv("SOUND_AGENT_WORKSPACE");
    if (workspace) {
        if (workspace[0] != '/') return 125;
        allow(ruleset, workspace, job_fs);
    }
    allow(ruleset, argv[2], read_exec | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, "/apex/com.android.runtime", read_exec | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, "/system/bin/sh", read_exec);
    allow(ruleset, "/system/lib64", read_exec | LANDLOCK_ACCESS_FS_READ_DIR);
    allow(ruleset, "/linkerconfig/ld.config.txt", LANDLOCK_ACCESS_FS_READ_FILE);
    allow(ruleset, "/dev/null", LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE);
    allow(ruleset, "/dev/urandom", LANDLOCK_ACCESS_FS_READ_FILE);
    if (script) {
        allow(ruleset, "/proc/self/stat", LANDLOCK_ACCESS_FS_READ_FILE);
        allow(ruleset, "/proc/self/maps", LANDLOCK_ACCESS_FS_READ_FILE);
    } else {
        allow(ruleset, "/proc", LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR);
    }
    if (chdir(argv[1]) < 0) fail("chdir");
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) fail("PR_SET_NO_NEW_PRIVS");
    if (syscall(__NR_landlock_restrict_self, ruleset, 0) < 0) fail("landlock_restrict_self");
    close(ruleset);
    if (script) restrict_script();
    fprintf(stderr, "Landlock ABI=%ld uid=%d\n", abi, getuid());
    execv(argv[3], &argv[3]);
    fail("execv");
}
