cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(action_relay_combined)
target_sources(app PRIVATE src/main.c src/hr_measurement.c src/activity.c ../protocol/miwear_auth.c)
