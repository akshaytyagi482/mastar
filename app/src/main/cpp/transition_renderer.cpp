#include "transition_renderer.h"

#include <android/log.h>
#include <vector>

#define LOG_TAG "MastarGL"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace mastar {

namespace {

const char *kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
out vec2 vUv;
void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)";

// gl-transitions snippets assume these helpers exist. We provide them and
// splice the MIT-licensed transition body in between.
const char *kFragmentHeader = R"(#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform float uProgress;
uniform float uRatio;
float progress;
float ratio;
vec4 getFromColor(vec2 uv) { return texture(uFrom, uv); }
vec4 getToColor(vec2 uv) { return texture(uTo, uv); }
)";

const char *kFragmentFooter = R"(
void main() {
    progress = uProgress;
    ratio = uRatio;
    fragColor = transition(vUv);
}
)";

// Fullscreen quad as a triangle strip.
const float kQuad[] = {
        -1.f, -1.f,
        1.f, -1.f,
        -1.f, 1.f,
        1.f, 1.f,
};

} // namespace

TransitionRenderer::~TransitionRenderer() {
    // GL objects must be freed via destroy() on the GL thread; the destructor
    // only guards against leaks in debug builds.
    if (program_ != 0) {
        LOGE("TransitionRenderer destroyed without destroy(); leaking GL objects");
    }
}

GLuint TransitionRenderer::compileShader(GLenum type, const std::string &source) {
    GLuint shader = glCreateShader(type);
    const char *src = source.c_str();
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);

    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        GLint logLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> log(static_cast<size_t>(logLen) + 1);
        glGetShaderInfoLog(shader, logLen, nullptr, log.data());
        LOGE("Shader compile failed: %s", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool TransitionRenderer::compile(const std::string &transitionGlsl) {
    destroy();

    const std::string fragmentSource =
            std::string(kFragmentHeader) + transitionGlsl + kFragmentFooter;

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (vs == 0 || fs == 0) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }

    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint logLen = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> log(static_cast<size_t>(logLen) + 1);
        glGetProgramInfoLog(program_, logLen, nullptr, log.data());
        LOGE("Program link failed: %s", log.data());
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    uProgress_ = glGetUniformLocation(program_, "uProgress");
    uFrom_ = glGetUniformLocation(program_, "uFrom");
    uTo_ = glGetUniformLocation(program_, "uTo");
    uRatio_ = glGetUniformLocation(program_, "uRatio");

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuad), kQuad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glBindVertexArray(0);

    return true;
}

void TransitionRenderer::draw(float progress, GLuint fromTexture, GLuint toTexture,
                              int viewportWidth, int viewportHeight) {
    if (program_ == 0) return;

    glViewport(0, 0, viewportWidth, viewportHeight);
    glUseProgram(program_);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, fromTexture);
    glUniform1i(uFrom_, 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, toTexture);
    glUniform1i(uTo_, 1);

    glUniform1f(uProgress_, progress);
    if (uRatio_ >= 0 && viewportHeight > 0) {
        glUniform1f(uRatio_, static_cast<float>(viewportWidth) / viewportHeight);
    }

    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

void TransitionRenderer::destroy() {
    if (vbo_ != 0) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    if (vao_ != 0) {
        glDeleteVertexArrays(1, &vao_);
        vao_ = 0;
    }
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
}

} // namespace mastar
