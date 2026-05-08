cmake_minimum_required(VERSION 3.22.1)

project(xray_launcher)

add_library(xray_launcher SHARED xray_launcher.c)

target_link_libraries(xray_launcher log)
