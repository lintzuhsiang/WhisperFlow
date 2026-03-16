// ggml-backend-dl.cpp – POSIX implementation of the dynamic-library helpers
// declared in ggml-backend-dl.h.
//
// On Android (and any POSIX system) we delegate to dlfcn.h.
// The GGML_BACKEND_DIR feature is never defined in this build, so these
// functions are present for the linker but will not be called at runtime.

#include "ggml-backend-dl.h"

// ── dl_load_library ──────────────────────────────────────────────────────────
dl_handle * dl_load_library(const fs::path & path) {
#ifdef _WIN32
    return (dl_handle *) LoadLibraryW(path.wstring().c_str());
#else
    return (dl_handle *) dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
#endif
}

// ── dl_get_sym ───────────────────────────────────────────────────────────────
void * dl_get_sym(dl_handle * handle, const char * name) {
#ifdef _WIN32
    return (void *) GetProcAddress((HMODULE) handle, name);
#else
    return dlsym(handle, name);
#endif
}

// ── dl_error ─────────────────────────────────────────────────────────────────
const char * dl_error() {
#ifdef _WIN32
    return nullptr;   // not used in this build
#else
    return dlerror();
#endif
}
