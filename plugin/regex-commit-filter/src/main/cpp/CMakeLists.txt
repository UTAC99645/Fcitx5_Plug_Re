cmake_minimum_required(VERSION 3.18)

project(fcitx5-android-plugin-regex-commit VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(ECM MODULE)
find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --- RE2 (pinned) and its abseil dependency -------------------------------
include(FetchContent)

set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
# abseil must export its targets so re2's install(EXPORT) can reference them.
set(ABSL_ENABLE_INSTALL ON CACHE BOOL "" FORCE)
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
        abseil
        GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
        GIT_TAG 20240722.1
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(abseil)

set(RE2_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
        re2
        GIT_REPOSITORY https://github.com/google/re2.git
        GIT_TAG 2024-07-02
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(re2)
# --------------------------------------------------------------------------

add_fcitx5_addon(regexcommit
        regexcommitaddon.cpp
        rule_engine.cpp
        rulesocket.cpp)

target_include_directories(regexcommit PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
target_link_libraries(regexcommit Fcitx5::Core re2)

# The addon .so is packaged by AGP as a JNI library and loaded from the plugin's
# nativeLibraryDir, so it must NOT also be installed into assets/usr.

configure_file(regexcommit.conf.in.in regexcommit.conf.in @ONLY)
fcitx5_translate_desktop_file(${CMAKE_CURRENT_BINARY_DIR}/regexcommit.conf.in regexcommit.conf)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/regexcommit.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config)
