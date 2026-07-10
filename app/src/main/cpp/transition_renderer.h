#ifndef MASTAR_TRANSITION_RENDERER_H
#define MASTAR_TRANSITION_RENDERER_H

#include <GLES3/gl3.h>
#include <string>

namespace mastar {

/**
 * Hosts a gl-transitions (gltransitions.com, MIT) fragment shader.
 *
 * A transition shader defines:  vec4 transition(vec2 uv)
 * and samples the built-in helpers getFromColor(uv) / getToColor(uv).
 * This class wraps that snippet in a full GLES3 fragment shader, binds the
 * outgoing and incoming clips as two textures, and draws a fullscreen quad
 * at the given progress (0.0 = fully "from" clip, 1.0 = fully "to" clip).
 */
class TransitionRenderer {
public:
    TransitionRenderer() = default;
    ~TransitionRenderer();

    TransitionRenderer(const TransitionRenderer &) = delete;
    TransitionRenderer &operator=(const TransitionRenderer &) = delete;

    /** Compiles the wrapped shader. Returns false (and logs) on GLSL errors. */
    bool compile(const std::string &transitionGlsl);

    /**
     * Draws the transition. Textures must already be bound to units 0 ("from")
     * and 1 ("to"). Caller owns the EGL context and viewport.
     */
    void draw(float progress, GLuint fromTexture, GLuint toTexture,
              int viewportWidth, int viewportHeight);

    bool isReady() const { return program_ != 0; }

    /** Frees GL objects; must be called on the GL thread. */
    void destroy();

private:
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLint uProgress_ = -1;
    GLint uFrom_ = -1;
    GLint uTo_ = -1;
    GLint uRatio_ = -1;

    static GLuint compileShader(GLenum type, const std::string &source);
};

} // namespace mastar

#endif // MASTAR_TRANSITION_RENDERER_H
