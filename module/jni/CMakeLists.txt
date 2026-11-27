cmake_minimum_required(VERSION 3.22)
project(dl_guard CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -Wall -Wno-unused-parameter -D_GNU_SOURCE")

add_library(dl_guard SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/../module.cpp
)

target_include_directories(dl_guard PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
)

target_link_libraries(dl_guard log)
