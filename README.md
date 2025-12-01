# Monument Photo Exporter

**Version 0.4** — 2025-12-01

## AUTHORS

- **Maciekberry** - initial version and current maintainer
- **Frava735** - the author of all advanced features: nesting, tags export, gps, etc

---

## Quick Start / TL;DR

Export all photos from a Monument M2 device with album structure, edits, captions, GPS, and tags.

### Command

```bash
java -jar MonumentPhotoExporter-0.4.jar --source <source_dir> --dest <destination_dir> [options]
```

### Required

- **`--source <path>`**: Path to Monument disk (`monument/.userdata/m.sqlite3` must exist)
- **`--dest <path>`**: Destination directory (will be created if doesn't exist). Be careful to have enough free space.

### Options

| Option | Description                                                                                                      |
|--------|------------------------------------------------------------------------------------------------------------------|
| `--dry-run` | Simulate export without copying files                                                                            |
| `--flatten` | Flatten all nested folders under album level - the destination folder will be a concatenation of the folder path |
| `--save-edits` | Export edited images (`_edited` suffix)                                                                          |
| `--save-comments` | Export Monument captions to EXIF                                                                                 |
| `--export-gps` | Export GPS coordinates to EXIF (always written, never skipped)                                                   |
| `--export-tags` | Export Monument tags to EXIF Keywords (standardized format)                                                      |
| `--tags-as-folders` | Create tag folders with photo copies                                                                             |
| `--help` | Show help message                                                                                                |

### ⚠️ Warnings

- **Disk space is not checked**; the tool will stop after filling the space
- **Read-only operation**: The tool **NEVER modifies** the Monument disk, database, or source files. All writes are **only to the destination directory**.
- **Test with a small subset first** using `--dry-run`


## DESCRIPTION

**Monument Photo Exporter** is a Java utility that exports all photos and videos from a **Monument M2** device into an organized folder structure.  
It preserves albums and users, supports edited images, EXIF metadata (including GPS, captions, and tags), and provides a `--dry-run` simulation mode for safe operation.



## WHY

As of 2023, **Monument Labs**, creators of the Monument M1 and M2 photo devices, appear to be inactive.  
The devices and servers still function, but there is **no customer support or contact**.

This exporter allows users to **recover their complete photo libraries** directly from the Monument M2 internal disk, keeping the album and folder hierarchy intact — something the official export function does not do.

---

## FEATURES

- ✅ Preserves **user / album / date-based folder structure**
- ✅ Correctly handles **shared folders and albums**
- ✅ Exports **edited photos** with `_edited` suffix and full EXIF metadata
- ✅ Writes **Monument captions** into EXIF `ImageDescription`
- ✅ **GPS coordinates ALWAYS written** to EXIF (separate fields, never skipped)
- ✅ Exports **Monument tags** to EXIF in **standardized format** (`Keywords: tag1, tag2, tag3`)
- ✅ **Smart truncation**: Automatically truncates captions/tags to fit EXIF limits
- ✅ Creates **tag-based folders** with photo copies (all tags included in EXIF)
- ✅ Optionally **flattens folder hierarchy** (`--flatten`)
- ✅ Handles **file name collisions** with automatic renaming (checksum-based)
- ✅ **NTFS-safe naming** (removes trailing dots and forbidden characters)
- ✅ Preserves **original file timestamps** (modification time from taken_at)
- ✅ Generates **detailed logs and per-album/per-tag statistics**
- ✅ **Automatic cleanup** of temporary files (.tmp)

---

## USAGE EXAMPLES

### Basic export

```bash
java -jar MonumentPhotoExporter-0.13.jar --source /media/monument --dest /export/photos
```

### Export with all options

```bash
java -jar MonumentPhotoExporter-0.13.jar --source /media/monument --dest /export/photos \
  --flatten --save-edits --save-comments --export-gps --export-tags
```

### Export with tags as folders

```bash
java -jar MonumentPhotoExporter-0.13.jar --source /media/monument --dest /export/photos \
  --tags-as-folders --save-comments --export-gps --export-tags
```

### Dry-run simulation

```bash
java -jar MonumentPhotoExporter-0.13.jar --source /media/monument --dest /export/photos \
  --dry-run --flatten --export-tags
```

---

## OUTPUT STRUCTURE

### Without `--flatten` (Hierarchical):

```
destination/
  ├─ user_name_id/
  │  ├─ FolderName/
  │  │  ├─ album1/
  │  │  │  ├─ photo.jpg
  │  │  │  └─ photo_edited.jpg
  │  └─ PHOTOS_WITHOUT_ALBUM/
  │     └─ 2023/07/14/
  │        └─ photo.jpg
```

### With `--flatten`:

```
destination/
  ├─ user_name_id/
  │  ├─ FolderName - album1/
  │  │  ├─ photo.jpg
  │  │  └─ photo_edited.jpg
  │  └─ PHOTOS_WITHOUT_ALBUM/
  │     └─ photo.jpg
```

### With `--tags-as-folders`:

```
destination/
  ├─ user_name_id/
  │  ├─ album1/
  │  │  └─ photo.jpg
  │  └─ tags/
  │     ├─ Tag_vacation/
  │     │  └─ photo.jpg (copy with all tags)
  │     ├─ Tag_paris/
  │     │  └─ photo.jpg (copy with all tags)
  │     └─ Tag_family/
  │        └─ photo.jpg (copy with all tags)
```

> 🗂️ **Without `--flatten`**, photos are organized into the original folder/album hierarchy.  
> **With `--tags-as-folders`**, photos are duplicated into tag folders, and **each copy contains ALL tags** in EXIF, not just the folder tag.

---

## EXIF METADATA FORMAT

Tags are now written in **industry-standard format** for maximum compatibility:

```
ImageDescription: Beautiful sunset at the Eiffel Tower

This was taken during our summer vacation.

Keywords: paris, vacation, sunset, eiffel-tower, 2024
```

The `Keywords:` format is **universally recognized** by:
- ✅ **Samsung Gallery** (Android)
- ✅ **Google Photos**
- ✅ **Immich** (auto-parsing)
- ✅ **Ente**
- ✅ **Eye of GNOME** (Linux)
- ✅ Most photo management applications

### Complete Metadata Example

When using `--save-comments`, `--export-gps`, and `--export-tags`:

```
EXIF ImageDescription:
  Beautiful sunset at the Eiffel Tower
  
  This was taken during our summer vacation.
  
  Keywords: paris, vacation, sunset, eiffel-tower, 2024

GPS (separate EXIF fields):
  Latitude: 48.858370 N
  Longitude: 2.294481 E
```

### Smart Truncation (v0.13)

**GPS is ALWAYS written** (separate EXIF fields, not affected by size limits).

For captions and tags, the tool uses **progressive truncation** to fit EXIF limits:

1. **Attempt 1**: Caption 500 chars + Tags 300 chars
2. **Attempt 2**: Caption 300 chars + Tags 200 chars
3. **Attempt 3**: Caption 150 chars + Tags 100 chars
4. **Attempt 4**: Caption 100 chars + Tags 50 chars
5. **Attempt 5**: Caption 50 chars + Tags 0 (GPS only)
6. **Final**: Caption 0 + Tags 0 (GPS only, if captions still too large)

**Messages:**
```
INFO: EXIF written with truncated text (attempt 3)
WARNING: Caption truncated from 850 to 300 characters
```

This ensures:
- 📍 **GPS is NEVER skipped** (always in separate EXIF fields)
- 📝 **Captions preserved** when possible, truncated intelligently if needed
- 🏷️ **Tags preserved** when possible, truncated or skipped if EXIF is too large
- 🚫 **No APP1 overflow errors** on Samsung/modern phones with extensive EXIF

---

## FILE NAMING AND COLLISION HANDLING

### Collision Resolution

When files with the same name exist in the destination:

1. Try original filename: `photo.jpg`
2. Add checksum: `photo-abc123def.jpg`
3. Add counter: `photo-abc123def_1.jpg`, `photo-abc123def_2.jpg`, etc.

This ensures **no files are overwritten** and duplicates are tracked in statistics.

### Filename Sanitization

Invalid characters for NTFS/ext4 are replaced with underscore:
- Removed: `/` `\` `:` `*` `?` `"` `<` `>` `|`
- Trailing dots and spaces removed
- Empty names → `unnamed`

### Album/Folder Name Sanitization

Same rules as filenames, plus:
- Empty names → `unnamed_album`
- Tag folders prefixed with `Tag_`

---

## VIDEO SUPPORT

### Current Support

- ✅ Videos are **copied** to destination
- ✅ Timestamps preserved
- ✅ Included in export statistics and file count
- ❌ No EXIF metadata (EXIF only works for JPEG/JPG images)

### Formats

The exporter copies **all files** from Monument, including common video formats:
- MP4, MOV, AVI, MKV, etc.

**Note**: EXIF metadata (captions, GPS, tags) is only written to JPEG/JPG images. Videos are copied as-is without metadata modifications.

---

## EDITED PHOTOS

When `--save-edits` is enabled:

### Filename Convention

```
original.jpg       → Original photo
original_edited.jpg → Edited version
```

### Metadata on Edited Photos

- Caption includes `\n\nedited` marker
- GPS coordinates copied from original (if present)
- Camera make/model set to "Monument M2"
- Timestamp from original photo (or database `taken_at`)

### Detection

Edited photos are detected from the `ContentEdit` table with `status = 1`.

---

## TIMESTAMP PRESERVATION

File modification times are set from:

1. **Primary**: Original file's timestamp
2. **Fallback**: `taken_at` from database (if original missing)
3. **Edited files**: Original photo's timestamp, or `stored_at` from ContentEdit

All timestamps are preserved across all export modes.

---

## STATISTICS AND REPORTING

At completion, displays comprehensive statistics with **separate counters**:

```
================================================
 EXPORT SUMMARY
================================================
Mode: REAL EXPORT
Option: FLATTEN enabled
Option: EXPORT-GPS enabled
Option: EXPORT-TAGS enabled
Option: TAGS-AS-FOLDERS enabled

DATABASE STATISTICS:
  Total entries in database: 100000
  Unique files processed: 100000

FILE OPERATIONS:
  Total physical files copied: 147000
    - Main album copies: 100000
    - Tag folder copies: 46500
    - Edited versions: 500
  Files renamed (collisions): 1234
  Files with owner change: 5

EXIF METADATA:
  Comments written: 45678
  Captions truncated: 234
  GPS coordinates written: 38901
  Tags written: 42000

TAG FOLDERS:
  Photos copied to tag folders: 46500
  Top 20 tags by photo count:
    - vacation: 3456 photo(s)
    - family: 2890 photo(s)
    - paris: 1234 photo(s)
    ...

================================================
SUCCESS: Export completed
================================================
```

### Detailed Logging 

Each file export shows **DB entry count vs physical files**:

```
[DB:00001 | Files:00001][FLATTENED]
  Content ID : 12345
  Source     : /monument/user_1/photos/IMG_1234.jpg
  Album      : Summer Vacation
  Destination: /backup/photos/john_123/Vacation/IMG_1234.jpg

[DB:00002 | Files:00005][FLATTENED] [TAG_COPY]
  Content ID : 12346
  Source     : /monument/user_1/photos/IMG_5678.jpg
  Album      : Paris Trip
  Destination: /backup/photos/john_123/tags/Tag_paris/IMG_5678.jpg
```

- **DB**: Database entries processed (unique photos)
- **Files**: Total physical files written (includes tag folder copies)

### Warnings

The tool logs warnings for:
- Missing source files
- EXIF truncation (when captions/tags reduced to fit)
- Tag folder export issues
- Ownership changes
- Database query errors
- Temporary file cleanup

---
## REQUIREMENTS

- ☕ **Java 17 or newer**
- 💾 Monument disk with `m.sqlite3` database present
- 📂 Destination directory (created if doesn't exist)
- 💿 Sufficient free space must be available on the destination disk (**not checked by the tool**)

---

## LIMITATIONS

- ❌ Processes only **non-deleted content** (`deleted_at IS NULL`)
- 🖼️ EXIF writing supports **JPEG/JPG files only**
- 🔤 Captions with non-ASCII characters are converted to ASCII equivalents
- 🗻 GPS altitude **not available** (not stored in Monument database)
- 📏 **EXIF size limit**: 60KB to prevent APP1 segment overflow
- 💾 **Destination disk space is not checked**; if insufficient, the tool will stop or crash

---

## TECHNICAL DETAILS

| Component | Version / Library |
|-----------|-------------------|
| **Database** | SQLite 3.45.0.0 |
| **EXIF Library** | Apache Commons Imaging 1.0-alpha3 |
| **Metadata Library** | metadata-extractor 2.19.0 |
| **Java Target** | 17 |

---

## AUTHOR & DISCLAIMER

**Originally created by** [Maciekberry](https://github.com/maciekberry/MonumentPhotoExporter)  
**Enhanced by** Frava735 (v0.4)

**Disclaimer**: Use at your own risk. There are **no guarantees** regarding the correctness or safety of the export.  
It is **strongly recommended** to test the tool on a small subset of your data first before performing a full export.

---

## LICENSE

See the included `LICENSE` file for details.

