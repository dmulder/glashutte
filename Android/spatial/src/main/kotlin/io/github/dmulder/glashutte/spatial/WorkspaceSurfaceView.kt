package io.github.dmulder.glashutte.spatial

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper

/**
 * The [GLSurfaceView] the workspace is drawn into — an OpenGL ES 3.0 context rendering
 * continuously, so screen frames and (from M2) head motion update every frame. ES 3 is a
 * superset of the ES 2 calls/shaders used here; it's requested for async PBO framebuffer
 * readback (capture/recording without a glReadPixels pipeline stall).
 *
 * It hosts the same [WorkspaceRenderer] whether it lives in a `Presentation` on the glasses
 * or in the on-phone preview activity.
 */
class WorkspaceSurfaceView(context: Context) : GLSurfaceView(context) {

    val workspaceRenderer = WorkspaceRenderer(
        context = context,
        mainHandler = Handler(Looper.getMainLooper()),
    )

    init {
        setEGLContextClientVersion(3)
        // Keep the GL context across pause/resume, so the desktop and screens survive a blip.
        preserveEGLContextOnPause = true
        setRenderer(workspaceRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
