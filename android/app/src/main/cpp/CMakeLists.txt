cmake_minimum_required(VERSION 3.22)
project(TMNTAndroid LANGUAGES C)
if(NOT ANDROID_ABI STREQUAL "armeabi-v7a")
    message(FATAL_ERROR "The guest ABI is 32-bit ARM. armeabi-v7a is the only supported Android ABI.")
endif()
set(ROOT "${TMNT_ANDROID_ROOT}")
set(DEPS "${ROOT}/build/android-deps")
if(NOT EXISTS "${DEPS}/SDL2/CMakeLists.txt" OR NOT EXISTS "${DEPS}/gl4es/CMakeLists.txt")
    message(FATAL_ERROR "Run python3 tools/android/fetch_deps.py first.")
endif()
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_UNINSTALL ON CACHE BOOL "" FORCE)
# Java owns Android gamepads; avoid the desktop libusb/HIDAPI C++ dependency.
set(SDL_HIDAPI OFF CACHE BOOL "" FORCE)
set(SDL_HIDAPI_LIBUSB OFF CACHE BOOL "" FORCE)
add_subdirectory("${DEPS}/SDL2" "${CMAKE_CURRENT_BINARY_DIR}/SDL2")
set(ANDROID ON CACHE BOOL "" FORCE)
set(NOX11 ON CACHE BOOL "" FORCE)
set(NO_GBM ON CACHE BOOL "" FORCE)
set(STATICLIB ON CACHE BOOL "" FORCE)
set(NO_INIT_CONSTRUCTOR ON CACHE BOOL "" FORCE)
set(DEFAULT_ES 2 CACHE STRING "" FORCE)
set(USE_ANDROID_LOG ON CACHE BOOL "" FORCE)
add_subdirectory("${DEPS}/gl4es" "${CMAKE_CURRENT_BINARY_DIR}/gl4es")

set(RT "${ROOT}/rt")
add_library(tmnt_runtime SHARED
    ${RT}/runtime.c ${RT}/win32.c ${RT}/com.c ${RT}/d3d9.c ${RT}/d3d9_gl.c
    ${RT}/dinput.c ${RT}/dsound.c ${RT}/game_tmnt.c ${RT}/movie.c ${RT}/par.c
    ${RT}/png.c ${RT}/android/plat_android.c ${RT}/android/code_android.c
    ${RT}/android/runtime_android.c ${RT}/android/touch.c ${RT}/android/bridge.c)
target_compile_definitions(tmnt_runtime PRIVATE TMNT_CODE_SO=1 GUEST_GENERATED_STACK_GUARD=0)
target_compile_options(tmnt_runtime PRIVATE -std=gnu11 -O3 -fno-strict-aliasing -fno-math-errno
-Wall -Wno-unused-function -Wno-unused-variable)
target_include_directories(tmnt_runtime PRIVATE ${RT} ${DEPS}/gl4es/include ${DEPS}/SDL2/include)
# The translated module resolves the runtime's C ABI through its DT_NEEDED entry.
target_link_options(tmnt_runtime PRIVATE -Wl,-Bsymbolic -Wl,-z,max-page-size=16384 -Wl,-z,now)
target_link_libraries(tmnt_runtime PRIVATE SDL2
    -Wl,--whole-archive GL -Wl,--no-whole-archive EGL GLESv2 android log z dl m)
set_target_properties(tmnt_runtime PROPERTIES OUTPUT_NAME tmnt_runtime NO_SONAME OFF)
set_target_properties(GL PROPERTIES ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/gl4es/lib")
# Keep the Android loader contract stable even if upstream SDL changes its Unix versioning defaults.
set_target_properties(SDL2 PROPERTIES OUTPUT_NAME SDL2 NO_SONAME OFF VERSION "" SOVERSION "")
# An import library for the user's own pack generator; never put generated game code in the APK.
add_custom_command(TARGET tmnt_runtime POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${ROOT}/build/android-runtime/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:tmnt_runtime>"
        "${ROOT}/build/android-runtime/${ANDROID_ABI}/libtmnt_runtime.so")
