# MonumentPhotoExporter

## Change log 0.2

I am publishing the v0.2 with following changes:

- The "PHOTOS_WITHOUT_ALBUM" photos are now organized into subfolders (year/month/date). This solves the problem 
  of duplicate file names (thanks @goeland86!)
- The readme file has been updated :)


## About

A script for exporting all photos from a Monument M2 device (www.getmonument.com).

## Why?

As of 2023, the Monument Labs - the company behind the extremely nice Monument M2 product seems dead. The servers work,
the device works, but there is no support and not contact with them on the discord channel.

Therefore, I have scratched the itch and started to reverse-engineer the monument database on the disk (a standard
SQL lite database) in order to be able to export my 30000+ photos and videos from the device with their structure.

The last point is the most important. The standard "export" feature of Monument DOES NOT include the albums. Only raw
files are exported - in folders "per camera", which makes this feature absolutely useless in my use case (many users,
many custom folders, many albums).

If you find it useful, drop me a line. If you see something wrong or are unable to use it, also contact me - I am available
in the monument discord.

## Features


This script is meant for the M2 owners that want to export their photos from the device with following features:

- Keeping the folder and album structure for all users
- Moving the photos to the appropriate albums
- Correctly handling the shared folders/albums edge cases (see below)
- Photos without album are copied to "PHOTOS_WITHOUT_ALBUM" folder for each user separately
- Photos in "incoming" folder are copied to the appropriate user folder, with no album
- The deleted files are not exported
- The faces database is not exported (it does not work very well anyway)
- The structure of cameras is not exported (personally, I do not care about it at all)

## Important

The exported structure is folder and album based. This solves the following situation:

- User A creates a folder and shares with other users
- User B creates an album inside the shared folder from user A
- User C places a photo inside the shared album of the user B

=> the script will export the photo inside the structure of user "A". This corresponds to my use case and 
hopefully also to yours.

Important: the script does NOT currently manage the edited photos when the M2 stores the exif metadata in the database. 
I do not have many of such files. If anyone is interested in the feature, let me know, I can try to add it

## Usage

The script is local based: you must take the disk out of your monument and connect it to your computer before launching.

It was tested on Linux Mint 21.

Command line:

java -jar MonumentPhotoExporter-0.1.jar \<source directory\> \<destination directory\> --dry-run

Source directory must point to the monument root folder (containing "monument" and "Other Files" folders).
Destination directory must exists. The program does not verify the amount of space available on the export storage.
Use the --dry-run optional option before you do the actual export for checking the output.

On windows 11 and jdk 17.0.7 rebuild the project:
javac -cp lib\sqlite-jdbc-3.43.0.0.jar -d bin src\main\java\*.java

And run:
java -cp bin;lib\sqlite-jdbc-3.43.0.0.jar Main \<source directory\> \<destination directory\> --dry-run

## Is it safe?

The script DOES NOT modify anything on the monument drive - the database is opened in read-only mode and files are only
read. Nothing is written to the drive.

## How does it work? Caveats

Please note that the entire behaviour is based on my reverse-engineering, I have not got any access to any documentation.
I am pretty sure I understand most of the features, but there are things that let me stumbled, indeed (for example,
what the hell is the format of the data in "ContentEdit>edits" column?)

The export script is based on the internal Monument database (a file in SQLite format, in ".userdata" folder). Therefore, 
it only copies the files recorded in the database. In my database, the following things were observed:

- In most of the cases, for HEIC files, monument also creates an extracted "MOV" file. The mov file is not exported.
- Very rarely (several cases in my 30k+ database), there are files not recorded in the database. After manual inspection, 
they were actual photos but not interesting ones, so I suspect, these were deleted and monument did not delete the files.
- In some cases, monument stores an edited photo next to the original one with a slightly modified name (prefix "edit" or "1").
Because I have only very few files edited that way, I have decided to only copy the original. 
- During the tests, I encountered once a situation when a file from the database did not exist in the disk. I was
unable to reproduce it, so the script ignores such a situation with an appropriate message.

## Future

If anyone finds this interesting, I will be very happy :) If you have any non-managed situation in your monument database,
let me know, I can work on it.

