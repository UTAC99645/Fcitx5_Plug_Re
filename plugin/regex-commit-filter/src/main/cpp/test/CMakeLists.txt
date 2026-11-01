cmake_minimum_required(VERSION 3.18)

project(regexcommit_rule_engine_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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

add_executable(rule_engine_tests test_main.cpp ../rule_engine.cpp)
target_include_directories(rule_engine_tests PRIVATE .. ../third_party)
target_link_libraries(rule_engine_tests PRIVATE re2)
