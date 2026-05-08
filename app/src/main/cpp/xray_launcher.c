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

static char *copy_string(JNIEnv *env, jstring value) {
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) return NULL;

    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    if (copy == NULL) {
        throw_state(env, "strdup failed", errno);
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

static char **copy_env(JNIEnv *env, jobjectArray values) {
    jsize count = (*env)->GetArrayLength(env, values);
    char **envp = calloc((size_t) count + 1, sizeof(char *));
    if (envp == NULL) {
        throw_state(env, "calloc failed", errno);
        return NULL;
    }

    for (jsize i = 0; i < count; i++) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        if (value == NULL) {
            free_string_array(envp, i);
            throw_state(env, "environment entry is null", EINVAL);
            return NULL;
        }
        envp[i] = copy_string(env, value);
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

    if (tun_fd < 0) {
        throw_state(env, "invalid TUN fd", EBADF);
        return -1;
    }

    char *binary = copy_string(env, binary_path);
    char *config = copy_string(env, config_path);
    char *working = copy_string(env, working_dir);
    char *log = copy_string(env, log_path);
    char **child_env = copy_env(env, env_values);
    if ((*env)->ExceptionCheck(env)) {
        free(binary);
        free(config);
        free(working);
        free(log);
        free_string_array(child_env, env_values == NULL ? 0 : (*env)->GetArrayLength(env, env_values));
        return -1;
    }

    int child_tun_fd = fcntl(tun_fd, F_DUPFD, 3);
    if (child_tun_fd < 0) {
        int err = errno;
        free(binary);
        free(config);
        free(working);
        free(log);
        free_string_array(child_env, (*env)->GetArrayLength(env, env_values));
        throw_state(env, "failed to duplicate TUN fd", err);
        return -1;
    }

    int fd_flags = fcntl(child_tun_fd, F_GETFD);
    if (fd_flags < 0 || fcntl(child_tun_fd, F_SETFD, fd_flags & ~FD_CLOEXEC) < 0) {
        int err = errno;
        close(child_tun_fd);
        free(binary);
        free(config);
        free(working);
        free(log);
        free_string_array(child_env, (*env)->GetArrayLength(env, env_values));
        throw_state(env, "failed to prepare TUN fd", err);
        return -1;
    }

    char fd_env[64];
    snprintf(fd_env, sizeof(fd_env), "xray.tun.fd=%d", child_tun_fd);
    char fd_env_compat[64];
    snprintf(fd_env_compat, sizeof(fd_env_compat), "XRAY_TUN_FD=%d", child_tun_fd);

    jsize env_count = (*env)->GetArrayLength(env, env_values);
    char **spawn_env = calloc((size_t) env_count + 3, sizeof(char *));
    if (spawn_env == NULL) {
        int err = errno;
        close(child_tun_fd);
        free(binary);
        free(config);
        free(working);
        free(log);
        free_string_array(child_env, env_count);
        throw_state(env, "calloc failed", err);
        return -1;
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
        int err = errno;
        close(child_tun_fd);
        free(binary);
        free(config);
        free(working);
        free(log);
        free(spawn_env);
        free_string_array(child_env, env_count);
        throw_state(env, "failed to fork xray", err);
        return -1;
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
        for (int fd = STDERR_FILENO + 1; fd < max_fd; fd++) {
            if (fd != child_tun_fd) close(fd);
        }
        execve(binary, argv, spawn_env);
        _exit(127);
    }

    close(child_tun_fd);

    free(spawn_env);
    free_string_array(child_env, env_count);
    free(binary);
    free(config);
    free(working);
    free(log);

    return (jint) pid;
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
