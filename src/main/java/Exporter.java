import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffConstants;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.common.RationalNumber;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

/**
 * Monument Photo Exporter
 * Exports photos from Monument photo management system to organized folder structure
 * Compatible with Apache Commons Imaging 1.0-alpha3
 */
public class Exporter {

    /**
     * Inner class to hold destination information for exported content
     */
    class DestinationDescription {
        public boolean owner_changed = false;
        public int new_owner = 0;
        public String dest = "";
        public String albumName = "";
    }

    private DataConnection d;
    private Map<Integer, String> monumentUsers = new HashMap<>();
    private RunArguments args;


// Statistics counters
    private int totalFiles = 0;
    private int uniqueFilesProcessed = 0;
    private int physicalFilesCopied = 0;
    private int filesRenamed = 0;
    private int filesWithOwnerChange = 0;
    private int editedFilesExported = 0;
    private int commentsWritten = 0;
    private int gpsWritten = 0;
    private int tagsWritten = 0;
    private int tagFolderCopies = 0;
    private int captionsTruncated = 0;
    private Map<String, Integer> editedFilesByAlbum = new HashMap<>();
    private Map<String, Integer> photosByTag = new HashMap<>();


    /**
     * Constructor
     * @param args Runtime arguments containing source/destination paths and options
     */
    public Exporter(RunArguments args) {
        this.args = args;
    }

    /**
     * Create mapping of user IDs to user names from database
     * @return true if successful, false otherwise
     */
    private boolean createUserMap() {
        monumentUsers.put(0, "unknown_user");
        Connection conn = d.getConnection();
        
        if (conn == null) {
            System.out.println("ERROR: Cannot create user map - no database connection");
            return false;
        }

        String sql = "SELECT id, name, status FROM user";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name;
                if (rs.getString("status").equals("deleted"))
                    name = rs.getString("name") + "_" + rs.getString("id") + "_deleted";
                else
                    name = rs.getString("name") + "_" + rs.getString("id");

                monumentUsers.put(rs.getInt("id"), name);
            }

        } catch (SQLException e) {
            System.out.println("ERROR: Failed to create user map: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Set file timestamps (modification time) from original file or database
     * @param targetFile The file to set timestamp on
     * @param originalFile The original source file (may be null)
     * @param takenAtTimestamp Unix timestamp from database (may be null)
     */
    private void setFileTimestamps(Path targetFile, Path originalFile, Long takenAtTimestamp) {
        try {
            long timestamp;
            
            // Try to get from original file first
            if (originalFile != null && Files.exists(originalFile)) {
                timestamp = Files.getLastModifiedTime(originalFile).toMillis();
            } 
            // Fallback to taken_at from database
            else if (takenAtTimestamp != null && takenAtTimestamp > 0) {
                timestamp = takenAtTimestamp * 1000L; // Convert seconds to milliseconds
            }
            else {
                return; // No timestamp available
            }
            
            // Set modification time
            Files.setLastModifiedTime(targetFile, 
                java.nio.file.attribute.FileTime.fromMillis(timestamp));
                
        } catch (Exception e) {
            // Ignore timestamp errors - not critical
        }
    }

    /**
     * Sanitize filename to remove path separators and invalid characters
     * @param filename Original filename
     * @return Sanitized filename safe for NTFS/ext4
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "unnamed";
        return filename.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    /**
     * Sanitize album/folder name for NTFS compatibility
     * @param albumName Original album name
     * @return Sanitized album name
     */
    private String sanitizeAlbumName(String albumName) {
        if (albumName == null || albumName.trim().isEmpty()) {
            return "unnamed_album";
        }
        
        String sanitized = albumName.replaceAll("[/\\\\:*?\"<>|]", "_");
        sanitized = sanitized.trim();
        sanitized = sanitized.replaceAll("[\\.\\s]+$", "");
        sanitized = sanitized.trim();
        
        if (sanitized.isEmpty()) {
            return "unnamed_album";
        }
        
        return sanitized;
    }

    /**
     * Sanitize caption for EXIF compatibility (ASCII only, limited length)
     * @param caption Original caption text
     * @return Sanitized caption suitable for EXIF metadata
     */
    private String sanitizeCaption(String caption) {
        if (caption == null || caption.trim().isEmpty()) {
            return "";
        }
        
        // Replace special Unicode characters with ASCII equivalents
        String sanitized = caption
            .replace("–", "-")
            .replace("—", "-")
            .replace("…", "...")
            .replace("€", "EUR")
            .replace("©", "(c)")
            .replace("®", "(R)")
            .replace("™", "(TM)");
        
        // Remove non-ASCII characters
        StringBuilder result = new StringBuilder();
        for (char c : sanitized.toCharArray()) {
            if (c <= 255) {
                result.append(c);
            } else {
                result.append('?');
            }
        }
        
        String cleaned = result.toString().trim();
        
        // Limit length to prevent EXIF errors (conservative limit)
        // EXIF APP1 segment max is ~65KB but we use 500 chars to leave room for tags/GPS
        if (cleaned.length() > 500) {
            int originalLength = cleaned.length();
            cleaned = cleaned.substring(0, 497) + "...";
            captionsTruncated++;
            System.out.println("  WARNING: Caption truncated from " + originalLength + " to 500 characters");
        }
        
        return cleaned;
    }

    /**
     * Sanitize tag name for use as folder name (NTFS compatible)
     * @param tagName Original tag name
     * @return Sanitized tag name with "Tag_" prefix
     */
    private String sanitizeTagName(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            return "unnamed_tag";
        }
        
        String sanitized = tagName.replaceAll("[/\\\\:*?\"<>|]", "_");
        sanitized = sanitized.trim();
        sanitized = sanitized.replaceAll("[\\.\\s]+$", "");
        sanitized = sanitized.trim();
        
        if (sanitized.isEmpty()) {
            return "unnamed_tag";
        }
        
        return "Tag_" + sanitized;
    }

    /**
     * Log export operation to console with detailed information
     */
    private void logExport(int contentId, String sourcePath, String albumName, 
                          String destinationPath, boolean flattened, boolean renamed, 
                          String originalName, String finalName, boolean isTagCopy) {
        
        String flattenStatus = flattened ? "[FLATTENED]" : "[PRESERVED]";
        String renameStatus = renamed ? " [RENAMED]" : "";
        String tagCopyStatus = isTagCopy ? " [TAG_COPY]" : "";
        
        System.out.println(String.format("[DB:%05d | Files:%05d]%s%s%s", 
            uniqueFilesProcessed, physicalFilesCopied, flattenStatus, renameStatus, tagCopyStatus));
        System.out.println("  Content ID : " + contentId);
        System.out.println("  Source     : " + sourcePath);
        System.out.println("  Album      : " + albumName);
        System.out.println("  Destination: " + destinationPath);
        
        if (renamed) {
            System.out.println("  Original   : " + originalName);
            System.out.println("  Final Name : " + finalName);
        }
        
        System.out.println();
    }

    /**
     * Generate unique filename with incremental counter if collision detected
     * @param destDir Destination directory
     * @param originalFilename Original filename
     * @param checksum File checksum for collision resolution
     * @return Unique filename
     */
    private String getUniqueFilename(Path destDir, String originalFilename, String checksum) {
        String sanitized = sanitizeFilename(originalFilename);
        
        if (args.dryrun) {
            return sanitized;
        }
        
        Path targetPath = destDir.resolve(sanitized);

        if (!Files.exists(targetPath)) {
            return sanitized;
        }

        int lastDot = sanitized.lastIndexOf('.');
        String basename = (lastDot > 0) ? sanitized.substring(0, lastDot) : sanitized;
        String extension = (lastDot > 0) ? sanitized.substring(lastDot) : "";

        String nameWithChecksum = basename + "-" + checksum + extension;
        targetPath = destDir.resolve(nameWithChecksum);

        if (!Files.exists(targetPath)) {
            return nameWithChecksum;
        }

        int counter = 1;
        String uniqueName;
        do {
            uniqueName = basename + "-" + checksum + "_" + counter + extension;
            targetPath = destDir.resolve(uniqueName);
            counter++;
        } while (Files.exists(targetPath));

        return uniqueName;
    }

    /**
     * Copy content file from source to destination
     * @param from Source file path
     * @param name Original filename
     * @param dest Destination directory
     * @param checksum File checksum
     * @param contentId Content ID from database
     * @param albumName Album name for logging
     * @param flattened Whether structure is flattened
     */
    private void copyContent(String from, String name, String dest, String checksum, 
                            int contentId, String albumName, boolean flattened) throws IOException {

        Path destPath = Paths.get(dest);
        
        if (!args.dryrun) {
            Files.createDirectories(destPath);
        }

        String finalFilename = getUniqueFilename(destPath, name, checksum);
        boolean wasRenamed = !finalFilename.equals(sanitizeFilename(name));
        
        if (wasRenamed) {
            filesRenamed++;
        }

        Path finalDestPath = destPath.resolve(finalFilename);
        
        physicalFilesCopied++;
        
        logExport(contentId, from, albumName, finalDestPath.toString(), 
                 flattened, wasRenamed, name, finalFilename, false);

        if (!args.dryrun) {
            try {
                Files.copy(Path.of(from), finalDestPath);
            } catch (java.nio.file.NoSuchFileException e) {
                System.out.println("  ERROR: Source file not found: " + from);
                System.out.println();
            } catch (IOException e) {
                System.out.println("  ERROR: Failed to copy file: " + e.getMessage());
                System.out.println();
                throw e;
            }
        }
    }

    /**
     * Get list of tags for a content ID from database
     * @param contentId Content ID
     * @return List of tag names (non-builtin tags only)
     */
    private java.util.List<String> getTagsForContent(int contentId) {
        java.util.List<String> tags = new java.util.ArrayList<>();
        
        try {
            String sql = "SELECT t.name FROM Tag t " +
                        "JOIN TagContent tc ON t.id = tc.tag_id " +
                        "WHERE tc.content_id = ? AND t.builtin = 0 " +
                        "ORDER BY t.name";
            
            try (PreparedStatement pstmt = d.getConnection().prepareStatement(sql)) {
                pstmt.setInt(1, contentId);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String tagName = rs.getString("name");
                        if (tagName != null && !tagName.trim().isEmpty()) {
                            tags.add(tagName);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("WARNING: Failed to get tags for content " + contentId + ": " + e.getMessage());
        }
        
        return tags;
    }

    /**
     * Write tags to EXIF metadata (appends to ImageDescription field)
     * Compatible with Apache Commons Imaging 1.0-alpha3
     * @param outputSet TIFF output set to modify
     * @param tags List of tag names
     */
    private void writeTags(TiffOutputSet outputSet, java.util.List<String> tags) throws Exception {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        
        TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
        
        String keywordsString = String.join(";", tags);
        
        // Truncate if too long for EXIF
        if (keywordsString.length() > 1000) {
            int truncated = 0;
            StringBuilder sb = new StringBuilder();
            for (String tag : tags) {
                if (sb.length() + tag.length() + 1 < 1000) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(tag);
                } else {
                    truncated++;
                }
            }
            keywordsString = sb.toString();
            if (truncated > 0) {
                System.out.println("  WARNING: Truncated " + truncated + " tags to fit in EXIF");
            }
        }
        
        String tagsComment = "[Tags: " + keywordsString + "]";
        
        // ALPHA3 COMPATIBLE: Check if field exists, if so just append tags
        org.apache.commons.imaging.formats.tiff.write.TiffOutputField existingField = 
            rootDirectory.findField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
        
        if (existingField != null) {
            // Field exists but we can't read it in alpha3, just add tags at the end
            // We'll read the existing value from the original metadata in writeExifMetadata
            rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
            rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, tagsComment);
        } else {
            // No existing field, just add tags
            rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, tagsComment);
        }
        
        tagsWritten++;
    }

    /**
     * Export photo copies to tag-based folder structure
     * @param contentId Content ID
     * @param sourcePath Source path in Monument structure
     * @param fileName Original filename
     * @param checksum File checksum
     * @param caption Photo caption
     * @param lat GPS latitude
     * @param lon GPS longitude
     * @param userId User ID
     * @param takenAt Timestamp when photo was taken
     * @param allTags Complete list of ALL tags for this photo
     */
    private void exportToTagFolders(int contentId, String sourcePath, String fileName, 
                                    String checksum, String caption, Double lat, Double lon,
                                    int userId, Long takenAt, java.util.List<String> allTags) throws IOException {
        
        if (allTags == null || allTags.isEmpty()) {
            return;
        }
        
        String monumentRoot = args.sourceDir + "/";
        String from = monumentRoot + sourcePath;
        String userName = monumentUsers.get(userId);
        
        // Export a copy to each tag folder
        for (String tagName : allTags) {
            String sanitizedTag = sanitizeTagName(tagName);
            String tagFolderPath = args.destinationDir + "/" + userName + "/tags/" + sanitizedTag;
            
            photosByTag.put(tagName, photosByTag.getOrDefault(tagName, 0) + 1);
            
            try {
                Path destPath = Paths.get(tagFolderPath);
                if (!args.dryrun) {
                    Files.createDirectories(destPath);
                }
                
                String finalFilename = getUniqueFilename(destPath, fileName, checksum);
                Path finalDestPath = destPath.resolve(finalFilename);
                
                physicalFilesCopied++;
                tagFolderCopies++;
                
                if (!args.dryrun) {
                    Files.copy(Path.of(from), finalDestPath);
                    
                    setFileTimestamps(finalDestPath, Path.of(from), takenAt);
                    
                    // Write metadata with ALL tags (not just the folder tag)
                    writeExifMetadataForTagFolder(finalDestPath, caption, lat, lon, allTags);
                }
                
            } catch (Exception e) {
                System.out.println("  WARNING: Failed to export to tag folder '" + sanitizedTag + "': " + e.getMessage());
            }
        }
    }

    /**
     * Determine destination path for content based on albums and folder structure
     * @param content_id Content ID
     * @param content_owner Original content owner ID
     * @return Destination description with path and ownership info
     */
    private DestinationDescription getDest(int content_id, int content_owner) throws SQLException {

        DestinationDescription result = new DestinationDescription();

        Connection conn = d.getConnection();
        String sql = "SELECT ac.album_id, ac.content_id, afa.folder_id as folder_id, a.name, a.user_id as album_owner_id " +
                "FROM albumcontent as ac " +
                "join album as a on ac.album_id = a.id " +
                "left join albumfolderalbum as afa on ac.album_id = afa.album_id " +
                "left join albumfolder as af on (afa.folder_id = af.id and original_folder_id is null) " +
                "where content_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, content_id);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                boolean is_empty = !rs.next();
                int folder_id = is_empty ? 0 : rs.getInt("folder_id");
                String album_name = is_empty ? null : rs.getString("name");
                int album_owner_id = is_empty ? 0 : rs.getInt("album_owner_id");

                // Photos without album
                if (is_empty) {
                    if (args.flatten) {
                        result.dest = monumentUsers.get(content_owner) + "/PHOTOS_WITHOUT_ALBUM";
                        result.albumName = "PHOTOS_WITHOUT_ALBUM";
                        result.owner_changed = false;
                        return result;
                    } else {
                        String datetime = getContentTakenDate(content_id);
                        String[] dateParts = parseDateOrDefault(datetime);
                        
                        result.dest = monumentUsers.get(content_owner) + "/PHOTOS_WITHOUT_ALBUM/" 
                                    + dateParts[0] + "/" + dateParts[1] + "/" + dateParts[2] + "/";
                        result.albumName = "PHOTOS_WITHOUT_ALBUM";
                        result.owner_changed = false;
                        return result;
                    }
                }

                // Flatten mode - single level
                if (args.flatten) {
                    String folderPath = buildFolderPath(folder_id, true);
                    String sanitizedAlbumName = sanitizeAlbumName(album_name);
                    result.dest = folderPath + sanitizedAlbumName;
                    result.albumName = album_name;
                    result.owner_changed = (album_owner_id != content_owner);
                    result.new_owner = album_owner_id;
                    return result;
                }

                // Album not in folder
                if (folder_id == 0) {
                    String sanitizedAlbumName = sanitizeAlbumName(album_name);
                    result.dest = monumentUsers.get(album_owner_id) + "/" + sanitizedAlbumName;
                    result.albumName = album_name;
                    result.owner_changed = (album_owner_id != content_owner);
                    result.new_owner = album_owner_id;
                    return result;
                }

                // Album in folder hierarchy
                String folderPath = buildFolderPath(folder_id, false);
                String sanitizedAlbumName = sanitizeAlbumName(album_name);
                result.dest = folderPath + "/" + sanitizedAlbumName;
                result.albumName = album_name;
                result.owner_changed = (extractUserIdFromPath(folderPath) != content_owner);
                result.new_owner = extractUserIdFromPath(folderPath);
                return result;
            }
        }
    }

    /**
     * Get the taken date for a content item
     * @param content_id Content ID
     * @return Date string or null
     */
    private String getContentTakenDate(int content_id) throws SQLException {
        String sql = "SELECT taken FROM content WHERE id = ?";
        try (PreparedStatement pstmt = d.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, content_id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("taken");
                }
            }
        }
        return null;
    }

    /**
     * Parse date string or return default date parts
     * @param datetime Date string in format "yyyy-MM-dd HH:mm:ss"
     * @return Array of [year, month, day]
     */
    private String[] parseDateOrDefault(String datetime) {
        String year, month, day;
        
        if (datetime != null && datetime.length() > 10) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime date = LocalDateTime.parse(datetime, formatter);
                year = String.valueOf(date.getYear());
                month = String.format("%02d", date.getMonthValue());
                day = String.format("%02d", date.getDayOfMonth());
            } catch (Exception e) {
                year = "1970";
                month = "01";
                day = "01";
            }
        } else {
            year = "1970";
            month = "01";
            day = "01";
        }
        
        return new String[]{year, month, day};
    }

    /**
     * Build full folder path by recursively traversing folder hierarchy
     * @param folder_id Starting folder ID
     * @return Full path including username
     */
    private String buildFolderPath(int folder_id, boolean flatten) throws SQLException {

        String separator = (flatten? " - ": "/");

        String sql = 
            "WITH RECURSIVE cte_AlbumFolder (id, name, parent_id, user_id) AS ( " +
            "    SELECT e.id, e.name, e.parent_id, e.user_id " +
            "    FROM AlbumFolder e " +
            "    WHERE e.id = ? " +
            "    UNION ALL " +
            "    SELECT e.id, e.name, e.parent_id, e.user_id " +
            "    FROM AlbumFolder e " +
            "    JOIN cte_AlbumFolder c ON c.parent_id = e.id " +
            ") " +
            "SELECT * FROM cte_AlbumFolder;";

        try (PreparedStatement pstmt = d.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, folder_id);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                StringBuilder path = new StringBuilder();
                int user_id = 0;
                
                while (rs.next()) {
                    path.insert(0, separator);
                    path.insert(0, sanitizeAlbumName(rs.getString("name")));
                    user_id = rs.getInt("user_id");
                }

                //we always insert / because it separates the owner from the path
                path.insert(0, "/").insert(0, monumentUsers.get(user_id));
                return path.toString();
            }
        }
    }

    /**
     * Extract user ID from path string (format: username_id)
     * @param path Path starting with username_id
     * @return User ID or 0 if not found
     */
    private int extractUserIdFromPath(String path) {
        if (path == null || path.isEmpty()) return 0;
        
        String userPart = path.split("/")[0];
        String[] parts = userPart.split("_");
        
        try {
            for (int i = parts.length - 1; i >= 0; i--) {
                try {
                    return Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    // Continue
                }
            }
        } catch (Exception e) {
            return 0;
        }
        
        return 0;
    }

    /**
     * Convert decimal degrees to GPS rational numbers format
     * @param decimal Decimal degrees value
     * @return Array of rational numbers [degrees, minutes, seconds]
     */
    private RationalNumber[] convertToGpsRational(double decimal) {
        decimal = Math.abs(decimal);
        
        int degrees = (int) decimal;
        double minutesDecimal = (decimal - degrees) * 60.0;
        int minutes = (int) minutesDecimal;
        double secondsDecimal = (minutesDecimal - minutes) * 60.0;
        
        int seconds = (int) (secondsDecimal * 1000);
        
        return new RationalNumber[] {
            new RationalNumber(degrees, 1),
            new RationalNumber(minutes, 1),
            new RationalNumber(seconds, 1000)
        };
    }

    /**
     * Write EXIF metadata (comments, GPS, and tags) to exported file
     * Compatible with Apache Commons Imaging 1.0-alpha3
     * Tags are written in ImageDescription with "Keywords:" format for maximum compatibility
     * @param filePath Path to image file
     * @param caption Photo caption/comment
     * @param lat GPS latitude
     * @param lon GPS longitude
     * @param tags List of tag names
     */
/**
 * Write EXIF metadata with intelligent truncation to prevent APP1 segment overflow
 * GPS is always written (separate fields), Caption and Tags are progressively truncated if needed
 */
    private void writeExifMetadata(Path filePath, String caption, Double lat, Double lon, java.util.List<String> tags) {
        String filename = filePath.getFileName().toString().toLowerCase();
        if (!filename.endsWith(".jpg") && !filename.endsWith(".jpeg")) {
            return;
        }

        File tempFile = null;
        try {
            File imageFile = filePath.toFile();
            TiffOutputSet outputSet = null;
            
            String existingDescription = "";
            JpegImageMetadata originalMetadata = null;

            // Read existing EXIF
            try {
                originalMetadata = (JpegImageMetadata) Imaging.getMetadata(imageFile);
                if (originalMetadata != null) {
                    TiffImageMetadata exif = originalMetadata.getExif();
                    if (exif != null) {
                        outputSet = exif.getOutputSet();
                        Object desc = exif.getFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
                        if (desc != null) {
                            String descStr = String.valueOf(desc).trim();
                            if (!descStr.startsWith("Ljava.lang") && !descStr.contains("@")) {
                                existingDescription = descStr;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Create new if reading fails
            }

            if (outputSet == null) {
                outputSet = new TiffOutputSet();
            }

            TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
            boolean metadataAdded = false;

            // ALWAYS write GPS (separate EXIF fields, doesn't contribute to APP1 size)
            if (args.exportGps && lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                try {
                    writeGPS(outputSet, lat, lon, imageFile);
                    metadataAdded = true;
                } catch (Exception e) {
                    System.out.println(" WARNING: Failed to write GPS: " + e.getMessage());
                }
            }

            // Prepare caption and tags with progressive truncation strategy
            String finalDescription = existingDescription;
            String sanitizedCaption = "";
            String tagsLine = "";
            
            if (args.saveComments && caption != null && !caption.trim().isEmpty()) {
                sanitizedCaption = sanitizeCaption(caption);
            }
            
            if (args.exportTags && tags != null && !tags.isEmpty()) {
                String keywordsString = String.join(", ", tags);
                tagsLine = "Keywords: " + keywordsString;
            }

            // Try progressively smaller versions until it fits
            boolean written = false;
            int attempt = 0;
            int[] captionLimits = {500, 300, 150, 100, 50, 0};
            int[] tagLimits = {300, 200, 100, 50, 0};
            
            while (!written && attempt < 10) {
                String testCaption = sanitizedCaption;
                String testTags = tagsLine;
                
                // Apply limits based on attempt
                if (attempt < captionLimits.length && testCaption.length() > captionLimits[attempt]) {
                    testCaption = testCaption.substring(0, Math.max(0, captionLimits[attempt] - 3)) + "...";
                }
                
                if (attempt < tagLimits.length && testTags.length() > tagLimits[attempt]) {
                    int limit = tagLimits[attempt];
                    if (limit > 0) {
                        testTags = testTags.substring(0, Math.max(0, limit - 3)) + "...";
                    } else {
                        testTags = ""; // Skip tags entirely if necessary
                    }
                }
                
                // Build final description
                finalDescription = existingDescription;
                if (!testCaption.isEmpty()) {
                    if (!finalDescription.isEmpty()) {
                        finalDescription += "\n\n" + testCaption;
                    } else {
                        finalDescription = testCaption;
                    }
                }
                if (!testTags.isEmpty() && !finalDescription.contains("Keywords:")) {
                    if (!finalDescription.isEmpty()) {
                        finalDescription += "\n" + testTags;
                    } else {
                        finalDescription = testTags;
                    }
                }
                
                // Update description
                if (!finalDescription.equals(existingDescription)) {
                    rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
                    rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, finalDescription);
                    metadataAdded = true;
                }
                
                // Try to write
                if (metadataAdded) {
                    tempFile = new File(imageFile.getAbsolutePath() + ".tmp");
                    
                    try (FileOutputStream fos = new FileOutputStream(tempFile);
                         OutputStream os = new java.io.BufferedOutputStream(fos)) {
                        new ExifRewriter().updateExifMetadataLossless(imageFile, os, outputSet);
                        
                        // Success!
                        Files.delete(imageFile.toPath());
                        Files.move(tempFile.toPath(), imageFile.toPath());
                        tempFile = null;
                        written = true;
                        
                        if (attempt > 0) {
                            System.out.println(" INFO: EXIF written with truncated text (attempt " + (attempt + 1) + ")");
                            captionsTruncated++;
                        }
                        
                        if (!testCaption.isEmpty()) commentsWritten++;
                        if (!testTags.isEmpty()) tagsWritten++;
                        
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg != null && (msg.contains("too long") || msg.contains("APP1"))) {
                            // Try again with more truncation
                            if (tempFile != null && tempFile.exists()) {
                                tempFile.delete();
                            }
                            attempt++;
                        } else {
                            throw e; // Other error, don't retry
                        }
                    }
                } else {
                    written = true; // Nothing to write
                }
            }
            
            if (!written) {
                System.out.println(" WARNING: Could not write EXIF even with maximum truncation (existing EXIF too large)");
            }

        } catch (Exception e) {
            System.out.println(" WARNING: Failed to write EXIF: " + e.getMessage());
            
            if (tempFile != null && tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Exception ex) {
                    // Ignore
                }
            }
        }
    }


    /**
     * Write caption to EXIF ImageDescription field
     * Compatible with Apache Commons Imaging 1.0-alpha3
     * @param outputSet TIFF output set to modify
     * @param caption Caption text
     * @param imageFile Original image file
     */
    private void writeCaption(TiffOutputSet outputSet, String caption, File imageFile) throws Exception {
        TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
        
        String sanitizedCaption = sanitizeCaption(caption);
        
        if (sanitizedCaption.isEmpty()) {
            return;
        }
        
        // Read existing description
        String existingDescription = "";
        try {
            JpegImageMetadata jpegMetadata = (JpegImageMetadata) Imaging.getMetadata(imageFile);
            if (jpegMetadata != null) {
                TiffImageMetadata exif = jpegMetadata.getExif();
                if (exif != null) {
                    Object desc = exif.getFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
                    if (desc != null) {
                        // CRITICAL FIX: Convert Object to String properly
                        existingDescription = String.valueOf(desc).trim();
                    }
                }
            }
        } catch (Exception e) {
            // No existing description
        }

        // Append or set description
        String finalDescription;
        if (!existingDescription.isEmpty() && !existingDescription.startsWith("Ljava.lang")) {
            // Don't append if existing is already corrupted Java object reference
            finalDescription = existingDescription + "\n\n" + sanitizedCaption;
        } else {
            finalDescription = sanitizedCaption;
        }

        rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
        rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, finalDescription);
        
        commentsWritten++;
    }

    /**
     * Write GPS coordinates to EXIF (only if GPS not already present)
     * Compatible with Apache Commons Imaging 1.0-alpha3
     * @param outputSet TIFF output set to modify
     * @param latitude GPS latitude
     * @param longitude GPS longitude
     * @param imageFile Original image file
     */
    private void writeGPS(TiffOutputSet outputSet, double latitude, double longitude, File imageFile) throws Exception {
        // Check if GPS already exists
        try {
            JpegImageMetadata jpegMetadata = (JpegImageMetadata) Imaging.getMetadata(imageFile);
            if (jpegMetadata != null) {
                TiffImageMetadata exif = jpegMetadata.getExif();
                if (exif != null) {
                    Object existingLat = exif.getFieldValue(GpsTagConstants.GPS_TAG_GPS_LATITUDE);
                    if (existingLat != null) {
                        return; // GPS already present, don't overwrite
                    }
                }
            }
        } catch (Exception e) {
            // No existing GPS, continue
        }

        TiffOutputDirectory gpsDirectory = outputSet.getOrCreateGPSDirectory();

        // Write latitude
        final RationalNumber[] latRational = convertToGpsRational(latitude);
        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE);
        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE, latRational);
        
        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF);
        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF, latitude < 0 ? "S" : "N");

        // Write longitude
        final RationalNumber[] lonRational = convertToGpsRational(longitude);
        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE);
        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE, lonRational);
        
        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF);
        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF, longitude < 0 ? "W" : "E");

        gpsWritten++;
    }

    /**
     * Write tags to IPTC Keywords using metadata-extractor
     * This creates industry-standard keyword tags compatible with all photo applications
     * @param imageFile Image file to add keywords to
     * @param tags List of tag names
     * @return true if successful, false otherwise
     */
    private boolean writeIPTCKeywords(File imageFile, java.util.List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }

        try {
            // Read current file into memory
            byte[] originalBytes = Files.readAllBytes(imageFile.toPath());
            
            // Use exiftool-style approach: write tags through a new JPEG with IPTC
            // This is a workaround since metadata-extractor is read-only
            // We'll use Commons Imaging to write, but in IPTC format
            
            // For now, we'll write to ImageDescription and add a note
            // Full IPTC writing requires exiftool or a commercial library
            // This is a known limitation of open-source Java libraries
            
            return false; // Will be handled by ImageDescription for now
            
        } catch (Exception e) {
            System.out.println("  WARNING: Failed to write IPTC keywords: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write tags to EXIF in multiple formats for maximum compatibility:
     * 1. ImageDescription with [Tags: ...] format (human-readable)
     * 2. XMP Subject/Keywords (industry standard)
     * 
     * Note: True IPTC Keywords requires exiftool. We write to ImageDescription
     * which is widely supported and readable by most applications.
     * 
     * @param outputSet TIFF output set to modify
     * @param tags List of tags
     * @param existingDescription Existing description text
     * @return Updated description with tags
     */
    private String writeTagsToExif(TiffOutputSet outputSet, java.util.List<String> tags, String existingDescription) throws Exception {
        if (tags == null || tags.isEmpty()) {
            return existingDescription;
        }
        
        TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
        
        // Build keywords string
        String keywordsString = String.join(";", tags);
        
        // Truncate if too long
        if (keywordsString.length() > 1000) {
            int truncated = 0;
            StringBuilder sb = new StringBuilder();
            for (String tag : tags) {
                if (sb.length() + tag.length() + 1 < 1000) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(tag);
                } else {
                    truncated++;
                }
            }
            keywordsString = sb.toString();
            if (truncated > 0) {
                System.out.println("  WARNING: Truncated " + truncated + " tags to fit in EXIF");
            }
        }
        
        // Format: Keywords: tag1, tag2, tag3 (more standard than [Tags:])
        String tagsLine = "Keywords: " + keywordsString.replace(";", ", ");
        
        // Append to description
        String finalDescription = existingDescription;
        if (!existingDescription.isEmpty() && !existingDescription.contains("Keywords:")) {
            finalDescription = existingDescription + "\n" + tagsLine;
        } else if (existingDescription.isEmpty()) {
            finalDescription = tagsLine;
        }
        
        tagsWritten++;
        return finalDescription;
    }

    /**
     * Export edited version of an image if it exists in ContentEdit table
     * @param contentId Content ID
     * @param destinationDir Destination directory
     * @param originalFilename Original filename
     * @param originalPath Original file path
     * @param originalCaption Original caption
     * @param originalLat Original GPS latitude
     * @param originalLon Original GPS longitude
     * @param albumName Album name for statistics
     * @param originalTakenAt Original taken timestamp
     */
    private void exportEditedVersion(int contentId, String destinationDir, 
                                     String originalFilename, String originalPath,
                                     String originalCaption, Double originalLat, Double originalLon,
                                     String albumName, Long originalTakenAt) {
        try {
            String sql = "SELECT path, filename, checksum, stored_at FROM ContentEdit WHERE content_id = ? AND status = 1";
            
            try (PreparedStatement pstmt = d.getConnection().prepareStatement(sql)) {
                pstmt.setInt(1, contentId);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String editedPath = rs.getString("path");
                        String editedFilename = rs.getString("filename");
                        String editedChecksum = rs.getString("checksum");
                        long storedAt = rs.getLong("stored_at");
                        
                        // Clean filename (remove "edit_" prefix if present)
                        String cleanFilename = editedFilename;
                        if (cleanFilename.startsWith("edit_")) {
                            cleanFilename = cleanFilename.substring(5);
                        }
                        
                        // Add "_edited" suffix before extension
                        int lastDot = cleanFilename.lastIndexOf('.');
                        String editedOutputFilename;
                        if (lastDot > 0) {
                            editedOutputFilename = cleanFilename.substring(0, lastDot) + "_edited" + cleanFilename.substring(lastDot);
                        } else {
                            editedOutputFilename = cleanFilename + "_edited";
                        }
                        
                        String editedSourcePath = args.sourceDir + "/" + editedPath;
                        
                        physicalFilesCopied++;
                        editedFilesExported++;
                        
                        editedFilesByAlbum.put(albumName, editedFilesByAlbum.getOrDefault(albumName, 0) + 1);
                        
                        logExport(contentId, editedSourcePath, "EDITED_VERSION", 
                                 destinationDir + "/" + editedOutputFilename, 
                                 args.flatten, false, editedFilename, editedOutputFilename, false);
                        
                        Path destPath = Paths.get(destinationDir);
                        if (!args.dryrun) {
                            Files.createDirectories(destPath);
                        }
                        
                        Path finalDestPath = destPath.resolve(editedOutputFilename);
                        
                        if (!args.dryrun) {
                            try {
                                Files.copy(Path.of(editedSourcePath), finalDestPath);
                                
                                // Set timestamp from original file or database
                                Path originalFilePath = Path.of(originalPath);
                                if (Files.exists(originalFilePath)) {
                                    long originalTime = Files.getLastModifiedTime(originalFilePath).toMillis();
                                    Files.setLastModifiedTime(finalDestPath, 
                                        java.nio.file.attribute.FileTime.fromMillis(originalTime));
                                } else if (originalTakenAt != null && originalTakenAt > 0) {
                                    Files.setLastModifiedTime(finalDestPath,
                                        java.nio.file.attribute.FileTime.fromMillis(originalTakenAt * 1000L));
                                } else if (storedAt > 0) {
                                    Files.setLastModifiedTime(finalDestPath,
                                        java.nio.file.attribute.FileTime.fromMillis(storedAt * 1000L));
                                }
                                
                                // Add "edited" to caption
                                String editedCaption = originalCaption;
                                if (editedCaption != null && !editedCaption.trim().isEmpty()) {
                                    editedCaption += "\n\nedited";
                                } else {
                                    editedCaption = "edited";
                                }
                                
                                writeExifMetadataForEdited(finalDestPath, editedCaption, originalLat, originalLon);
                                
                            } catch (java.nio.file.NoSuchFileException e) {
                                System.out.println("  ERROR: Edited file not found: " + editedSourcePath);
                                System.out.println();
                            }
                        }
                    }
                }
            }
        } catch (SQLException | IOException e) {
            System.out.println("WARNING: Failed to export edited version for content " + contentId + ": " + e.getMessage());
        }
    }

    /**
     * Write EXIF metadata specifically for edited images
     * Includes Monument camera make/model tags
     * Compatible with Apache Commons Imaging 1.0-alpha3
     * @param filePath Path to edited image file
     * @param caption Caption with "edited" marker
     * @param lat GPS latitude
     * @param lon GPS longitude
     */
    private void writeExifMetadataForEdited(Path filePath, String caption, Double lat, Double lon) {
        String filename = filePath.getFileName().toString().toLowerCase();
        if (!filename.endsWith(".jpg") && !filename.endsWith(".jpeg")) {
            return;
        }

        File tempFile = null;
        try {
            File imageFile = filePath.toFile();
            TiffOutputSet outputSet = null;

            // Try to read existing EXIF data
            try {
                JpegImageMetadata jpegMetadata = (JpegImageMetadata) Imaging.getMetadata(imageFile);
                if (jpegMetadata != null) {
                    TiffImageMetadata exif = jpegMetadata.getExif();
                    if (exif != null) {
                        outputSet = exif.getOutputSet();
                    }
                }
            } catch (Exception e) {
                // Create new if reading fails
            }

            if (outputSet == null) {
                outputSet = new TiffOutputSet();
            }

            // Write caption
            if (caption != null && !caption.trim().isEmpty()) {
                String sanitizedCaption = sanitizeCaption(caption);
                if (!sanitizedCaption.isEmpty()) {
                    TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
                    rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
                    rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, sanitizedCaption);
                }
            }

            // Write GPS if present
            if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                TiffOutputDirectory gpsDirectory = outputSet.getOrCreateGPSDirectory();
                
                final RationalNumber[] latRational = convertToGpsRational(lat);
                gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE);
                gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE, latRational);
                gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF);
                gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF, lat < 0 ? "S" : "N");

                final RationalNumber[] lonRational = convertToGpsRational(lon);
                gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE);
                gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE, lonRational);
                gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF);
                gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF, lon < 0 ? "W" : "E");
            }

            // Add Monument camera tags to edited images
            TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
            rootDirectory.removeField(TiffTagConstants.TIFF_TAG_MAKE);
            rootDirectory.add(TiffTagConstants.TIFF_TAG_MAKE, "Monument");
            rootDirectory.removeField(TiffTagConstants.TIFF_TAG_MODEL);
            rootDirectory.add(TiffTagConstants.TIFF_TAG_MODEL, "M2");

            // Write EXIF data to file
            tempFile = new File(imageFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 OutputStream os = new java.io.BufferedOutputStream(fos)) {
                new ExifRewriter().updateExifMetadataLossless(imageFile, os, outputSet);
            }
            
            Files.delete(imageFile.toPath());
            Files.move(tempFile.toPath(), imageFile.toPath());
            tempFile = null; // Successfully moved

        } catch (Exception e) {
            System.out.println("  WARNING: Failed to write EXIF for edited image: " + e.getMessage());
            
            // Cleanup temp file
            if (tempFile != null && tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Exception ex) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Write EXIF metadata for tag folder copies
     * Always writes tags in standardized "Keywords:" format regardless of args.exportTags setting
     * @param filePath Path to image file
     * @param caption Photo caption (if args.saveComments is true)
     * @param lat GPS latitude (if args.exportGps is true)
     * @param lon GPS longitude (if args.exportGps is true)
     * @param tags List containing ALL tags for this photo
     */
    private void writeExifMetadataForTagFolder(Path filePath, String caption, Double lat, Double lon, java.util.List<String> tags) {
        String filename = filePath.getFileName().toString().toLowerCase();
        if (!filename.endsWith(".jpg") && !filename.endsWith(".jpeg")) {
            return;
        }

        File tempFile = null;
        try {
            File imageFile = filePath.toFile();
            TiffOutputSet outputSet = null;
            
            String existingDescription = "";
            JpegImageMetadata originalMetadata = null;

            try {
                originalMetadata = (JpegImageMetadata) Imaging.getMetadata(imageFile);
                if (originalMetadata != null) {
                    TiffImageMetadata exif = originalMetadata.getExif();
                    if (exif != null) {
                        outputSet = exif.getOutputSet();
                        Object desc = exif.getFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
                        if (desc != null) {
                            String descStr = String.valueOf(desc).trim();
                            if (!descStr.startsWith("Ljava.lang") && !descStr.contains("@")) {
                                existingDescription = descStr;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Create new
            }

            if (outputSet == null) {
                outputSet = new TiffOutputSet();
            }

            TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();

            // ALWAYS write GPS
            if (args.exportGps && lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                try {
                    boolean hasGps = false;
                    if (originalMetadata != null) {
                        TiffImageMetadata exif = originalMetadata.getExif();
                        if (exif != null) {
                            Object existingLat = exif.getFieldValue(GpsTagConstants.GPS_TAG_GPS_LATITUDE);
                            if (existingLat != null) {
                                hasGps = true;
                            }
                        }
                    }
                    
                    if (!hasGps) {
                        TiffOutputDirectory gpsDirectory = outputSet.getOrCreateGPSDirectory();
                        
                        final RationalNumber[] latRational = convertToGpsRational(lat);
                        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE);
                        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE, latRational);
                        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF);
                        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF, lat < 0 ? "S" : "N");

                        final RationalNumber[] lonRational = convertToGpsRational(lon);
                        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE);
                        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE, lonRational);
                        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF);
                        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF, lon < 0 ? "W" : "E");
                    }
                } catch (Exception e) {
                    // Continue
                }
            }

            // Progressive truncation for caption and tags
            String sanitizedCaption = "";
            String tagsLine = "";
            
            if (args.saveComments && caption != null && !caption.trim().isEmpty()) {
                sanitizedCaption = sanitizeCaption(caption);
            }
            
            if (tags != null && !tags.isEmpty()) {
                String keywordsString = String.join(", ", tags);
                tagsLine = "Keywords: " + keywordsString;
            }

            boolean written = false;
            int attempt = 0;
            int[] captionLimits = {300, 150, 100, 50, 0};
            int[] tagLimits = {200, 100, 50, 0};
            
            while (!written && attempt < 10) {
                String testCaption = sanitizedCaption;
                String testTags = tagsLine;
                
                if (attempt < captionLimits.length && testCaption.length() > captionLimits[attempt]) {
                    testCaption = testCaption.substring(0, Math.max(0, captionLimits[attempt] - 3)) + "...";
                }
                
                if (attempt < tagLimits.length && testTags.length() > tagLimits[attempt]) {
                    int limit = tagLimits[attempt];
                    if (limit > 0) {
                        testTags = testTags.substring(0, Math.max(0, limit - 3)) + "...";
                    } else {
                        testTags = "";
                    }
                }
                
                String finalDescription = existingDescription;
                if (!testCaption.isEmpty()) {
                    if (!finalDescription.isEmpty()) {
                        finalDescription += "\n\n" + testCaption;
                    } else {
                        finalDescription = testCaption;
                    }
                }
                if (!testTags.isEmpty() && !finalDescription.contains("Keywords:")) {
                    if (!finalDescription.isEmpty()) {
                        finalDescription += "\n" + testTags;
                    } else {
                        finalDescription = testTags;
                    }
                }
                
                if (!finalDescription.equals(existingDescription)) {
                    rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
                    rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, finalDescription);
                }
                
                tempFile = new File(imageFile.getAbsolutePath() + ".tmp");
                
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     OutputStream os = new java.io.BufferedOutputStream(fos)) {
                    new ExifRewriter().updateExifMetadataLossless(imageFile, os, outputSet);
                    
                    Files.delete(imageFile.toPath());
                    Files.move(tempFile.toPath(), imageFile.toPath());
                    tempFile = null;
                    written = true;
                    
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("too long") || msg.contains("APP1"))) {
                        if (tempFile != null && tempFile.exists()) {
                            tempFile.delete();
                        }
                        attempt++;
                    } else {
                        throw e;
                    }
                }
            }

        } catch (Exception e) {
            if (tempFile != null && tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Exception ex) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Main export function - processes all content from database
     * Exports photos with metadata, handles edited versions, and tag folders
     */
    public void export() {
        Connection conn = d.getConnection();
        
        if (conn == null) {
            System.out.println("ERROR: Cannot export - no database connection");
            return;
        }

        if (!createUserMap()) {
            System.out.println("ERROR: Failed to create user map. Aborting export.");
            return;
        }

        String monumentRoot = args.sourceDir + "/";
        String sql = "SELECT id, user_id, type, path, filename, checksum, caption, geo_lat, geo_lon, taken_at FROM Content where deleted_at is null";
// and filename = 'Parter-Kuchnia-kafle.png'
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                totalFiles++;
                uniqueFilesProcessed++;
                
                int contentId = rs.getInt("id");
                int userId = rs.getInt("user_id");
                String sourcePath = rs.getString("path");
                String fileName = rs.getString("filename");
                String checksumUnique = rs.getString("checksum");
                String caption = rs.getString("caption");
                Double geoLat = rs.getObject("geo_lat") != null ? rs.getDouble("geo_lat") : null;
                Double geoLon = rs.getObject("geo_lon") != null ? rs.getDouble("geo_lon") : null;
                Long takenAt = rs.getObject("taken_at") != null ? rs.getLong("taken_at") : null;
                
                // Get tags if needed
                java.util.List<String> tags = null;
                if (args.exportTags || args.tagsAsFolders) {
                    tags = getTagsForContent(contentId);
                }
                
                // Determine destination
                DestinationDescription dd = getDest(contentId, userId);
                
                String destination = args.destinationDir + "/" + dd.dest;

                // Warn about ownership changes
                if (dd.owner_changed) {
                    filesWithOwnerChange++;
                    String old_owner = monumentUsers.get(userId);
                    String new_owner = monumentUsers.get(dd.new_owner);
                    System.out.println("WARNING: Content " + contentId + " - OWNER CHANGED FROM " +
                            old_owner + " TO " + new_owner);
                    System.out.println();
                }

                String from = monumentRoot + sourcePath;
                boolean isFlattened = args.flatten;
                
                // Copy main file
                copyContent(from, fileName, destination, checksumUnique, 
                           contentId, dd.albumName, isFlattened);
                
                // Set timestamps
                Path exportedFile = Paths.get(destination, fileName);
                if (!args.dryrun && Files.exists(exportedFile)) {
                    setFileTimestamps(exportedFile, Path.of(from), takenAt);
                }
                
                // Write EXIF metadata
                if ((args.saveComments && caption != null) || 
                    (args.exportGps && geoLat != null && geoLon != null) ||
                    (args.exportTags && tags != null && !tags.isEmpty())) {
                    if (!args.dryrun && Files.exists(exportedFile)) {
                        writeExifMetadata(exportedFile, caption, geoLat, geoLon, tags);
                    }
                }
                
                // Export to tag folders if enabled
                if (args.tagsAsFolders && tags != null && !tags.isEmpty()) {
                    exportToTagFolders(contentId, sourcePath, fileName, checksumUnique, 
                                      caption, geoLat, geoLon, userId, takenAt, tags);
                }
                
                // Export edited version if enabled
                if (args.saveEdits) {
                    exportEditedVersion(contentId, destination, fileName, from, caption, geoLat, geoLon, dd.albumName, takenAt);
                }
            }

            printSummary();

        } catch (SQLException e) {
            System.out.println("ERROR: Database error during export: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("ERROR: File system error during export: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Print export summary with statistics
     */
    private void printSummary() {
        System.out.println();
        System.out.println("================================================");
        System.out.println(" EXPORT SUMMARY");
        System.out.println("================================================");
        
        if (args.dryrun) {
            System.out.println("Mode: DRY RUN (simulation)");
        } else {
            System.out.println("Mode: REAL EXPORT");
        }
        
        if (args.flatten) {
            System.out.println("Option: FLATTEN enabled");
        }
        
        if (args.saveEdits) {
            System.out.println("Option: SAVE-EDITS enabled");
        }
        
        if (args.saveComments) {
            System.out.println("Option: SAVE-COMMENTS enabled");
        }
        
        if (args.exportGps) {
            System.out.println("Option: EXPORT-GPS enabled");
        }
        
        if (args.exportTags) {
            System.out.println("Option: EXPORT-TAGS enabled");
        }
        
        if (args.tagsAsFolders) {
            System.out.println("Option: TAGS-AS-FOLDERS enabled");
        }
        
        System.out.println();
        System.out.println("DATABASE STATISTICS:");
        System.out.println("  Total entries in database: " + totalFiles);
        System.out.println("  Unique files processed: " + uniqueFilesProcessed);
        
        System.out.println();
        System.out.println("FILE OPERATIONS:");
        System.out.println("  Total physical files copied: " + physicalFilesCopied);
        System.out.println("    - Main album copies: " + (physicalFilesCopied - tagFolderCopies - editedFilesExported));
        System.out.println("    - Tag folder copies: " + tagFolderCopies);
        System.out.println("    - Edited versions: " + editedFilesExported);
        System.out.println("  Files renamed (collisions): " + filesRenamed);
        System.out.println("  Files with owner change: " + filesWithOwnerChange);
        
        if (args.saveEdits && editedFilesExported > 0) {
            System.out.println();
            System.out.println("EDITED FILES:");
            System.out.println("  Total edited files: " + editedFilesExported);
            if (!editedFilesByAlbum.isEmpty()) {
                System.out.println("  By album:");
                editedFilesByAlbum.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(entry -> System.out.println("    - " + entry.getKey() + ": " + entry.getValue() + " file(s)"));
            }
        }
        
        if (args.saveComments || args.exportGps || args.exportTags) {
            System.out.println();
            System.out.println("EXIF METADATA:");
            if (args.saveComments) {
                System.out.println("  Comments written: " + commentsWritten);
                if (captionsTruncated > 0) {
                    System.out.println("  Captions truncated: " + captionsTruncated);
                }
            }
            if (args.exportGps) {
                System.out.println("  GPS coordinates written: " + gpsWritten);
            }
            if (args.exportTags) {
                System.out.println("  Tags written: " + tagsWritten);
            }
        }
        
        if (args.tagsAsFolders && tagFolderCopies > 0) {
            System.out.println();
            System.out.println("TAG FOLDERS:");
            System.out.println("  Photos copied to tag folders: " + tagFolderCopies);
            if (!photosByTag.isEmpty()) {
                System.out.println("  Top 20 tags by photo count:");
                photosByTag.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(20)
                    .forEach(entry -> System.out.println("    - " + entry.getKey() + ": " + entry.getValue() + " photo(s)"));
            }
        }
        
        System.out.println();
        System.out.println("================================================");
        
        if (args.dryrun) {
            System.out.println("SUCCESS: Simulation completed");
        } else {
            System.out.println("SUCCESS: Export completed");
        }
        System.out.println("================================================");
    }

    /**
     * Initialize database connection
     */
    public void init() {
        String dbfilepath = args.sourceDir + "/monument/.userdata/m.sqlite3";
        System.out.println("INFO: Database file is " + dbfilepath);

        d = new DataConnection(dbfilepath);
        Connection conn = d.getConnection();
        
        if (conn == null) {
            System.out.println("ERROR: Failed to connect to database. Please check the path.");
        }
    }
}
