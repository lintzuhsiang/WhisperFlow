#pragma once
// ggml-cpp.h – C++ RAII wrappers around the ggml C API.
// Used by whisper.cpp to manage ggml_backend and ggml_context lifetimes.

#include <memory>
#include "ggml-backend.h"
#include "ggml.h"

struct ggml_backend_deleter {
    void operator()(ggml_backend_t backend) const {
        ggml_backend_free(backend);
    }
};

using ggml_backend_ptr = std::unique_ptr<struct ggml_backend, ggml_backend_deleter>;

struct ggml_context_deleter {
    void operator()(struct ggml_context * ctx) const {
        ggml_free(ctx);
    }
};

using ggml_context_ptr = std::unique_ptr<struct ggml_context, ggml_context_deleter>;
