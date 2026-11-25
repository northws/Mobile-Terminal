#define EGL_NO_X11
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <wayland-client.h>
#include <wayland-egl.h>
#include "xdg-shell-client.h"

#define SIZE 256
#define APP_ID "mobile-terminal-egl-probe"

struct wayland {
    struct wl_compositor *compositor;
    struct xdg_wm_base *shell;
    int configured;
};

static void global(void *data, struct wl_registry *registry, uint32_t name,
                   const char *interface, uint32_t version)
{
    struct wayland *wayland = data;
    (void)version;
    if (strcmp(interface, wl_compositor_interface.name) == 0)
        wayland->compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 1);
    if (strcmp(interface, xdg_wm_base_interface.name) == 0)
        wayland->shell = wl_registry_bind(registry, name, &xdg_wm_base_interface, 1);
}

static void removed(void *data, struct wl_registry *registry, uint32_t name)
{
    (void)data;
    (void)registry;
    (void)name;
}

static void ping(void *data, struct xdg_wm_base *shell, uint32_t serial)
{
    (void)data;
    xdg_wm_base_pong(shell, serial);
}

static void configure(void *data, struct xdg_surface *surface, uint32_t serial)
{
    struct wayland *wayland = data;
    xdg_surface_ack_configure(surface, serial);
    wayland->configured = 1;
}

static const struct wl_registry_listener registry_events = { global, removed };
static const struct xdg_wm_base_listener shell_events = { ping };
static const struct xdg_surface_listener surface_events = { configure };

static int check_bgra_texture(void)
{
    /* Exercise the upload and channel order hidden by ANGLE's Qualcomm workaround. */
    const GLubyte bgra[] = {211, 87, 19, 255};
    const GLubyte expected[] = {19, 87, 211, 255};
    GLubyte pixel[4] = {0};
    GLuint texture, framebuffer;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_BGRA_EXT, 1, 1, 0, GL_BGRA_EXT, GL_UNSIGNED_BYTE, bgra);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &framebuffer);
    glDeleteTextures(1, &texture);
    int ok = memcmp(pixel, expected, sizeof(pixel)) == 0;
    while (glGetError() != GL_NO_ERROR) ok = 0;
    return ok;
}

static int draw(EGLDisplay display, EGLNativeWindowType window, long frames)
{
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL)) return 12;
    if (!eglBindAPI(EGL_OPENGL_ES_API)) return 13;
    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE,
    };
    EGLConfig config;
    EGLint count;
    if (!eglChooseConfig(display, config_attributes, &config, 1, &count) || count == 0) return 13;
    const EGLint context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    EGLSurface surface = eglCreateWindowSurface(display, config, window, NULL);
    if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(display, surface, surface, context)) return 14;

    const char *vendor = (const char *)glGetString(GL_VENDOR);
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    if (!vendor || !renderer) return 15;
    printf("GL_VENDOR=%s\nGL_RENDERER=%s\n", vendor, renderer);
    if (strcmp(vendor, "Qualcomm") == 0 && strcmp(renderer, "Adreno (TM) 840") == 0)
        printf("BGRA8888=%d\n", check_bgra_texture());
    fflush(stdout);
    for (long frame = 0; frame < frames; ++frame) {
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0, 0, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor((frame % 48) * 4, 0, 64, SIZE);
        glClearColor(1, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        if (frame == 0) {
            unsigned char pixel[4] = {0};
            glReadPixels(10, 10, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            if (glGetError() != GL_NO_ERROR || pixel[0] != 255 || pixel[1] || pixel[2]) return 15;
        }
        if (!eglSwapBuffers(display, surface)) return 16;
        usleep(25000);
    }
    printf("FRAMES=%ld\n", frames);
    fflush(stdout);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    eglTerminate(display);
    return 0;
}

static int run_wayland(long frames)
{
    struct wl_display *display = wl_display_connect(NULL);
    if (!display) return 11;
    struct wayland wayland = {0};
    struct wl_registry *registry = wl_display_get_registry(display);
    wl_registry_add_listener(registry, &registry_events, &wayland);
    if (wl_display_roundtrip(display) < 0 || !wayland.shell || !wayland.compositor) return 11;
    xdg_wm_base_add_listener(wayland.shell, &shell_events, NULL);
    struct wl_surface *surface = wl_compositor_create_surface(wayland.compositor);
    struct xdg_surface *xdg = xdg_wm_base_get_xdg_surface(wayland.shell, surface);
    xdg_surface_add_listener(xdg, &surface_events, &wayland);
    struct xdg_toplevel *toplevel = xdg_surface_get_toplevel(xdg);
    xdg_toplevel_set_app_id(toplevel, APP_ID);
    xdg_toplevel_set_title(toplevel, "Adreno Wayland");
    wl_surface_commit(surface);
    while (!wayland.configured)
        if (wl_display_dispatch(display) < 0) return 11;
    struct wl_egl_window *window = wl_egl_window_create(surface, SIZE, SIZE);
    if (!window) return 14;
    int result = draw(eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, display, NULL),
                      (EGLNativeWindowType)(uintptr_t)window, frames);
    wl_egl_window_destroy(window);
    wl_display_disconnect(display);
    return result;
}

static int run_x11(long frames)
{
    Display *display = XOpenDisplay(NULL);
    if (!display) return 11;
    Window window = XCreateSimpleWindow(display, DefaultRootWindow(display), 0, 0, SIZE, SIZE, 0, 0, 0);
    XClassHint hint = { APP_ID, APP_ID };
    XSetClassHint(display, window, &hint);
    XStoreName(display, window, "Adreno X11");
    XSelectInput(display, window, StructureNotifyMask);
    XMapWindow(display, window);
    XEvent event;
    do { XNextEvent(display, &event); } while (event.type != MapNotify);
    int result = draw(eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR, display, NULL), (EGLNativeWindowType)window, frames);
    XCloseDisplay(display);
    return result;
}

int main(int argc, char **argv)
{
    if (argc < 2 || argc > 3) return 2;
    char *end = NULL;
    long frames = argc == 3 ? strtol(argv[2], &end, 10) : 48;
    if (frames < 1 || frames > 1200 || (end && *end)) return 2;
    int result;
    if (strcmp(argv[1], "x11") == 0) result = run_x11(frames);
    else if (strcmp(argv[1], "wayland") == 0) result = run_wayland(frames);
    else return 2;
    if (result != 0) fprintf(stderr, "Adreno probe stage %d\n", result);
    return result;
}
