// Pseudo-terminal support for the interactive Terminal.
//
// Opens a master/slave pair through /dev/ptmx, forks, makes the child a
// session leader with the slave as its controlling terminal, and execs the
// shell. The parent keeps the master fd; Kotlin reads and writes it, resizes
// it with TIOCSWINSZ and waits for the child with waitpid.
#include <jni.h>
#include <dirent.h>
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

namespace {

void throwIo(JNIEnv *env, const char *what) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", what, strerror(errno));
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

char *copyString(JNIEnv *env, jstring value) {
    const char *utf = env->GetStringUTFChars(value, nullptr);
    char *copy = strdup(utf);
    env->ReleaseStringUTFChars(value, utf);
    return copy;
}

// Null-terminated copy of a Java string array, built before fork so the child
// only reads memory.
char **toArgv(JNIEnv *env, jobjectArray array) {
    jsize n = array == nullptr ? 0 : env->GetArrayLength(array);
    auto out = static_cast<char **>(calloc(n + 1, sizeof(char *)));
    jsize used = 0;
    for (jsize i = 0; i < n; i++) {
        auto item = (jstring) env->GetObjectArrayElement(array, i);
        if (item == nullptr) continue;
        out[used++] = copyString(env, item);
        env->DeleteLocalRef(item);
    }
    return out;
}

void freeArgv(char **argv) {
    for (char **p = argv; *p != nullptr; p++) free(*p);
    free(argv);
}

void closeInheritedFds() {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3, ~0U, 0) == 0) return;
#endif
    DIR *dir = opendir("/proc/self/fd");
    if (dir == nullptr) {
        long max = sysconf(_SC_OPEN_MAX);
        for (int fd = 3; fd < max; fd++) close(fd);
        return;
    }
    int self = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != self) close(fd);
    }
    closedir(dir);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_androidharness_app_terminal_Pty_nativeStart(
    JNIEnv *env, jclass, jstring jpath, jobjectArray jargv, jobjectArray jenvp,
    jstring jcwd, jint rows, jint cols, jintArray jpid) {
    char *path = copyString(env, jpath);
    char *cwd = copyString(env, jcwd);
    char **argv = toArgv(env, jargv);
    char **envp = toArgv(env, jenvp);
    auto release = [&]() { free(path); free(cwd); freeArgv(argv); freeArgv(envp); };

    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) { throwIo(env, "open /dev/ptmx"); release(); return -1; }
    char slaveName[64];
    if (grantpt(master) != 0 || unlockpt(master) != 0 ||
        ptsname_r(master, slaveName, sizeof(slaveName)) != 0) {
        throwIo(env, "pty setup");
        close(master);
        release();
        return -1;
    }

    // UTF-8 aware line discipline, no flow control so Ctrl+S reaches programs.
    struct termios tios{};
    tcgetattr(master, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(master, TCSANOW, &tios);

    struct winsize size{};
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) cols;
    ioctl(master, TIOCSWINSZ, &size);

    pid_t pid = fork();
    if (pid < 0) {
        throwIo(env, "fork");
        close(master);
        release();
        return -1;
    }
    if (pid == 0) {
        // Child: only async-signal-safe calls from here on where possible.
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, nullptr);
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);
        close(master);
        setsid();
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        closeInheritedFds();
        if (chdir(cwd) != 0) chdir("/");
        execve(path, argv, envp);
        const char *msg = "failed to start shell\r\n";
        write(2, msg, strlen(msg));
        _exit(127);
    }

    release();
    jint pidValue = pid;
    env->SetIntArrayRegion(jpid, 0, 1, &pidValue);
    return master;
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_terminal_Pty_nativeResize(
    JNIEnv *, jclass, jint fd, jint rows, jint cols, jint widthPx, jint heightPx) {
    struct winsize size{};
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) cols;
    size.ws_xpixel = (unsigned short) widthPx;
    size.ws_ypixel = (unsigned short) heightPx;
    ioctl(fd, TIOCSWINSZ, &size);
}

// Blocks until the child exits. Returns the exit code, or 128 + signal.
extern "C" JNIEXPORT jint JNICALL
Java_com_androidharness_app_terminal_Pty_nativeWaitFor(JNIEnv *, jclass, jint pid) {
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

// Process group of the terminal's foreground job, or -1.
extern "C" JNIEXPORT jint JNICALL
Java_com_androidharness_app_terminal_Pty_nativeForegroundGroup(JNIEnv *, jclass, jint fd) {
    return tcgetpgrp(fd);
}
