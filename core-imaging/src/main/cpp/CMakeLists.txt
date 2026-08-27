cmake_minimum_required(VERSION 3.22.1)

project("core-imaging")

find_package(OpenCV QUIET)

add_library(
    imaging_native
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

find_library(
    jnigraphics-lib
    jnigraphics
)

if (OpenCV_FOUND)
    target_include_directories(
        imaging_native
        PRIVATE
        ${OpenCV_INCLUDE_DIRS}
    )
    target_link_libraries(
        imaging_native
        ${log-lib}
        ${jnigraphics-lib}
        ${OpenCV_LIBS}
    )
    target_compile_definitions(imaging_native PRIVATE HAVE_OPENCV=1)
else()
    target_link_libraries(
        imaging_native
        ${log-lib}
        ${jnigraphics-lib}
    )
endif()
