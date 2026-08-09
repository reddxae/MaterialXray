#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static void throw_state(JNIEnv *env, const char *prefix, int err) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", prefix, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
}

// Reports a failure unless the VM already raised one of its own, which must not be replaced.
static void throw_state_if_clear(JNIEnv *env, const char *prefix, int err) {
    if ((*env)->ExceptionCheck(env)) return;
    throw_state(env, prefix, err);
}

// Returns NULL on failure without raising a Java exception. Almost no JNI function may be called
// while an exception is pending, so reporting is left to the single site that owns the cleanup.
// The reason is reported through out-parameters instead, to keep the failure diagnosable.
static char *copy_string(JNIEnv *env, jstring value, const char **failure, int *failure_errno) {
    if (value == NULL) {
        *failure = "launch argument is null";
        *failure_errno = EINVAL;
        return NULL;
    }

    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) {
        *failure = "failed to read launch argument";
        *failure_errno = ENOMEM;
        return NULL;
    }

    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    if (copy == NULL) {
        *failure = "strdup failed";
        *failure_errno = ENOMEM;
    }
    return copy;
}

static void free_string_array(char **items, int count) {
    if (items == NULL) return;
    for (int i = 0; i < count; i++) {
        free(items[i]);
    }
    free(items);
}

// Returns NULL on failure without raising a Java exception, matching copy_string.
static char **copy_env(JNIEnv *env, jobjectArray values, jsize count, const char **failure, int *failure_errno) {
    char **envp = calloc((size_t) count + 1, sizeof(char *));
    if (envp == NULL) {
        *failure = "calloc failed";
        *failure_errno = ENOMEM;
        return NULL;
    }

    for (jsize i = 0; i < count; i++) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        if (value == NULL) {
            free_string_array(envp, i);
            *failure = "environment entry is null";
            *failure_errno = EINVAL;
            return NULL;
        }
        envp[i] = copy_string(env, value, failure, failure_errno);
        (*env)->DeleteLocalRef(env, value);
        if (envp[i] == NULL) {
            free_string_array(envp, i);
            return NULL;
        }
    }
    envp[count] = NULL;
    return envp;
}

JNIEXPORT jint JNICALL
Java_com_material_xray_service_AndroidUserXrayProcessLauncher_nativeStart(
        JNIEnv *env,
        jclass clazz,
        jstring binary_path,
        jstring config_path,
        jstring working_dir,
        jstring log_path,
        jint tun_fd,
        jobjectArray env_values) {
    (void) clazz;

    char *binary = NULL;
    char *config = NULL;
    char *working = NULL;
    char *log = NULL;
    char **child_env = NULL;
    char **spawn_env = NULL;
    int child_tun_fd = -1;
    const char *failure = NULL;
    int failure_errno = 0;
    jint result = -1;

    if (tun_fd < 0) {
        throw_state(env, "invalid TUN fd", EBADF);
        return -1;
    }
    if (env_values == NULL) {
        throw_state(env, "environment array is null", EINVAL);
        return -1;
    }

    jsize env_count = (*env)->GetArrayLength(env, env_values);

    // Short-circuit evaluation guarantees that no further JNI call is made once one of these fails,
    // which is what keeps the sequence legal if the VM raised an OutOfMemoryError partway through.
    if ((binary = copy_string(env, binary_path, &failure, &failure_errno)) == NULL ||
        (config = copy_string(env, config_path, &failure, &failure_errno)) == NULL ||
        (working = copy_string(env, working_dir, &failure, &failure_errno)) == NULL ||
        (log = copy_string(env, log_path, &failure, &failure_errno)) == NULL ||
        (child_env = copy_env(env, env_values, env_count, &failure, &failure_errno)) == NULL) {
        goto cleanup;
    }

    // The duplicate stays close-on-exec until the child clears it, so an exec on another thread
    // cannot inherit the tunnel descriptor.
    child_tun_fd = fcntl(tun_fd, F_DUPFD_CLOEXEC, 3);
    if (child_tun_fd < 0) {
        failure = "failed to duplicate TUN fd";
        failure_errno = errno;
        goto cleanup;
    }

    char fd_env[64];
    snprintf(fd_env, sizeof(fd_env), "xray.tun.fd=%d", child_tun_fd);
    char fd_env_compat[64];
    snprintf(fd_env_compat, sizeof(fd_env_compat), "XRAY_TUN_FD=%d", child_tun_fd);

    spawn_env = calloc((size_t) env_count + 3, sizeof(char *));
    if (spawn_env == NULL) {
        failure = "calloc failed";
        failure_errno = errno;
        goto cleanup;
    }
    for (jsize i = 0; i < env_count; i++) {
        spawn_env[i] = child_env[i];
    }
    spawn_env[env_count] = fd_env;
    spawn_env[env_count + 1] = fd_env_compat;
    spawn_env[env_count + 2] = NULL;

    char *argv[] = {binary, "run", "-c", config, NULL};
    pid_t pid = fork();
    if (pid < 0) {
        failure = "failed to fork xray";
        failure_errno = errno;
        goto cleanup;
    }

    if (pid == 0) {
        int log_fd = open(log, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (log_fd >= 0) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            if (log_fd > STDERR_FILENO) close(log_fd);
        }
        chdir(working);
        long max_fd = sysconf(_SC_OPEN_MAX);
        if (max_fd < 0) max_fd = 1024;
        for (long fd = STDERR_FILENO + 1; fd < max_fd; fd++) {
            if (fd != (long) child_tun_fd) close((int) fd);
        }
        // Without this the core inherits a closed descriptor and fails in a way that only shows up
        // as an opaque crash, so a failure here has to stop the child instead.
        if (fcntl(child_tun_fd, F_SETFD, 0) < 0) _exit(127);
        execve(binary, argv, spawn_env);
        _exit(127);
    }

    result = (jint) pid;

cleanup:
    if (child_tun_fd >= 0) close(child_tun_fd);
    free(spawn_env);
    free_string_array(child_env, env_count);
    free(binary);
    free(config);
    free(working);
    free(log);
    if (failure != NULL) throw_state_if_clear(env, failure, failure_errno);

    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_material_xray_service_AndroidUserXrayProcessLauncher_nativeIsAlive(
        JNIEnv *env,
        jclass clazz,
        jint pid) {
    (void) env;
    (void) clazz;
    if (pid <= 0) return JNI_FALSE;

    int status = 0;
    pid_t waited = waitpid((pid_t) pid, &status, WNOHANG);
    if (waited == (pid_t) pid) return JNI_FALSE;
    if (waited == 0) return JNI_TRUE;
    if (errno == ECHILD) return kill((pid_t) pid, 0) == 0 ? JNI_TRUE : JNI_FALSE;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_material_xray_service_AndroidUserXrayProcessLauncher_nativeKill(
        JNIEnv *env,
        jclass clazz,
        jint pid,
        jint signal) {
    (void) env;
    (void) clazz;
    if (pid <= 0) return JNI_FALSE;
    return kill((pid_t) pid, signal) == 0 ? JNI_TRUE : JNI_FALSE;
}
