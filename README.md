# TikTok Camera Folder Fix

A lightweight Android utility that automatically moves media downloaded by TikTok from the **Camera** directory into a dedicated folder.

## Why?

Some versions of TikTok save downloaded photos and videos directly into the device's **Camera** folder.

This causes several problems:

* Downloaded media gets mixed with personal photos.
* Gallery applications become cluttered.
* Cloud backup services upload unnecessary files.
* Photo organization based on the Camera folder is disrupted.

This application solves the issue by automatically relocating those files as soon as they appear.

## How it works

```
                    +------------------+
                    |      TikTok      |
                    |  Download Media  |
                    +---------+--------+
                              |
                              v
                    +------------------+
                    |   DCIM/Camera    |
                    +---------+--------+
                              |
                              v
                    +------------------+
                    |   File Observer  |
                    +---------+--------+
                              |
                              v
             +--------------------------------+
             | Filename = 32-char Identifier? |
             +---------+-------------+--------+
                       |             |
                    No |             | Yes
                       |             |
                       v             v
                +-----------+   +----------------+
                |  Ignore   |   |   Move File    |
                +-----------+   +--------+-------+
                                         |
                                         v
                           +--------------------------+
                           | Pictures/TikTok Downloads|
                           +-------------+------------+
```

The application watches the configured source directory for newly created media files.

Instead of scanning every file, it only reacts to files whose names match TikTok's current naming convention:

```
[32-character identifier].jpg
[32-character identifier].jpeg
[32-character identifier].png
[32-character identifier].webp
[32-character identifier].mp4
```

Examples:

```
4e7a5dbef7b24ab69ef8d9d3c90fb4d2.jpg
bb30d62ec93f4c80b80b5332d4d1ab84.mp4
```

When such a file appears, it is immediately moved to the configured destination directory.

The current implementation intentionally relies on this filename pattern instead of metadata inspection or media analysis. This keeps the application lightweight while solving the problem effectively.

## Features

* Real-time directory monitoring
* Automatic media relocation
* Supports images and videos
* Very low resource usage
* Lightweight implementation
* Open source

## Supported formats

```
jpg
jpeg
png
webp
mp4
```

## Why not use metadata?

The application could inspect EXIF data, MediaStore entries, timestamps, or other metadata to identify TikTok downloads.

However, this additional complexity currently provides little practical benefit. Since TikTok consistently generates media files using a unique 32-character identifier as the filename, matching this pattern has proven to be a reliable and efficient solution.

If this behavior changes in future TikTok releases, more advanced detection methods can be introduced without affecting the overall architecture.

## Installation

Download the latest APK from the project's Releases page and install it.

Grant the required storage permissions, and the application will automatically monitor the configured directory.

## Building

```bash
git clone https://github.com/farukseker/TikTok-Camera-Fix.git

cd TikTok-Camera-Fix

./gradlew assembleRelease
```

## License

Released under the MIT License.

## Contributions

Issues and pull requests are welcome.
