cmake_minimum_required(VERSION 3.4.1)

# Add the project command (required)
project(syncmusic)

# Find the Oboe package
find_package(oboe REQUIRED CONFIG)

# Create your native library with actual source files
add_library(oboe-audio SHARED
    # Replace these with your actual C++ source files
    OboeAudioPlayer.cpp
    # Add more source files as needed
    # audio_engine.cpp
    # websocket_handler.cpp
)

# Link Oboe to your library
target_link_libraries(oboe-audio
    oboe::oboe
    log
    android
)
