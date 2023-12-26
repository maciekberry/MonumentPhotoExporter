# MonumentPhotoExporter
A script for exporting all photos from a Monument M2 device (www.getmonument.com).

This script is meant for the M2 owners that want to export their photos from the device with following features:

- Keeping the folder and album structure for all users
- Moving the photos to the appropriate albums
- Correctly handling the shared folders/albums edge cases (example: folder of user A, inside an album of user B and inside a photo from user C)
- Photos without album are copied to "PHOTOS_WITHOUT_ALBUM" folder for each user separately

The script does NOT currently manage the edited photos when the M2 stores the exif metadata in the database. 
I do not have many of such files. If anyone is interested in the feature, let me know, I can add it.

Usage:
java Main <source directory> <destination directory> --dry-run

Source directory must point to the monument root folder (containing "monument" and "Other Files" folders).
Destination directory must exists. The program does not verify the amount of space available on the export storage.
Use the --dry-run optional option before you do the actual export for checking the output.

The script DOES NOT modify anything on the monument drive - the database is opened in read-only mode and files are only
read. Nothing is written to the drive.

