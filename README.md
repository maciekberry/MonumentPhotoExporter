# MonumentPhotoExporter

A script for exporting all photos from a Monument M2 device (www.getmonument.com).

## Why?

As of 2023, the Monument Labs - the company behind the extremely nice Monument M2 product seems dead. The servers work,
the device works, but there is no support and not contact with them on the discord channel.

Therefore, I have scratched the itch and started to reverse-engineer the monument database on the disk (a standard
SQL lite database) in order to be able to export my 30000+ photos and videos from the device with their structure.

**The last point is the most important. The standard "export" feature of Monument DOES NOT include the albums. Only raw
files are exported - in folders "per camera", which makes this feature absolutely useless in my use case (many users,
many custom folders, many albums).**

If you find it useful, drop me a line. If you see something wrong or are unable to use it, also contact me - I am available
in the monument discord.

## Features


This script is meant for the M2 owners that want to export their photos from the device with following features:

- Keeping the folder and album structure for all users
- Moving the photos to the appropriate albums
- Correctly handling the shared folders/albums edge cases (example: folder of user A, inside an album of user B and inside a photo from user C)
- Photos without album are copied to "PHOTOS_WITHOUT_ALBUM" folder for each user separately
- The deleted files are not exported
- The faces database is not exported (it does not work very well anyway)

Important: the script does NOT currently manage the edited photos when the M2 stores the exif metadata in the database. 
I do not have many of such files. If anyone is interested in the feature, let me know, I can add it.

Usage:
java Main \<source directory\> \<destination directory\> --dry-run

Source directory must point to the monument root folder (containing "monument" and "Other Files" folders).
Destination directory must exists. The program does not verify the amount of space available on the export storage.
Use the --dry-run optional option before you do the actual export for checking the output.

## Is it safe?

The script DOES NOT modify anything on the monument drive - the database is opened in read-only mode and files are only
read. Nothing is written to the drive.

